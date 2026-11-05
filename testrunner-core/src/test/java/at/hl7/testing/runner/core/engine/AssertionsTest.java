package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.report.ActionResult;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static at.hl7.testing.runner.core.engine.Scripts.check;
import static at.hl7.testing.runner.core.engine.Scripts.on;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.stopTestOnFail;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.warningOnly;
import static org.assertj.core.api.Assertions.assertThat;

/** Assertions: the checks, {@code fail} versus {@code error}, {@code stopTestOnFail}, {@code warningOnly}. */
class AssertionsTest extends EngineTestBase {

    private TestScriptResult runAfterCapabilities(Assertion... assertions) {
        var actions = new ArrayList<Action>();
        actions.add(op("capabilities"));
        actions.addAll(List.of(assertions));
        harness.add("ts", script().test(test("t", actions.toArray(Action[]::new))).build());
        return harness.runOne();
    }

    @Test
    void passingChecks() {
        TestScriptResult result = runAfterCapabilities(
                responseCode("200"),
                check(new Check.Response("okay", 200)),
                check(new Check.ContentType("json")),
                check(new Check.ContentType("application/fhir+json"), Operator.EQUALS, null),
                check(new Check.Resource("CapabilityStatement")),
                check(new Check.HeaderField("Content-Type"), Operator.NOT_EMPTY, null),
                check(new Check.Expression("CapabilityStatement.fhirVersion"), Operator.EQUALS, "4.0.1"),
                check(new Check.Expression("CapabilityStatement.kind.exists()")),
                check(new Check.Path("$.format[0]"), Operator.EQUALS, "json"),
                check(new Check.Path("$.status"), Operator.IN, "draft,active"));

        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.PASS);
        assertThat(phase(result, "t").actions()).allMatch(a -> a.outcome() == Outcome.PASS);
    }

    @Test
    void failedAssertionIsRecordedAndTheTestGoesOn() {
        TestScriptResult result = runAfterCapabilities(responseCode("201"), check(new Check.ContentType("xml")),
                responseCode("200"));

        PhaseResult t = phase(result, "t");
        assertThat(t.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(t.actions()).extracting(ActionResult::outcome)
                .containsExactly(Outcome.PASS, Outcome.FAIL, Outcome.FAIL, Outcome.PASS);
        assertThat(t.messages().getFirst()).contains("REQ-0001", "Expected '201', got '200'");
    }

    @Test
    void stopTestOnFailEndsTheTestButNotTheTestScript() {
        harness.add("ts", script()
                .test(test("first", op("capabilities"), stopTestOnFail(responseCode("500")), responseCode("200")))
                .test(test("second", op("capabilities"), responseCode("200")))
                .build());

        TestScriptResult result = harness.runOne();

        assertThat(phase(result, "first").actions()).hasSize(2);
        assertThat(phase(result, "first").outcome()).isEqualTo(Outcome.FAIL);
        assertThat(phase(result, "second").outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void warningOnlyWinsOverStopTestOnFail() {
        TestScriptResult result = runAfterCapabilities(warningOnly(stopTestOnFail(responseCode("500"))), responseCode("200"));

        PhaseResult t = phase(result, "t");
        assertThat(t.outcome()).isEqualTo(Outcome.PASS);
        assertThat(t.actions()).hasSize(3);
        assertThat(t.messages()).anyMatch(m -> m.startsWith("WARNING:"));
    }

    @Test
    void assertionOnAKeptResponseBySourceId() {
        harness.add("ts", script().test(test("t",
                op("capabilities", null, null, null, null, "caps"),
                op("search", "Patient", null, null, null, null),
                on(check(new Check.Resource("CapabilityStatement")), "caps"),
                check(new Check.Resource("Bundle")))).build());

        assertThat(harness.runOne().outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void compareToSourceUsesTheValueOfTheOtherSource() {
        var compare = new Assertion(
                new Check.Expression("CapabilityStatement.fhirVersion"), null, null, null, Operator.EQUALS, null,
                null, "caps", "CapabilityStatement.fhirVersion", null, false, false);
        harness.add("ts", script().test(test("t",
                op("capabilities", null, null, null, null, "caps"),
                op("capabilities"),
                compare)).build());

        assertThat(harness.runOne().outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void unsupportedChecksAreErrors() {
        TestScriptResult result = runAfterCapabilities(check(new Check.RequestMethod("GET")));

        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(phase(result, "t").messages().getFirst()).contains("requestMethod", "not supported");
    }

    @Test
    void minimumIdPassesWhenTheResponseContainsTheFixtureWhateverItsId() {
        harness.resource("patient", PATIENT)
                .resource("minimum", """
                        {"resourceType":"Patient","id":"other","name":[{"family":"Bucher"}]}""")
                .add("ts", script().fixture("patient", false, false).fixture("minimum", false, false)
                        .test(test("t", op("create", "Patient", "patient", null, null, null),
                                check(new Check.MinimumId("minimum"))))
                        .build());

        assertThat(harness.runOne().outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void minimumIdListsEveryDifference() {
        harness.resource("patient", PATIENT)
                .resource("minimum", """
                        {"resourceType":"Patient","name":[{"family":"Meier"}],"gender":"female"}""")
                .add("ts", script().fixture("patient", false, false).fixture("minimum", false, false)
                        .test(test("t", op("create", "Patient", "patient", null, null, null),
                                check(new Check.MinimumId("minimum"))))
                        .build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(t.messages().getFirst()).contains("the response of REQ-0001 does not contain everything of"
                + " minimumId 'minimum'", "Patient.name.family: expected 'Meier', found 'Bucher'",
                "Patient.gender: missing, expected female");
    }

    @Test
    void navigationLinksNeedFirstLastAndNext() {
        server.on("GET", "/Patient", InMemoryFhirServer.Answer.json(200, """
                {"resourceType":"Bundle","type":"searchset","link":[
                  {"relation":"self","url":"http://x/Patient"},{"relation":"first","url":"http://x/Patient?p=1"},
                  {"relation":"next","url":"http://x/Patient?p=2"},{"relation":"last","url":"http://x/Patient?p=9"}]}"""));
        harness.add("ts", script().test(test("t",
                op("search", "Patient", null, null, null, "page"), check(new Check.NavigationLinks(true)),
                op("capabilities"), on(check(new Check.NavigationLinks(true)), "page"),
                check(new Check.NavigationLinks(true)))).build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.actions()).extracting(ActionResult::outcome)
                .containsExactly(Outcome.PASS, Outcome.PASS, Outcome.PASS, Outcome.PASS, Outcome.FAIL);
        assertThat(t.messages().getFirst()).contains("navigationLinks needs a Bundle", "a CapabilityStatement");
    }

    @Test
    void navigationLinksFalseExpectsTheLinksToBeIncomplete() {
        harness.add("ts", script().test(test("t", op("search", "Patient", null, null, null, null),
                check(new Check.NavigationLinks(false)), check(new Check.NavigationLinks(true)))).build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.actions()).extracting(ActionResult::outcome)
                .containsExactly(Outcome.PASS, Outcome.PASS, Outcome.FAIL);
        assertThat(t.messages().getFirst()).contains("missing: first, last, next");
    }

    @Test
    void validateProfileWithValidationDisabledIsAWarning() {
        harness.add("ts", script().profile("p", "http://example.org/StructureDefinition/p").test(test("t",
                op("capabilities"), check(new Check.ValidateProfile("p")))).build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.outcome()).isEqualTo(Outcome.PASS);
        assertThat(t.messages()).anyMatch(m -> m.contains("validation is disabled"));
    }
}
