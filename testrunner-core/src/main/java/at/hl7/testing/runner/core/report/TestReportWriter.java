package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.config.ReportingConfig;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.FhirVersionSupport;
import at.hl7.testing.runner.core.spi.TestReportContent;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes one FHIR {@code TestReport} per TestScript into {@code testreports/}, in the FHIR version of the TestScript,
 * so the results can be stored on a FHIR server or read by conformance tools. A TestScript whose version could not be
 * determined gets no TestReport (it is in the other reports).
 *
 * <p>What goes into a TestReport is decided here; the version module only encodes it
 * ({@link FhirVersionSupport#testReport}). The mapping of the runner's outcomes:
 * <ul>
 *   <li>TestReport {@code result}: {@code pass} if the TestScript passed, {@code pending} if it was skipped as a
 *       whole, {@code fail} otherwise (FHIR has no result "error"; the actions say {@code error}).</li>
 *   <li>Action {@code result}: as recorded; an action that passed with a warning (a failed {@code warningOnly}
 *       assertion, a profile not validated) is {@code warning}, never a plain {@code pass}.</li>
 *   <li>{@code score}: the percentage of the tests that passed, as the specification defines it.</li>
 *   <li>Action {@code detail}: the folder of the request in {@code communication/}, relative to the run folder.</li>
 *   <li>What happened outside of the actions (an unmet capability, a fixture that could not be created, a skipped
 *       test, a warning about the TestScript) becomes an action of its own.</li>
 * </ul>
 */
public final class TestReportWriter implements ReportWriter {

    /** The folder of the TestReports in the run folder. */
    public static final String FOLDER = "testreports";

    private static final Logger log = LoggerFactory.getLogger(TestReportWriter.class);
    private static final String TESTER = "FHIR TestScript Runner (at.hl7.testing)";

    private final FhirVersionRegistry versions;

    public TestReportWriter(@Nonnull FhirVersionRegistry versions) {
        this.versions = versions;
    }

    @Override
    @Nonnull
    public String format() {
        return ReportingConfig.TESTREPORT;
    }

    /** @return the folder of the TestReports */
    @Override
    @Nonnull
    public Path write(@Nonnull RunResult result, @Nonnull Path runDirectory) throws IOException {
        Path folder = runDirectory.resolve(FOLDER);
        Files.createDirectories(folder);
        Map<String, String> exchanges = ExchangeFolders.of(runDirectory);
        for (TestScriptResult testScript : result.testScripts()) {
            Optional<FhirVersionSupport> support = Optional.ofNullable(testScript.fhirVersion()).flatMap(versions::get);
            if (support.isEmpty()) {
                log.debug("No TestReport for {}: its FHIR version is not known", testScript.source().getFileName());
                continue;
            }
            String json;
            try {
                json = support.get().testReport(content(result, testScript, exchanges)).text();
            } catch (UnsupportedOperationException e) {
                log.warn("No TestReport for {}: {}", testScript.source().getFileName(), e.getMessage());
                continue;
            }
            Files.writeString(folder.resolve(fileName(testScript)), json, StandardCharsets.UTF_8);
        }
        return folder;
    }

    /** e.g. {@code TestReport-03_TestScript-testscript-patient-create-at-core.json} */
    static String fileName(TestScriptResult testScript) {
        String base = testScript.source().getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return "TestReport-%02d_%s.json".formatted(testScript.index(), base);
    }

    /**
     * @param exchanges the communication log folder of each request, relative to the run folder, by request id
     */
    static TestReportContent content(RunResult run, TestScriptResult testScript, Map<String, String> exchanges) {
        List<TestReportContent.Action> setup = new ArrayList<>();
        List<TestReportContent.Test> tests = new ArrayList<>();
        List<TestReportContent.Action> teardown = new ArrayList<>();
        for (PhaseResult phase : testScript.phases()) {
            List<TestReportContent.Action> actions = actions(phase, exchanges);
            switch (phase.kind()) {
                case LOAD, SETUP -> setup.addAll(actions);
                case TEST -> tests.add(new TestReportContent.Test(phase.name(), actions));
                case TEARDOWN -> teardown.addAll(actions);
            }
        }
        String url = testScript.url() != null && testScript.url().contains("://") ? testScript.url()
                : testScript.source().toUri().toString();
        String position = "%02d".formatted(testScript.index());
        return new TestReportContent(id(run.runId() + "-" + position), run.runId() + "/" + position, testScript.name(),
                url, result(testScript.outcome()), score(testScript), TESTER, testScript.started(),
                testScript.fhirServer(), setup, tests, teardown);
    }

    /**
     * The actions of a phase. What happened outside of the actions – a fixture that could not be created, an unmet
     * capability, a skipped test, a warning about the TestScript – becomes an action of its own, before the actions
     * (after them in the teardown, where autodelete runs last). A test gets at least one action, as FHIR requires.
     */
    private static List<TestReportContent.Action> actions(PhaseResult phase, Map<String, String> exchanges) {
        List<TestReportContent.Action> actions = new ArrayList<>();
        Set<String> actionMessages = new HashSet<>();
        for (ActionResult action : phase.actions()) {
            String result = action.warning() ? "warning" : code(action.outcome());
            String message = action.message() == null ? action.description()
                    : action.description() + ": " + action.message();
            actions.add(new TestReportContent.Action(action.kind().equals(ActionResult.ASSERT), result, message,
                    action.requestId() == null ? null : exchanges.getOrDefault(action.requestId(), action.requestId())));
            if (action.message() != null) {
                actionMessages.add(action.message());
                actionMessages.add("WARNING: " + action.message());
            }
        }
        List<String> own = phase.messages().stream().filter(m -> !actionMessages.contains(m)).toList();
        Outcome ofActions = phase.actions().stream().map(ActionResult::outcome).reduce(Outcome.PASS, Outcome::and);
        boolean unexplained = phase.outcome() != ofActions;
        if (unexplained || !own.isEmpty() || (actions.isEmpty() && phase.kind() == PhaseKind.TEST)) {
            boolean warningsOnly = own.stream().allMatch(m -> m.startsWith("WARNING: "));
            String result = unexplained || !warningsOnly ? code(phase.outcome()) : own.isEmpty() ? "pass" : "warning";
            String message = own.isEmpty() ? phase.kind().label() + " '" + phase.name() + "' has no actions"
                    : String.join("\n", own);
            TestReportContent.Action phaseAction = new TestReportContent.Action(false, result, message, null);
            actions.add(phase.kind() == PhaseKind.TEARDOWN ? actions.size() : 0, phaseAction);
        }
        return actions;
    }

    private static String code(Outcome outcome) {
        return switch (outcome) {
            case PASS -> "pass";
            case SKIP -> "skip";
            case FAIL -> "fail";
            case ERROR -> "error";
        };
    }

    private static String result(Outcome outcome) {
        return switch (outcome) {
            case PASS -> "pass";
            case SKIP -> "pending";
            case FAIL, ERROR -> "fail";
        };
    }

    /** The percentage of the tests that passed ({@code TestReport.score}), {@code null} without tests. */
    private static BigDecimal score(TestScriptResult testScript) {
        List<PhaseResult> tests = testScript.phases().stream().filter(p -> p.kind() == PhaseKind.TEST).toList();
        if (tests.isEmpty()) {
            return null;
        }
        long passed = tests.stream().filter(p -> p.outcome() == Outcome.PASS).count();
        return BigDecimal.valueOf(passed * 100).divide(BigDecimal.valueOf(tests.size()), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    /** A FHIR id: letters, digits, '-' and '.', at most 64 characters. */
    private static String id(String text) {
        String id = text.replaceAll("[^A-Za-z0-9.-]", "-");
        return id.length() > 64 ? id.substring(id.length() - 64) : id;
    }
}
