package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.http.FhirRequest;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.http.JdkHttpTransport;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.HttpMethod;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.toDestination;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TestScripts for several servers ({@code TestScript.destination}, ADR-0019): each destination index mapped to a
 * server of the suite ({@code suites[n].fhir-servers}), operations sent to the server of their destination, fixtures
 * created on each server, capabilities checked per server.
 */
class SeveralServersTest extends EngineTestBase {

    InMemoryFhirServer second;

    @BeforeEach
    void startTheSecondServer() {
        second = InMemoryFhirServer.start("4.0.1");
    }

    @AfterEach
    void stopTheSecondServer() {
        second.close();
    }

    /** Destination 1 is {@link #server}, destination 2 {@link #second}. */
    private Harness twoServers() {
        return harness.destination(1, FhirServerConfig.of(server.baseUrl()))
                .destination(2, FhirServerConfig.of(second.baseUrl()));
    }

    @Test
    void eachOperationGoesToTheServerOfItsDestination() {
        twoServers().resource("patient", PATIENT)
                .add("ts", script().destination(1).destination(2).fixture("patient", false, false)
                        .test(test("copy",
                                toDestination(op("create", "Patient", "patient", null, null, "created"), 1),
                                responseCode("201"),
                                toDestination(op("create", "Patient", "patient", null, null, null), 2),
                                responseCode("201"),
                                toDestination(op("read", "Patient", null, "created", null, null), 1),
                                responseCode("200")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).as(String.valueOf(result.phases())).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).containsExactly("POST /Patient", "GET /Patient/1");
        assertThat(second.requestLines()).containsExactly("POST /Patient");
        assertThat(result.fhirServers()).containsExactly(server.baseUrl(), second.baseUrl());
    }

    @Test
    void autocreatedFixturesAreCreatedAndDeletedOnEveryServerWithTheReferencesOfThatServer() {
        // the second server already holds a resource, so its ids differ from the first server's
        createSomethingOn(second);
        twoServers().resource("patient", PATIENT).resource("org", ORGANIZATION)
                .add("ts", script().destination(1).destination(2)
                        .fixture("patient", true, true).fixture("org", true, true)
                        .test(test("read on both",
                                toDestination(op("read", "Patient", null, "patient", null, null), 1),
                                responseCode("200"),
                                toDestination(op("read", "Patient", null, "patient", null, null), 2),
                                responseCode("200")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).as(String.valueOf(result.phases())).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).containsExactly("POST /Organization", "POST /Patient", "GET /Patient/2",
                "DELETE /Patient/2", "DELETE /Organization/1");
        assertThat(second.requestLines()).containsExactly("POST /Patient", "POST /Organization", "POST /Patient",
                "GET /Patient/3", "DELETE /Patient/3", "DELETE /Organization/2");
        assertThat(server.resource("Patient/2")).isNull();
        assertThat(second.requests().get(2).body()).as("referencing the organization of the second server")
                .contains("Organization/2");
    }

    /** One resource on the server before the run, so that its ids are one ahead. */
    private static void createSomethingOn(InMemoryFhirServer target) {
        try (JdkHttpTransport transport = new JdkHttpTransport(HttpConfig.defaults())) {
            transport.send(new FhirRequest(HttpMethod.POST, URI.create(target.baseUrl() + "/Patient"),
                    HttpHeaders.of(Map.of("Content-Type", List.of("application/fhir+json"))),
                    Body.json("{\"resourceType\":\"Patient\"}")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void unmappedDestinationsAreALoadErrorNamingThePropertyAndNothingIsSent() {
        harness.destination(1, FhirServerConfig.of(server.baseUrl()))
                .add("ts", script().destination(1).destination(2)
                        .test(test("t", toDestination(op("capabilities"), 1), toDestination(op("capabilities"), 2)))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.phases().getFirst().kind()).isEqualTo(PhaseKind.LOAD);
        assertThat(result.phases().getFirst().messages().getFirst()).contains(
                "The TestScript addresses 2 servers (destinations [1, 2])",
                "destination 2 (FHIR-Server) is not mapped to a server: set testrunner.suites[0].fhir-servers.2.base-url");
        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.SKIP);
        assertThat(server.requests()).isEmpty();
        assertThat(second.requests()).isEmpty();
    }

    @Test
    void theServerOfTheRunIsNoFallbackForSeveralServers() {
        harness.add("ts", script().destination(1).destination(2)
                .test(test("t", toDestination(op("capabilities"), 1), toDestination(op("capabilities"), 2)))
                .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.phases().getFirst().messages().getFirst())
                .contains("fhir-servers.1.base-url", "fhir-servers.2.base-url");
        assertThat(server.requests()).as("both would otherwise go to one server").isEmpty();
    }

    @Test
    void aTestScriptForOneServerUsesTheMappingOfItsDestinationElseTheServerOfTheSuite() {
        harness.destination(2, FhirServerConfig.of(second.baseUrl()))
                .add("to-two", script().destination(2)
                        .test(test("t", toDestination(op("capabilities"), 2), responseCode("200"))).build())
                .add("undeclared", script().test(test("t", op("capabilities"), responseCode("200"))).build());

        var run = harness.run();

        assertThat(run.testScripts()).allSatisfy(r -> assertThat(r.outcome()).isEqualTo(Outcome.PASS));
        assertThat(second.requestLines()).containsExactly("GET /metadata");
        assertThat(server.requestLines()).containsExactly("GET /metadata");
    }

    @Test
    void theRulesOfSeveralServersAreCheckedBeforeAnythingIsSentAlsoWithoutServers() {
        harness.noServer().add("ts", script().destination(1).destination(2).destination(2)
                .requires("http://example.org/CapabilityStatement/needs", 3)
                .setup(op("capabilities"))
                .test(test("t", toDestination(op("capabilities"), 1), toDestination(op("capabilities"), 4)))
                .build());

        TestScriptResult result = harness.check().testScripts().getFirst();

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.phases().getFirst().messages().getFirst()).contains(
                "TestScript.destination 2 is declared twice",
                "Setup, action 1: the operation names no destination, but the TestScript addresses several servers"
                        + " (destinations [1, 2, 3, 4])",
                "Test 't', action 2: destination 4 is not declared in TestScript.destination (declared: [1, 2])",
                "metadata.capability 'needs http://example.org/CapabilityStatement/needs': destination 3 is not"
                        + " declared");
    }

    @Test
    void aCapabilityIsCheckedOnTheServerOfItsDestination() {
        second.on("GET", "/metadata", InMemoryFhirServer.Answer.json(200, """
                {"resourceType":"CapabilityStatement","status":"active","kind":"instance","fhirVersion":"4.0.1",
                 "format":["json"],"rest":[{"mode":"server","resource":[{"type":"Patient",
                   "interaction":[{"code":"search-type"}]}]}]}"""));
        twoServers().exampleInstance("CapabilityStatement-needs.json", CapabilitiesTest.NEEDS_READ_AND_DELETE)
                .add("ts", script().destination(1).destination(2)
                        .requires("http://example.org/CapabilityStatement/needs", 2)
                        .test(test("t", toDestination(op("capabilities"), 1), toDestination(op("capabilities"), 2)))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.SKIP);
        assertThat(phase(result, "Setup").messages().getFirst())
                .startsWith("The server of destination 2 [REQ-").contains("no match for {code=read}");
        assertThat(server.requests()).as("destination 1 is not asked").isEmpty();
        assertThat(second.requestLines()).containsExactly("GET /metadata");
    }

    @Test
    void eachServerHasItsHeadersAndTheExchangesShareTheFolderOfTheTestScript() throws Exception {
        harness.destination(1, new FhirServerConfig(URI.create(server.baseUrl()), Map.of("X-Server", "one"), null))
                .destination(2, new FhirServerConfig(URI.create(second.baseUrl()), Map.of("X-Server", "two"), null))
                .add("ts", script().destination(1).destination(2)
                        .test(test("t", toDestination(op("capabilities"), 1), responseCode("200"),
                                toDestination(op("capabilities"), 2), responseCode("200")))
                        .build());

        var run = harness.run();

        assertThat(server.requests().getFirst().header("X-Server")).isEqualTo("one");
        assertThat(second.requests().getFirst().header("X-Server")).isEqualTo("two");
        try (var folders = Files.list(run.runDirectory().resolve("communication/01_ts"))) {
            assertThat(folders.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("REQ-0001_GET_metadata", "REQ-0002_GET_metadata");
        }
    }
}
