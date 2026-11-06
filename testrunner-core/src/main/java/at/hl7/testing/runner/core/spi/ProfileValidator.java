package at.hl7.testing.runner.core.spi;

import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * Validates resources against the base specification or a profile. Used for {@code assert.validateProfileId}
 * and for the check of each TestScript before it runs. Implementations must be thread-safe.
 */
public interface ProfileValidator {

    /** Whether validation is available. When not, {@code validateProfileId} assertions become warnings. */
    boolean isEnabled();

    /** How validation is done, for the log at the start of a run. */
    @Nonnull
    default String description() {
        return isEnabled() ? "enabled" : "disabled";
    }

    /**
     * Problems in the configuration of this validator that keep every run from starting, each naming the property to
     * fix, e.g. a validator jar that is not set. The runner adds them to the problems of the configuration: the run
     * does not start, exit code 2. Problems that only some validations meet are thrown by {@link #validate} instead.
     *
     * @return the problems, empty if there are none (the default)
     */
    @Nonnull
    default List<String> configurationProblems() {
        return List.of();
    }

    /**
     * Validates a resource.
     *
     * @throws ValidatorUnavailableException if the validation could not be performed at all; this must never be
     *                                       reported as a successful validation
     */
    @Nonnull
    ValidationResult validate(@Nonnull ValidationRequest request);

    /** A validator that validates nothing, used when validation is switched off. */
    ProfileValidator DISABLED = new ProfileValidator() {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public ValidationResult validate(ValidationRequest request) {
            throw new ValidatorUnavailableException("Profile validation is disabled");
        }
    };
}
