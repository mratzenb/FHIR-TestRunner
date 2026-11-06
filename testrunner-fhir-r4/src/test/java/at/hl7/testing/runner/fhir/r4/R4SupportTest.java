package at.hl7.testing.runner.fhir.r4;

import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Destination;
import at.hl7.testing.runner.core.model.FhirTemporal;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.OperationType;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.spi.TestReportContent;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import at.hl7.testing.runner.testsupport.SampleData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class R4SupportTest {

    final R4Support support = new R4Support();

    static Body read(Path file) throws IOException {
        return Body.json(Files.readString(file));
    }

    /** Every sample TestScript written in R4; samples/TestScript-testscript-delete.json is R5. */
    static Stream<Path> r4TestScripts() throws IOException {
        return Stream.concat(Files.list(SampleData.r4().resolve("Test_Scripts")),
                Files.list(SampleData.samples().resolve("Test_Scripts"))
                        .filter(f -> !f.getFileName().toString().equals(R5_SAMPLE)));
    }

    static final String R5_SAMPLE = "TestScript-testscript-delete.json";

    @ParameterizedTest
    @MethodSource("r4TestScripts")
    void readsEverySampleR4TestScriptExactly(Path file) throws IOException {
        Body content = read(file);

        assertThat(support.check(content).errors()).as("errors").isEmpty();
        TestScript testScript = support.readTestScript(content).testScript();
        assertThat(testScript.fhirVersion()).isEqualTo(FhirVersion.R4);
        assertThat(testScript.tests()).isNotEmpty();
    }

    @Test
    void mapsTheAtCoreUpdateScript() throws IOException {
        TestScript ts = support.readTestScript(read(SampleData.testScript(SampleData.r4(),
                "TestScript-testscript-patient-update-at-core.json"))).testScript();

        assertThat(ts.name()).isEqualTo("TestScriptATCorePatientUpdate");
        assertThat(ts.fixtures()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("patient-update");
            assertThat(f.autocreate()).isFalse();
            assertThat(f.reference()).isEqualTo("Patient/HL7ATCorePatientUpdateTestExample");
        });
        // R4: TestScript.profile is a Reference carrying the id
        assertThat(ts.profiles()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("at-core-patient-profile");
            assertThat(p.canonical()).isEqualTo("https://fhir.hl7.at/core/r4/StructureDefinition/at-core-patient");
        });
        assertThat(ts.variables()).singleElement().satisfies(v -> {
            assertThat(v.name()).isEqualTo("patientResourceId");
            assertThat(v.defaultValue()).isEqualTo("HL7ATCorePatientUpdateTestExample");
        });
        Operation setupOperation = (Operation) ts.setup().getFirst();
        assertThat(setupOperation.type()).isEqualTo(OperationType.UPDATE);
        assertThat(setupOperation.params()).isEqualTo("/${patientResourceId}");
        assertThat(setupOperation.responseId()).isEqualTo("setup-response");
        Operation testOperation = (Operation) ts.tests().getFirst().actions().getFirst();
        assertThat(testOperation.requestHeaders()).singleElement()
                .satisfies(h -> assertThat(h.field() + ": " + h.value()).isEqualTo("Prefer: return=representation"));
        Assertion profileAssertion = (Assertion) ts.tests().getFirst().actions().get(2);
        assertThat(profileAssertion.check()).isEqualTo(new Check.ValidateProfile("at-core-patient-profile"));
        // R4 has no stopTestOnFail
        assertThat(profileAssertion.stopTestOnFail()).isFalse();
    }

    @Test
    void mapsEveryOperationTypeOfTheSampleScript() throws IOException {
        TestScript ts = support.readTestScript(read(SampleData.testScript(SampleData.samples(),
                "TestScript-testscript-all-operation-variants-with-assertions.json"))).testScript();

        List<OperationType> types = ts.tests().stream().flatMap(t -> t.actions().stream())
                .filter(Operation.class::isInstance).map(a -> ((Operation) a).type()).distinct().toList();
        assertThat(types).contains(OperationType.CREATE, OperationType.READ, OperationType.VREAD, OperationType.UPDATE,
                OperationType.PATCH, OperationType.SEARCH, OperationType.HISTORY, OperationType.CAPABILITIES,
                OperationType.DELETE, OperationType.BATCH, OperationType.TRANSACTION);
        assertThat(ts.tests().getFirst().actions()).filteredOn(Assertion.class::isInstance)
                .extracting(a -> ((Assertion) a).check())
                .contains(new Check.ResponseCode("201"), new Check.Response("created", 201));
    }

    @Test
    void mapsTheDestinationOfTheTestScriptAndOfItsOperations() throws IOException {
        TestScript ts = support.readTestScript(read(SampleData.testScript(SampleData.samples(),
                "TestScript-testscript-patient-update-at-core.json"))).testScript();

        assertThat(ts.destinations()).containsExactly(new Destination(1, "FHIR-Server"));
        assertThat(ts.tests().getFirst().actions()).filteredOn(Operation.class::isInstance)
                .extracting(a -> ((Operation) a).destination()).containsExactly(1, 1);
        assertThat(ts.capabilities()).isEmpty();
    }

    @Test
    void mapsMinimumIdAndNavigationLinks() throws IOException {
        TestScript ts = support.readTestScript(read(SampleData.testScript(SampleData.samples(),
                "TestScript-testscript-minimumId-navigationLinks.json"))).testScript();

        assertThat(ts.tests()).flatExtracting(t -> t.actions()).filteredOn(Assertion.class::isInstance)
                .extracting(a -> ((Assertion) a).check())
                .containsExactly(new Check.MinimumId("patient-create"), new Check.MinimumId("patient-create-xml"),
                        new Check.MinimumId("patient-create-xml"), new Check.NavigationLinks(false));
    }

    static TestReportContent report(String testScript) {
        return new TestReportContent("run-01", "run/01", "TS", testScript, "fail", new java.math.BigDecimal("50"),
                "tester", java.time.Instant.parse("2026-10-26T10:00:00Z"),
                List.of("http://localhost:8080/fhir", "http://localhost:8081/fhir"),
                List.of(new TestReportContent.Action(false, "pass", "create Patient (POST)", "communication/01_ts/REQ-0001_POST_Patient")),
                List.of(new TestReportContent.Test("t1", List.of(
                                new TestReportContent.Action(true, "warning", "responseCode '201': Assert failed", "REQ-0001"))),
                        new TestReportContent.Test("t2", List.of(
                                new TestReportContent.Action(true, "fail", "minimumId 'm': differences", "REQ-0002")))),
                List.of(new TestReportContent.Action(true, "error", "Autodelete failed", null)));
    }

    @Test
    void writesAValidR4TestReportWithTheTestScriptAsReference() {
        Body json = support.testReport(report("http://example.org/TestScript/ts"));

        org.hl7.fhir.r4.model.TestReport report = ca.uhn.fhir.context.FhirContext.forR4Cached().newJsonParser()
                .parseResource(org.hl7.fhir.r4.model.TestReport.class, json.text());
        assertThat(report.getIdPart()).isEqualTo("run-01");
        assertThat(report.getStatus()).isEqualTo(org.hl7.fhir.r4.model.TestReport.TestReportStatus.COMPLETED);
        assertThat(report.getTestScript().getReference()).isEqualTo("http://example.org/TestScript/ts");
        assertThat(report.getResult()).isEqualTo(org.hl7.fhir.r4.model.TestReport.TestReportResult.FAIL);
        assertThat(report.getScore().intValue()).isEqualTo(50);
        assertThat(report.getParticipant()).as("one participant per server").extracting(p -> p.getType().toCode() + " "
                + p.getUri()).containsExactly("server http://localhost:8080/fhir", "server http://localhost:8081/fhir");
        assertThat(report.getSetup().getActionFirstRep().getOperation().getDetail())
                .isEqualTo("communication/01_ts/REQ-0001_POST_Patient");
        assertThat(report.getTest()).extracting(t -> t.getActionFirstRep().getAssert().getResult().toCode())
                .containsExactly("warning", "fail");
        // the teardown has operations only
        assertThat(report.getTeardown().getActionFirstRep().getOperation().getResult().toCode()).isEqualTo("error");
    }

    @Test
    void rejectsAnR5TestScript() throws IOException {
        Body r5 = read(SampleData.testScript(SampleData.r5(), "TestScript-testscript-patient-create-at-core.json"));

        assertThat(support.check(r5).readable()).isFalse();
        assertThatThrownBy(() -> support.readTestScript(r5)).isInstanceOf(TestScriptFormatException.class)
                .hasMessageContaining("Not a valid FHIR R4 TestScript");
    }

    @Test
    void theR5SampleAmongTheSamplesIsNoR4TestScript() throws IOException {
        assertThat(support.check(read(SampleData.testScript(SampleData.samples(), R5_SAMPLE))).readable()).isFalse();
    }

    @Test
    void rejectsAnAssertWithTwoChecks() {
        Body twoChecks = Body.json("""
                {"resourceType":"TestScript","name":"x","status":"active","test":[{"action":[
                  {"assert":{"responseCode":"200","contentType":"json","warningOnly":false}}]}]}""");

        assertThatThrownBy(() -> support.readTestScript(twoChecks)).hasMessageContaining("exactly one");
    }

    @Test
    void fhirPathOnJsonAndXml() throws IOException {
        Body json = read(SampleData.r4().resolve("Example_Instances/Patient-HL7ATCorePatientCreateTestExample.json"));
        Body xml = Body.xml(Files.readString(SampleData.samples()
                .resolve("Example_Instances/Patient-HL7ATCorePatientCreateTestExampleXML.xml")));

        assertThat(support.fhirPath().evaluate(json, "Patient.id")).containsExactly("HL7ATCorePatientCreateTestExample");
        assertThat(support.fhirPath().evaluate(xml, "Patient.name.family")).containsExactly("Wolfesberger");
        assertThat(support.fhirPath().evaluate(xml, "Patient.name.given.count() = 2")).containsExactly(true);
    }

    @Test
    void fhirPathKeepsTheTypeOfDatesAndNumbersAsWritten() {
        Body observation = Body.json("""
                {"resourceType":"Observation","status":"final","code":{"text":"x"},
                 "effectiveDateTime":"2026-01-01T10:00:00+01:00","issued":"2026-01-01T09:00:00.000Z",
                 "valueQuantity":{"value":1.50},"meta":{"lastUpdated":"2026-01-01T09:00:00Z"}}""");
        Body patient = Body.json("{\"resourceType\":\"Patient\",\"birthDate\":\"1980-02\"}");

        assertThat(support.fhirPath().evaluate(observation, "Observation.effective")).singleElement()
                .isInstanceOf(FhirTemporal.class).hasToString("2026-01-01T10:00:00+01:00");
        assertThat(support.fhirPath().evaluate(observation, "Observation.issued")).singleElement()
                .isInstanceOf(FhirTemporal.class).hasToString("2026-01-01T09:00:00.000Z");
        assertThat(support.fhirPath().evaluate(patient, "Patient.birthDate")).singleElement()
                .isInstanceOf(FhirTemporal.class).hasToString("1980-02");
        assertThat(support.fhirPath().evaluate(observation, "Observation.value.value")).singleElement()
                .isEqualTo(new BigDecimal("1.50"));
        assertThat(support.fhirPath().evaluate(observation, "Observation.status")).containsExactly("final");
        // the text as written, for a TestScript that compares it as text (CHANGELOG)
        assertThat(support.fhirPath().evaluate(observation, "Observation.value.value.toString()")).containsExactly("1.50");
        assertThat(support.fhirPath().evaluate(observation, "Observation.effective.toString()"))
                .containsExactly("2026-01-01T10:00:00+01:00");
    }

    @Test
    void resourceTypeWorksAsInJsonBasedEngines() throws IOException {
        Body json = read(SampleData.r4().resolve("Example_Instances/Patient-HL7ATCorePatientCreateTestExample.json"));

        assertThat(support.fhirPath().evaluate(json, "Patient.resourceType = 'Patient'")).containsExactly(true);
        assertThat(support.fhirPath().evaluate(json, "Patient.resourceType")).containsExactly("Patient");
    }
}
