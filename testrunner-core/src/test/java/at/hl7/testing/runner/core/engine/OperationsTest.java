package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.RequestHeader;
import at.hl7.testing.runner.core.model.VariableDefinition;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.Test;

import static at.hl7.testing.runner.core.engine.Scripts.noEncoding;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;

/** Operations: the request each type sends, and what is kept of the response. */
class OperationsTest extends EngineTestBase {

    @Test
    void createKeepsTheResponseForLaterOperationsAndVariables() {
        harness.resource("patient", PATIENT)
                .add("ts", script().fixture("patient", false, false)
                        .variable(new VariableDefinition("pid", null, "Patient.id", null, null, "created", null))
                        .test(test("t",
                                op("create", "Patient", "patient", null, null, "created"),
                                responseCode("201"),
                                op("read", "Patient", null, null, "/${pid}", "read"),
                                responseCode("200"),
                                op("vread", "Patient", null, "read", null, null),
                                responseCode("200")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).containsExactly("POST /Patient", "GET /Patient/1", "GET /Patient/1/_history/1");
    }

    @Test
    void updatePutsTheFixtureWithTheIdOfTheUrl() {
        harness.resource("patient", PATIENT)
                .add("ts", script().fixture("patient", false, false)
                        .test(test("t", op("update", "Patient", "patient", null, "/other-id", null), responseCode("200,201")))
                        .build());

        harness.runOne();

        InMemoryFhirServer.Request put = server.requests().getFirst();
        assertThat(put.line()).isEqualTo("PUT /Patient/other-id");
        assertThat(put.body()).contains("\"id\" : \"other-id\"");
        assertThat(put.header("Content-Type")).isEqualTo("application/fhir+json");
    }

    @Test
    void conditionalUpdateSendsTheFixtureAsWritten() {
        harness.resource("patient", PATIENT)
                .add("ts", script().fixture("patient", false, false)
                        .test(test("t",
                                op("update", "Patient", "patient", null, "?name=Bucher", null), responseCode("201"),
                                op("update", "Patient", "patient", null, "?name=Bucher", null), responseCode("200")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        InMemoryFhirServer.Request put = server.requests().getFirst();
        assertThat(put.line()).isEqualTo("PUT /Patient");
        assertThat(put.query()).isEqualTo("name=Bucher");
        assertThat(put.body()).isEqualTo(PATIENT);
        assertThat(server.resource("Patient/pat-1")).isNotNull();
    }

    @Test
    void capabilitiesTransactionAndSearchUrls() {
        harness.resource("bundle", "{\"resourceType\":\"Bundle\",\"type\":\"batch\"}")
                .add("ts", script().fixture("bundle", false, false)
                        .test(test("t",
                                op("capabilities"), responseCode("200"),
                                op("batch", null, "bundle", null, null, null), responseCode("200"),
                                op("search", "Patient", null, null, "?family=Müller Meier", null), responseCode("200")))
                        .build());

        harness.runOne();

        assertThat(server.requestLines()).containsExactly("GET /metadata", "POST /", "GET /Patient");
        assertThat(server.requests().get(2).query()).isEqualTo("family=M%C3%BCller%20Meier");
    }

    @Test
    void requestHeadersAndAcceptAreSent() {
        harness.add("ts", script().test(test("t",
                Scripts.withHeaders(op("search", "Patient", null, null, null, null), new RequestHeader("Prefer", "return=minimal")),
                responseCode("200"))).build());

        harness.runOne();

        assertThat(server.requests().getFirst().header("Prefer")).isEqualTo("return=minimal");
        assertThat(server.requests().getFirst().header("Accept")).isEqualTo("application/fhir+json");
    }

    @Test
    void operationThatCannotBeBuiltWithItsValuesIsAnErrorAndEndsTheTest() {
        harness.add("ts", script()
                        .variable(new VariableDefinition("type", "../x y", null, null, null, null, null))
                        .test(test("t",
                                noEncoding(op("read", "${type}", null, null, "/1", null)),
                                responseCode("200")))
                        .build());

        TestScriptResult result = harness.runOne();

        PhaseResult t = phase(result, "t");
        assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(t.messages().getFirst()).contains("Invalid request URL");
        assertThat(t.actions()).hasSize(1);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void unreachableServerIsAnErrorNamingTheRequest() {
        server.close();
        harness.add("ts", script().test(test("t", op("capabilities"), responseCode("200"))).build());

        TestScriptResult result = harness.runOne();

        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(phase(result, "t").actions().getFirst().requestId()).isEqualTo("REQ-0001");
    }
}
