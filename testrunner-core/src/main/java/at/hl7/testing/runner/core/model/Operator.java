package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/** The operators of an assertion ({@code assert.operator}). */
public enum Operator {
    EQUALS("equals"),
    NOT_EQUALS("notEquals"),
    IN("in"),
    NOT_IN("notIn"),
    GREATER_THAN("greaterThan"),
    LESS_THAN("lessThan"),
    EMPTY("empty"),
    NOT_EMPTY("notEmpty"),
    CONTAINS("contains"),
    NOT_CONTAINS("notContains"),
    EVAL("eval"),
    /** R5 only, needs a human; not supported by an automated runner. */
    MANUAL_EVAL("manualEval");

    private final String code;

    Operator(String code) {
        this.code = code;
    }

    /** The code as written in a TestScript, e.g. {@code notEquals}. */
    @Nonnull
    public String code() {
        return code;
    }

    /** The operator for a code such as {@code notEquals}; {@code null} for {@code null}. */
    @Nullable
    public static Operator fromCode(@Nullable String code) {
        if (code == null) {
            return null;
        }
        for (Operator operator : values()) {
            if (operator.code.equals(code)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("Unknown assert operator '" + code + "'");
    }
}
