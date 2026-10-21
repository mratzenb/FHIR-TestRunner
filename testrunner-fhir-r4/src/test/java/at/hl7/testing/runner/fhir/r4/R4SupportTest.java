package at.hl7.testing.runner.fhir.r4;

import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.OperationType;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import at.hl7.testing.runner.testsupport.SampleData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
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
    void resourceTypeWorksAsInJsonBasedEngines() throws IOException {
        Body json = read(SampleData.r4().resolve("Example_Instances/Patient-HL7ATCorePatientCreateTestExample.json"));

        assertThat(support.fhirPath().evaluate(json, "Patient.resourceType = 'Patient'")).containsExactly(true);
        assertThat(support.fhirPath().evaluate(json, "Patient.resourceType")).containsExactly("Patient");
    }
}
