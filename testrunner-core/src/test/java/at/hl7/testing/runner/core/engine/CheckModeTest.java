package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static at.hl7.testing.runner.core.engine.Scripts.check;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Check mode (F1, M3.1 of docs/wiki/next-steps.md): {@link TestRunner#check} loads and checks the TestScripts as a run
 * does before it sends anything, and reports the phase {@code Load} only.
 */
class CheckModeTest extends EngineTestBase {

    @Test
    void reportsTheProblemsOfEveryTestScriptAndSendsNothing() {
        harness.resource("patient", PATIENT)
                .add("good", script().fixture("patient", true, true)
                        .test(test("read", op("read", "Patient", null, "patient", null, null), responseCode("200")))
                        .build())
                .add("bad", script().test(test("t", op("read", null, null, "nope", null, null))).build());

        RunResult result = harness.check();

        assertThat(result.testScripts()).extracting(TestScriptResult::outcome).containsExactly(Outcome.ERROR,
                Outcome.PASS);
        TestScriptResult bad = result.testScripts().getFirst();
        assertThat(bad.phases()).as("no tests reported as skipped: nothing would have run them")
                .extracting(PhaseResult::kind).containsExactly(PhaseKind.LOAD);
        assertThat(bad.phases().getFirst().messages().getFirst())
                .contains("Test 't', action 1: targetId 'nope' is neither a fixture");
        TestScriptResult good = result.testScripts().get(1);
        assertThat(good.phases()).singleElement().satisfies(load -> {
            assertThat(load.kind()).isEqualTo(PhaseKind.LOAD);
            assertThat(load.outcome()).isEqualTo(Outcome.PASS);
            assertThat(load.messages()).isEmpty();
        });
        assertThat(good.fhirServer()).as("not run against a server").isNull();
        assertThat(server.requests()).as("not even the autocreate fixture").isEmpty();
        assertThat(result.runId()).as("a folder of its own").startsWith("check_");
        assertThat(result.runDirectory().resolve("communication")).doesNotExist();
        assertThat(result.reportFiles()).containsOnlyKeys("junit", "summary", "json");
        assertThat(result.reportFiles().get("summary")).content().contains("errors:          1",
                "targetId 'nope' is neither");
    }

    @Test
    void needsNoFhirServer() {
        harness.noServer().add("ts", script().test(test("t", op("capabilities"), responseCode("200"))).build());

        TestScriptResult result = harness.check().testScripts().getFirst();

        assertThat(result.outcome()).as("checked, not skipped").isEqualTo(Outcome.PASS);
    }

    @Test
    void warningsARunWouldGiveBeforeSendingAreOnTheLoadPhase() {
        harness.resource("patient", PATIENT).resource("organization", ORGANIZATION)
                .add("ts", script().fixture("patient", false, false).fixture("organization", false, false)
                        .test(test("t",
                                op("create", "Patient", "patient", null, null, null),
                                op("create", "Organization", "organization", null, null, null),
                                check(new Check.RequestMethod("POST"))))
                        .build());

        PhaseResult load = harness.check().testScripts().getFirst().phases().getFirst();

        assertThat(load.outcome()).as("warnings only").isEqualTo(Outcome.PASS);
        assertThat(load.messages()).satisfiesExactly(
                unsupported -> assertThat(unsupported).startsWith("WARNING: Test 't', action 3: requestMethod is not"
                        + " supported"),
                order -> assertThat(order).startsWith("WARNING: Test 't', action 1 sends fixture 'patient', which"
                        + " references Organization/org-1 – but fixture 'organization' is only created by Test 't',"
                        + " action 2"));
    }

    @Test
    void validatesTheTestScriptsWhenThatIsOn() {
        AtomicInteger validations = new AtomicInteger();
        harness.validateTestScripts().validator(validator(validations, List.of()))
                .add("ts", script().test(test("t", op("capabilities"))).build());

        PhaseResult load = harness.check().testScripts().getFirst().phases().getFirst();

        assertThat(validations).hasValue(1);
        assertThat(load.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(load.messages().getFirst()).contains("TestScript is not valid FHIR R4", "TestScript.status: required");
    }

    @Test
    void theValidatorIsOnlyNeededWhenItValidatesTheTestScripts() {
        AtomicInteger validations = new AtomicInteger();
        harness.validator(validator(validations, List.of("testrunner.validation.validator-jar is not set")))
                .add("ts", script().test(test("t", op("capabilities"))).build());

        assertThat(harness.check().outcome()).as("validate-test-scripts off: the validator is not used")
                .isEqualTo(Outcome.PASS);
        assertThat(validations).hasValue(0);
        assertThatThrownBy(() -> harness.validateTestScripts().check()).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("validator-jar is not set");
    }

    @Test
    void writesNoFhirTestReports() {
        harness.formats("junit", "testreport").add("ts", script().test(test("t", op("capabilities"))).build());

        RunResult result = harness.check();

        assertThat(result.reportFiles()).as("a TestReport says a TestScript was executed").containsOnlyKeys("junit");
        assertThat(result.runDirectory().resolve("testreports")).doesNotExist();
    }

    @Test
    void failFastDoesNotStopACheck() {
        harness.limits(null, null, true)
                .add("a", script().test(test("t", op("read", null, null, "nope", null, null))).build())
                .add("b", script().test(test("t", op("capabilities"))).build());

        assertThat(harness.check().testScripts()).extracting(TestScriptResult::outcome)
                .containsExactly(Outcome.ERROR, Outcome.PASS);
    }

    /** A validator that finds an error in every TestScript and has the given configuration problems. */
    private static ProfileValidator validator(AtomicInteger validations, List<String> configurationProblems) {
        return new ProfileValidator() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public List<String> configurationProblems() {
                return configurationProblems;
            }

            @Override
            public ValidationResult validate(ValidationRequest request) {
                validations.incrementAndGet();
                return new ValidationResult(List.of(new ValidationResult.Issue(ValidationResult.Severity.ERROR,
                        "TestScript.status", "TestScript.status: required")));
            }
        };
    }
}
