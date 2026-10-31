package at.hl7.testing.runner.app;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.FakeValidatorJar;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import at.hl7.testing.runner.testsupport.SampleData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the sample TestScripts of the proof of concept through the complete application: Spring Boot context,
 * externalized configuration, version detection, HTTP, validator (server mode, the default; process mode in
 * {@link #negativeControlFailsBecauseValidationRuns}), reports. The FHIR server is in memory and the validator a fake
 * jar, so no network and no real server are needed.
 */
class EndToEndIT {

    @TempDir
    static Path shared;
    static Path validatorJar;

    @TempDir
    Path dir;
    InMemoryFhirServer r4Server;
    InMemoryFhirServer r5Server;
    ConfigurableApplicationContext context;

    @BeforeAll
    static void fakeValidator() {
        validatorJar = FakeValidatorJar.create(shared);
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
        if (r4Server != null) {
            r4Server.close();
        }
        if (r5Server != null) {
            r5Server.close();
        }
    }

    private RunResult run(String... properties) {
        List<String> args = new ArrayList<>(List.of(
                "--testrunner.run-on-startup=false",
                "--testrunner.validation.validator-jar=" + validatorJar,
                "--testrunner.reporting.output-dir=" + dir.resolve("results"),
                "--testrunner.reporting.formats=junit,summary,json,testreport"));
        for (String property : properties) {
            args.add("--" + property);
        }
        context = new SpringApplicationBuilder(TestRunnerApplication.class).run(args.toArray(String[]::new));
        return context.getBean(TestRunner.class).run(context.getBean(RunConfiguration.class));
    }

    private static TestScriptResult script(RunResult run, String fileName) {
        return run.testScripts().stream().filter(r -> r.source().getFileName().toString().equals(fileName))
                .findFirst().orElseThrow();
    }

    private static PhaseResult phase(TestScriptResult result, String name) {
        return result.phases().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void atCoreR4SuitePasses() {
        r4Server = InMemoryFhirServer.start("4.0.1");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.suites[0].base-dir=" + SampleData.r4(),
                "testrunner.suites[0].include[0]=TestScript-testscript-patient-create-at-core.json",
                "testrunner.suites[0].include[1]=TestScript-testscript-patient-update-at-core.json");

        assertThat(run.testScripts()).hasSize(2).allSatisfy(r -> {
            assertThat(r.outcome()).as(r.name()).isEqualTo(Outcome.PASS);
            assertThat(r.fhirVersion()).hasToString("R4");
        });
        assertThat(r4Server.requestLines()).contains("POST /Patient", "PUT /Patient/HL7ATCorePatientUpdateTestExample");
        assertThat(r4Server.requests()).filteredOn(r -> r.method().equals("POST"))
                .allSatisfy(r -> assertThat(r.header("Prefer")).isEqualTo("return=representation"));
        assertThat(run.reportFiles()).containsKeys("junit", "summary", "json");
        assertThat(run.runDirectory().resolve("testrunner.log")).content()
                .contains("[01_TestScript-testscript-patient-create-at-core]", "REQ-0001 POST", "Validating");
    }

    @Test
    void atCoreR5SuitePassesWithDetectedVersion() {
        r5Server = InMemoryFhirServer.start("5.0.0");

        RunResult run = run("testrunner.fhir-server.base-url=" + r5Server.baseUrl(),
                "testrunner.suites[0].base-dir=" + SampleData.r5());

        assertThat(run.testScripts()).hasSize(2).allSatisfy(r -> {
            assertThat(r.outcome()).as(r.name()).isEqualTo(Outcome.PASS);
            assertThat(r.fhirVersion()).hasToString("R5");
        });
    }

    @Test
    void mixedSuiteRunsR4AndR5InParallelAgainstTheirOwnServers() {
        r4Server = InMemoryFhirServer.start("4.0.1");
        r5Server = InMemoryFhirServer.start("5.0.0");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.execution.parallelism=4",
                "testrunner.suites[0].name=r4",
                "testrunner.suites[0].base-dir=" + SampleData.r4(),
                "testrunner.suites[0].include[0]=TestScript-testscript-patient-create-at-core.json",
                "testrunner.suites[0].include[1]=TestScript-testscript-patient-update-at-core.json",
                "testrunner.suites[1].name=r5",
                "testrunner.suites[1].base-dir=" + SampleData.r5(),
                "testrunner.suites[1].fhir-server.base-url=" + r5Server.baseUrl());

        assertThat(run.testScripts()).hasSize(4).allMatch(r -> r.outcome() == Outcome.PASS);
        assertThat(run.testScripts()).extracting(TestScriptResult::index).containsExactly(1, 2, 3, 4);
        assertThat(r4Server.requests()).hasSize(3);
        assertThat(r5Server.requests()).hasSize(3);
        List<String> requestIds = run.testScripts().stream().flatMap(r -> r.phases().stream())
                .flatMap(p -> p.actions().stream()).map(a -> a.requestId()).filter(id -> id != null).distinct().toList();
        assertThat(requestIds).hasSize(6);
    }

    @Test
    void writesAFhirTestReportPerTestScriptInItsVersion() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");
        r5Server = InMemoryFhirServer.start("5.0.0");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.suites[0].name=r4",
                "testrunner.suites[0].base-dir=" + SampleData.r4(),
                "testrunner.suites[0].include=TestScript-testscript-patient-update-at-core.json",
                "testrunner.suites[1].name=r5",
                "testrunner.suites[1].base-dir=" + SampleData.r5(),
                "testrunner.suites[1].include=TestScript-testscript-patient-create-at-core.json",
                "testrunner.suites[1].fhir-server.base-url=" + r5Server.baseUrl());

        Path folder = run.reportFiles().get("testreport");
        assertThat(folder).isEqualTo(run.runDirectory().resolve("testreports"));
        org.hl7.fhir.r4.model.TestReport r4 = ca.uhn.fhir.context.FhirContext.forR4Cached().newJsonParser()
                .parseResource(org.hl7.fhir.r4.model.TestReport.class,
                        Files.readString(folder.resolve("TestReport-01_TestScript-testscript-patient-update-at-core.json")));
        assertThat(r4.getResult().toCode()).isEqualTo("pass");
        assertThat(r4.getTestScript().getReference())
                .isEqualTo("https://fhir.hl7.at/core/r4/TestScript/testscript-patient-update-at-core");
        assertThat(r4.getParticipantFirstRep().getUri()).isEqualTo(r4Server.baseUrl());
        String detail = r4.getTestFirstRep().getActionFirstRep().getOperation().getDetail();
        assertThat(run.runDirectory().resolve(detail).resolve("request.http")).exists();

        org.hl7.fhir.r5.model.TestReport r5 = ca.uhn.fhir.context.FhirContext.forR5Cached().newJsonParser()
                .parseResource(org.hl7.fhir.r5.model.TestReport.class,
                        Files.readString(folder.resolve("TestReport-02_TestScript-testscript-patient-create-at-core.json")));
        assertThat(r5.getTestScript()).isEqualTo("https://fhir.hl7.at/core/r5/TestScript/testscript-patient-create-at-core");
        assertThat(r5.getScore().intValue()).isEqualTo(100);
    }

    @Test
    void negativeControlFailsBecauseValidationRuns() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");
        // the fake validator reports an error for this marker, like the real one for the second social security number
        Path suite = copy(SampleData.r4(), dir.resolve("negative"));
        Path patient = suite.resolve("Example_Instances/Patient-HL7ATCorePatientNonConformantTestExample.json");
        Files.writeString(patient, Files.readString(patient).replace("\"active\"",
                "\"_marker\" : {\"id\": \"" + FakeValidatorJar.INVALID_MARKER + "\"},\n  \"active\""));

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.validation.mode=process",
                "testrunner.validation.validate-test-scripts=false",
                "testrunner.suites[0].base-dir=" + suite,
                "testrunner.suites[0].include=TestScript-testscript-patient-create-negative-at-core.json");

        TestScriptResult negative = run.testScripts().getFirst();
        assertThat(negative.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(phase(negative, "RejectNonConformantPatient").messages().getFirst())
                .contains("Not conformant to https://fhir.hl7.at/core/r4/StructureDefinition/at-core-patient");
    }

    @Test
    void sampleScriptsRunWithTheExpectedOutcomes() {
        r4Server = InMemoryFhirServer.start("4.0.1");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.validation.enabled=false",
                "testrunner.suites[0].base-dir=" + SampleData.samples());

        assertThat(run.testScripts()).hasSize(9);
        TestScriptResult all = script(run, "TestScript-testscript-all-operation-variants-with-assertions.json");
        assertThat(all.phases()).filteredOn(p -> p.outcome() != Outcome.PASS)
                .as("every operation type against the in-memory server").isEmpty();

        // an R4 TestScript using the R5-only stopTestOnFail: read as R4, the element is ignored and reported
        TestScriptResult stop = script(run, "TestScript-testscript-stopTestOnFail.json");
        assertThat(stop.fhirVersion()).hasToString("R4");
        assertThat(phase(stop, "Setup").messages()).anyMatch(m -> m.contains("unknown element 'stopTestOnFail'"));
        PhaseResult test02 = phase(stop, "Test02");
        assertThat(test02.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(test02.actions()).as("without stopTestOnFail every assertion runs").hasSize(4);

        // an undeclared profile id fails every run, so it is reported before anything is sent
        TestScriptResult profile = script(run, "TestScript-testscript-validateProfile.json");
        assertThat(profile.phases().getFirst().kind()).isEqualTo(PhaseKind.LOAD);
        assertThat(profile.phases().getFirst().messages().getFirst())
                .contains("Test 'CheckProfileSupportFalseWarningFalse', action 1: validateProfileId 'test1234' is not"
                        + " declared in TestScript.profile (declared: [at-core-patient-profile])");

        // minimumId on JSON and XML, navigationLinks on a search result
        TestScriptResult minimum = script(run, "TestScript-testscript-minimumId-navigationLinks.json");
        assertThat(minimum.outcome()).isEqualTo(Outcome.PASS);
        assertThat(phase(minimum, "MinimumId").messages()).singleElement().asString()
                .startsWith("WARNING:").contains("does not contain everything of minimumId 'patient-create-xml'");

        TestScriptResult xml = script(run, "TestScript-testscript-patient-create-xml-expression.json");
        assertThat(xml.outcome()).isEqualTo(Outcome.PASS);
        assertThat(r4Server.requests()).filteredOn(r -> "application/fhir+xml".equals(r.header("Content-Type"))).isNotEmpty();

        // the R5 sample among the R4 ones: detected as R5, but its Example Instance is missing (also in the PoC)
        TestScriptResult delete = script(run, "TestScript-testscript-delete.json");
        assertThat(delete.fhirVersion()).hasToString("R5");
        assertThat(delete.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(delete.phases().getFirst().messages().getFirst())
                .contains("No Example Instance found for fixture 'fixture-patient-create'");
    }

    @Test
    void testScriptRequiringACapabilityTheServerLacksIsSkipped() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");
        Path suite = copy(SampleData.r4(), dir.resolve("capabilities"));
        Path testScript = suite.resolve("Test_Scripts/TestScript-testscript-patient-create-at-core.json");
        Files.writeString(testScript, Files.readString(testScript).replace("\"fixture\" :", """
                "metadata" : {"capability" : [{"required" : true, "validated" : false,
                  "description" : "Patient patch", "capabilities" : "http://example.org/CapabilityStatement/history"}]},
                "fixture" :"""));
        Files.writeString(suite.resolve("Example_Instances/CapabilityStatement-history.json"), """
                {"resourceType":"CapabilityStatement","id":"history","status":"active","date":"2026-10-26",
                 "kind":"requirements","fhirVersion":"4.0.1","format":["json"],"rest":[{"mode":"server",
                 "resource":[{"type":"Patient","interaction":[{"code":"create"},{"code":"patch"}]}]}]}""");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.validation.enabled=false",
                "testrunner.suites[0].base-dir=" + suite,
                "testrunner.suites[0].include=TestScript-testscript-patient-create-at-core.json");

        TestScriptResult result = run.testScripts().getFirst();
        assertThat(result.outcome()).isEqualTo(Outcome.SKIP);
        assertThat(phase(result, "Setup").messages().getFirst())
                .contains("does not support required capability 'Patient patch'", "no match for {code=patch}");
        assertThat(r4Server.requestLines()).containsExactly("GET /metadata");
        assertThat(run.reportFiles().get("junit")).content().contains("<skipped message=\"The server [REQ-0001]");
    }

    @Test
    void suiteFromAFhirPackageInTheCache() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");
        // an IG package as the IG Publisher builds it: TestScripts and profiles in package/, examples in example/
        Path packageDir = dir.resolve("cache/example.at.core#dev/package");
        Files.createDirectories(packageDir.resolve("example"));
        Files.writeString(packageDir.resolve("package.json"), """
                {"name":"example.at.core","version":"dev","fhirVersions":["4.0.1"],
                 "dependencies":{"hl7.fhir.r4.core":"4.0.1"},"directories":{"lib":"package","example":"example"}}""");
        Files.copy(SampleData.r4().resolve("Test_Scripts/TestScript-testscript-patient-create-at-core.json"),
                packageDir.resolve("TestScript-testscript-patient-create-at-core.json"));
        Files.copy(SampleData.r4().resolve("Example_Instances/Patient-HL7ATCorePatientCreateTestExample.json"),
                packageDir.resolve("example/Patient-HL7ATCorePatientCreateTestExample.json"));

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.packages.cache-dir=" + dir.resolve("cache"),
                "testrunner.suites[0].package-id=example.at.core#dev");

        TestScriptResult result = run.testScripts().getFirst();
        assertThat(run.testScripts()).hasSize(1);
        assertThat(result.suite()).isEqualTo("example.at.core#dev");
        assertThat(result.fhirVersion()).hasToString("R4");
        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(r4Server.requestLines()).contains("POST /Patient");
        assertThat(run.runDirectory().resolve("testrunner.log")).content()
                .contains("Suite 'example.at.core#dev': package example.at.core#dev in " + packageDir.toAbsolutePath() + " (FHIR R4)",
                        "Suite 'example.at.core#dev' validates with IGs [example.at.core#dev]");
    }

    @Test
    void requiredCapabilityStatementInThePackageRootIsChecked() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");
        // the IG Publisher puts conformance resources such as CapabilityStatements into package/, not example/
        Path packageDir = Files.createDirectories(dir.resolve("cache/example.caps#dev/package/example"));
        packageDir = packageDir.getParent();
        Files.writeString(packageDir.resolve("package.json"), """
                {"name":"example.caps","version":"dev","fhirVersions":["4.0.1"],"directories":{"example":"example"}}""");
        Files.writeString(packageDir.resolve("CapabilityStatement-needs-patch.json"), """
                {"resourceType":"CapabilityStatement","id":"needs-patch","status":"active","kind":"requirements",
                 "fhirVersion":"4.0.1","format":["json"],"rest":[{"mode":"server",
                 "resource":[{"type":"Patient","interaction":[{"code":"patch"}]}]}]}""");
        Files.copy(SampleData.r4().resolve("Example_Instances/Patient-HL7ATCorePatientCreateTestExample.json"),
                packageDir.resolve("example/Patient-HL7ATCorePatientCreateTestExample.json"));
        Path testScript = packageDir.resolve("TestScript-testscript-patient-create-at-core.json");
        Files.writeString(testScript, Files.readString(SampleData.r4().resolve(
                "Test_Scripts/TestScript-testscript-patient-create-at-core.json")).replace("\"fixture\" :", """
                "metadata" : {"capability" : [{"required" : true, "validated" : false,
                  "capabilities" : "http://example.org/CapabilityStatement/needs-patch"}]},
                "fixture" :"""));

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.validation.enabled=false",
                "testrunner.packages.cache-dir=" + dir.resolve("cache"),
                "testrunner.suites[0].package-id=example.caps#dev");

        TestScriptResult result = run.testScripts().getFirst();
        assertThat(result.outcome()).as("checked, and the in-memory server has no patch").isEqualTo(Outcome.SKIP);
        assertThat(phase(result, "Setup").messages().getFirst()).contains("no match for {code=patch}");
    }

    @Test
    void missingPackageStopsTheRunBeforeAnythingIsSent() {
        r4Server = InMemoryFhirServer.start("4.0.1");

        assertThatThrownBy(() -> run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.packages.cache-dir=" + dir.resolve("empty-cache"),
                "testrunner.suites[0].package-id=example.at.core#dev"))
                .isInstanceOf(at.hl7.testing.runner.core.engine.ConfigurationException.class)
                .hasMessageContaining("example.at.core#dev is not in the package cache");
        assertThat(r4Server.requests()).isEmpty();
    }

    @Test
    void unreadableTestScriptIsAnErrorAndTheOthersStillRun() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");
        Path suite = copy(SampleData.r4(), dir.resolve("broken"));
        Files.writeString(suite.resolve("Test_Scripts/TestScript-broken.json"), "{ not json");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.validation.enabled=false",
                "testrunner.suites[0].base-dir=" + suite,
                "testrunner.suites[0].include[0]=TestScript-broken.json",
                "testrunner.suites[0].include[1]=TestScript-testscript-patient-update-at-core.json");

        assertThat(run.testScripts()).extracting(TestScriptResult::outcome).containsExactly(Outcome.ERROR, Outcome.PASS);
        assertThat(run.testScripts().getFirst().phases().getFirst().messages().getFirst())
                .contains("could not be determined");
    }

    @Test
    void communicationLogsAndReportsAreWrittenPerRun() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.suites[0].base-dir=" + SampleData.r4(),
                "testrunner.suites[0].include=TestScript-testscript-patient-update-at-core.json");

        Path communication = run.runDirectory().resolve("communication/01_TestScript-testscript-patient-update-at-core");
        try (var folders = Files.list(communication)) {
            assertThat(folders.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly(
                    "REQ-0001_PUT_Patient_HL7ATCorePatientUpdateTestExample",
                    "REQ-0002_PUT_Patient_HL7ATCorePatientUpdateTestExample");
        }
        assertThat(communication.resolve("REQ-0002_PUT_Patient_HL7ATCorePatientUpdateTestExample/request.http")).content()
                .contains("Prefer: return=representation", "\"resourceType\"");
        assertThat(run.reportFiles().get("junit")).content()
                .contains("<testsuite name=\"TestScriptATCorePatientUpdate\"", "<property name=\"fhirVersion\" value=\"R4\"");
        assertThat(run.reportFiles().get("summary")).content().contains("passed:          1");
    }

    @Test
    void serverModeValidatesWithOneServerAndStopsItWithTheApplication() throws Exception {
        r4Server = InMemoryFhirServer.start("4.0.1");
        Path calls = dir.resolve("validator-calls.log");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.validation.mode=server",
                "testrunner.validation.jvm-arguments=-DFAKE_VALIDATOR_LOG=" + calls,
                "testrunner.execution.parallelism=2",
                "testrunner.suites[0].base-dir=" + SampleData.r4(),
                "testrunner.suites[0].fhir-version=R4",
                "testrunner.suites[0].igs=hl7.at.fhir.core.r4#2.0.0",
                "testrunner.suites[0].include[0]=TestScript-testscript-patient-create-at-core.json",
                "testrunner.suites[0].include[1]=TestScript-testscript-patient-update-at-core.json");

        assertThat(run.testScripts()).hasSize(2).allMatch(r -> r.outcome() == Outcome.PASS);
        List<String> lines = Files.readAllLines(calls);
        // two TestScript validations + two validateProfileId assertions, all by one server with the suite's IGs
        assertThat(lines).filteredOn(l -> l.startsWith("start")).singleElement().asString()
                .contains("-ig " + SampleData.r4().resolve("Profiles").toAbsolutePath(), "-ig hl7.at.fhir.core.r4#2.0.0");
        assertThat(lines).filteredOn(l -> l.startsWith("validate")).hasSize(4);
        assertThat(lines).filteredOn(l -> l.startsWith("process")).isEmpty();
        assertThat(run.runDirectory().resolve("testrunner.log")).content()
                .contains("Profile validation: HL7 validator in server mode");

        context.close();
        context = null;
        assertThat(Files.readAllLines(calls)).as("closing the application stops the server").contains("stop");
    }

    @Test
    void validatorMissingIsAnErrorNotAPass() {
        r4Server = InMemoryFhirServer.start("4.0.1");

        RunResult run = run("testrunner.fhir-server.base-url=" + r4Server.baseUrl(),
                "testrunner.validation.validator-jar=" + dir.resolve("missing.jar"),
                "testrunner.validation.validate-test-scripts=false",
                "testrunner.suites[0].base-dir=" + SampleData.r4(),
                "testrunner.suites[0].include=TestScript-testscript-patient-create-at-core.json");

        TestScriptResult result = run.testScripts().getFirst();
        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.phases()).flatExtracting(PhaseResult::messages)
                .anyMatch(((Predicate<String>) m -> m.contains("missing.jar")).and(m -> m.contains("does not exist")));
    }

    private static Path copy(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (Path source : paths.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        return to;
    }
}
