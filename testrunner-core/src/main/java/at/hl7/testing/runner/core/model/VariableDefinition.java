package at.hl7.testing.runner.core.model;

/**
 * A variable declared by a TestScript ({@code TestScript.variable}). Variables are evaluated each time a
 * {@code ${name}} placeholder is replaced, so they always reflect the latest responses.
 *
 * <p>Absent values are {@code null}. At most one of {@code expression}, {@code path} and {@code headerField} is set.
 *
 * @param name         the name used in {@code ${name}} placeholders
 * @param defaultValue value used when nothing else is set, or when the evaluation finds nothing
 * @param expression   FHIRPath expression evaluated against {@code sourceId}
 * @param path         XPath or JSONPath evaluated against {@code sourceId}
 * @param headerField  response header read from {@code sourceId}
 * @param sourceId     fixture or response the variable is evaluated against; the last response if absent
 * @param hint         description for a human tester
 */
public record VariableDefinition(String name, String defaultValue, String expression, String path,
                                 String headerField, String sourceId, String hint) {
}
