package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Validates with the HL7 FHIR Validator CLI ({@code validator_cli.jar}) run as a separate process.
 *
 * <p>The result is read from the OperationOutcome the validator writes with {@code -output}, not from its console
 * output. A run that produces no OperationOutcome is a {@link ValidatorUnavailableException}, never a passed
 * validation: a validator that cannot start (missing jar, Java too old) looks exactly like one that finds nothing.
 *
 * <p>Thread-safe: every validation works in its own temporary folder.
 *
 * <p>This is {@code testrunner.validation.mode=process}, and the fallback of {@link ValidatorServerProfileValidator}.
 */
public final class ValidatorCliProfileValidator implements ProfileValidator {

    private static final Logger log = LoggerFactory.getLogger(ValidatorCliProfileValidator.class);

    private final ValidationConfig config;
    private final ProcessRunner processRunner;

    public ValidatorCliProfileValidator(@Nonnull ValidationConfig config) {
        this(config, ProcessRunner.DEFAULT);
    }

    ValidatorCliProfileValidator(ValidationConfig config, ProcessRunner processRunner) {
        this.config = config;
        this.processRunner = processRunner;
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    /** Problems that keep the validator from running, for the start of the run; empty if it looks usable. */
    @Nonnull
    public List<String> problems() {
        return ValidatorArguments.problems(config);
    }

    @Override
    @Nonnull
    public List<String> configurationProblems() {
        return ValidatorArguments.configurationProblems(config);
    }

    @Override
    @Nonnull
    public String description() {
        return "HL7 validator, one process per validation (" + config.validatorJar() + ")";
    }

    @Override
    @Nonnull
    public ValidationResult validate(@Nonnull ValidationRequest request) {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new ValidatorUnavailableException(String.join("; ", problems));
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("testrunner-validation-");
            String extension = switch (request.resource().format()) {
                case JSON -> ".json";
                case XML -> ".xml";
                case OTHER -> throw new ValidatorUnavailableException("Only JSON and XML resources can be validated");
            };
            Path resource = workDir.resolve("resource" + extension);
            Path output = workDir.resolve("outcome.json");
            Files.writeString(resource, request.resource().text(), StandardCharsets.UTF_8);
            List<String> command = command(request, resource, output);
            log.debug("Running the FHIR validator: {}", String.join(" ", command));
            long start = System.nanoTime();
            ProcessRunner.Result process = processRunner.run(command, workDir, config.timeout());
            log.debug("FHIR validator finished in {} ms with exit code {}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), process.exitCode());
            if (log.isTraceEnabled()) {
                log.trace("FHIR validator output:\n{}", process.output());
            }
            if (process.timedOut()) {
                throw new ValidatorUnavailableException("The FHIR validator did not finish within " + config.timeout());
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new ValidatorUnavailableException("The FHIR validator produced no result (exit code "
                        + process.exitCode() + "), so nothing was validated.\nCommand: " + String.join(" ", command)
                        + "\n" + tail(process.output()));
            }
            ValidationResult result = OperationOutcomeReader.read(Files.readString(output, StandardCharsets.UTF_8));
            log.debug("FHIR validator: {} error(s), {} other issue(s)", result.errors().size(), result.warnings().size());
            return result;
        } catch (IOException e) {
            throw new ValidatorUnavailableException("The FHIR validator could not be run: " + e.getMessage(), e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    List<String> command(ValidationRequest request, Path resource, Path output) {
        List<String> command = ValidatorArguments.launcher(config);
        command.add(resource.toString());
        command.addAll(ValidatorArguments.engine(config, request.fhirVersion(), request.profilesDir(), request.igs()));
        if (request.profile() != null) {
            command.add("-profile");
            command.add(request.profile());
        }
        command.add("-output");
        command.add(output.toString());
        return command;
    }

    static String tail(String output) {
        String[] lines = output.strip().split("\n");
        int from = Math.max(0, lines.length - 15);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static void deleteQuietly(Path folder) {
        if (folder == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // a left-over temp file is not worth failing the validation for
                }
            });
        } catch (IOException ignored) {
            // see above
        }
    }
}
