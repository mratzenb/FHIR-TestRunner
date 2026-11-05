package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.model.VariableDefinition;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.TestScriptResult;
import org.junit.jupiter.api.Test;

import static at.hl7.testing.runner.core.engine.Scripts.check;
import static at.hl7.testing.runner.core.engine.Scripts.on;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;

/** Problems found before anything is sent: phase {@code Load} is {@code error}, the server sees no request. */
class ChecksBeforeSendingTest extends EngineTestBase {

    private String loadError(TestScript script) {
        harness.add("ts", script);
        TestScriptResult result = harness.runOne();
        assertThat(result.phases().getFirst().kind()).isEqualTo(PhaseKind.LOAD);
        assertThat(result.phases().getFirst().outcome()).isEqualTo(Outcome.ERROR);
        assertThat(server.requests()).isEmpty();
        return result.phases().getFirst().messages().getFirst();
    }

    @Test
    void undefinedVariable() {
        assertThat(loadError(script().test(test("t", op("search", "Patient", null, null, "?id=${nope}", null)))
                .build())).contains("Variable 'nope' (Test 't', action 1) is used but not defined");
    }

    @Test
    void unknownValueOfTheRunner() {
        assertThat(loadError(script().test(test("t", op("search", "Patient", null, null,
                "?id=${testrunner.nope}", null))).build()))
                .contains("'${testrunner.nope}'", "is no value of the runner", "${testrunner.uuid}");
    }

    @Test
    void undefinedVariableInAFixtureIsNamedWithTheFixture() {
        harness.resource("p", """
                {"resourceType":"Patient","name":[{"family":"${nope}"}]}""");

        assertThat(loadError(script().fixture("p", true, false).test(test("t", op("capabilities"))).build()))
                .contains("Variable 'nope' (Fixture 'p' (autocreate)) is used but not defined");
    }

    @Test
    void unknownSourceIds() {
        harness.resource("patient", PATIENT);

        String message = loadError(script().fixture("patient", false, false).test(test("t",
                op("create", "Patient", "patinet", null, null, "created"),
                op("read", null, null, "creatd", null, null),
                on(responseCode("200"), "nope"),
                check(new Check.MinimumId("minimun")))).build());

        assertThat(message).contains(
                "Test 't', action 1: sourceId 'patinet' is neither a fixture nor the responseId or requestId of an"
                        + " operation",
                "Test 't', action 2: targetId 'creatd' is neither",
                "Test 't', action 3: sourceId 'nope' is neither",
                "Test 't', action 4: minimumId 'minimun' is neither");
    }

    @Test
    void responseIdUsedBeforeTheOperationKeepingIt() {
        String message = loadError(script()
                .setup(op("read", null, null, "later", null, null))
                .test(test("t", op("capabilities", null, null, null, null, "later"))).build());

        assertThat(message).contains("Setup, action 1: targetId 'later' is not there yet, it is kept by"
                + " Test 't', action 1");
    }

    @Test
    void variableOnAResponseUsedInAnAutocreatedFixture() {
        harness.resource("obs", """
                {"resourceType":"Observation","subject":{"reference":"Patient/${pid}"}}""");

        String message = loadError(script().fixture("obs", true, false)
                .variable(new VariableDefinition("pid", null, "Patient.id", null, null, "created", null))
                .test(test("t", op("capabilities", null, null, null, null, "created"))).build());

        assertThat(message).contains("Fixture 'obs' (autocreate): variable 'pid' reads 'created', which is not"
                + " there yet");
    }

    @Test
    void variableOnTheLastResponseBeforeAnyOperation() {
        String message = loadError(script()
                .variable(new VariableDefinition("loc", null, null, null, "Location", null, null))
                .test(test("t", op("read", "Patient", null, null, "/${loc}", null))).build());

        assertThat(message).contains("Test 't', action 1: variable 'loc' reads the last response, but no"
                + " operation runs before");
    }

    @Test
    void headerOfAFixture() {
        harness.resource("p", PATIENT);

        String message = loadError(script().fixture("p", false, false)
                .variable(new VariableDefinition("etag", null, null, null, "ETag", "p", null))
                .test(test("t", op("read", "Patient", null, null, "/${etag}", null))).build());

        assertThat(message).contains("variable 'etag' reads header ETag, but 'p' is a fixture, not a response");
    }

    @Test
    void targetIdOfAFixtureThatIsNotCreated() {
        harness.resource("p", PATIENT);

        assertThat(loadError(script().fixture("p", false, false)
                .test(test("t", op("read", null, null, "p", null, null))).build()))
                .contains("targetId 'p' is a fixture without autocreate, so it has no id on the server");
    }

    @Test
    void assertionBeforeAnyOperation() {
        assertThat(loadError(script().test(test("t", responseCode("200"))).build()))
                .contains("Test 't', action 1: the assertion has no sourceId and no operation runs before it");
    }

    @Test
    void undeclaredProfile() {
        assertThat(loadError(script().profile("p", "http://example.org/StructureDefinition/p")
                .test(test("t", op("capabilities"), check(new Check.ValidateProfile("q")))).build()))
                .contains("Test 't', action 2: validateProfileId 'q' is not declared in TestScript.profile"
                        + " (declared: [p])");
    }

    @Test
    void everyProblemIsListed() {
        String message = loadError(script().test(test("t",
                op("search", "Patient", null, null, "?a=${a}&b=${b}", null),
                check(new Check.MinimumId("m")))).build());

        assertThat(message).contains("Variable 'a'", "Variable 'b'", "minimumId 'm'");
    }

    /** D2 of docs/wiki/next-steps.md: rules that hold whatever the values are, checked before fixtures are created. */
    @Test
    void operatorTheCheckDoesNotAllowIsFoundBeforeTheSetup() {
        harness.resource("p", PATIENT);

        String message = loadError(script().fixture("p", true, false).test(test("t",
                op("capabilities"),
                check(new Check.ContentType("json"), Operator.GREATER_THAN, "x"),
                check(new Check.MinimumId("p"), Operator.CONTAINS, null))).build());

        assertThat(message).contains(
                "Test 't', action 2: Operator greaterThan cannot be used with contentType (allowed: contains,"
                        + " notContains, equals, notEquals)",
                "Test 't', action 3: Operator contains cannot be used with minimumId (allowed: equals)");
    }

    @Test
    void operatorWithoutTheValueItNeeds() {
        assertThat(loadError(script().test(test("t",
                op("capabilities"),
                check(new Check.HeaderField("ETag"), Operator.EQUALS, null),
                check(new Check.HeaderField("ETag"), Operator.NOT_EMPTY, null))).build()))
                .contains("Test 't', action 2: Operator equals needs a value or compareToSourceId")
                .doesNotContain("action 3");
    }

    @Test
    void operationsThatCannotBeBuilt() {
        harness.resource("p", PATIENT);

        String message = loadError(script().fixture("p", true, false).test(test("t",
                op("create", "Patient", "p", "p", null, null),
                op("create", "Patient", "p", null, "/1", null),
                op("transaction", null, "p", null, "?x=1", null),
                op("custom", "Patient", null, null, null, null),
                op("read"),
                op("read", null, null, null, "/1", null),
                op("update", "Patient", null, null, "/1", null))).build());

        assertThat(message).contains(
                "Test 't', action 1: create must not have a targetId",
                "Test 't', action 2: create must not have params",
                "Test 't', action 3: transaction must not have params",
                "Test 't', action 4: The HTTP method cannot be determined: the operation has no method and the"
                        + " unknown type 'custom'",
                "Test 't', action 5: The URL cannot be determined for operation 'read': it has no resource, params,"
                        + " url or targetId",
                "Test 't', action 6: Operation 'read' with params needs a resource type",
                "Test 't', action 7: The PUT of operation 'update' has no sourceId, so there is nothing to send");
    }

    @Test
    void operationsWhoseValuesAreOnlyKnownWhenTheyRunAreAccepted() {
        harness.resource("p", PATIENT)
                .add("ts", script().fixture("p", false, false)
                        .variable(new VariableDefinition("fixture", "p", null, null, null, null, null))
                        .test(test("t",
                                op("create", null, "${fixture}", null, null, "created"), responseCode("201"),
                                op("capabilities"),
                                check(new Check.Expression("CapabilityStatement.status.exists()"))))
                        .build());

        assertThat(harness.runOne().outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).containsExactly("POST /Patient", "GET /metadata");
    }

    @Test
    void responseKeptInAnEarlierTestAndPlaceholdersInIdsAreAccepted() {
        harness.add("ts", script()
                .variable(new VariableDefinition("kept", "caps", null, null, null, null, null))
                .test(test("first", op("capabilities", null, null, null, null, "caps")))
                .test(test("second", op("capabilities"), on(responseCode("200"), "caps"),
                        on(responseCode("200"), "${kept}")))
                .build());

        assertThat(harness.runOne().outcome()).isEqualTo(Outcome.PASS);
    }
}
