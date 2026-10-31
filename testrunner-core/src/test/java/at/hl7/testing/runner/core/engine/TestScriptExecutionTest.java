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
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static at.hl7.testing.runner.core.engine.Scripts.check;
import static at.hl7.testing.runner.core.engine.Scripts.on;
import static at.hl7.testing.runner.core.engine.Scripts.onRequest;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.stopTestOnFail;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.toDestination;
import static at.hl7.testing.runner.core.engine.Scripts.warningOnly;
import static at.hl7.testing.runner.core.engine.Scripts.withHeaders;
import static at.hl7.testing.runner.core.engine.Scripts.withRequestId;
import static org.assertj.core.api.Assertions.assertThat;

class TestScriptExecutionTest {

    static final String PATIENT = """
            {"resourceType":"Patient","id":"pat-1","name":[{"family":"Bucher"}],
             "managingOrganization":{"reference":"Organization/org-1"}}""";
    static final String ORGANIZATION = """
            {"resourceType":"Organization","id":"org-1","name":"Burgers"}""";

    @TempDir
    Path dir;
    InMemoryFhirServer server;
    Harness harness;

    @BeforeEach
    void start() {
        server = InMemoryFhirServer.start("4.0.1");
        harness = new Harness(dir, server.baseUrl());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static PhaseResult phase(TestScriptResult result, String name) {
        return result.phases().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Nested
    class Fixtures {

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

    @Nested
    class Operations {

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
        void operationThatCannotBeBuiltIsAnErrorAndEndsTheTest() {
            harness.add("ts", script().test(test("t",
                    op("read"),
                    responseCode("200"))).build());

            TestScriptResult result = harness.runOne();

            PhaseResult t = phase(result, "t");
            assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
            assertThat(t.messages().getFirst()).contains("The URL cannot be determined");
            assertThat(t.actions()).hasSize(1);
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

    @Nested
    class Assertions {

        private TestScriptResult runAfterCapabilities(at.hl7.testing.runner.core.model.Assertion... assertions) {
            var actions = new java.util.ArrayList<at.hl7.testing.runner.core.model.Action>();
            actions.add(op("capabilities"));
            actions.addAll(List.of(assertions));
            harness.add("ts", script().test(test("t", actions.toArray(at.hl7.testing.runner.core.model.Action[]::new))).build());
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
            var compare = new at.hl7.testing.runner.core.model.Assertion(
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
        void invalidOperatorForTheCheckIsAnError() {
            TestScriptResult result = runAfterCapabilities(check(new Check.ContentType("json"), Operator.GREATER_THAN, null));

            assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.ERROR);
            assertThat(phase(result, "t").messages().getFirst()).contains("greaterThan cannot be used with contentType");
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

    @Nested
    class Capabilities {

        static final String NEEDS_READ_AND_DELETE = """
                {"resourceType":"CapabilityStatement","id":"needs","url":"http://example.org/CapabilityStatement/needs",
                 "name":"Needs","status":"active","date":"2026-01-01","kind":"requirements","fhirVersion":"4.0.1",
                 "format":["json"],"rest":[{"mode":"server","resource":[
                   {"type":"Patient","interaction":[{"code":"read"},{"code":"delete"}]}]}]}""";

        @BeforeEach
        void serverSupportsPatientRead() {
            server.on("GET", "/metadata", InMemoryFhirServer.Answer.json(200, """
                    {"resourceType":"CapabilityStatement","status":"active","date":"2026-10-01","kind":"instance",
                     "fhirVersion":"4.0.1","format":["xml","json"],"rest":[{"mode":"server","resource":[
                       {"type":"Organization","interaction":[{"code":"read"}]},
                       {"type":"Patient","interaction":[{"code":"search-type"},{"code":"read"}]}]}]}"""));
        }

        @Test
        void serverWithoutARequiredCapabilitySkipsTheTestScriptBeforeTheSetup() {
            harness.exampleInstance("CapabilityStatement-needs.json", NEEDS_READ_AND_DELETE)
                    .resource("patient", PATIENT)
                    .add("ts", script().requires("http://example.org/CapabilityStatement/needs")
                            .fixture("patient", true, true)
                            .setup(op("capabilities"))
                            .test(test("t", op("search", "Patient", null, null, null, null), responseCode("200")))
                            .build());

            TestScriptResult result = harness.runOne();

            assertThat(result.outcome()).isEqualTo(Outcome.SKIP);
            assertThat(result.phases()).extracting(PhaseResult::kind, PhaseResult::outcome).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(PhaseKind.SETUP, Outcome.SKIP),
                    org.assertj.core.groups.Tuple.tuple(PhaseKind.TEST, Outcome.SKIP));
            assertThat(phase(result, "Setup").messages().getFirst()).contains("REQ-0001",
                    "'needs http://example.org/CapabilityStatement/needs'",
                    "CapabilityStatement.rest.resource.interaction[1]: no match for {code=delete}");
            assertThat(server.requestLines()).as("only the CapabilityStatement, no fixture").containsExactly("GET /metadata");
        }

        @Test
        void serverWithTheRequiredCapabilitiesRunsTheTestScript() {
            harness.exampleInstance("CapabilityStatement-needs.json", NEEDS_READ_AND_DELETE.replace(",{\"code\":\"delete\"}", ""))
                    .add("ts", script().requires("http://example.org/CapabilityStatement/needs")
                            .test(test("t", op("search", "Patient", null, null, null, null), responseCode("200")))
                            .build());

            TestScriptResult result = harness.runOne();

            assertThat(result.outcome()).isEqualTo(Outcome.PASS);
            assertThat(server.requestLines()).containsExactly("GET /metadata", "GET /Patient");
        }

        @Test
        void aServerOfferingMoreThanRequiredMeetsTheRequirement() {
            server.on("GET", "/metadata", InMemoryFhirServer.Answer.json(200, """
                    {"resourceType":"CapabilityStatement","status":"active","kind":"instance","fhirVersion":"4.0.1",
                     "format":["application/fhir+json","application/fhir+xml"],"rest":[{"mode":"server","resource":[
                       {"type":"Patient","versioning":"versioned-update","updateCreate":true,
                        "conditionalDelete":"multiple","conditionalRead":"full-support",
                        "interaction":[{"code":"read"},{"code":"update"}]}]}]}"""));
            harness.exampleInstance("CapabilityStatement-needs.json", """
                            {"resourceType":"CapabilityStatement","url":"http://example.org/CapabilityStatement/needs",
                             "status":"active","kind":"requirements","fhirVersion":"4.0.1","format":["json"],
                             "rest":[{"mode":"server","resource":[{"type":"Patient","versioning":"versioned",
                               "updateCreate":false,"conditionalDelete":"not-supported","conditionalRead":"modified-since",
                               "interaction":[{"code":"read"}]}]}]}""")
                    .add("ts", script().requires("http://example.org/CapabilityStatement/needs")
                            .test(test("t", op("capabilities"), responseCode("200"))).build());

            TestScriptResult result = harness.runOne();

            assertThat(result.outcome()).isEqualTo(Outcome.PASS);
            assertThat(server.requestLines()).containsExactly("GET /metadata", "GET /metadata");
        }

        @Test
        void aServerOfferingLessIsReportedPerValue() {
            var differences = at.hl7.testing.runner.core.content.FhirElement.of(
                            at.hl7.testing.runner.core.model.Body.json("""
                                    {"rest":[{"resource":[{"type":"Patient","versioning":"versioned-update",
                                      "updateCreate":true,"conditionalDelete":"single"}]}],"format":["xml"]}"""))
                    .notContainedIn(at.hl7.testing.runner.core.content.FhirElement.of(
                            at.hl7.testing.runner.core.model.Body.json("""
                                    {"rest":[{"resource":[{"type":"Patient","versioning":"versioned",
                                      "updateCreate":false,"conditionalDelete":"not-supported"}]}],
                                     "format":["application/fhir+json"]}""")), "CapabilityStatement",
                            CapabilityCheck.AT_LEAST);

            assertThat(differences).containsExactlyInAnyOrder(
                    "CapabilityStatement.rest.resource.versioning: expected 'versioned-update', found 'versioned'",
                    "CapabilityStatement.rest.resource.updateCreate: expected 'true', found 'false'",
                    "CapabilityStatement.rest.resource.conditionalDelete: expected 'single', found 'not-supported'",
                    "CapabilityStatement.format: expected 'xml', found 'application/fhir+json'");
        }

        @Test
        void theServerIsAskedOncePerRun() {
            harness.exampleInstance("CapabilityStatement-needs.json", NEEDS_READ_AND_DELETE);
            for (String name : List.of("a", "b", "c")) {
                harness.add(name, script().requires("http://example.org/CapabilityStatement/needs")
                        .test(test("t", op("capabilities"))).build());
            }

            var run = harness.run();

            assertThat(run.testScripts()).allMatch(r -> r.outcome() == Outcome.SKIP);
            assertThat(server.requestLines()).containsExactly("GET /metadata");
        }

        @Test
        void requiredCapabilityThatIsNotAmongTheExampleInstancesIsAWarning() {
            harness.add("ts", script().requires("http://example.org/CapabilityStatement/unknown")
                    .test(test("t", op("capabilities"), responseCode("200"))).build());

            TestScriptResult result = harness.runOne();

            assertThat(result.outcome()).isEqualTo(Outcome.PASS);
            assertThat(phase(result, "Setup").messages()).anyMatch(m -> m.startsWith("WARNING:")
                    && m.contains("not checked") && m.contains("not among the Example Instances"));
            assertThat(server.requestLines()).as("nothing to compare, so the server is not asked")
                    .containsExactly("GET /metadata");
        }
    }

    /** {@code operation.requestId} and {@code assert.direction = request}: the runner's own requests can be read. */
    @Nested
    class Requests {

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

    /** Problems found before anything is sent: phase {@code Load} is {@code error}, the server sees no request. */
    @Nested
    class Checks {

        private String loadError(at.hl7.testing.runner.core.model.TestScript script) {
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

    @Nested
    class Phases {

        @Test
        void failedSetupSkipsTheTestsButRunsTheTeardown() {
            harness.add("ts", script()
                    .setup(op("capabilities"), responseCode("500"))
                    .test(test("t", op("capabilities"), responseCode("200")))
                    .teardown(op("search", "Patient", null, null, null, null))
                    .build());

            TestScriptResult result = harness.runOne();

            assertThat(result.phases()).extracting(PhaseResult::kind, PhaseResult::outcome).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(PhaseKind.SETUP, Outcome.FAIL),
                    org.assertj.core.groups.Tuple.tuple(PhaseKind.TEST, Outcome.SKIP),
                    org.assertj.core.groups.Tuple.tuple(PhaseKind.TEARDOWN, Outcome.PASS));
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
        void testScriptForSeveralServersIsInvalid() {
            harness.add("ts", script().destination(1).destination(2)
                    .test(test("t", toDestination(op("capabilities"), 1), toDestination(op("capabilities"), 2)))
                    .build());

            TestScriptResult result = harness.runOne();

            assertThat(result.phases().getFirst().kind()).isEqualTo(PhaseKind.LOAD);
            assertThat(result.phases().getFirst().messages().getFirst())
                    .contains("more than one server", "operation.destination [1, 2]");
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

    @Test
    void variablesWithDefaultValueAndHeaderField() {
        harness.add("ts", script()
                .variable(new VariableDefinition("type", "Patient", null, null, null, null, null))
                .variable(new VariableDefinition("ctype", null, null, null, "Content-Type", "caps", null))
                .test(test("t",
                        op("capabilities", null, null, null, null, "caps"),
                        op("search", "${type}", null, null, null, null),
                        check(new Check.ContentType("${ctype}"), Operator.EQUALS, null)))
                .build());

        TestScriptResult result = harness.runOne();

        assertThat(server.requestLines()).containsExactly("GET /metadata", "GET /Patient");
        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
    }

    @Nested
    class FixtureValues {

        @Test
        void fixturesGetAnIdentifierOfTheirOwnPerExecution() {
            String patient = """
                    {"resourceType":"Patient","identifier":[{"system":"urn:test","value":"${testrunner.testScriptId}"}],
                     "name":[{"family":"${family}"}]}""";
            for (String name : List.of("a", "b")) {
                harness.resource("p-" + name, patient)
                        .add(name, script().fixture("p-" + name, true, false)
                                .variable(new VariableDefinition("family", "Bucher", null, null, null, null, null))
                                .test(test("t", op("search", "Patient", null, null,
                                        "?identifier=${testrunner.testScriptId}", null),
                                        check(new Check.Expression("Bundle.total"), Operator.EQUALS, "1")))
                                .build());
            }

            var run = harness.parallelism(2).run();

            assertThat(run.testScripts()).allMatch(r -> r.outcome() == Outcome.PASS);
            assertThat(server.resource("Patient/1").path("identifier").get(0).path("value").asText())
                    .startsWith(run.runId().replace('_', '-')).matches(".*-0[12]");
            assertThat(server.resource("Patient/1").path("name").get(0).path("family").asText()).isEqualTo("Bucher");
            assertThat(server.resource("Patient/1").path("identifier").get(0).path("value").asText())
                    .isNotEqualTo(server.resource("Patient/2").path("identifier").get(0).path("value").asText());
        }

        @Test
        void placeholdersInXmlFixturesAreReplacedInValues() {
            harness.exampleInstance("file-of-xml.xml", """
                            <Patient xmlns="http://hl7.org/fhir"><id value="x"/><name><family value="${testrunner.uuid}"/></name></Patient>""")
                    .add("ts", script().fixture("xml", false, false)
                            .test(test("t", op("create", "Patient", "xml", null, null, null), responseCode("201")))
                            .build());

            harness.runOne();

            assertThat(server.requests().getFirst().body()).matches("(?s).*<family value=\"[0-9a-f-]{36}\"/>.*");
        }

        @Test
        void placeholderInAnAutocreatedFixtureThatFindsNothingIsAnErrorOfTheSetup() {
            harness.resource("p", """
                            {"resourceType":"Patient","name":[{"family":"${family}"}]}""")
                    .resource("source", """
                            {"resourceType":"Patient"}""")
                    .add("ts", script().fixture("p", true, false).fixture("source", false, false)
                            .variable(new VariableDefinition("family", null, "Patient.name.family", null, null, "source", null))
                            .test(test("t", op("capabilities"))).build());

            TestScriptResult result = harness.runOne();

            assertThat(phase(result, "Setup").outcome()).isEqualTo(Outcome.ERROR);
            assertThat(phase(result, "Setup").messages().getFirst()).contains("Fixture 'p'", "Variable 'family' has no value");
            assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.SKIP);
            assertThat(server.requests()).isEmpty();
        }

        @Test
        void fixtureSentInATestUsesAVariableOnAnEarlierResponse() {
            harness.resource("patient", PATIENT.replace("Organization/org-1", "Organization/none"))
                    .resource("obs", """
                            {"resourceType":"Observation","status":"final","subject":{"reference":"Patient/${pid}"}}""")
                    .add("ts", script().fixture("patient", false, false).fixture("obs", false, false)
                            .variable(new VariableDefinition("pid", null, "Patient.id", null, null, "created", null))
                            .test(test("t",
                                    op("create", "Patient", "patient", null, null, "created"),
                                    op("create", "Observation", "obs", null, null, null),
                                    responseCode("201")))
                            .build());

            TestScriptResult result = harness.runOne();

            assertThat(result.outcome()).isEqualTo(Outcome.PASS);
            assertThat(server.resource("Observation/2").path("subject").path("reference").asText())
                    .isEqualTo("Patient/1");
        }

        @Test
        void fixtureSentInATestReferencesTheServerIdOfAnAutocreatedFixture() {
            harness.resource("patient", PATIENT.replace("Organization/org-1", "Organization/none"))
                    .resource("obs", """
                            {"resourceType":"Observation","status":"final","subject":{"reference":"Patient/pat-1"}}""")
                    .add("ts", script().fixture("patient", true, false).fixture("obs", false, false)
                            .test(test("t",
                                    op("create", "Observation", "obs", null, null, null),
                                    responseCode("201"),
                                    check(new Check.MinimumId("obs"))))
                            .build());

            TestScriptResult result = harness.runOne();

            assertThat(result.outcome()).isEqualTo(Outcome.PASS);
            assertThat(server.resource("Observation/2").path("subject").path("reference").asText())
                    .isEqualTo("Patient/1");
        }

        @Test
        void failingPlaceholderInAFixtureIsAnErrorWhereTheFixtureIsUsed() {
            harness.resource("p", """
                            {"resourceType":"Patient","name":[{"family":"${missing}"}]}""")
                    .add("ts", script().fixture("p", false, false)
                            .variable(new VariableDefinition("missing", null, null, null, "X-Missing", null, null))
                            .test(test("t", op("capabilities"), op("create", "Patient", "p", null, null, null)))
                            .build());

            TestScriptResult result = harness.runOne();

            assertThat(phase(result, "Setup").outcome()).isEqualTo(Outcome.PASS);
            assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.ERROR);
            assertThat(phase(result, "t").messages().getFirst()).contains("Fixture 'p'", "Variable 'missing' has no value");
            assertThat(server.requestLines()).containsExactly("GET /metadata");
        }

        @Test
        void variableThatReadsTheFixtureItIsUsedInIsAnError() {
            harness.resource("p", """
                            {"resourceType":"Patient","id":"x","name":[{"family":"${self}"}]}""")
                    .add("ts", script().fixture("p", false, false)
                            .variable(new VariableDefinition("self", null, "Patient.id", null, null, "p", null))
                            .test(test("t", op("create", "Patient", "p", null, null, null)))
                            .build());

            PhaseResult t = phase(harness.runOne(), "t");

            assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
            assertThat(t.messages().getFirst()).contains("Fixture 'p'", "reads the fixture it is used in");
        }
    }

    @Test
    void suiteWithParallelFalseRunsAloneBeforeTheOthers() {
        for (String name : List.of("s1", "s2")) {
            harness.addSerial(name, script().test(test("t", op("search", "Patient", null, null, "?s=" + name, null)))
                    .build());
        }
        for (String name : List.of("p1", "p2", "p3")) {
            harness.add(name, script().test(test("t", op("search", "Patient", null, null, "?p=" + name, null))).build());
        }

        var run = harness.parallelism(3).run();

        assertThat(run.testScripts()).extracting(TestScriptResult::index).containsExactly(1, 2, 3, 4, 5);
        assertThat(server.requests()).extracting(InMemoryFhirServer.Request::query).startsWith("s=s1", "s=s2")
                .containsExactlyInAnyOrder("s=s1", "s=s2", "p=p1", "p=p2", "p=p3");
    }

    @Test
    void packageSuiteRunsWithoutValidatorModuleAndDefaultValidationSettings() throws Exception {
        // library use without testrunner-validation-cli: validation "enabled" by default, but no jar and no validator
        Path packageDir = java.nio.file.Files.createDirectories(dir.resolve("cache/example.ig#dev/package/example"))
                .getParent();
        java.nio.file.Files.writeString(packageDir.resolve("package.json"), """
                {"name":"example.ig","fhirVersions":["4.0.1"],"directories":{"example":"example"}}""");
        java.nio.file.Files.writeString(packageDir.resolve("TestScript-ts.json"), "ts");
        var configuration = new at.hl7.testing.runner.core.config.RunConfiguration(
                at.hl7.testing.runner.core.config.FhirServerConfig.of(server.baseUrl()),
                List.of(new at.hl7.testing.runner.core.config.SuiteConfig(null, null, null, null, null, null, null,
                        null, null, null, "example.ig#dev")),
                null, null, null,
                new at.hl7.testing.runner.core.config.ReportingConfig(dir.resolve("results"), null, false, false, null),
                new at.hl7.testing.runner.core.config.PackagesConfig(dir.resolve("cache"), null));
        var scripts = java.util.Map.of("ts", script().test(test("t", op("capabilities"), responseCode("200"))).build());
        var versions = new at.hl7.testing.runner.core.spi.FhirVersionRegistry(List.of(StubVersionSupport.of(scripts)));

        try (var transport = new at.hl7.testing.runner.core.http.JdkHttpTransport(
                at.hl7.testing.runner.core.config.HttpConfig.defaults())) {
            var run = new TestRunner(versions, transport, at.hl7.testing.runner.core.spi.ProfileValidator.DISABLED,
                    TestRunners.defaultReportWriters(versions), List.of(), java.time.Clock.systemDefaultZone())
                    .run(configuration);

            assertThat(run.testScripts()).singleElement().satisfies(r -> {
                assertThat(r.suite()).isEqualTo("example.ig#dev");
                assertThat(r.outcome()).isEqualTo(Outcome.PASS);
            });
        }
    }

    @Test
    void communicationLogHasOneFolderPerRequest() throws Exception {
        harness.add("ts", script().test(test("t", op("capabilities"), op("search", "Patient", null, null, null, null))).build());

        var run = harness.run();

        Path folder = run.runDirectory().resolve("communication/01_ts");
        assertThat(folder.resolve("REQ-0001_GET_metadata/request.http")).content().startsWith("GET " + server.baseUrl() + "/metadata HTTP/1.1");
        assertThat(folder.resolve("REQ-0001_GET_metadata/response.http")).content().startsWith("HTTP/1.1 200 OK")
                .contains("CapabilityStatement");
        assertThat(folder.resolve("REQ-0002_GET_Patient/response.http")).exists();
        assertThat(run.reportFiles()).containsOnlyKeys("junit", "summary", "json");
        assertThat(run.reportFiles().get("junit")).content().contains("<testsuite name=\"TS\"", "assertions=\"0\"");
    }

    @Test
    void parallelRunsKeepTheirStateApartAndRequestIdsUnique() {
        for (int i = 0; i < 6; i++) {
            harness.resource("p" + i, PATIENT.replace("pat-1", "pat-" + i).replace("Organization/org-1", "Organization/none"));
            harness.add("ts" + i, script().fixture("p" + i, false, false)
                    .variable(new VariableDefinition("pid", null, "Patient.id", null, null, "created", null))
                    .test(test("t",
                            op("create", "Patient", "p" + i, null, null, "created"),
                            responseCode("201"),
                            op("read", "Patient", null, null, "/${pid}", null),
                            check(new Check.Expression("Patient.id"), Operator.EQUALS, "${pid}")))
                    .build());
        }

        var run = harness.parallelism(3).run();

        assertThat(run.testScripts()).hasSize(6).allMatch(r -> r.outcome() == Outcome.PASS);
        List<String> requestIds = run.testScripts().stream().flatMap(r -> r.phases().stream())
                .flatMap(p -> p.actions().stream()).filter(a -> a.kind().equals(ActionResult.OPERATION))
                .map(ActionResult::requestId).toList();
        assertThat(requestIds).hasSize(12).doesNotHaveDuplicates();
    }
}
