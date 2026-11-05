package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.model.FhirVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The command line arguments both modes share, so a process and a server validate with the same settings. */
final class ValidatorArguments {

    private ValidatorArguments() {
    }

    /** {@code java <jvm arguments> -jar <validator>}. */
    static List<String> launcher(ValidationConfig config) {
        List<String> command = new ArrayList<>();
        command.add(config.resolvedJavaExecutable().toString());
        command.addAll(config.jvmArguments());
        command.add("-jar");
        command.add(config.validatorJar().toAbsolutePath().toString());
        return command;
    }

    /**
     * The engine settings: version, terminology, locale, the suite's IGs (its profiles folder, then its {@code igs})
     * and extra arguments.
     */
    static List<String> engine(ValidationConfig config, FhirVersion version, Path profilesDir, List<String> igs) {
        List<String> arguments = new ArrayList<>(List.of(
                "-version", version.majorMinor(),
                "-tx", config.terminologyServer(),
                "-locale", config.locale()));
        if (profilesDir != null && Files.isDirectory(profilesDir)) {
            arguments.add("-ig");
            arguments.add(profilesDir.toAbsolutePath().toString());
        }
        for (String ig : igs) {
            arguments.add("-ig");
            arguments.add(ig);
        }
        arguments.addAll(config.extraArguments());
        return arguments;
    }

    /**
     * Problems in the configuration that keep every run from starting ({@code ProfileValidator.configurationProblems}):
     * the validator jar is not set.
     */
    static List<String> configurationProblems(ValidationConfig config) {
        if (config.validatorJar() == null) {
            return List.of("testrunner.validation.validator-jar is not set; set it to the location of"
                    + " validator_cli.jar or disable validation with testrunner.validation.enabled=false");
        }
        return List.of();
    }

    /** Problems that keep the validator from starting at all; empty if it looks usable. */
    static List<String> problems(ValidationConfig config) {
        List<String> problems = new ArrayList<>();
        if (config.validatorJar() == null) {
            problems.add("testrunner.validation.validator-jar is not set");
        } else if (!Files.isRegularFile(config.validatorJar())) {
            problems.add("The validator " + config.validatorJar().toAbsolutePath() + " does not exist. Download it from"
                    + " https://github.com/hapifhir/org.hl7.fhir.core/releases/latest/download/validator_cli.jar"
                    + " or disable validation (testrunner.validation.enabled=false)");
        }
        Path java = config.resolvedJavaExecutable();
        if (!Files.isExecutable(java)) {
            problems.add("The java executable " + java + " does not exist; set testrunner.validation.java-executable");
        }
        return problems;
    }
}
