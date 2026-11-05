package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.FhirElement;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** {@code metadata.capability}: the required CapabilityStatement compared with the server's. */
class CapabilitiesTest extends EngineTestBase {

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
                tuple(PhaseKind.SETUP, Outcome.SKIP),
                tuple(PhaseKind.TEST, Outcome.SKIP));
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
        var differences = FhirElement.of(Body.json("""
                        {"rest":[{"resource":[{"type":"Patient","versioning":"versioned-update",
                          "updateCreate":true,"conditionalDelete":"single"}]}],"format":["xml"]}"""))
                .notContainedIn(FhirElement.of(Body.json("""
                        {"rest":[{"resource":[{"type":"Patient","versioning":"versioned",
                          "updateCreate":false,"conditionalDelete":"not-supported"}]}],
                         "format":["application/fhir+json"]}""")), "CapabilityStatement", CapabilityCheck.AT_LEAST);

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
