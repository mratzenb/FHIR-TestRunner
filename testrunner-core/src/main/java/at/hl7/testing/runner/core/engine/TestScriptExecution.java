package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.http.Exchange;
import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.http.FhirRequest;
import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.report.ActionResult;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.source.LoadedTestScript;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * </ul>
 */
final class TestScriptExecution {

    private static final Logger log = LoggerFactory.getLogger(TestScriptExecution.class);

    private final LoadedTestScript loaded;
    private final TestScript testScript;
    private final FhirHttpClient http;
    private final ExecutionState state = new ExecutionState();
    private final VariableResolver variables;
    private final RequestFactory requests;
    private final AssertionEvaluator assertions;
    private final FixtureManager fixtures;

    TestScriptExecution(LoadedTestScript loaded, FhirHttpClient http, String fhirBase, ProfileValidator validator,
                        Path profilesDir) {
        this.loaded = loaded;
        this.testScript = loaded.testScript();
        this.http = http;
        this.variables = new VariableResolver(testScript.variables(), state, loaded.support().fhirPath());
        this.requests = new RequestFactory(fhirBase, state);
        this.assertions = new AssertionEvaluator(state, loaded.support().fhirPath(), validator, testScript.profiles(),
                testScript.fhirVersion(), profilesDir);
        this.fixtures = new FixtureManager(state, http, fhirBase);
    }

    List<PhaseResult> run() {
        List<PhaseResult> phases = new ArrayList<>();
        PhaseResult setup = setup();
        phases.add(setup);
        if (setup.outcome() == Outcome.PASS) {
            for (TestDefinition test : testScript.tests()) {
                phases.add(test(test));
            }
        } else {
            String reason = "Skipped: the setup did not pass";
            for (TestDefinition test : testScript.tests()) {
                log.warn("Test '{}' skipped: the setup did not pass", test.name());
                phases.add(PhaseResult.skipped(PhaseKind.TEST, test.name(), test.assertionCount(), reason));
            }
        }
        phases.add(teardown());
        return phases;
    }

    private PhaseResult setup() {
        log.info("--- Setup");
        PhaseRecorder phase = new PhaseRecorder(PhaseKind.SETUP, "Setup", assertionCount(testScript.setup()));
        // content the FHIR version module had to ignore, e.g. an R5-only element in an R4 TestScript
        loaded.readWarnings().forEach(w -> phase.warning("Ignored while reading the TestScript: " + w));
        try {
            fixtures.register(testScript.fixtures(), loaded.fixtureResources());
            fixtures.autocreate(testScript.fixtures());
        } catch (TestScriptException | FhirHttpClient.ExchangeFailedException e) {
            phase.problem(Outcome.ERROR, "Fixture initialization failed: " + e.getMessage());
            log.error("Fixture initialization failed: {}", e.getMessage());
            return finish(phase);
        }
        runActions(testScript.setup(), phase);
        return finish(phase);
    }

    private PhaseResult test(TestDefinition test) {
        log.info("--- Test '{}'", test.name());
        if (test.description() != null) {
            log.debug("Test description: {}", test.description());
        }
        PhaseRecorder phase = new PhaseRecorder(PhaseKind.TEST, test.name(), test.assertionCount());
        runActions(test.actions(), phase);
        return finish(phase);
    }

    private PhaseResult teardown() {
        log.info("--- Teardown");
        PhaseRecorder phase = new PhaseRecorder(PhaseKind.TEARDOWN, "Teardown", assertionCount(testScript.teardown()));
        runActions(testScript.teardown(), phase);
        for (String problem : fixtures.autodelete(testScript.fixtures())) {
            phase.problem(Outcome.ERROR, problem);
        }
        return finish(phase);
    }

    /** Runs actions in order until one ends the phase. */
    private void runActions(List<Action> actions, PhaseRecorder phase) {
        for (Action action : actions) {
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
            log.info("Operation {}", described);
            FhirRequest request = requests.create(operation);
            Exchange exchange = http.send(request);
            requestId = exchange.requestId();
            state.responseReceived(exchange, operation.responseId());
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
        String method = operation.method() != null ? operation.method().name()
                : operation.type() != null && operation.type().defaultMethod() != null
                ? operation.type().defaultMethod().name() : "?";
        return Describe.operation(operation, method);
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
