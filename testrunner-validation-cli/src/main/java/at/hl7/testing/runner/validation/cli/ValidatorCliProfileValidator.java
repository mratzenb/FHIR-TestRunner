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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates with the HL7 FHIR Validator CLI ({@code validator_cli.jar}) run as a separate process.
 *
 * <p>The result is read from the OperationOutcome the validator writes with {@code -output}, not from its console
 * output. A run that produces no OperationOutcome is a {@link ValidatorUnavailableException}, never a passed
 * validation: a validator that cannot start (missing jar, Java too old) looks exactly like one that finds nothing.
 *
 * <p>Thread-safe: every validation works in its own temporary folder. The first validation of each FHIR version,
 * profiles folder and list of IGs runs alone ({@link PackageCacheGate}), because validator processes that install
 * the same packages into an empty package cache at the same time fail each other.
 *
 * <p>This is {@code testrunner.validation.mode=process}, and the fallback of {@link ValidatorServerProfileValidator}.
 */
public final class ValidatorCliProfileValidator implements ProfileValidator {

    private static final Logger log = LoggerFactory.getLogger(ValidatorCliProfileValidator.class);

    private static final int TAIL_LINES = 15;
    private static final int CAUSE_LINES = 5;
    /** The first line of a stack trace or of a cause, e.g. {@code java.nio.file.NoSuchFileException: a -> b}. */
    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("^\\s*(Caused by: |Exception in thread \"[^\"]*\" )?([\\w$]+\\.)+[\\w$]*(Exception|Error)\\b.*");

    private final ValidationConfig config;
    private final ProcessRunner processRunner;
    private final PackageCacheGate gate;

    public ValidatorCliProfileValidator(@Nonnull ValidationConfig config) {
        this(config, ProcessRunner.DEFAULT, new PackageCacheGate());
    }

    ValidatorCliProfileValidator(ValidationConfig config, ProcessRunner processRunner) {
        this(config, processRunner, new PackageCacheGate());
    }

    /** With the gate of the server validator this is the fallback of, as both fill the same package cache. */
    ValidatorCliProfileValidator(ValidationConfig config, PackageCacheGate gate) {
        this(config, ProcessRunner.DEFAULT, gate);
    }

    ValidatorCliProfileValidator(ValidationConfig config, ProcessRunner processRunner, PackageCacheGate gate) {
        this.config = config;
        this.processRunner = processRunner;
        this.gate = gate;
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
        return gate.run(EngineKey.of(request), () -> validateInProcess(request));
    }

    private ValidationResult validateInProcess(ValidationRequest request) {
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

    /**
     * The last lines of the validator's output. A Java stack trace is longer than that and pushes its own message out
     * of the window, so the exception lines before the window are kept in front of it: they name the cause.
     */
    static String tail(String output) {
        List<String> lines = output.strip().lines().toList();
        int from = Math.max(0, lines.size() - TAIL_LINES);
        List<String> causes = lines.subList(0, from).stream()
                .filter(line -> EXCEPTION_LINE.matcher(line).matches())
                .toList();
        List<String> excerpt = new ArrayList<>(causes.subList(Math.max(0, causes.size() - CAUSE_LINES), causes.size()));
        if (!excerpt.isEmpty()) {
            excerpt.add("   ...");
        }
        excerpt.addAll(lines.subList(from, lines.size()));
        return String.join("\n", excerpt);
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
