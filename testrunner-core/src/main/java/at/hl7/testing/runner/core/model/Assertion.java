package at.hl7.testing.runner.core.model;

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
public record Assertion(Check check, String label, String description, String direction, Operator operator,
                        String value, String sourceId, String compareToSourceId, String compareToSourceExpression,
                        String compareToSourcePath, boolean warningOnly, boolean stopTestOnFail) implements Action {

    /** Returns a copy with every value passed through {@code values}, which replaces {@code ${variable}} placeholders. */
    public Assertion withValues(UnaryOperator<String> values) {
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
        return new Assertion(resolved, label, description, direction, operator, apply(values, value),
                apply(values, sourceId), apply(values, compareToSourceId), apply(values, compareToSourceExpression),
                apply(values, compareToSourcePath), warningOnly, stopTestOnFail);
    }
}
