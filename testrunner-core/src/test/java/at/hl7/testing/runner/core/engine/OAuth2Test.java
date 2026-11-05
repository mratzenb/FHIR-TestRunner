package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.OAuth2Config;
import at.hl7.testing.runner.core.model.RequestHeader;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static at.hl7.testing.runner.core.engine.Scripts.withHeaders;
import static org.assertj.core.api.Assertions.assertThat;

/** The OAuth2 access token for the FHIR server: fetched once per run, sent with every request. */
class OAuth2Test extends EngineTestBase {

    @Test
    void oauth2TokenIsFetchedOncePerRunAndSentWithEveryRequest() throws Exception {
        server.on("POST", "/token", InMemoryFhirServer.Answer.json(200, """
                {"access_token":"tok-1","token_type":"Bearer","expires_in":3600}"""));
        harness.oauth2(new OAuth2Config(URI.create(server.baseUrl() + "/token"), "runner", "secret", "system/*.rs",
                null));
        for (String name : List.of("a", "b")) {
            harness.add(name, script().test(test("t", op("capabilities"),
                    withHeaders(op("search", "Patient", null, null, null, null),
                            new RequestHeader("Authorization", "Bearer wrong")))).build());
        }

        var run = harness.parallelism(2).run();

        assertThat(run.testScripts()).allMatch(r -> r.outcome() == Outcome.PASS);
        assertThat(server.requestLines()).filteredOn("POST /token"::equals).hasSize(1);
        assertThat(server.requests()).filteredOn(r -> r.path().equals("/metadata"))
                .allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer tok-1"));
        assertThat(server.requests()).filteredOn(r -> r.path().equals("/Patient"))
                .as("a TestScript setting Authorization itself keeps it")
                .allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer wrong"));
        try (var folders = Files.list(run.runDirectory().resolve("communication/01_a"))) {
            assertThat(folders.map(f -> f.getFileName().toString()))
                    .as("the token request is no exchange with the FHIR server").noneMatch(f -> f.contains("token"));
        }
    }

    @Test
    void failingTokenRequestIsAnErrorOfTheOperation() {
        server.on("POST", "/token", InMemoryFhirServer.Answer.json(401, """
                {"error":"invalid_client"}"""));
        harness.oauth2(new OAuth2Config(URI.create(server.baseUrl() + "/token"), "runner", "wrong", null, null))
                .add("ts", script().test(test("t", op("capabilities"), responseCode("200"))).build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(t.messages().getFirst()).contains("REQ-0001 GET", "not sent",
                "No OAuth2 access token from " + server.baseUrl() + "/token: HTTP 401", "invalid_client");
        assertThat(server.requestLines()).containsExactly("POST /token");
    }
}
