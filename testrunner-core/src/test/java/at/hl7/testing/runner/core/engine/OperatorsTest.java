package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.FhirTemporal;
import at.hl7.testing.runner.core.model.Operator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Typed comparisons (M5.2): a value found by FHIRPath keeps its type, the expected text is read as that type. Text
 * from a path or a header compares as text, except that {@code greaterThan} and {@code lessThan} order numbers and
 * dates.
 */
class OperatorsTest {

    static void holds(Operator operator, Object actual, Object expected) {
        assertThatCode(() -> Operators.apply(operator, actual, expected)).as(actual + " " + operator + " " + expected)
                .doesNotThrowAnyException();
    }

    static void fails(Operator operator, Object actual, Object expected, String message) {
        assertThatThrownBy(() -> Operators.apply(operator, actual, expected)).as(actual + " " + operator + " " + expected)
                .isInstanceOf(AssertionFailure.class).hasMessageContaining(message);
    }

    static FhirTemporal temporal(String text) {
        return FhirTemporal.parse(text).orElseThrow();
    }

    @Test
    void numbersCompareByValue() {
        holds(Operator.EQUALS, new BigDecimal("1.0"), "1.00");
        holds(Operator.EQUALS, 5, "5.0");
        holds(Operator.IN, new BigDecimal("2.50"), "1, 2.5, 3");
        holds(Operator.GREATER_THAN, new BigDecimal("10"), "9.99");
        holds(Operator.LESS_THAN, 2, new BigDecimal("10"));
        fails(Operator.EQUALS, new BigDecimal("1.0"), "1.01", "Expected '1.01', got '1.0'");
        fails(Operator.EQUALS, new BigDecimal("1.0"), "one", "Expected 'one', got '1.0'");
        fails(Operator.GREATER_THAN, new BigDecimal("1"), "x", "'1' and 'x' cannot be compared: 'x' is no number");
    }

    @Test
    void datesCompareAsPointsInTime() {
        holds(Operator.EQUALS, temporal("2026-01-01T10:00:00+01:00"), "2026-01-01T09:00:00Z");
        holds(Operator.NOT_EQUALS, temporal("2026-01-01T10:00:00+01:00"), "2026-01-01T10:00:00Z");
        holds(Operator.GREATER_THAN, temporal("2026-01-01T10:00:00+01:00"), "2026-01-01T08:59:59Z");
        holds(Operator.LESS_THAN, temporal("2026-01-01T10:00:00+01:00"), "2026-01-01T09:00:01Z");
        holds(Operator.IN, temporal("2026-01-01"), "2025-12-31, 2026-01-01");
        fails(Operator.GREATER_THAN, temporal("2026-01-01T10:00:00+01:00"), "2026-01-01T09:00:00Z",
                "Expected '2026-01-01T10:00:00+01:00' to be greater than '2026-01-01T09:00:00Z'");
    }

    @Test
    void datesOfDifferentPrecisionFailWithTheReason() {
        fails(Operator.EQUALS, temporal("2026-01"), "2026-01-01",
                "Expected '2026-01-01', got '2026-01', but '2026-01' and '2026-01-01' cannot be compared: only"
                        + " '2026-01-01' has a day");
        fails(Operator.NOT_EQUALS, temporal("2026-01"), "2026-01-01", "cannot be compared");
        fails(Operator.LESS_THAN, temporal("2026-01-01T10:00:00"), "2026-01-01T11:00:00Z",
                "only '2026-01-01T11:00:00Z' has a time zone offset");
        fails(Operator.IN, temporal("2026-01"), "2025-12, 2026-01-02",
                "Expected '2026-01' to be one of [2025-12, 2026-01-02], but '2026-01' and '2026-01-02' cannot be compared");
        // a value that matches decides, even if another of the list cannot be compared
        holds(Operator.IN, temporal("2026-01"), "2026-01-02, 2026-01");
    }

    @Test
    void booleansCompareAsBooleans() {
        holds(Operator.EQUALS, true, "true");
        fails(Operator.EQUALS, false, "true", "Expected 'true', got 'false'");
        fails(Operator.GREATER_THAN, true, "false", "true and false have no order");
    }

    @Test
    void textComparesAsText() {
        // a path or a header is text: equal means the same text
        fails(Operator.EQUALS, "1.0", "1.00", "Expected '1.00', got '1.0'");
        holds(Operator.EQUALS, "W/\"1\"", "W/\"1\"");
        holds(Operator.CONTAINS, temporal("2026-01-01T10:00:00Z"), "2026-01");
        // ordering: numbers and dates if both are, else lexicographically
        holds(Operator.GREATER_THAN, "10", "9");
        holds(Operator.GREATER_THAN, "2026-01-01T10:00:00+01:00", "2026-01-01T08:00:00Z");
        holds(Operator.GREATER_THAN, "b", "a");
        fails(Operator.LESS_THAN, "2026-01", "2026-01-01", "cannot be compared: only '2026-01-01' has a day");
    }

    @Test
    void expectedValueOfAnotherSourceKeepsItsTypeToo() {
        holds(Operator.EQUALS, temporal("2026-01-01T10:00:00+01:00"), temporal("2026-01-01T09:00:00Z"));
        holds(Operator.EQUALS, "2026-01-01T10:00:00+01:00", temporal("2026-01-01T09:00:00Z"));
        holds(Operator.EQUALS, new BigDecimal("3"), new BigDecimal("3.000"));
    }
}
