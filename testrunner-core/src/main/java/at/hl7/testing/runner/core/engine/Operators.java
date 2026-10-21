package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Operator;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Applies assertion operators to the actual value (from the response) and the expected value (from the TestScript).
 * Values are compared as text; {@code greaterThan} and {@code lessThan} compare numerically when both are numbers.
 */
final class Operators {

    private Operators() {
    }

    /**
     * @param actual   the value found, {@code null} if nothing was found
     * @param expected the value of the TestScript; a comma separated list for {@code in} and {@code notIn}
     * @throws AssertionFailure if the operator does not hold
     */
    static void apply(Operator operator, String actual, String expected) {
        switch (operator) {
            case EQUALS -> require(actual != null && actual.equals(expected),
                    "Expected '%s', got '%s'", expected, actual);
            case NOT_EQUALS -> require(actual == null || !actual.equals(expected),
                    "Expected a value other than '%s'", expected);
            case IN -> require(actual != null && list(expected).contains(actual),
                    "Expected '%s' to be one of %s", actual, list(expected));
            case NOT_IN -> require(actual == null || !list(expected).contains(actual),
                    "Expected '%s' not to be one of %s", actual, list(expected));
            case GREATER_THAN -> require(actual != null && compare(actual, expected) > 0,
                    "Expected '%s' to be greater than '%s'", actual, expected);
            case LESS_THAN -> require(actual != null && compare(actual, expected) < 0,
                    "Expected '%s' to be less than '%s'", actual, expected);
            case EMPTY -> require(actual == null || actual.isEmpty(), "Expected no value, got '%s'", actual);
            case NOT_EMPTY -> require(actual != null && !actual.isEmpty(), "Expected a value, got none");
            case CONTAINS -> require(actual != null && actual.contains(expected),
                    "Expected '%s' to contain '%s'", actual, expected);
            case NOT_CONTAINS -> require(actual == null || !actual.contains(expected),
                    "Expected '%s' not to contain '%s'", actual, expected);
            case EVAL -> require("true".equals(actual), actual == null
                    ? "Expected the expression to be true, got an empty result" : "Expected the expression to be true, got '%s'", actual);
            case MANUAL_EVAL -> throw new TestScriptException("Operator manualEval needs a human tester and is not"
                    + " supported by an automated runner");
        }
    }

    /** Whether an operator needs {@code expected}. */
    static boolean needsValue(Operator operator) {
        return switch (operator) {
            case EMPTY, NOT_EMPTY, EVAL, MANUAL_EVAL -> false;
            default -> true;
        };
    }

    private static List<String> list(String values) {
        return Arrays.stream(values.split(",")).map(String::trim).toList();
    }

    private static int compare(String actual, String expected) {
        try {
            return new BigDecimal(actual.trim()).compareTo(new BigDecimal(expected.trim()));
        } catch (NumberFormatException e) {
            return actual.compareTo(expected);
        }
    }

    private static void require(boolean condition, String message, Object... arguments) {
        if (!condition) {
            throw new AssertionFailure(message.formatted(arguments));
        }
    }
}
