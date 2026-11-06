package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import org.junit.jupiter.api.Test;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.toDestination;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** Phase rules for the TestScript as a whole: setup not passing, invalid TestScripts, no server. */
class PhasesTest extends EngineTestBase {

    @Test
    void failedSetupSkipsTheTestsButRunsTheTeardown() {
        harness.add("ts", script()
                .setup(op("capabilities"), responseCode("500"))
                .test(test("t", op("capabilities"), responseCode("200")))
                .teardown(op("search", "Patient", null, null, null, null))
                .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.phases()).extracting(PhaseResult::kind, PhaseResult::outcome).containsExactly(
                tuple(PhaseKind.SETUP, Outcome.FAIL),
                tuple(PhaseKind.TEST, Outcome.SKIP),
                tuple(PhaseKind.TEARDOWN, Outcome.PASS));
        assertThat(server.requestLines()).containsExactly("GET /metadata", "GET /Patient");
    }

    @Test
    void duplicateIdsMakeTheTestScriptInvalidBeforeAnythingIsSent() {
        harness.resource("dup", PATIENT).add("ts", script().fixture("dup", false, false)
                .test(test("t", op("search", "Patient", null, null, null, "dup"))).build());

        TestScriptResult result = harness.runOne();

        assertThat(result.phases().getFirst().kind()).isEqualTo(PhaseKind.LOAD);
        assertThat(result.phases().getFirst().messages().getFirst()).contains("Duplicate id 'dup'");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void testScriptForSeveralServersNeedsEachDestinationMapped() {
        harness.add("ts", script().destination(1).destination(2)
                .test(test("t", toDestination(op("capabilities"), 1), toDestination(op("capabilities"), 2)))
                .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.phases().getFirst().kind()).isEqualTo(PhaseKind.LOAD);
        assertThat(result.phases().getFirst().messages().getFirst())
                .contains("addresses 2 servers (destinations [1, 2])", "fhir-servers.2.base-url");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void oneUndeclaredDestinationIsTheConfiguredServer() {
        harness.add("ts", script().test(test("t", toDestination(op("capabilities"), 1), responseCode("200"))).build());

        assertThat(harness.runOne().outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void withoutServerEverythingIsSkipped() {
        harness.noServer().add("ts", script().test(test("t", op("capabilities"))).build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.SKIP);
    }
}
