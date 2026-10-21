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
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.stopTestOnFail;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.warningOnly;
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
                            .test(test("t", responseCode("200"))).build());

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
                    .add("ts", script().fixture("patient", true, false).test(test("t", responseCode("200"))).build());

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
                    op("create", "Patient", "unknown-fixture", null, null, null),
                    responseCode("201"))).build());

            TestScriptResult result = harness.runOne();

            PhaseResult t = phase(result, "t");
            assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
            assertThat(t.messages().getFirst()).contains("unknown-fixture");
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
        void assertionWithoutResponseIsAnError() {
            harness.add("ts", script().test(test("t", responseCode("200"))).build());

            PhaseResult t = phase(harness.runOne(), "t");

            assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
            assertThat(t.messages().getFirst()).contains("no operation has been executed yet");
        }

        @Test
        void unsupportedChecksAreErrors() {
            TestScriptResult result = runAfterCapabilities(check(new Check.MinimumId("x")));

            assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.ERROR);
            assertThat(phase(result, "t").messages().getFirst()).contains("minimumId", "not supported");
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

    @Test
    void unknownVariableIsAnError() {
        harness.add("ts", script().test(test("t", op("search", "Patient", null, null, "?id=${nope}", null))).build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(t.messages().getFirst()).contains("Variable 'nope'");
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
