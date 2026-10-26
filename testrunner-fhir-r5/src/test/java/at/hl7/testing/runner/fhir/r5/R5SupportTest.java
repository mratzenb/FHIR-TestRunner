package at.hl7.testing.runner.fhir.r5;

import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.spi.TestReportContent;
import at.hl7.testing.runner.testsupport.SampleData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class R5SupportTest {

    final R5Support support = new R5Support();

    static Body r5(String file) throws IOException {
        return Body.json(Files.readString(SampleData.testScript(SampleData.r5(), file)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TestScript-testscript-patient-create-at-core.json", "TestScript-testscript-patient-update-at-core.json"})
    void readsTheR5SamplesExactly(String file) throws IOException {
        assertThat(support.check(r5(file)).exact()).isTrue();
        assertThat(support.readTestScript(r5(file)).testScript().fhirVersion()).isEqualTo(FhirVersion.R5);
    }

    @Test
    void mapsTheCanonicalProfileWithTheIdOfThePrimitiveExtension() throws IOException {
        TestScript ts = support.readTestScript(r5("TestScript-testscript-patient-update-at-core.json")).testScript();

        // R5: TestScript.profile is a canonical, its id comes from _profile
        assertThat(ts.profiles()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("at-core-patient-profile");
            assertThat(p.canonical()).isEqualTo("https://fhir.hl7.at/core/r5/StructureDefinition/at-core-patient");
        });
        Assertion setupAssert = (Assertion) ts.setup().get(1);
        assertThat(setupAssert.stopTestOnFail()).isTrue();
    }

    @Test
    void mapsTheRequiredCapabilitiesOfTheMetadata() throws IOException {
        Body delete = Body.json(Files.readString(SampleData.testScript(SampleData.samples(), "TestScript-testscript-delete.json")));

        TestScript ts = support.readTestScript(delete).testScript();

        assertThat(ts.capabilities()).singleElement().satisfies(c -> {
            assertThat(c.required()).isTrue();
            assertThat(c.validated()).isFalse();
            assertThat(c.description()).isEqualTo("Patient Update, Read and Delete Operations");
            assertThat(c.capabilities()).isEqualTo("http://hl7.org/fhir/CapabilityStatement/example");
            assertThat(c.links()).hasSize(3).first().isEqualTo("http://hl7.org/fhir/http.html#delete");
        });
        assertThat(ts.destinations()).isEmpty();
    }

    @Test
    void writesAValidR5TestReportWithTheTestScriptAsCanonical() {
        TestReportContent content = new TestReportContent("run-02", "run/02", "TS", "http://example.org/TestScript/ts",
                "pending", null, "tester", java.time.Instant.parse("2026-10-26T10:00:00Z"), null,
                List.of(new TestReportContent.Action(false, "skip", "The server does not support ...", null)),
                List.of(new TestReportContent.Test("t", List.of(
                        new TestReportContent.Action(false, "skip", "Skipped: the server lacks a capability", null)))),
                List.of());

        org.hl7.fhir.r5.model.TestReport report = ca.uhn.fhir.context.FhirContext.forR5Cached().newJsonParser()
                .parseResource(org.hl7.fhir.r5.model.TestReport.class, support.testReport(content).text());

        assertThat(report.getTestScript()).isEqualTo("http://example.org/TestScript/ts");
        assertThat(report.getResult()).isEqualTo(org.hl7.fhir.r5.model.TestReport.TestReportResult.PENDING);
        assertThat(report.hasScore()).isFalse();
        assertThat(report.hasParticipant()).as("nothing was sent").isFalse();
        assertThat(report.hasTeardown()).isFalse();
        assertThat(report.getTestFirstRep().getActionFirstRep().getOperation().getResult().toCode()).isEqualTo("skip");
    }

    @Test
    void canonicalWithoutIdIsAddressedByItsLastSegment() {
        Body content = Body.json("""
                {"resourceType":"TestScript","name":"x","status":"active",
                 "profile":["http://example.org/StructureDefinition/my-patient|1.0"]}""");

        assertThat(support.readTestScript(content).testScript().profiles().getFirst().id()).isEqualTo("my-patient");
    }

    @Test
    void anR4TestScriptIsNotAnExactR5TestScript() throws IOException {
        Body r4 = Body.json(Files.readString(SampleData.testScript(SampleData.r4(), "TestScript-testscript-patient-update-at-core.json")));

        assertThat(support.check(r4).exact()).isFalse();
    }
}
