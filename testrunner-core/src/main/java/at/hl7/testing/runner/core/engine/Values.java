package at.hl7.testing.runner.core.engine;

import java.math.BigDecimal;
import java.util.List;

/** Conversion of evaluation results to the text the operators compare. */
final class Values {

    private Values() {
    }

    /** The single value of a result collection as text; {@code null} for an empty collection. */
    static String single(List<?> values, String what) {
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new AssertionFailure(what + " returned " + values.size() + " values where one was expected: " + values);
        }
        return text(values.getFirst());
    }

    static String text(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }
}
