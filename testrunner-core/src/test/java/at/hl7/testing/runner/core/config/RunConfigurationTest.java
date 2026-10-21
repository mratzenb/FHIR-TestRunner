package at.hl7.testing.runner.core.config;

import at.hl7.testing.runner.core.model.FhirVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RunConfigurationTest {

    @Test
    void defaultsApplyToAbsentValues() {
        RunConfiguration config = new RunConfiguration(null, null, null, null, null, null);
        assertThat(config.execution().parallelism()).isEqualTo(1);
        assertThat(config.validation().terminologyServer()).isEqualTo("n/a");
        assertThat(config.reporting().formats()).containsExactly("junit", "summary");
        assertThat(config.http().followRedirects()).isTrue();
    }

    @Test
    void reportsEveryProblem(@TempDir Path dir) {
        RunConfiguration config = new RunConfiguration(null,
                List.of(new SuiteConfig(null, "R7", dir.resolve("missing"), null, null, null, null, null)),
                null, new ExecutionConfig(0), null,
                new ReportingConfig(null, List.of("pdf"), null, null, "LOUD"));

        assertThat(config.problems()).hasSize(5).anyMatch(p -> p.contains("does not exist"))
                .anyMatch(p -> p.contains("R7")).anyMatch(p -> p.contains("parallelism"))
                .anyMatch(p -> p.contains("LOUD")).anyMatch(p -> p.contains("validator-jar"));
        assertThat(config.unknownFormats(Set.of("junit", "json"))).singleElement().asString().contains("'pdf'");
    }

    @Test
    void namesTheMissingVersionModule(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("Test_Scripts"));
        RunConfiguration config = new RunConfiguration(null, List.of(SuiteConfig.of(dir, "R6")), null, null,
                ValidationConfig.disabled(), null);

        assertThat(config.problems()).isEmpty();
        assertThat(config.unsupportedVersions(Set.of(FhirVersion.R4, FhirVersion.R5)))
                .singleElement().asString().contains("testrunner-fhir-r6");
    }

    @Test
    void suiteFallsBackToTheServerOfTheRun(@TempDir Path dir) {
        SuiteConfig own = new SuiteConfig("own", null, dir, null, null, null, null, FhirServerConfig.of("http://own/fhir/"));
        SuiteConfig shared = SuiteConfig.of(dir, null);
        RunConfiguration config = new RunConfiguration(FhirServerConfig.of("http://shared/fhir"), List.of(own, shared),
                null, null, null, null);

        assertThat(config.fhirServerOf(own).base()).isEqualTo("http://own/fhir");
        assertThat(config.fhirServerOf(shared).base()).isEqualTo("http://shared/fhir");
        assertThat(shared.name()).isEqualTo(dir.getFileName().toString());
        assertThat(shared.pinnedFhirVersion()).isEmpty();
    }
}
