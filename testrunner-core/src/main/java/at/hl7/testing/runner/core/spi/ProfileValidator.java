package at.hl7.testing.runner.core.spi;

/**
 * Validates resources against the base specification or a profile. Used for {@code assert.validateProfileId}
 * and for the check of each TestScript before it runs. Implementations must be thread-safe.
 */
public interface ProfileValidator {

    /** Whether validation is available. When not, {@code validateProfileId} assertions become warnings. */
    boolean isEnabled();

    /** How validation is done, for the log at the start of a run. */
    default String description() {
        return isEnabled() ? "enabled" : "disabled";
    }

    /**
     * Validates a resource.
     *
     * @throws ValidatorUnavailableException if the validation could not be performed at all; this must never be
     *                                       reported as a successful validation
     */
    ValidationResult validate(ValidationRequest request);

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
