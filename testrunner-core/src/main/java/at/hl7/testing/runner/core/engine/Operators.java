package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Operator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Applies assertion operators to the actual value (from the response) and the expected value (from the TestScript).
 *
 * <p>Values are compared by their type ({@link Values#equal}, {@link Values#order}): a FHIRPath result keeps its
 * type and the expected value is read as that type – {@code 1.0} equals {@code 1.00}, and
 * {@code 2026-01-01T10:00:00+01:00} equals {@code 2026-01-01T09:00:00Z}. Text from a path or a header is equal only
 * to the same text; {@code greaterThan} and {@code lessThan} order two texts as numbers or as dates and times if both
 * are. Values that cannot be compared – dates of different precision, a number and a text – fail with the reason.
 * {@code contains} and {@code notContains} always work on the text.
 */
final class Operators {

    private Operators() {
    }

    /**
     * @param actual   the value found, {@code null} if nothing was found
     * @param expected the value of the TestScript (a comma separated list for {@code in} and {@code notIn}), or the
     *                 value found by {@code compareToSourceExpression} or {@code compareToSourcePath}
     * @throws AssertionFailure if the operator does not hold, or the values cannot be compared
     */
    static void apply(Operator operator, Object actual, Object expected) {
        String actualText = actual == null ? null : Values.text(actual);
        String expectedText = expected == null ? null : Values.text(expected);
        switch (operator) {
            case EQUALS -> require(actual != null && equal(actual, expected, actualText, expectedText),
                    "Expected '%s', got '%s'", expectedText, actualText);
            case NOT_EQUALS -> require(actual == null || !equal(actual, expected, actualText, expectedText),
                    "Expected a value other than '%s'", expectedText);
            case IN -> require(actual != null && in(actual, actualText, expectedText),
                    "Expected '%s' to be one of %s", actualText, list(expectedText));
            case NOT_IN -> require(actual == null || !in(actual, actualText, expectedText),
                    "Expected '%s' not to be one of %s", actualText, list(expectedText));
            case GREATER_THAN -> require(actual != null && order(actual, expected) > 0,
                    "Expected '%s' to be greater than '%s'", actualText, expectedText);
            case LESS_THAN -> require(actual != null && order(actual, expected) < 0,
                    "Expected '%s' to be less than '%s'", actualText, expectedText);
            case EMPTY -> require(actual == null || actualText.isEmpty(), "Expected no value, got '%s'", actualText);
            case NOT_EMPTY -> require(actual != null && !actualText.isEmpty(), "Expected a value, got none");
            case CONTAINS -> require(actual != null && actualText.contains(expectedText),
                    "Expected '%s' to contain '%s'", actualText, expectedText);
            case NOT_CONTAINS -> require(actual == null || !actualText.contains(expectedText),
                    "Expected '%s' not to contain '%s'", actualText, expectedText);
            case EVAL -> require("true".equals(actualText), actual == null
                    ? "Expected the expression to be true, got an empty result" : "Expected the expression to be true, got '%s'", actualText);
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

    private static boolean equal(Object actual, Object expected, String actualText, String expectedText) {
        try {
            return Values.equal(actual, expected);
        } catch (Values.IncomparableException e) {
            throw new AssertionFailure("Expected '" + expectedText + "', got '" + actualText + "', but "
                    + e.getMessage());
        }
    }

    /** One of the list equals {@code actual}; an entry that cannot be compared with it fails the assertion. */
    private static boolean in(Object actual, String actualText, String expectedText) {
        List<String> incomparable = new ArrayList<>();
        for (String entry : list(expectedText)) {
            try {
                if (Values.equal(actual, entry)) {
                    return true;
                }
            } catch (Values.IncomparableException e) {
                incomparable.add(e.getMessage());
            }
        }
        if (!incomparable.isEmpty()) {
            throw new AssertionFailure("Expected '" + actualText + "' to be one of " + list(expectedText) + ", but "
                    + String.join("; ", incomparable));
        }
        return false;
    }

    private static int order(Object actual, Object expected) {
        try {
            return Values.order(actual, expected);
        } catch (Values.IncomparableException e) {
            throw new AssertionFailure(e.getMessage());
        }
    }

    private static List<String> list(String values) {
        return Arrays.stream(values.split(",")).map(String::trim).toList();
    }

    private static void require(boolean condition, String message, Object... arguments) {
        if (!condition) {
            throw new AssertionFailure(message.formatted(arguments));
        }
    }
}
