package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.RequestHeader;
import at.hl7.testing.runner.core.model.VariableDefinition;
import at.hl7.testing.runner.core.report.ActionResult;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import org.junit.jupiter.api.Test;

import static at.hl7.testing.runner.core.engine.Scripts.check;
import static at.hl7.testing.runner.core.engine.Scripts.on;
import static at.hl7.testing.runner.core.engine.Scripts.onRequest;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.withHeaders;
import static at.hl7.testing.runner.core.engine.Scripts.withRequestId;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code operation.requestId} and {@code assert.direction = request}: the runner's own requests can be read. */
class KeptRequestsTest extends EngineTestBase {

    @Test
    void requestIdKeepsTheRequestForVariablesAndAssertions() {
        harness.resource("patient", """
                {"resourceType":"Patient","identifier":[{"system":"urn:test","value":"${testrunner.uuid}"}]}""")
                .add("ts", script().fixture("patient", false, false)
                        .variable(new VariableDefinition("sent", null, "Patient.identifier[0].value", null, null,
                                "create-request", null))
                        .test(test("t",
                                withRequestId(op("create", "Patient", "patient", null, null, null), "create-request"),
                                op("search", "Patient", null, null, "?identifier=${sent}", null),
                                check(new Check.Expression("Bundle.total"), Operator.EQUALS, "1"),
                                on(check(new Check.ContentType("json")), "create-request"),
                                on(check(new Check.Resource("Patient")), "create-request")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requests().get(1).query()).matches("identifier=[0-9a-f-]{36}");
        assertThat(phase(result, "t").actions().get(3).requestId()).isEqualTo("REQ-0001");
    }

    @Test
    void directionRequestChecksTheLastRequestSent() {
        harness.resource("patient", PATIENT.replace("Organization/org-1", "Organization/none"))
                .add("ts", script().fixture("patient", false, false)
                        .test(test("t",
                                withHeaders(op("create", "Patient", "patient", null, null, null),
                                        new RequestHeader("Prefer", "return=representation")),
                                onRequest(check(new Check.HeaderField("Prefer"), null, "return=representation")),
                                onRequest(check(new Check.Expression("Patient.id"), Operator.EQUALS, "pat-1")),
                                check(new Check.Expression("Patient.id"), Operator.EQUALS, "1"),
                                onRequest(responseCode("201"))))
                        .build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.actions()).extracting(ActionResult::outcome)
                .containsExactly(Outcome.PASS, Outcome.PASS, Outcome.PASS, Outcome.PASS, Outcome.ERROR);
        assertThat(t.actions().get(1).description()).isEqualTo("headerField 'Prefer' 'return=representation' on"
                + " the request");
        assertThat(t.messages().getFirst()).contains("responseCode can only be checked on a response, not on the"
                + " request REQ-0001");
    }

    @Test
    void requestIdIsNoTargetId() {
        harness.resource("patient", PATIENT);
        harness.add("ts", script().fixture("patient", false, false).test(test("t",
                withRequestId(op("create", "Patient", "patient", null, null, null), "sent"),
                op("read", null, null, "sent", null, null))).build());

        TestScriptResult result = harness.runOne();

        assertThat(result.phases().getFirst().kind()).isEqualTo(PhaseKind.LOAD);
        assertThat(result.phases().getFirst().messages().getFirst())
                .contains("Test 't', action 2: targetId 'sent' is a requestId; use the responseId");
    }
}
