package at.hl7.testing.runner.core.model;

import java.util.function.UnaryOperator;

/** A header an operation adds to its request ({@code operation.requestHeader}). */
public record RequestHeader(String field, String value) {

    RequestHeader map(UnaryOperator<String> values) {
        return new RequestHeader(field, values.apply(value));
    }
}
