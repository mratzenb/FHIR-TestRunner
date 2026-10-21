package at.hl7.testing.runner.core.engine;

/** An assertion did not hold: the server under test did not behave as expected. Reported as {@code fail}. */
public class AssertionFailure extends RuntimeException {

    public AssertionFailure(String message) {
        super(message);
    }
}
