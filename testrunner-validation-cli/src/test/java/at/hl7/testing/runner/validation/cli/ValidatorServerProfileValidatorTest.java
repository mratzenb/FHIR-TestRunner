package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.config.ServerModeConfig;
import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.config.ValidatorMode;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import at.hl7.testing.runner.testsupport.FakeValidatorJar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Server mode with the fake validator started as a real process. The fake writes every start and call to a file
 * ({@code -DFAKE_VALIDATOR_LOG} passed as JVM argument), so the tests can count them.
 */
class ValidatorServerProfileValidatorTest {

    static final String PROFILE = "https://fhir.hl7.at/core/r4/StructureDefinition/at-core-patient";
    static final String VALID = "{\"resourceType\":\"Patient\"}";
    static final String INVALID = "{\"resourceType\":\"Patient\",\"x\":\"" + FakeValidatorJar.INVALID_MARKER + "\"}";
    static final List<String> IGS = List.of("hl7.at.fhir.core.r4#2.0.0");

    @TempDir
    static Path shared;
    static Path jar;

    @TempDir
    Path dir;
    Path calls;
    Path profilesR4;
    Path profilesOther;
    ValidatorServerProfileValidator validator;

    @BeforeAll
    static void createFakeValidator() {
        jar = FakeValidatorJar.create(shared);
    }

    @BeforeEach
    void folders() throws IOException {
        calls = dir.resolve("calls.log");
        profilesR4 = Files.createDirectories(dir.resolve("r4/Profiles"));
        profilesOther = Files.createDirectories(dir.resolve("other/Profiles"));
    }

    @AfterEach
    void close() {
        if (validator != null) {
            validator.close();
        }
    }

    ValidationConfig config(boolean fallback, Path validatorJar, String... jvmArguments) {
        List<String> jvm = new ArrayList<>(List.of("-DFAKE_VALIDATOR_LOG=" + calls));
        jvm.addAll(List.of(jvmArguments));
        return new ValidationConfig(true, ValidatorMode.SERVER, validatorJar, null, jvm, null, null,
                Duration.ofSeconds(30), true, null, new ServerModeConfig(Duration.ofSeconds(30), fallback));
    }

    ValidationRequest request(String json, FhirVersion version, Path profiles) {
        return request(json, version, profiles, IGS);
    }

    ValidationRequest request(String json, FhirVersion version, Path profiles, List<String> igs) {
        return new ValidationRequest(Body.json(json), version, PROFILE, profiles, igs);
    }

    List<String> calls(String prefix) throws IOException {
        return Files.exists(calls)
                ? Files.readAllLines(calls).stream().filter(l -> l.startsWith(prefix)).toList() : List.of();
    }

    @Test
    void oneServerServesAllValidationsOfAVersionAndFolder() throws Exception {
        validator = new ValidatorServerProfileValidator(config(true, jar));

        ValidationResult valid = validator.validate(request(VALID, FhirVersion.R4, profilesR4));
        ValidationResult invalid = validator.validate(request(INVALID, FhirVersion.R4, profilesR4));
        validator.validate(new ValidationRequest(Body.json(VALID), FhirVersion.R4, null, profilesR4, IGS));

        assertThat(valid.isValid()).isTrue();
        assertThat(valid.issues()).isEmpty();
        assertThat(invalid.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("fake error 1 against " + PROFILE));
        assertThat(calls("start")).singleElement().asString()
                .contains("-version 4.0", "-tx n/a", "-ig " + profilesR4.toAbsolutePath(), "-ig hl7.at.fhir.core.r4#2.0.0");
        assertThat(calls("validate")).containsExactly(
                "validate " + PROFILE + " application/fhir+json",
                "validate " + PROFILE + " application/fhir+json",
                "validate null application/fhir+json");
        assertThat(calls("process")).isEmpty();
    }

    @Test
    void separateServersPerFhirVersionProfilesFolderAndIgs() throws Exception {
        validator = new ValidatorServerProfileValidator(config(true, jar));

        validator.validate(request(VALID, FhirVersion.R4, profilesR4));
        validator.validate(request(VALID, FhirVersion.R5, profilesR4));
        validator.validate(request(VALID, FhirVersion.R4, profilesOther));
        validator.validate(request(VALID, FhirVersion.R4, profilesR4, List.of()));
        validator.validate(request(VALID, FhirVersion.R4, profilesR4));

        assertThat(validator.serverCount()).isEqualTo(4);
        assertThat(calls("start")).hasSize(4).anyMatch(l -> l.contains("-version 5.0"))
                .anyMatch(l -> !l.contains("hl7.at.fhir.core.r4#2.0.0"));
    }

    @Test
    void parallelValidationsShareTheServer() throws Exception {
        validator = new ValidatorServerProfileValidator(config(true, jar));
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Future<ValidationResult>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String json = i % 2 == 0 ? VALID : INVALID;
                results.add(pool.submit(() -> validator.validate(request(json, FhirVersion.R4, profilesR4))));
            }
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get().isValid()).isEqualTo(i % 2 == 0);
            }
        }
        assertThat(calls("start")).hasSize(1);
        assertThat(calls("validate")).hasSize(12);
    }

    @Test
    void crashedServerFallsBackToOneProcessPerValidationAndIsNotRestarted() throws Exception {
        validator = new ValidatorServerProfileValidator(config(true, jar));
        validator.validate(request(VALID, FhirVersion.R4, profilesR4));

        String crash = "{\"resourceType\":\"Patient\",\"x\":\"" + FakeValidatorJar.CRASH_MARKER + "\"}";
        ValidationResult afterCrash = validator.validate(request(crash, FhirVersion.R4, profilesR4));
        ValidationResult next = validator.validate(request(INVALID, FhirVersion.R4, profilesR4));

        assertThat(afterCrash.isValid()).as("validated by a process instead").isTrue();
        assertThat(next.isValid()).isFalse();
        assertThat(calls("start")).hasSize(1);
        assertThat(calls("process")).hasSize(2);
        assertThat(validator.serverCount()).isZero();
    }

    @Test
    void hangingServerIsEndedAndItsValidationsFallBack() throws Exception {
        ValidationConfig config = config(true, jar);
        validator = new ValidatorServerProfileValidator(new ValidationConfig(true, ValidatorMode.SERVER, jar, null,
                config.jvmArguments(), null, null, Duration.ofSeconds(2), true, null, config.server()));
        validator.validate(request(VALID, FhirVersion.R4, profilesR4));

        String hang = "{\"resourceType\":\"Patient\",\"x\":\"" + FakeValidatorJar.HANG_MARKER + "\"}";
        long started = System.nanoTime();
        ValidationResult afterHang = validator.validate(request(hang, FhirVersion.R4, profilesR4));
        ValidationResult next = validator.validate(request(INVALID, FhirVersion.R4, profilesR4));

        assertThat(afterHang.isValid()).as("validated by a process instead").isTrue();
        assertThat(next.isValid()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("one timeout, not one per queued validation")
                .isLessThan(Duration.ofSeconds(20));
        assertThat(calls("start")).hasSize(1);
        assertThat(calls("process")).hasSize(2);
        assertThat(validator.serverCount()).as("the hanging server was ended").isZero();
    }

    @Test
    void withoutFallbackAServerFailureIsAnErrorNamingTheSwitches() {
        validator = new ValidatorServerProfileValidator(config(false, jar, "-DFAKE_VALIDATOR_UNUSED=1"));
        validator.validate(request(VALID, FhirVersion.R4, profilesR4));
        String crash = "{\"resourceType\":\"Patient\",\"x\":\"" + FakeValidatorJar.CRASH_MARKER + "\"}";

        assertThatThrownBy(() -> validator.validate(request(crash, FhirVersion.R4, profilesR4)))
                .isInstanceOf(ValidatorUnavailableException.class)
                .hasMessageContaining("stopped")
                .hasMessageContaining("fallback-to-process is off")
                .hasMessageContaining("testrunner.validation.mode=process");
        assertThatThrownBy(() -> validator.validate(request(VALID, FhirVersion.R4, profilesR4)))
                .as("a failed server stays failed").isInstanceOf(ValidatorUnavailableException.class);
    }

    @Test
    void serverThatExitsAtStartUpFallsBack() throws Exception {
        validator = new ValidatorServerProfileValidator(config(true, jar, "-DFAKE_VALIDATOR_SERVER_FAILS=1"));

        ValidationResult result = validator.validate(request(INVALID, FhirVersion.R4, profilesR4));

        assertThat(result.isValid()).isFalse();
        assertThat(calls("start")).isEmpty();
        assertThat(calls("process")).hasSize(1);
    }

    @Test
    void serverThatExitsAtStartUpWithoutFallbackNamesTheOutput() {
        validator = new ValidatorServerProfileValidator(config(false, jar, "-DFAKE_VALIDATOR_SERVER_FAILS=1"));

        assertThatThrownBy(() -> validator.validate(request(VALID, FhirVersion.R4, profilesR4)))
                .isInstanceOf(ValidatorUnavailableException.class)
                .hasMessageContaining("could not be started")
                .hasMessageContaining("exit code 3")
                .hasMessageContaining("simulated start-up failure");
    }

    @Test
    void serverThatDoesNotComeUpInTimeIsAFailure() throws Exception {
        ValidationConfig slow = config(false, jar, "-DFAKE_VALIDATOR_STARTUP_MILLIS=20000")
                .withServer(new ServerModeConfig(Duration.ofMillis(800), false));
        validator = new ValidatorServerProfileValidator(slow);

        assertThatThrownBy(() -> validator.validate(request(VALID, FhirVersion.R4, profilesR4)))
                .isInstanceOf(ValidatorUnavailableException.class)
                .hasMessageContaining("did not accept connections within PT0.8S")
                .hasMessageContaining("startup-timeout");
        assertThat(validator.serverCount()).as("the slow process was ended").isZero();
    }

    @Test
    void notAJarFallsBack() throws Exception {
        Path notAJar = Files.writeString(dir.resolve("broken.jar"), "not a jar");
        ProfileValidator fallback = new ValidatorCliProfileValidator(config(true, jar).withMode(ValidatorMode.PROCESS));
        validator = new ValidatorServerProfileValidator(config(true, notAJar), fallback);

        ValidationResult result = validator.validate(request(INVALID, FhirVersion.R4, profilesR4));

        assertThat(result.isValid()).isFalse();
        assertThat(calls("process")).hasSize(1);
    }

    @Test
    void problemOfASingleValidationIsReportedWithoutFallback() throws Exception {
        validator = new ValidatorServerProfileValidator(config(true, jar));

        assertThatThrownBy(() -> validator.validate(new ValidationRequest(Body.json(VALID), FhirVersion.R4,
                FakeValidatorJar.UNRESOLVABLE_PROFILE, profilesR4, IGS)))
                .isInstanceOf(ValidatorUnavailableException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("Unable to resolve profile");
        assertThat(calls("process")).isEmpty();
        assertThat(validator.validate(request(VALID, FhirVersion.R4, profilesR4)).isValid())
                .as("the server is still used").isTrue();
        assertThat(calls("start")).hasSize(1);
    }

    @Test
    void closeStopsEveryServer() throws Exception {
        validator = new ValidatorServerProfileValidator(config(true, jar));
        validator.validate(request(VALID, FhirVersion.R4, profilesR4));
        validator.validate(request(VALID, FhirVersion.R5, profilesR4));

        validator.close();

        assertThat(calls("stop")).hasSize(2);
        assertThat(validator.serverCount()).isZero();
        assertThatThrownBy(() -> validator.validate(request(VALID, FhirVersion.R4, profilesR4)))
                .hasMessageContaining("closed");
    }

    @Test
    void factorySelectsTheAdapterByMode() {
        assertThat(ProfileValidators.create(config(true, jar).withMode(ValidatorMode.PROCESS)))
                .isInstanceOf(ValidatorCliProfileValidator.class);
        ProfileValidator server = ProfileValidators.create(config(true, jar));
        assertThat(server).isInstanceOf(ValidatorServerProfileValidator.class);
        ((ValidatorServerProfileValidator) server).close();
        assertThat(ProfileValidators.create(ValidationConfig.disabled())).isSameAs(ProfileValidator.DISABLED);
    }

    @Test
    void serverCommandUsesTheSameEngineArgumentsAsTheProcess() {
        ValidatorServer server = new ValidatorServer(config(true, jar), FhirVersion.R5, profilesR4, IGS, null);

        assertThat(String.join(" ", server.command(4711))).contains("-DFAKE_VALIDATOR_LOG=", "-jar " + jar.toAbsolutePath(),
                "server 4711 -version 5.0 -tx n/a -locale en -ig " + profilesR4.toAbsolutePath(),
                "-ig hl7.at.fhir.core.r4#2.0.0");
    }
}
