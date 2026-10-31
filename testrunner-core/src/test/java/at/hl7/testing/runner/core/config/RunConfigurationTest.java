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
        RunConfiguration config = new RunConfiguration(null, null, null, null, null, null, null);
        assertThat(config.execution().parallelism()).isEqualTo(1);
        assertThat(config.validation().terminologyServer()).isEqualTo("n/a");
        assertThat(config.reporting().formats()).containsExactly("junit", "summary");
        assertThat(config.http().followRedirects()).isTrue();
        assertThat(config.reporting().redactHeaders())
                .containsExactly("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie");
    }

    @Test
    void reportsEveryProblem(@TempDir Path dir) {
        RunConfiguration config = new RunConfiguration(null,
                List.of(new SuiteConfig(null, "R7", dir.resolve("missing"), null, null, null, null, null, null, null, null)),
                null, new ExecutionConfig(0), null,
                new ReportingConfig(null, List.of("pdf"), null, null, "LOUD", null), null);

        assertThat(config.problems()).hasSize(5).anyMatch(p -> p.contains("does not exist"))
                .anyMatch(p -> p.contains("R7")).anyMatch(p -> p.contains("parallelism"))
                .anyMatch(p -> p.contains("LOUD")).anyMatch(p -> p.contains("validator-jar"));
        assertThat(config.unknownFormats(Set.of("junit", "json"))).singleElement().asString().contains("'pdf'");
    }

    @Test
    void oauth2NeedsTokenUrlClientAndSecretAndNoAuthorizationHeader(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("Test_Scripts"));
        FhirServerConfig server = new FhirServerConfig(java.net.URI.create("http://s/fhir"),
                java.util.Map.of("authorization", "Bearer x"), new OAuth2Config(null, " ", null, null, null));
        RunConfiguration config = new RunConfiguration(server, List.of(SuiteConfig.of(dir, "R4")), null, null,
                ValidationConfig.disabled(), null, null);

        assertThat(config.problems()).containsExactly(
                "testrunner.fhir-server.oauth2.token-url is not set",
                "testrunner.fhir-server.oauth2.client-id is not set",
                "testrunner.fhir-server.oauth2.client-secret is not set",
                "testrunner.fhir-server.headers.Authorization and testrunner.fhir-server.oauth2 are both set; the"
                        + " OAuth2 access token is sent as Authorization header, so remove one of them");
    }

    @Test
    void namesTheMissingVersionModule(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("Test_Scripts"));
        RunConfiguration config = new RunConfiguration(null, List.of(SuiteConfig.of(dir, "R6")), null, null,
                ValidationConfig.disabled(), null, null);

        assertThat(config.problems()).isEmpty();
        assertThat(config.unsupportedVersions(Set.of(FhirVersion.R4, FhirVersion.R5)))
                .singleElement().asString().contains("testrunner-fhir-r6");
    }

    @Test
    void igsNeedAPinnedFhirVersion(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("Test_Scripts"));
        List<String> igs = List.of("hl7.at.fhir.core.r4#2.0.0");
        SuiteConfig auto = new SuiteConfig(null, null, dir, null, null, null, igs, null, null, null, null);
        SuiteConfig pinned = new SuiteConfig(null, "R4", dir, null, null, null, igs, null, null, null, null);

        assertThat(new RunConfiguration(null, List.of(auto), null, null, ValidationConfig.disabled(), null, null).problems())
                .singleElement().asString().contains("testrunner.suites[0].igs", "fhir-version");
        assertThat(new RunConfiguration(null, List.of(pinned), null, null, ValidationConfig.disabled(), null, null).problems())
                .isEmpty();
        assertThat(SuiteConfig.of(dir, null).igs()).isEmpty();
    }

    @Test
    void suiteFromAPackageNeedsNoFolderButNotBoth(@TempDir Path dir) {
        SuiteConfig fromPackage = new SuiteConfig(null, null, null, null, null, null, null, null, null, null,
                "hl7.at.fhir.core.r4#dev");
        SuiteConfig both = new SuiteConfig(null, null, dir, null, null, null, null, null, null, null,
                "hl7.at.fhir.core.r4#dev");

        assertThat(fromPackage.name()).isEqualTo("hl7.at.fhir.core.r4#dev");
        assertThat(new RunConfiguration(null, List.of(fromPackage), null, null, ValidationConfig.disabled(), null, null)
                .problems()).isEmpty();
        assertThat(new RunConfiguration(null, List.of(both), null, null, ValidationConfig.disabled(), null, null).problems())
                .singleElement().asString().contains("both base-dir and package-id");
        SuiteConfig unknownVersion = new SuiteConfig(null, "R7", null, null, null, null, null, null, null, null,
                "hl7.at.fhir.core.r4#dev");
        assertThat(new RunConfiguration(null, List.of(unknownVersion), null, null, ValidationConfig.disabled(), null, null)
                .problems()).singleElement().asString().contains("testrunner.suites[0].fhir-version", "R7");
        assertThat(new RunConfiguration(null, null, null, null, null, null, null).packages().cacheDir())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".fhir", "packages"));
    }

    @Test
    void suiteFallsBackToTheServerOfTheRun(@TempDir Path dir) {
        SuiteConfig own = new SuiteConfig("own", null, dir, null, null, null, null, null,
                FhirServerConfig.of("http://own/fhir/"), null, null);
        SuiteConfig shared = SuiteConfig.of(dir, null);
        RunConfiguration config = new RunConfiguration(FhirServerConfig.of("http://shared/fhir"), List.of(own, shared),
                null, null, null, null, null);

        assertThat(config.fhirServerOf(own).base()).isEqualTo("http://own/fhir");
        assertThat(config.fhirServerOf(shared).base()).isEqualTo("http://shared/fhir");
        assertThat(shared.name()).isEqualTo(dir.getFileName().toString());
        assertThat(shared.pinnedFhirVersion()).isEmpty();
    }
}
