package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.Test;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures with {@code autocreate} and {@code autodelete}: order, references between them, failures. */
class FixturesTest extends EngineTestBase {

    @Test
    void autocreatesDependenciesFirstAndRewritesReferences() {
        harness.resource("patient", PATIENT).resource("org", ORGANIZATION)
                .add("ts", script().fixture("patient", true, false).fixture("org", true, false)
                        .test(test("read", op("read", "Patient", null, "patient", null, null), responseCode("200")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).startsWith("POST /Organization", "POST /Patient", "GET /Patient/2");
        assertThat(server.resource("Patient/2").path("managingOrganization").path("reference").asText())
                .isEqualTo("Organization/1");
    }

    @Test
    void autodeletesInReverseOrderAfterTheTeardown() {
        harness.resource("patient", PATIENT).resource("org", ORGANIZATION)
                .add("ts", script().fixture("patient", true, true).fixture("org", true, true)
                        .test(test("t", op("search", "Patient", null, null, null, null), responseCode("200")))
                        .build());

        harness.runOne();

        assertThat(server.requestLines()).endsWith("DELETE /Patient/2", "DELETE /Organization/1");
    }

    @Test
    void referenceCycleIsAnErrorOfTheSetupAndSkipsTheTests() {
        harness.resource("a", """
                        {"resourceType":"Patient","id":"a","link":[{"other":{"reference":"Patient/b"}}]}""")
                .resource("b", """
                        {"resourceType":"Patient","id":"b","link":[{"other":{"reference":"Patient/a"}}]}""")
                .add("ts", script().fixture("a", true, false).fixture("b", true, false)
                        .test(test("t", op("capabilities"), responseCode("200"))).build());

        TestScriptResult result = harness.runOne();

        assertThat(phase(result, "Setup").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(phase(result, "Setup").messages().getFirst()).contains("cycle");
        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.SKIP);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void rejectedFixtureNamesTheRequest() {
        server.on("POST", "/Patient", InMemoryFhirServer.Answer.json(422, "{\"resourceType\":\"OperationOutcome\"}"));
        harness.resource("patient", PATIENT)
                .add("ts", script().fixture("patient", true, false)
                        .test(test("t", op("capabilities"), responseCode("200"))).build());

        TestScriptResult result = harness.runOne();

        assertThat(phase(result, "Setup").messages().getFirst()).contains("REQ-0001", "422");
    }
}
