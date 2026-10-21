package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.spi.ProfileValidator;

import java.util.List;

/**
 * Creates the HL7 validator adapter for a {@link ValidationConfig}, by {@code testrunner.validation.mode}.
 * A validator in server mode holds processes: close it ({@link AutoCloseable}) when done; Spring does so itself.
 */
public final class ProfileValidators {

    private ProfileValidators() {
    }

    public static ProfileValidator create(ValidationConfig config) {
        if (!config.enabled()) {
            return ProfileValidator.DISABLED;
        }
        return switch (config.mode()) {
            case PROCESS -> new ValidatorCliProfileValidator(config);
            case SERVER -> new ValidatorServerProfileValidator(config);
        };
    }

    /** Problems that keep the validator from running, e.g. a missing jar; empty if it looks usable. */
    public static List<String> problems(ValidationConfig config) {
        return config.enabled() ? ValidatorArguments.problems(config) : List.of();
    }
}
