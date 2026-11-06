package at.hl7.testing.runner.core.spi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Thrown when a validation could not be performed, e.g. because the validator is missing or produced no result.
 * Reported as an error, never as a passed validation: a validator that does not run looks exactly like one that
 * finds nothing.
 */
public class ValidatorUnavailableException extends RuntimeException {

    public ValidatorUnavailableException(@Nonnull String message) {
        super(message);
    }

    public ValidatorUnavailableException(@Nonnull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
