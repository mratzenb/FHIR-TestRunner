package at.hl7.testing.runner.core.engine;

/**
 * An action could not be executed: the TestScript is invalid, uses an unsupported feature, or a fixture or
 * response it refers to does not exist. Reported as {@code error}, not as {@code fail}.
 */
public class TestScriptException extends RuntimeException {

    public TestScriptException(String message) {
        super(message);
    }

    public TestScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
