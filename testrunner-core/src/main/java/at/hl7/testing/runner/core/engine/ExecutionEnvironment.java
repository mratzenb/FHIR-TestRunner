package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.ReferenceRewriting;
import at.hl7.testing.runner.core.spi.ProfileValidator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What one TestScript execution gets from its run, built by {@link ExecutionFactory}.
 *
 * @param servers      the TestScript's FHIR servers, by destination index, each with its client; one communication
 *                     log for all
 * @param validator    the profile validator of the run
 * @param profilesDir  the suite's profiles folder, passed to the validator
 * @param igs          the suite's further IGs, passed to the validator
 * @param capabilities the run-wide check of {@code metadata.capability}, which asks each server once
 * @param builtIn      the values the runner provides for {@code ${testrunner.<name>}} placeholders, by name
 * @param rewriting    which references in fixture resources are rewritten to the ids the server assigned
 * @param warnings     what the checks before sending found worth a warning, put on the Setup: assertions the
 *                     runner cannot evaluate ({@link TestScriptChecks#unsupportedWarnings}), fixtures sent before a
 *                     fixture they reference is created ({@link TestScriptChecks#orderWarnings})
 * @param deadline     when the execution must stop: the run was stopped, or the TestScript timeout has passed
 */
record ExecutionEnvironment(Servers servers, ProfileValidator validator, Path profilesDir,
                            List<String> igs, CapabilityCheck capabilities, Map<String, String> builtIn,
                            ReferenceRewriting rewriting, List<String> warnings, Deadline deadline) {

    ExecutionEnvironment {
        igs = List.copyOf(igs);
        builtIn = Map.copyOf(builtIn);
        warnings = List.copyOf(warnings);
    }
}
