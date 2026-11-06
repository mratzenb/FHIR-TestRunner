package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;

import java.util.function.UnaryOperator;

/** A header an operation adds to its request ({@code operation.requestHeader}). */
public record RequestHeader(@Nonnull String field, @Nonnull String value) {

    RequestHeader map(UnaryOperator<String> values) {
        return new RequestHeader(field, values.apply(value));
    }
}
