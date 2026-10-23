package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import at.hl7.testing.runner.testsupport.FakeValidatorJar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorCliProfileValidatorTest {

    static final String PROFILE = "https://fhir.hl7.at/core/r4/StructureDefinition/at-core-patient";

    @TempDir
    static Path dir;
    static Path jar;

    @BeforeAll
    static void createFakeValidator() {
        jar = FakeValidatorJar.create(dir);
    }

    static ValidatorCliProfileValidator validator(Path validatorJar) {
        return new ValidatorCliProfileValidator(new ValidationConfig(true, null, validatorJar, null, null, null, null,
                Duration.ofSeconds(60), true, null, null));
    }

    static ValidationRequest request(String json) {
        return new ValidationRequest(Body.json(json), FhirVersion.R4, PROFILE, dir, List.of("hl7.at.fhir.core.r4#2.0.0"));
    }

    @Test
    void validResourceHasNoIssues() {
        ValidationResult result = validator(jar).validate(request("{\"resourceType\":\"Patient\"}"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.issues()).as("'All OK' is not an issue").isEmpty();
    }

    @Test
    void errorsComeFromTheOperationOutcome() {
        ValidationResult result = validator(jar).validate(request(
                "{\"resourceType\":\"Patient\",\"a\":\"" + FakeValidatorJar.INVALID_MARKER + "\",\"b\":\""
                        + FakeValidatorJar.INVALID_MARKER + "\"}"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).hasSize(2).first().satisfies(issue -> {
            assertThat(issue.location()).isEqualTo("Patient.name");
            assertThat(issue.message()).contains("fake error 1 against " + PROFILE);
        });
    }

    @Test
    void validatorWithoutResultIsNeverAPass() {
        assertThatThrownBy(() -> validator(jar).validate(request("{\"x\":\"" + FakeValidatorJar.SILENT_MARKER + "\"}")))
                .isInstanceOf(ValidatorUnavailableException.class)
                .hasMessageContaining("produced no result")
                .hasMessageContaining("simulated validator failure");
    }

    @Test
    void missingJarIsReportedWithAHint() {
        ValidatorCliProfileValidator missing = validator(dir.resolve("nope.jar"));

        assertThat(missing.problems()).singleElement().asString().contains("does not exist", "validator_cli.jar");
        assertThatThrownBy(() -> missing.validate(request("{}"))).isInstanceOf(ValidatorUnavailableException.class);
    }

    @Test
    void commandPassesVersionTerminologyIgsAndProfile() {
        List<String> command = validator(jar).command(request("{}"), Path.of("r.json"), Path.of("o.json"));

        assertThat(String.join(" ", command)).contains("-jar " + jar.toAbsolutePath(), "r.json", "-version 4.0",
                "-tx n/a", "-locale en", "-ig " + dir.toAbsolutePath(), "-ig hl7.at.fhir.core.r4#2.0.0",
                "-profile " + PROFILE, "-output o.json");
    }

    @Test
    void readsABundleOfOutcomes() {
        ValidationResult result = OperationOutcomeReader.read("""
                {"resourceType":"Bundle","entry":[
                  {"resource":{"resourceType":"OperationOutcome","issue":[{"severity":"warning","diagnostics":"w"}]}},
                  {"resource":{"resourceType":"OperationOutcome","issue":[{"severity":"fatal","diagnostics":"f"}]}}]}""");

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.errors()).singleElement().satisfies(i -> assertThat(i.severity()).isEqualTo(ValidationResult.Severity.FATAL));
    }
}
