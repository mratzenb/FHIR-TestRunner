package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.function.UnaryOperator;

import static at.hl7.testing.runner.core.model.Operation.apply;

/**
 * An assertion of a setup, test or teardown ({@code action.assert}).
 *
 * <p>Absent values are {@code null}.
 *
 * @param check                     what is checked
 * @param label                     label for the log
 * @param description               description for the log
 * @param direction                 {@code response} (default) or {@code request}
 * @param operator                  the operator; the default depends on the check
 * @param value                     the value compared with
 * @param sourceId                  fixture or response checked; the last response if absent
 * @param compareToSourceId         fixture or response the expected value is taken from
 * @param compareToSourceExpression FHIRPath expression evaluated on {@code compareToSourceId}
 * @param compareToSourcePath       XPath or JSONPath evaluated on {@code compareToSourceId}
 * @param warningOnly               whether a failure is only reported as warning
 * @param stopTestOnFail            whether a failure stops the current test (R5; absent in R4 means continue)
 */
public record Assertion(@Nonnull Check check, @Nullable String label, @Nullable String description,
                        @Nullable String direction, @Nullable Operator operator, @Nullable String value,
                        @Nullable String sourceId, @Nullable String compareToSourceId,
                        @Nullable String compareToSourceExpression, @Nullable String compareToSourcePath,
                        boolean warningOnly, boolean stopTestOnFail) implements Action {

    /** A builder of an assertion of {@code check}; nothing else is set. */
    @Nonnull
    public static Builder builder(@Nonnull Check check) {
        return new Builder(check);
    }

    /** A builder with every value of this assertion, to make a copy that differs in a few. */
    @Nonnull
    public Builder toBuilder() {
        return new Builder(check).label(label).description(description).direction(direction).operator(operator)
                .value(value).sourceId(sourceId).compareToSourceId(compareToSourceId)
                .compareToSourceExpression(compareToSourceExpression).compareToSourcePath(compareToSourcePath)
                .warningOnly(warningOnly).stopTestOnFail(stopTestOnFail);
    }

    /** Returns a copy with every value passed through {@code values}, which replaces {@code ${variable}} placeholders. */
    @Nonnull
    public Assertion withValues(@Nonnull UnaryOperator<String> values) {
        Check resolved = switch (check) {
            case Check.ContentType c -> new Check.ContentType(values.apply(c.expected()));
            case Check.Expression e -> new Check.Expression(values.apply(e.expression()));
            case Check.HeaderField h -> new Check.HeaderField(values.apply(h.name()));
            case Check.MinimumId m -> new Check.MinimumId(values.apply(m.fixtureId()));
            case Check.Path p -> new Check.Path(values.apply(p.path()));
            case Check.RequestMethod r -> new Check.RequestMethod(values.apply(r.method()));
            case Check.RequestUrl r -> new Check.RequestUrl(values.apply(r.url()));
            case Check.Resource r -> new Check.Resource(values.apply(r.type()));
            case Check.ResponseCode r -> new Check.ResponseCode(values.apply(r.codes()));
            case Check.ValidateProfile v -> new Check.ValidateProfile(values.apply(v.profileId()));
            case Check.NavigationLinks n -> n;
            case Check.Response r -> r;
        };
        return toBuilder().check(resolved).value(apply(values, value)).sourceId(apply(values, sourceId))
                .compareToSourceId(apply(values, compareToSourceId))
                .compareToSourceExpression(apply(values, compareToSourceExpression))
                .compareToSourcePath(apply(values, compareToSourcePath))
                .build();
    }

    /**
     * Builds an {@link Assertion} by name rather than by the position of its 12 components. Not thread-safe; the
     * assertion built is immutable.
     */
    public static final class Builder {
        private Check check;
        private String label;
        private String description;
        private String direction;
        private Operator operator;
        private String value;
        private String sourceId;
        private String compareToSourceId;
        private String compareToSourceExpression;
        private String compareToSourcePath;
        private boolean warningOnly;
        private boolean stopTestOnFail;

        private Builder(Check check) {
            this.check = check;
        }

        @Nonnull
        public Builder check(@Nonnull Check value) {
            this.check = value;
            return this;
        }

        @Nonnull
        public Builder label(@Nullable String value) {
            this.label = value;
            return this;
        }

        @Nonnull
        public Builder description(@Nullable String value) {
            this.description = value;
            return this;
        }

        @Nonnull
        public Builder direction(@Nullable String value) {
            this.direction = value;
            return this;
        }

        @Nonnull
        public Builder operator(@Nullable Operator value) {
            this.operator = value;
            return this;
        }

        @Nonnull
        public Builder value(@Nullable String newValue) {
            this.value = newValue;
            return this;
        }

        @Nonnull
        public Builder sourceId(@Nullable String value) {
            this.sourceId = value;
            return this;
        }

        @Nonnull
        public Builder compareToSourceId(@Nullable String value) {
            this.compareToSourceId = value;
            return this;
        }

        @Nonnull
        public Builder compareToSourceExpression(@Nullable String value) {
            this.compareToSourceExpression = value;
            return this;
        }

        @Nonnull
        public Builder compareToSourcePath(@Nullable String value) {
            this.compareToSourcePath = value;
            return this;
        }

        @Nonnull
        public Builder warningOnly(boolean value) {
            this.warningOnly = value;
            return this;
        }

        @Nonnull
        public Builder stopTestOnFail(boolean value) {
            this.stopTestOnFail = value;
            return this;
        }

        @Nonnull
        public Assertion build() {
            return new Assertion(check, label, description, direction, operator, value, sourceId, compareToSourceId,
                    compareToSourceExpression, compareToSourcePath, warningOnly, stopTestOnFail);
        }
    }
}
