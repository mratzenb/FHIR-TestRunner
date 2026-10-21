package at.hl7.testing.runner.core.spi;

/**
 * Thrown when a validation could not be performed, e.g. because the validator is missing or produced no result.
 * Reported as an error, never as a passed validation: a validator that does not run looks exactly like one that
 * finds nothing.
 */
public class ValidatorUnavailableException extends RuntimeException {

    public ValidatorUnavailableException(String message) {
        super(message);
    }

    public ValidatorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
