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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

    static ValidationConfig config(Path validatorJar) {
        return new ValidationConfig(true, null, validatorJar, null, null, null, null, Duration.ofSeconds(60), true, null,
                null);
    }

    static ValidatorCliProfileValidator validator(Path validatorJar) {
        return new ValidatorCliProfileValidator(config(validatorJar));
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
    void failureOutputKeepsTheExceptionLineThatTheStackTracePushedOutOfTheTail() {
        String frames = IntStream.range(0, 20).mapToObj(i -> "\tat org.example.Frame" + i + ".run(Frame.java:1)")
                .collect(Collectors.joining("\n"));
        String output = "Installing hl7.fhir.r4.core#4.0.1\nUnable to load validationEngine.\n"
                + "java.nio.file.NoSuchFileException: a.lock -> a.lock-deletion\n" + frames;

        String tail = ValidatorCliProfileValidator.tail(output);

        assertThat(tail).startsWith("java.nio.file.NoSuchFileException: a.lock -> a.lock-deletion\n   ...\n")
                .contains("Frame19", "Frame5").doesNotContain("Frame4.", "Installing");
    }

    @Test
    void failureOutputKeepsTheCausesOfNestedExceptions() {
        String frames = IntStream.range(0, 20).mapToObj(i -> "\tat org.example.Frame" + i + ".run(Frame.java:1)")
                .collect(Collectors.joining("\n"));
        String output = "Exception in thread \"main\" java.lang.IllegalStateException: outer\n" + frames
                + "\nCaused by: java.io.IOException: inner\n" + frames;

        assertThat(ValidatorCliProfileValidator.tail(output)).contains("IllegalStateException: outer",
                "Caused by: java.io.IOException: inner");
    }

    @Test
    void shortOutputIsKeptAsItIs() {
        assertThat(ValidatorCliProfileValidator.tail("  line 1\nline 2\n")).isEqualTo("line 1\nline 2");
        assertThat(ValidatorCliProfileValidator.tail("")).isEmpty();
    }

    @Test
    void onAColdCacheTheFirstProcessRunsAloneAndTheOthersFollowInParallel() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        ProcessRunner runner = (command, workDir, timeout) -> {
            events.add("start");
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Files.writeString(Path.of(command.getLast()), "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}");
            running.decrementAndGet();
            events.add("end");
            return new ProcessRunner.Result(0, "Done", false);
        };
        ValidatorCliProfileValidator validator = new ValidatorCliProfileValidator(config(jar), runner);

        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<ValidationResult>> results = IntStream.range(0, 5)
                    .mapToObj(i -> pool.submit(() -> validator.validate(request("{}")))).toList();
            for (Future<ValidationResult> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).isValid()).isTrue();
            }
        }

        assertThat(events.subList(0, 2)).as("the first process ends before the second starts")
                .containsExactly("start", "end");
        assertThat(events).hasSize(10);
        assertThat(maxRunning.get()).as("the other four ran in parallel").isGreaterThan(1);
    }

    @Test
    void validatorsOfDifferentFhirVersionsDoNotStartTheirFirstProcessesTogether() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        ProcessRunner runner = (command, workDir, timeout) -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Files.writeString(Path.of(command.getLast()), "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}");
            running.decrementAndGet();
            return new ProcessRunner.Result(0, "Done", false);
        };
        ValidatorCliProfileValidator validator = new ValidatorCliProfileValidator(config(jar), runner);
        ValidationRequest r4 = request("{}");
        ValidationRequest r5 = new ValidationRequest(Body.json("{}"), FhirVersion.R5, PROFILE, dir, List.of());

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ValidationResult> first = pool.submit(() -> validator.validate(r4));
            Future<ValidationResult> second = pool.submit(() -> validator.validate(r5));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }

        assertThat(maxRunning.get()).as("R4 and R5 share the core packages: one after the other").isEqualTo(1);
    }

    @Test
    void missingJarIsReportedWithAHint() {
        ValidatorCliProfileValidator missing = validator(dir.resolve("nope.jar"));

        assertThat(missing.problems()).singleElement().asString().contains("does not exist", "validator_cli.jar");
        assertThat(missing.configurationProblems()).as("the run starts; each validation is an error").isEmpty();
        assertThatThrownBy(() -> missing.validate(request("{}"))).isInstanceOf(ValidatorUnavailableException.class);
    }

    @Test
    void jarNotSetIsAConfigurationProblemInBothModes() {
        ValidationConfig config = ValidationConfig.enabled(null);

        assertThat(new ValidatorCliProfileValidator(config).configurationProblems()).singleElement().asString()
                .startsWith("testrunner.validation.validator-jar is not set");
        assertThat(new ValidatorServerProfileValidator(config).configurationProblems()).singleElement().asString()
                .startsWith("testrunner.validation.validator-jar is not set");
        assertThat(ProfileValidators.create(ValidationConfig.disabled()).configurationProblems()).isEmpty();
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
