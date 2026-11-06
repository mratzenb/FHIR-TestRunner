package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.FhirTemporal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The values the operators compare, and their text for variables and messages.
 *
 * <p>A value keeps the type it was found with: a FHIRPath result is a {@link BigDecimal}, a {@link Boolean}, a
 * {@link FhirTemporal} (date, dateTime, instant, time) or a {@link String}. Text from a path, a header or the
 * TestScript has no type of its own. Two values compare by the type of the typed one, with the other read as that
 * type – numbers by value, dates and times as points in time, by the rules of FHIRPath. Two texts are equal only if
 * they are the same text, and are ordered as numbers or as dates and times if both are, else as text.
 */
final class Values {

    private Values() {
    }

    /** The single value of a result collection as text; {@code null} for an empty collection. */
    static String single(List<?> values, String what) {
        Object value = value(values, what);
        return value == null ? null : text(value);
    }

    /** The single value of a result collection with its type; {@code null} for an empty collection. */
    static Object value(List<?> values, String what) {
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new AssertionFailure(what + " returned " + values.size() + " values where one was expected: "
                    + values.stream().map(Values::text).toList());
        }
        return typed(values.getFirst());
    }

    static String text(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * Whether the two are equal: by the type of the typed one, or as text. A number and a text that is no number
     * are not equal.
     *
     * @throws IncomparableException if they are dates or times that cannot be compared, e.g. of different precision
     */
    static boolean equal(Object actual, Object expected) {
        Object a = typed(actual);
        Object e = typed(expected);
        if (a instanceof String && e instanceof String) {
            return a.equals(e);
        }
        Object typed = a instanceof String ? e : a;
        Object other = as(typed, typed == a ? e : a);
        if (other == null) {
            return false;
        }
        return switch (typed) {
            case BigDecimal number -> number.compareTo((BigDecimal) other) == 0;
            case FhirTemporal temporal -> compare(temporal, (FhirTemporal) other) == 0;
            default -> typed.equals(other);
        };
    }

    /**
     * The order of the two: negative if {@code actual} is less, zero if equal, positive if greater.
     *
     * @throws IncomparableException if they have no order: of different types, booleans, or dates and times that
     *                               cannot be compared
     */
    static int order(Object actual, Object expected) {
        Object a = typed(actual);
        Object e = typed(expected);
        if (a instanceof String textA && e instanceof String textE) {
            BigDecimal numberA = number(textA);
            BigDecimal numberE = number(textE);
            if (numberA != null && numberE != null) {
                return numberA.compareTo(numberE);
            }
            Optional<FhirTemporal> temporalA = FhirTemporal.parse(textA);
            Optional<FhirTemporal> temporalE = FhirTemporal.parse(textE);
            if (temporalA.isPresent() && temporalE.isPresent()) {
                return compare(temporalA.get(), temporalE.get());
            }
            return textA.compareTo(textE);
        }
        Object typed = a instanceof String ? e : a;
        Object convertedA = as(typed, a);
        Object convertedE = as(typed, e);
        if (convertedA == null || convertedE == null) {
            throw new IncomparableException("'" + text(a) + "' and '" + text(e) + "' cannot be compared: '"
                    + text(convertedA == null ? a : e) + "' is no " + kind(typed));
        }
        return switch (typed) {
            case BigDecimal number -> ((BigDecimal) convertedA).compareTo((BigDecimal) convertedE);
            case FhirTemporal temporal -> compare((FhirTemporal) convertedA, (FhirTemporal) convertedE);
            case Boolean bool -> throw new IncomparableException("'" + text(a) + "' and '" + text(e)
                    + "' cannot be compared: true and false have no order");
            default -> text(convertedA).compareTo(text(convertedE));
        };
    }

    /** Numbers as {@link BigDecimal}, so that {@code 5} (an integer) and {@code 5.0} (a decimal) are equal. */
    private static Object typed(Object value) {
        return value instanceof Number number && !(value instanceof BigDecimal) ? new BigDecimal(number.toString())
                : value;
    }

    /** {@code value} read as the type of {@code typed}; {@code null} if it is not of that type. */
    private static Object as(Object typed, Object value) {
        if (value == null || value.getClass() == typed.getClass()) {
            return value;
        }
        String text = text(value).trim();
        return switch (typed) {
            case BigDecimal number -> number(text);
            case FhirTemporal temporal -> FhirTemporal.parse(text).orElse(null);
            case Boolean bool -> text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
            default -> text(value);
        };
    }

    private static int compare(FhirTemporal a, FhirTemporal b) {
        try {
            return a.compare(b);
        } catch (IllegalArgumentException e) {
            throw new IncomparableException(e.getMessage());
        }
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String kind(Object typed) {
        return switch (typed) {
            case BigDecimal number -> "number";
            case FhirTemporal temporal -> "date or time";
            case Boolean bool -> "boolean";
            default -> "text";
        };
    }

    /** Two values that cannot be compared; the message names both and says why. */
    static final class IncomparableException extends RuntimeException {
        IncomparableException(String message) {
            super(message);
        }
    }
}
