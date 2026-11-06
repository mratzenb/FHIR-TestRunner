package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.config.SelectConfig;
import at.hl7.testing.runner.core.http.HttpTransport;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.source.LoadedTestScript;
import at.hl7.testing.runner.core.source.PackageSuites;
import at.hl7.testing.runner.core.source.TestScriptDiscovery;
import at.hl7.testing.runner.core.source.TestScriptLoader;
import at.hl7.testing.runner.core.source.TestScriptSource;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs the TestScripts of a {@link RunConfiguration} and writes the reports. The entry point of the engine.
 *
 * <p>{@link #check} does everything a run does before it sends anything – discovery, loading, the checks before
 * sending, the validation of the TestScripts – and reports that, without a FHIR server. {@link #list} only says what
 * a run would run.
 *
 * <p>{@code RunConfiguration.select} narrows a run, a check and a list to some TestScripts and tests; see
 * {@link #list}.
 *
 * <p>Stateless between runs and thread-safe: everything about a run lives in its {@link RunContext}. The
 * collaborators are passed in, so the runner works without any framework; the Spring Boot auto-configuration
 * merely wires them.
 */
public final class TestRunner {

    /** MDC key holding {@code <NN>_<TestScript>} while a TestScript runs, to tell parallel log lines apart. */
    public static final String MDC_TEST_SCRIPT = "testScript";

    private static final Logger log = LoggerFactory.getLogger(TestRunner.class);

    /** What a call of the runner does with the TestScripts. */
    private enum Mode {
        /** Run them against the server. */
        RUN,
        /** Load and check them, send nothing. */
        CHECK
    }

    private final FhirVersionRegistry versions;
    private final ProfileValidator validator;
    private final List<RunListener> listeners;
    private final Clock clock;
    private final Function<RunConfiguration, PackageSuites> packages;
    private final RunScheduler scheduler = new RunScheduler();
    private final ExecutionFactory executions;
    private final ReportPublisher reports;

    public TestRunner(@Nonnull FhirVersionRegistry versions, @Nonnull HttpTransport transport,
                      @Nonnull ProfileValidator validator, @Nonnull List<ReportWriter> reportWriters,
                      @Nonnull List<RunListener> listeners, @Nonnull Clock clock) {
        this(versions, transport, validator, reportWriters, listeners, clock, c -> new PackageSuites(c.packages()));
    }

    /**
     * @param packages resolves the suites read from FHIR packages, given the configuration of the run
     */
    public TestRunner(@Nonnull FhirVersionRegistry versions, @Nonnull HttpTransport transport,
                      @Nonnull ProfileValidator validator, @Nonnull List<ReportWriter> reportWriters,
                      @Nonnull List<RunListener> listeners, @Nonnull Clock clock,
                      @Nonnull Function<RunConfiguration, PackageSuites> packages) {
        this.versions = versions;
        this.validator = validator;
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
        this.packages = packages;
        this.executions = new ExecutionFactory(transport, validator, clock);
        this.reports = new ReportPublisher(reportWriters);
    }

    /**
     * Runs the configured TestScripts.
     *
     * @throws ConfigurationException if the configuration cannot be used, e.g. a package of a suite cannot be found;
     *                                nothing is run then
     */
    @Nonnull
    public RunResult run(@Nonnull RunConfiguration configuration) {
        return run(configuration, new RunControl());
    }

    /**
     * Runs the configured TestScripts under a {@link RunControl}, through which the run can be stopped from another
     * thread, e.g. by a shutdown hook.
     *
     * <p>Once the run has started, the reports are always written, whatever happens: after a normal end, a stop, an
     * unexpected exception (which is then rethrown) or {@link RunControl#reportNow()}. A TestScript that did not end is
     * {@code error}, one that did not start {@code skip}, both with the reason.
     *
     * @param control controls this run only; a new one is needed for every run
     * @throws ConfigurationException if the configuration cannot be used; nothing is run then
     */
    @Nonnull
    public RunResult run(@Nonnull RunConfiguration configuration, @Nonnull RunControl control) {
        try {
            return start(configuration, control, Mode.RUN);
        } finally {
            control.finish();
        }
    }

    /**
     * Checks the configured TestScripts without sending anything: discovers and loads them, applies the checks that
     * run before anything is sent and, if {@code validation.validate-test-scripts} is on, validates each TestScript.
     * Needs no FHIR server.
     *
     * <p>Every TestScript gets one phase, {@code Load}: {@code pass} with the warnings, or {@code error} with every
     * problem, as in a run. The reports are written as for a run, into a folder {@code check_<timestamp>}, except the
     * FHIR TestReports: nothing was tested.
     *
     * @throws ConfigurationException if the configuration cannot be used; nothing is checked then
     */
    @Nonnull
    public RunResult check(@Nonnull RunConfiguration configuration) {
        return check(configuration, new RunControl());
    }

    /**
     * {@link #check(RunConfiguration)} under a {@link RunControl}. The run timeout applies; the TestScript timeout and
     * fail-fast do not, since every TestScript is checked.
     *
     * @param control controls this check only; a new one is needed for every call
     */
    @Nonnull
    public RunResult check(@Nonnull RunConfiguration configuration, @Nonnull RunControl control) {
        try {
            return start(configuration, control, Mode.CHECK);
        } finally {
            control.finish();
        }
    }

    /**
     * What a run of this configuration would run, in the order it would run it: the TestScripts the suites hold and
     * {@code select} selects, with the tests selected of each. Reads the TestScripts, sends nothing, writes nothing.
     *
     * <p>A TestScript is selected when a pattern of {@code select.test-scripts} matches its file name, name or url, and,
     * if {@code select.tests} is given, at least one of its tests. A file that cannot be read is listed with an empty
     * list of tests and its problem; with {@code select.tests} it is listed only if a TestScript pattern names its file.
     *
     * @throws ConfigurationException if the configuration cannot be used
     */
    @Nonnull
    public List<Listed> list(@Nonnull RunConfiguration configuration) {
        RunConfiguration resolved = resolve(configuration, false);
        Selection selection = new Selection(resolved.select());
        List<Listed> listed = new ArrayList<>();
        TestScriptLoader loader = new TestScriptLoader(versions);
        for (TestScriptSource source : TestScriptDiscovery.discover(resolved.suites())) {
            TestScript testScript = null;
            String problem = null;
            try {
                testScript = loader.map(source);
            } catch (TestScriptFormatException e) {
                problem = e.getMessage();
            } catch (RuntimeException e) {
                problem = "Unexpected error: " + e;
            }
            if (selection.selects(source, testScript)) {
                listed.add(new Listed(listed.size() + 1, source.suite().name(), source.file(),
                        testScript == null ? null : testScript.fhirVersion(),
                        testScript == null ? null : testScript.displayName(),
                        testScript == null ? null : testScript.url(),
                        testScript == null ? List.of() : selection.tests(testScript).stream().map(TestDefinition::name)
                                .toList(),
                        testScript == null ? 0 : testScript.tests().size(), problem));
            }
        }
        return listed;
    }

    /**
     * A TestScript a run would run, see {@link #list}.
     *
     * @param index       its position in the run, starting at 1
     * @param suite       the name of its suite
     * @param file        the file
     * @param fhirVersion its FHIR version, {@code null} if it cannot be read
     * @param name        its name for reports ({@code TestScript.name}, title or id), {@code null} if it cannot be read
     * @param url         {@code TestScript.url}, may be {@code null}
     * @param tests       the names of the tests that would run
     * @param testCount   how many tests the TestScript has, selected or not
     * @param problem     why the file cannot be read, {@code null} if it can; the run reports it as {@code error}
     */
    public record Listed(int index, @Nonnull String suite, @Nonnull Path file, @Nullable FhirVersion fhirVersion,
                         @Nullable String name, @Nullable String url, @Nonnull List<String> tests, int testCount,
                         @Nullable String problem) {

        public Listed {
            tests = List.copyOf(tests);
        }
    }

    /**
     * Checks the configuration and resolves its package suites.
     *
     * @param validates whether the run uses the validator, whose configuration problems then count
     * @throws ConfigurationException if the configuration cannot be used
     */
    private RunConfiguration resolve(RunConfiguration configuration, boolean validates) {
        List<String> problems = new ArrayList<>(configuration.problems());
        problems.addAll(configuration.unsupportedVersions(versions.versions()));
        problems.addAll(configuration.unknownFormats(reports.formats()));
        if (validates) {
            problems.addAll(validator.configurationProblems());
        }
        if (problems.isEmpty()) {
            try {
                configuration = configuration.withSuites(packages.apply(configuration).resolve(configuration.suites()));
                problems.addAll(configuration.unsupportedVersions(versions.versions())); // versions of the packages
            } catch (PackageSuites.PackageException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        return configuration;
    }

    private RunResult start(RunConfiguration configuration, RunControl control, Mode mode) {
        configuration = resolve(configuration, mode == Mode.RUN || configuration.validation().validateTestScripts());
        RunContext run = RunContext.start(configuration, clock.instant(), mode == Mode.CHECK ? "check" : "run");
        try {
            Files.createDirectories(run.runDirectory());
        } catch (IOException e) {
            throw new ConfigurationException(List.of("Cannot create the result folder " + run.runDirectory() + ": " + e));
        }
        RunProgress progress = new RunProgress();
        Duration timeout = configuration.execution().timeout();
        control.begin(clock, timeout == null ? null : run.started().plus(timeout),
                "the run timeout of " + (timeout == null ? "" : RunControl.describe(timeout)) + " has passed",
                () -> publish(run, progress, mode, "the run was aborted before it ended"));
        RunInfo info = run.info();
        notify(listener -> listener.runStarted(info));
        Throwable failure = null;
        try {
            Selection selection = new Selection(configuration.select());
            List<TestScriptSource> sources = select(TestScriptDiscovery.discover(configuration.suites()), selection,
                    configuration.select());
            progress.found(sources);
            logStart(run, mode, sources);
            CapabilityCheck capabilities = new CapabilityCheck();
            scheduler.runAll(run, sources, control,
                    (index, source) -> {
                        progress.started(index);
                        TestScriptResult result = runOne(run, control, mode, selection, index, sources.size(), source,
                                capabilities);
                        progress.finished(result);
                        return result;
                    },
                    (index, source, reason) -> {
                        TestScriptResult result = notRun(run, index, source, reason);
                        progress.finished(result);
                        return result;
                    });
            if (control.stopReason() != null) {
                log.warn("The run ended early: {}", control.stopReason());
            }
        } catch (RuntimeException | Error e) {
            log.error("The run failed: {}", e.toString(), e);
            failure = e;
        }
        // without a failure every TestScript has a result, and the reason is never used
        RunResult result = publish(run, progress, mode,
                failure == null ? "the run ended" : "the run failed: " + failure);
        switch (failure) {
            case null -> {
                return result;
            }
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new IllegalStateException(failure);
        }
    }

    /**
     * Writes the reports and tells the listeners, once per run: at its end, after an unexpected exception, or when a
     * shutdown cannot wait ({@link RunControl#reportNow()}). A TestScript without a result is {@code error} if it had
     * started, {@code skip} if not.
     *
     * @param reason why a TestScript has no result, for its message
     */
    private RunResult publish(RunContext run, RunProgress progress, Mode mode, String reason) {
        return progress.publishOnce(() -> {
            List<TestScriptResult> results = new ArrayList<>();
            List<TestScriptSource> sources = progress.sources();
            for (int index = 1; index <= sources.size(); index++) {
                TestScriptResult result = progress.result(index);
                results.add(result != null ? result : unfinished(index, sources.get(index - 1),
                        progress.isStarted(index), reason));
            }
            RunResult published = reports.publish(run, new RunResult(run.runId(), run.runDirectory(), run.started(),
                    Duration.between(run.started(), clock.instant()), results, Map.of()), mode == Mode.CHECK);
            notify(listener -> listener.runFinished(run.info(), published));
            return published;
        });
    }

    /** Tells every listener; a listener that throws is logged and does not stop the run or the other listeners. */
    private void notify(java.util.function.Consumer<RunListener> event) {
        for (RunListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                log.error("The run listener {} failed: {}", listener.getClass().getName(), e.toString(), e);
            }
        }
    }

    /** The result of a TestScript that has no result of its own when the reports are written. */
    private TestScriptResult unfinished(int index, TestScriptSource source, boolean started, String reason) {
        PhaseResult phase = started
                ? PhaseResult.error(PhaseKind.LOAD, "Load", Duration.ZERO, "Did not end: " + reason)
                : PhaseResult.skipped(PhaseKind.LOAD, "Load", 0, "Not started: " + reason);
        return new TestScriptResult(index, source.suite().name(), source.baseName(), source.file().toString(),
                source.file(), null, null, clock.instant(), Duration.ZERO, List.of(phase));
    }

    /**
     * The result of a TestScript that did not start: its tests are {@code skip} with the reason. Its listeners are told
     * like for every other TestScript.
     */
    private TestScriptResult notRun(RunContext run, int index, TestScriptSource source, String reason) {
        log.warn("TestScript {} not started: {}", source.file().getFileName(), reason);
        TestScriptResult result = new TestScriptResult(index, source.suite().name(), source.baseName(),
                source.file().toString(), source.file(), null, null, clock.instant(), Duration.ZERO,
                List.of(PhaseResult.skipped(PhaseKind.LOAD, "Load", 0, "Not started: " + reason)));
        notify(listener -> listener.testScriptFinished(run.info(), result));
        return result;
    }

    /**
     * The TestScripts selected by {@code select}, in discovery order; logs how many were left out. Reads a TestScript
     * only if its name, url or tests decide; a file that cannot be read stays in if its file name is selected, so
     * that its error is reported.
     */
    private List<TestScriptSource> select(List<TestScriptSource> found, Selection selection, SelectConfig select) {
        if (selection.all()) {
            return found;
        }
        TestScriptLoader loader = new TestScriptLoader(versions);
        List<TestScriptSource> selected = new ArrayList<>();
        for (TestScriptSource source : found) {
            if (selection.selectsByFileName(source)) {
                selected.add(source);
                continue;
            }
            TestScript testScript;
            try {
                testScript = loader.map(source);
            } catch (RuntimeException e) {
                testScript = null; // loaded again when it runs, which reports the problem
            }
            if (selection.selects(source, testScript)) {
                selected.add(source);
            }
        }
        log.info("Selected {} of {} TestScript(s) ({}); {} left out", selected.size(), found.size(),
                select.describe(), found.size() - selected.size());
        return selected;
    }

    /** Runs or checks one TestScript; never throws, every problem ends up in the result. */
    private TestScriptResult runOne(RunContext run, RunControl control, Mode mode, Selection selection, int index,
                                    int total, TestScriptSource source, CapabilityCheck capabilities) {
        String label = "%02d_%s".formatted(index, source.baseName());
        Instant started = clock.instant();
        MDC.put(MDC_TEST_SCRIPT, label);
        try {
            log.info("===== TestScript {}/{}: {}", index, total, source.file());
            TestScriptResult result;
            try {
                LoadedTestScript complete = new TestScriptLoader(versions).load(source);
                LoadedTestScript loaded = selected(complete, selection);
                Map<String, String> leftOut = TestScriptChecks.keptBy(complete.testScript().tests().stream()
                        .filter(t -> !selection.selects(t)).toList());
                result = execute(run, control, mode, index, label, loaded, leftOut, started, capabilities);
            } catch (TestScriptFormatException | ValidatorUnavailableException e) {
                log.error("TestScript {} could not be loaded: {}", source.file().getFileName(), e.getMessage());
                var version = e instanceof TestScriptFormatException format ? format.fhirVersion().orElse(null) : null;
                result = new TestScriptResult(index, source.suite().name(), source.baseName(), source.file().toString(),
                        source.file(), version, null, started, Duration.between(started, clock.instant()),
                        List.of(PhaseResult.error(PhaseKind.LOAD, "Load", Duration.between(started, clock.instant()),
                                "TestScript could not be loaded: " + e.getMessage())));
            } catch (RuntimeException e) {
                log.error("Unexpected error in TestScript {}: {}", source.file().getFileName(), e.toString(), e);
                result = new TestScriptResult(index, source.suite().name(), source.baseName(), source.file().toString(),
                        source.file(), null, null, started, Duration.between(started, clock.instant()),
                        List.of(PhaseResult.error(PhaseKind.LOAD, "Unexpected error",
                                Duration.between(started, clock.instant()), "Unexpected error: " + e)));
            }
            logFinished(result);
            if (mode == Mode.RUN && run.configuration().execution().failFast()
                    && (result.outcome() == Outcome.FAIL || result.outcome() == Outcome.ERROR)) {
                control.stopStarting("fail-fast: TestScript " + label + " ended with " + result.outcome());
            }
            TestScriptResult finished = result;
            notify(listener -> listener.testScriptFinished(run.info(), finished));
            return result;
        } finally {
            MDC.remove(MDC_TEST_SCRIPT);
        }
    }

    /**
     * The TestScript with only its selected tests; setup and teardown stay. Tests left out are neither run nor
     * reported, and the checks before sending see only what runs.
     */
    private static LoadedTestScript selected(LoadedTestScript loaded, Selection selection) {
        if (!selection.selectsTests()) {
            return loaded;
        }
        TestScript testScript = loaded.testScript();
        List<TestDefinition> tests = selection.tests(testScript);
        if (tests.size() == testScript.tests().size()) {
            return loaded;
        }
        log.info("{} of {} test(s) selected: {}", tests.size(), testScript.tests().size(),
                tests.stream().map(TestDefinition::name).toList());
        return loaded.withTestScript(testScript.withTests(tests));
    }

    /**
     * @param leftOut where the responseIds and requestIds of the tests left out by the selection are kept
     */
    private TestScriptResult execute(RunContext run, RunControl control, Mode mode, int index, String label,
                                     LoadedTestScript loaded, Map<String, String> leftOut, Instant started,
                                     CapabilityCheck capabilities) {
        var testScript = loaded.testScript();
        var suite = loaded.source().suite();
        log.info("TestScript '{}' ({}, FHIR {})", testScript.displayName(),
                testScript.url() == null ? "no url" : testScript.url(), testScript.fhirVersion());
        log.debug("{} fixture(s), {} variable(s), {} profile(s), {} test(s)", testScript.fixtures().size(),
                testScript.variables().size(), testScript.profiles().size(), testScript.tests().size());
        loaded.readWarnings().forEach(w -> log.warn("Ignored while reading the TestScript: {}", w));

        Map<String, String> builtIn = ExecutionFactory.builtInValues(run, index, started);
        List<String> unsupported = TestScriptChecks.unsupportedWarnings(testScript);
        unsupported.forEach(w -> log.warn("{}", w));
        List<String> problems = new ArrayList<>(TestScriptChecks.problems(testScript, loaded.fixtureResources(),
                builtIn.keySet(), leftOut));
        problems.addAll(validateTestScript(run, loaded));
        if (!problems.isEmpty()) {
            problems.forEach(p -> log.error("{}", p));
            List<String> messages = new ArrayList<>();
            messages.add("TestScript is not valid: " + String.join("; ", problems));
            readWarnings(loaded).forEach(w -> messages.add("WARNING: " + w));
            unsupported.forEach(w -> messages.add("WARNING: " + w));
            List<PhaseResult> phases = new ArrayList<>();
            phases.add(new PhaseResult(PhaseKind.LOAD, "Load", Outcome.ERROR, Duration.between(started, clock.instant()),
                    0, List.of(), messages));
            if (mode == Mode.RUN) {
                skipAll(testScript.tests(), "Skipped: the TestScript is not valid", phases);
            }
            return result(index, loaded, null, started, phases);
        }
        if (mode == Mode.CHECK) {
            return checked(index, loaded, started, unsupported);
        }

        FhirServerConfig server = run.configuration().fhirServerOf(suite);
        Optional<String> base = server.base();
        if (base.isEmpty()) {
            log.warn("TestScript '{}' skipped: no FHIR server configured", testScript.displayName());
            List<PhaseResult> phases = new ArrayList<>();
            phases.add(PhaseResult.skipped(PhaseKind.SETUP, "Setup", 0, "Skipped: no FHIR server configured"));
            skipAll(testScript.tests(), "Skipped: no FHIR server configured", phases);
            return result(index, loaded, null, started, phases);
        }

        List<String> orderWarnings = TestScriptChecks.orderWarnings(testScript, loaded.fixtureResources());
        orderWarnings.forEach(w -> log.warn("{}", w));
        List<String> warnings = new ArrayList<>(unsupported);
        warnings.addAll(orderWarnings);
        Duration testScriptTimeout = run.configuration().execution().testScriptTimeout();
        Deadline deadline = new Deadline(control, clock, testScriptTimeout == null ? null : started.plus(testScriptTimeout),
                "the TestScript timeout of " + (testScriptTimeout == null ? "" : RunControl.describe(testScriptTimeout))
                        + " has passed");
        ExecutionEnvironment environment = executions.create(run, label, suite, server, base.get(), capabilities,
                builtIn, warnings, deadline);
        List<PhaseResult> phases = new TestScriptExecution(loaded, environment).run();
        return result(index, loaded, base.get(), started, phases);
    }

    /**
     * The result of a TestScript without problems in check mode: phase {@code Load} passed, with every warning a run
     * would give before sending anything.
     */
    private TestScriptResult checked(int index, LoadedTestScript loaded, Instant started, List<String> unsupported) {
        List<String> warnings = new ArrayList<>(readWarnings(loaded));
        warnings.addAll(unsupported);
        List<String> orderWarnings = TestScriptChecks.orderWarnings(loaded.testScript(), loaded.fixtureResources());
        orderWarnings.forEach(w -> log.warn("{}", w));
        warnings.addAll(orderWarnings);
        log.info("TestScript '{}' has no problems ({} test(s); check mode, nothing sent)",
                loaded.testScript().displayName(), loaded.testScript().tests().size());
        return result(index, loaded, null, started, List.of(new PhaseResult(PhaseKind.LOAD, "Load", Outcome.PASS,
                Duration.between(started, clock.instant()), 0, List.of(),
                warnings.stream().map(w -> "WARNING: " + w).toList())));
    }

    /** What the FHIR version module ignored while reading the TestScript, as warnings. */
    private static List<String> readWarnings(LoadedTestScript loaded) {
        return loaded.readWarnings().stream().map(w -> "Ignored while reading the TestScript: " + w).toList();
    }

    /** Validates the TestScript itself against the base specification of its version. */
    private List<String> validateTestScript(RunContext run, LoadedTestScript loaded) {
        if (!run.configuration().validation().validateTestScripts() || !validator.isEnabled()) {
            log.debug("TestScript validation skipped (disabled)");
            return List.of();
        }
        // the suite's profiles folder and IGs are passed although a TestScript needs none of them: in server mode,
        // validations with the same FHIR version, folder and IGs share one validator server
        var suite = loaded.source().suite();
        ValidationResult result = validator.validate(new ValidationRequest(loaded.original(),
                loaded.testScript().fhirVersion(), null, suite.resolvedProfilesDir(), suite.igs()));
        if (!result.isValid()) {
            return List.of("TestScript is not valid FHIR " + loaded.testScript().fhirVersion() + ":\n"
                    + ValidationResult.describe(result.errors()));
        }
        log.info("TestScript is valid FHIR {}", loaded.testScript().fhirVersion());
        return List.of();
    }

    private TestScriptResult result(int index, LoadedTestScript loaded, String fhirServer, Instant started,
                                    List<PhaseResult> phases) {
        var testScript = loaded.testScript();
        return new TestScriptResult(index, loaded.source().suite().name(), testScript.displayName(),
                testScript.url() != null ? testScript.url() : loaded.source().file().toString(), loaded.source().file(),
                testScript.fhirVersion(), fhirServer, started, Duration.between(started, clock.instant()), phases);
    }

    private static void skipAll(List<TestDefinition> tests, String reason, List<PhaseResult> phases) {
        tests.forEach(t -> phases.add(PhaseResult.skipped(PhaseKind.TEST, t.name(), t.assertionCount(), reason)));
    }

    private void logStart(RunContext run, Mode mode, List<TestScriptSource> sources) {
        RunConfiguration c = run.configuration();
        log.info("FHIR TestScript Runner - {}", run.runId());
        if (mode == Mode.CHECK) {
            log.info("  Mode:               check - the TestScripts are loaded and checked, nothing is sent");
        }
        if (mode == Mode.RUN) {
            log.info("  FHIR server:        {}", c.fhirServer().base().orElse("- (TestScripts will be skipped)"));
        }
        if (c.fhirServer().oauth2() != null) {
            log.info("  OAuth2:             client {} at {}", c.fhirServer().oauth2().clientId(),
                    c.fhirServer().oauth2().tokenUrl());
        }
        log.info("  FHIR versions:      {}", versions.versions());
        log.info("  Results:            {}", run.runDirectory().toAbsolutePath());
        log.info("  Parallelism:        {}", c.execution().parallelism());
        log.info("  Rewrite references: {}", c.execution().rewriteReferences().name().toLowerCase(java.util.Locale.ROOT));
        log.info("  Profile validation: {}", validator.description());
        if (mode == Mode.RUN) {
            log.info("  Communication logs: {}", c.reporting().communicationLog() ? run.communicationDirectory()
                    : "disabled");
        }
        c.suites().forEach(s -> log.info("  Suite '{}': {}{} (FHIR {})", s.name(),
                s.packageId() == null ? "" : "package " + s.packageId() + " in ", s.baseDir().toAbsolutePath(),
                s.fhirVersion()));
        c.suites().stream().filter(s -> !s.igs().isEmpty())
                .forEach(s -> log.info("  Suite '{}' validates with IGs {}", s.name(), s.igs()));
        if (!c.select().selectsAll()) {
            log.info("  Selected:           {}", c.select().describe());
        }
        log.info("{} TestScript(s) to {}", sources.size(), mode == Mode.CHECK ? "check" : "run");
        for (int i = 0; i < sources.size(); i++) {
            log.info("  {}. {}", i + 1, sources.get(i).file());
        }
        if (sources.isEmpty()) {
            log.warn(c.select().selectsAll() ? "No TestScripts found in the configured suites"
                    : "No TestScript is selected (" + c.select().describe() + ")");
        }
    }

    private static void logFinished(TestScriptResult result) {
        String line = "TestScript '{}' finished: {} ({} ms)";
        Object[] args = {result.name(), result.outcome(), result.duration().toMillis()};
        if (result.outcome() == Outcome.PASS) {
            log.info(line, args);
        } else if (result.outcome() == Outcome.SKIP) {
            log.warn(line, args);
        } else {
            log.error(line, args);
        }
    }
}
