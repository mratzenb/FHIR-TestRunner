package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.Exchange;
import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.http.FhirRequest;
import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.CapabilityRequirement;
import at.hl7.testing.runner.core.model.HttpMethod;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.report.ActionResult;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.source.LoadedTestScript;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Executes one TestScript: setup, every test, teardown. One instance per execution; it owns all state of that
 * execution and is used by a single thread.
 *
 * <p>Rules:
 * <ul>
 *   <li>A failed assertion is recorded as {@code fail} and the phase goes on, unless the assertion has
 *       {@code stopTestOnFail}, which ends the current phase. {@code warningOnly} turns a failure into a warning.</li>
 *   <li>An action that cannot be executed is recorded as {@code error} and ends the current phase.</li>
 *   <li>A setup that does not pass skips every test, since the tests depend on it.</li>
 *   <li>The teardown and the autodelete of fixtures always run once the setup has started.</li>
 *   <li>A server that lacks a required capability ({@code metadata.capability}) skips the whole TestScript before
 *       the setup: nothing is created, so there is nothing to tear down.</li>
 *   <li>With several servers ({@code TestScript.destination}) each operation goes to the server of its
 *       {@code destination}; autocreated fixtures are created on, and deleted from, every server; a capability is
 *       checked on the server of its {@code destination}, or on every server.</li>
 *   <li>A {@link Deadline} that has passed (run stopped, run or TestScript timeout) is checked before every action of
 *       the setup and the tests: the phase ends with {@code error} naming the reason, the remaining tests are
 *       {@code skip}. The teardown and the autodelete still run, so the server is cleaned up.</li>
 * </ul>
 */
final class TestScriptExecution {

    private static final Logger log = LoggerFactory.getLogger(TestScriptExecution.class);

    private final LoadedTestScript loaded;
    private final TestScript testScript;
    private final Servers servers;
    private final ExecutionState state = new ExecutionState();
    private final List<String> capabilityWarnings = new ArrayList<>();
    private final List<String> warnings;
    private final VariableResolver variables;
    private final RequestFactory requests;
    private final AssertionEvaluator assertions;
    private final FixtureManager fixtures;
    private final CapabilityCheck capabilities;
    private final Deadline deadline;

    /**
     * @param environment what the execution gets from its run, built by {@link ExecutionFactory}
     */
    TestScriptExecution(LoadedTestScript loaded, ExecutionEnvironment environment) {
        this.loaded = loaded;
        this.testScript = loaded.testScript();
        this.servers = environment.servers();
        this.capabilities = environment.capabilities();
        this.variables = new VariableResolver(testScript.variables(), environment.builtIn(), state,
                loaded.support().fhirPath());
        this.requests = new RequestFactory(state);
        this.assertions = new AssertionEvaluator(state, loaded.support().fhirPath(), environment.validator(),
                testScript.profiles(), testScript.fhirVersion(), environment.profilesDir(), environment.igs());
        this.fixtures = new FixtureManager(state, servers, variables::resolve, environment.rewriting());
        state.onServer(servers.first().index());
        this.warnings = environment.warnings();
        this.deadline = environment.deadline();
        state.resolveFixturesWith(fixtures::resolve);
    }

    List<PhaseResult> run() {
        List<PhaseResult> phases = new ArrayList<>();
        PhaseResult unsupported = checkCapabilities();
        if (unsupported != null) {
            phases.add(unsupported);
            String reason = "Skipped: " + (servers.several() ? "a server" : "the server") + " lacks a required capability";
            testScript.tests().forEach(t -> phases.add(PhaseResult.skipped(PhaseKind.TEST, t.name(), t.assertionCount(),
                    reason)));
            return phases;
        }
        PhaseResult setup = setup();
        phases.add(setup);
        if (setup.outcome() == Outcome.PASS) {
            for (TestDefinition test : testScript.tests()) {
                String stopped = deadline.reason();
                if (stopped != null) {
                    log.warn("Test '{}' skipped: {}", test.name(), stopped);
                    phases.add(PhaseResult.skipped(PhaseKind.TEST, test.name(), test.assertionCount(),
                            "Skipped: " + stopped));
                } else {
                    phases.add(test(test));
                }
            }
        } else {
            String stopped = deadline.reason();
            String reason = stopped != null ? "Skipped: " + stopped : "Skipped: the setup did not pass";
            for (TestDefinition test : testScript.tests()) {
                log.warn("Test '{}' {}", test.name(), reason.toLowerCase(Locale.ROOT));
                phases.add(PhaseResult.skipped(PhaseKind.TEST, test.name(), test.assertionCount(), reason));
            }
        }
        phases.add(teardown());
        return phases;
    }

    /**
     * Checks {@code metadata.capability}, each requirement on the server of its {@code destination}, or on every server.
     *
     * @return the skipped setup if a server lacks a required capability, {@code null} if the TestScript can run
     */
    private PhaseResult checkCapabilities() {
        if (testScript.capabilities().stream().noneMatch(CapabilityRequirement::required)) {
            return null;
        }
        List<String> unmet = new ArrayList<>();
        for (Servers.Endpoint server : servers.all()) {
            List<CapabilityRequirement> requirements = testScript.capabilities().stream()
                    .filter(c -> c.destination() == null || !servers.several()
                            || Objects.equals(c.destination(), server.index()))
                    .toList();
            if (requirements.stream().noneMatch(CapabilityRequirement::required)) {
                continue;
            }
            String which = servers.several() ? " of destination " + server.index() : "";
            CapabilityCheck.Result result;
            try {
                result = capabilities.check(requirements, loaded.capabilities(), server.http(), server.base());
            } catch (FhirHttpClient.ExchangeFailedException e) {
                capabilityWarnings.add("The required capabilities" + (which.isEmpty() ? "" : " of the server" + which)
                        + " were not checked: " + e.getMessage());
                continue;
            }
            capabilityWarnings.addAll(result.warnings());
            result.unmet().forEach(u -> unmet.add(which.isEmpty() ? u
                    : u.replaceFirst("^The server", "The server" + which)));
        }
        if (unmet.isEmpty()) {
            return null;
        }
        log.warn("--- Setup skipped: {} lacks a required capability", servers.several() ? "a server" : "the server");
        PhaseRecorder phase = setupRecorder();
        unmet.forEach(u -> phase.problem(Outcome.SKIP, u));
        return finish(phase);
    }

    private PhaseResult setup() {
        log.info("--- Setup");
        PhaseRecorder phase = setupRecorder();
        try {
            fixtures.register(testScript.fixtures(), loaded.fixtureResources());
            fixtures.autocreate(testScript.fixtures());
        } catch (TestScriptException | FhirHttpClient.ExchangeFailedException e) {
            phase.problem(Outcome.ERROR, "Fixture initialization failed: " + e.getMessage());
            log.error("Fixture initialization failed: {}", e.getMessage());
            return finish(phase);
        }
        runActions(testScript.setup(), phase, true);
        return finish(phase);
    }

    /** The recorder of the setup, with the warnings that concern the TestScript as a whole. */
    private PhaseRecorder setupRecorder() {
        PhaseRecorder phase = new PhaseRecorder(PhaseKind.SETUP, "Setup", assertionCount(testScript.setup()));
        // content the FHIR version module had to ignore, e.g. an R5-only element in an R4 TestScript
        loaded.readWarnings().forEach(w -> phase.warning("Ignored while reading the TestScript: " + w));
        capabilityWarnings.forEach(phase::warning);
        warnings.forEach(phase::warning);
        return phase;
    }

    private PhaseResult test(TestDefinition test) {
        log.info("--- Test '{}'", test.name());
        if (test.description() != null) {
            log.debug("Test description: {}", test.description());
        }
        PhaseRecorder phase = new PhaseRecorder(PhaseKind.TEST, test.name(), test.assertionCount());
        runActions(test.actions(), phase, true);
        return finish(phase);
    }

    private PhaseResult teardown() {
        log.info("--- Teardown");
        PhaseRecorder phase = new PhaseRecorder(PhaseKind.TEARDOWN, "Teardown", assertionCount(testScript.teardown()));
        runActions(testScript.teardown(), phase, false);
        for (String problem : fixtures.autodelete(testScript.fixtures())) {
            phase.problem(Outcome.ERROR, problem);
        }
        return finish(phase);
    }

    /**
     * Runs actions in order until one ends the phase.
     *
     * @param stoppable whether the {@link Deadline} ends the phase; not for the teardown, which cleans up
     */
    private void runActions(List<Action> actions, PhaseRecorder phase, boolean stoppable) {
        for (Action action : actions) {
            String stopped = stoppable ? deadline.reason() : null;
            if (stopped != null) {
                log.error("{} '{}' stopped: {}", phase.kind().label(), phase.name(), stopped);
                phase.problem(Outcome.ERROR, "Stopped before the next action: " + stopped);
                return;
            }
            boolean stop = switch (action) {
                case Operation operation -> !execute(operation, phase);
                case Assertion assertion -> !check(assertion, phase);
            };
            if (stop) {
                return;
            }
        }
    }

    /** @return whether the phase goes on */
    private boolean execute(Operation template, PhaseRecorder phase) {
        String described = describe(template);
        String requestId = null;
        try {
            if (template.description() != null) {
                log.debug("Operation description: {}", template.description());
            }
            Operation operation = template.withValues(variables::resolve);
            described = describe(operation);
            Servers.Endpoint server = servers.of(operation);
            state.onServer(server.index());
            log.info("Operation {}{}", described, servers.several() ? " to destination " + server.index() : "");
            FhirRequest request = requests.create(operation, server.base());
            Exchange exchange = server.http().send(request);
            requestId = exchange.requestId();
            state.exchanged(exchange, operation.requestId(), operation.responseId());
            fixtures.sentByOperation(operation, exchange);
            phase.action(new ActionResult(ActionResult.OPERATION, described, Outcome.PASS, null, requestId, false));
            return true;
        } catch (TestScriptException | FhirHttpClient.ExchangeFailedException e) {
            if (e instanceof FhirHttpClient.ExchangeFailedException failed) {
                requestId = failed.requestId();
            }
            String message = "Operation " + described + " could not be executed: " + e.getMessage();
            log.error("{}", message);
            phase.action(new ActionResult(ActionResult.OPERATION, described, Outcome.ERROR, message, requestId, false));
            return false;
        }
    }

    /** @return whether the phase goes on */
    private boolean check(Assertion template, PhaseRecorder phase) {
        String described = Describe.assertion(template);
        if (template.description() != null) {
            log.debug("Assert description: {}", template.description());
        }
        String requestId = state.lastResponse().map(Source::requestId).orElse(null);
        try {
            Assertion assertion = template.withValues(variables::resolve);
            AssertionEvaluator.Evaluation evaluation = assertions.evaluate(assertion);
            requestId = evaluation.source().requestId();
            log.info("Assert passed: {}{}", described, reference(requestId));
            phase.action(new ActionResult(ActionResult.ASSERT, described, Outcome.PASS, evaluation.warning(), requestId,
                    evaluation.warning() != null));
            return true;
        } catch (AssertionFailure e) {
            return failed(template, described, e.getMessage(), requestId, phase);
        } catch (AssertionEvaluator.ValidationSkipped e) {
            log.warn("Assert not checked: {} - {}", described, e.getMessage());
            phase.action(new ActionResult(ActionResult.ASSERT, described, Outcome.PASS, e.getMessage(), requestId, true));
            return true;
        } catch (TestScriptException | ValidatorUnavailableException e) {
            String message = "Assert " + described + " could not be evaluated: " + e.getMessage();
            log.error("{}{}", message, reference(requestId));
            phase.action(new ActionResult(ActionResult.ASSERT, described, Outcome.ERROR, message, requestId, false));
            return false;
        }
    }

    private boolean failed(Assertion assertion, String described, String reason, String requestId, PhaseRecorder phase) {
        String message = "Assert failed: " + described + reference(requestId) + " - " + reason;
        if (assertion.warningOnly()) {
            log.warn("Assert failed (warningOnly): {}{} - {}", described, reference(requestId), reason);
            phase.action(new ActionResult(ActionResult.ASSERT, described, Outcome.PASS, message, requestId, true));
            return true;
        }
        if (assertion.stopTestOnFail()) {
            log.error("Assert failed (stopTestOnFail): {}{} - {}", described, reference(requestId), reason);
            phase.action(new ActionResult(ActionResult.ASSERT, described, Outcome.FAIL, message, requestId, false));
            return false;
        }
        log.error("{}", message);
        phase.action(new ActionResult(ActionResult.ASSERT, described, Outcome.FAIL, message, requestId, false));
        return true;
    }

    private static String describe(Operation operation) {
        HttpMethod method = ActionRules.method(operation);
        return Describe.operation(operation, method == null ? "?" : method.name());
    }

    private static PhaseResult finish(PhaseRecorder phase) {
        PhaseResult result = phase.finish();
        String line = "{} '{}' result: {} ({} ms)";
        Object[] args = {result.kind().label(), result.name(), result.outcome(), result.duration().toMillis()};
        switch (result.outcome()) {
            case PASS -> log.info(line, args);
            case SKIP -> log.warn(line, args);
            case FAIL, ERROR -> log.error(line, args);
        }
        return result;
    }

    private static int assertionCount(List<Action> actions) {
        return (int) actions.stream().filter(Assertion.class::isInstance).count();
    }

    private static String reference(String requestId) {
        return requestId == null ? "" : " [" + requestId + "]";
    }
}
