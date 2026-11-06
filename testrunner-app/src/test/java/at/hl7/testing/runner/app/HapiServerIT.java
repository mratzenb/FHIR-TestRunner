package at.hl7.testing.runner.app;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.SampleData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample TestScripts against a real FHIR server: HAPI FHIR in Docker, R4 and R5. The in-memory server of the
 * other tests knows only a little FHIR; this test checks what the samples assume about a real one – that it accepts
 * the fixtures, deletes by search, answers with the expected codes and content types.
 *
 * <p>Skipped without Docker. The image is pinned, so a HAPI release cannot change the results unannounced. Profile
 * validation is off: {@link RealValidatorIT} covers the real validator.
 */
@Testcontainers(disabledWithoutDocker = true)
class HapiServerIT {

    /** HAPI FHIR JPA server starter 8.12.0, the release matching {@code hapi-fhir.version}. */
    static final String IMAGE = "hapiproject/hapi:v8.12.0-1";

    @Container
    static final GenericContainer<?> R4 = hapi("R4");
    @Container
    static final GenericContainer<?> R5 = hapi("R5");
    /** A second R4 server, for TestScripts that address two servers. */
    @Container
    static final GenericContainer<?> R4_SECOND = hapi("R4");

    @TempDir
    Path dir;
    ConfigurableApplicationContext context;

    /**
     * A HAPI server with an in-memory database. The fixtures of the samples reference resources that are not on the
     * server (e.g. {@code managingOrganization}) and reference each other, so neither writing nor deleting checks
     * referential integrity; the TestScripts delete by search and expect several matches to be deleted.
     */
    @SuppressWarnings("resource") // closed by the Testcontainers extension
    private static GenericContainer<?> hapi(String fhirVersion) {
        return new GenericContainer<>(IMAGE)
                .withEnv("hapi.fhir.fhir_version", fhirVersion)
                .withEnv("hapi.fhir.allow_multiple_delete", "true")
                .withEnv("hapi.fhir.enforce_referential_integrity_on_write", "false")
                .withEnv("hapi.fhir.enforce_referential_integrity_on_delete", "false")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/fhir/metadata").forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    private static String baseUrl(GenericContainer<?> server) {
        return "http://" + server.getHost() + ":" + server.getMappedPort(8080) + "/fhir";
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    private RunResult run(String... properties) {
        List<String> args = new ArrayList<>(List.of(
                "--testrunner.run-on-startup=false",
                "--testrunner.validation.enabled=false",
                "--testrunner.reporting.output-dir=" + dir.resolve("results"),
                "--testrunner.reporting.formats=junit,summary,json"));
        for (String property : properties) {
            args.add("--" + property);
        }
        context = new SpringApplicationBuilder(TestRunnerApplication.class).run(args.toArray(String[]::new));
        return context.getBean(TestRunner.class).run(context.getBean(RunConfiguration.class));
    }

    private static TestScriptResult script(RunResult run, String suite, String fileName) {
        return run.testScripts().stream()
                .filter(r -> r.suite().equals(suite) && r.source().getFileName().toString().equals(fileName))
                .findFirst().orElseThrow();
    }

    private static List<String> messages(TestScriptResult result) {
        return result.phases().stream().flatMap(p -> p.messages().stream()).toList();
    }

    @Test
    void atCoreSuitesPassOnR4AndR5() {
        RunResult run = run("testrunner.suites[0].name=at-core-r4",
                "testrunner.suites[0].base-dir=" + SampleData.r4(),
                "testrunner.suites[0].fhir-server.base-url=" + baseUrl(R4),
                "testrunner.suites[0].include[0]=TestScript-testscript-patient-create-at-core.json",
                "testrunner.suites[0].include[1]=TestScript-testscript-patient-update-at-core.json",
                "testrunner.suites[1].name=at-core-r5",
                "testrunner.suites[1].base-dir=" + SampleData.r5(),
                "testrunner.suites[1].fhir-server.base-url=" + baseUrl(R5));

        assertThat(run.testScripts()).hasSize(4).allSatisfy(r ->
                assertThat(r.outcome()).as(r.suite() + " " + r.name() + ": " + messages(r)).isEqualTo(Outcome.PASS));
        assertThat(run.testScripts()).extracting(r -> r.fhirVersion().name())
                .containsExactly("R4", "R4", "R5", "R5");
    }

    /**
     * Every sample, with the outcome a real server gives. The samples that do not pass, do not pass on purpose or
     * for a known reason, the same as in {@code EndToEndIT.sampleScriptsRunWithTheExpectedOutcomes}.
     */
    @Test
    void sampleScriptsRunWithTheExpectedOutcomesOnARealServer() {
        RunResult run = run("testrunner.fhir-server.base-url=" + baseUrl(R4),
                "testrunner.suites[0].name=samples",
                "testrunner.suites[0].base-dir=" + SampleData.samples());

        assertThat(run.testScripts()).hasSize(9);
        for (String passing : List.of("TestScript-testscript-all-operation-variants-with-assertions.json",
                "TestScript-testscript-assert-contentType-json.json",
                "TestScript-testscript-minimumId-navigationLinks.json",
                "TestScript-testscript-patient-create-at-core.json",
                "TestScript-testscript-patient-create-xml-expression.json",
                "TestScript-testscript-patient-update-at-core.json")) {
            TestScriptResult result = script(run, "samples", passing);
            assertThat(result.outcome()).as(passing + ": " + messages(result)).isEqualTo(Outcome.PASS);
        }
        // its assertions expect XML and a made-up content type from a JSON response, to show stopTestOnFail
        TestScriptResult stop = script(run, "samples", "TestScript-testscript-stopTestOnFail.json");
        assertThat(stop.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(stop.phases()).filteredOn(p -> p.outcome() == Outcome.FAIL).extracting(PhaseResult::name)
                .containsExactly("Test02");
        // an undeclared profile id, reported before anything is sent
        assertThat(script(run, "samples", "TestScript-testscript-validateProfile.json").outcome())
                .isEqualTo(Outcome.ERROR);
        // its Example Instance is missing, as in the proof of concept
        assertThat(script(run, "samples", "TestScript-testscript-delete.json").outcome()).isEqualTo(Outcome.ERROR);
    }

    /**
     * A TestScript for two servers (M6.1, ADR-0019): the patient is autocreated on both servers, read from each,
     * copied from the first to the second, and deleted from both. HAPI assigns its own ids on each server, so the
     * {@code targetId} of each read must address the copy on that server.
     */
    @Test
    void testScriptForTwoServersRunsAgainstBoth() {
        RunResult run = run("testrunner.suites[0].name=several-servers",
                "testrunner.suites[0].base-dir=" + SampleData.root().resolve("several-servers"),
                "testrunner.suites[0].fhir-servers.1.base-url=" + baseUrl(R4),
                "testrunner.suites[0].fhir-servers.2.base-url=" + baseUrl(R4_SECOND));

        TestScriptResult result = run.testScripts().getFirst();
        assertThat(result.outcome()).as(String.valueOf(messages(result))).isEqualTo(Outcome.PASS);
        assertThat(result.fhirServers()).containsExactly(baseUrl(R4), baseUrl(R4_SECOND));
    }

    /**
     * A conditional update ({@code params: ?identifier=…}) creates the resource on no match and updates the same
     * resource on one match. HAPI ignores the id in the body of a conditional update, so this does not detect D1 of
     * docs/wiki/next-steps.md (the in-memory server does); it checks that the runner builds the request as a real
     * server expects.
     */
    @Test
    void conditionalUpdateCreatesThenUpdatesTheSameResource() throws Exception {
        Path suite = dir.resolve("conditional");
        Files.createDirectories(suite.resolve("Test_Scripts"));
        Files.createDirectories(suite.resolve("Example_Instances"));
        Files.writeString(suite.resolve("Example_Instances/Patient-conditional.json"), """
                {"resourceType":"Patient","identifier":[{"system":"urn:test:hapi-server-it","value":"${testrunner.uuid}"}],
                 "name":[{"family":"Conditional"}]}""");
        Files.writeString(suite.resolve("Test_Scripts/TestScript-conditional-update.json"), """
                {"resourceType":"TestScript","id":"conditional-update","url":"http://example.org/TestScript/conditional-update",
                 "name":"ConditionalUpdate","status":"draft",
                 "fixture":[{"id":"patient","autocreate":false,"autodelete":false,
                             "resource":{"reference":"Patient/conditional"}}],
                 "test":[{"name":"ConditionalUpdate","action":[
                   {"operation":{"type":{"system":"http://terminology.hl7.org/CodeSystem/testscript-operation-codes","code":"update"},
                     "resource":"Patient","params":"?identifier=urn:test:hapi-server-it|${testrunner.uuid}",
                     "sourceId":"patient","encodeRequestUrl":true,"responseId":"created"}},
                   {"assert":{"description":"no match: created","responseCode":"201","warningOnly":false}},
                   {"operation":{"type":{"system":"http://terminology.hl7.org/CodeSystem/testscript-operation-codes","code":"update"},
                     "resource":"Patient","params":"?identifier=urn:test:hapi-server-it|${testrunner.uuid}",
                     "sourceId":"patient","encodeRequestUrl":true}},
                   {"assert":{"description":"one match: updated","responseCode":"200","warningOnly":false}},
                   {"assert":{"description":"the same resource","expression":"Patient.id","operator":"equals",
                     "compareToSourceId":"created","compareToSourceExpression":"Patient.id","warningOnly":false}}]}]}
                """);

        RunResult run = run("testrunner.fhir-server.base-url=" + baseUrl(R4),
                "testrunner.suites[0].name=conditional",
                "testrunner.suites[0].fhir-version=R4",
                "testrunner.suites[0].base-dir=" + suite);

        TestScriptResult result = run.testScripts().getFirst();
        assertThat(result.outcome()).as(String.valueOf(messages(result))).isEqualTo(Outcome.PASS);
    }
}
