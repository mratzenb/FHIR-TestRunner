package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.PathEvaluator;
import at.hl7.testing.runner.core.model.VariableDefinition;
import at.hl7.testing.runner.core.spi.FhirPathEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${name}} placeholders with the current value of the TestScript variable {@code name}.
 *
 * <p>Variables are evaluated when they are used, so a variable on a response reflects the response received last
 * under that id.
 */
final class VariableResolver {

    private static final Logger log = LoggerFactory.getLogger(VariableResolver.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)}");

    private final Map<String, VariableDefinition> variables = new LinkedHashMap<>();
    private final ExecutionState state;
    private final FhirPathEvaluator fhirPath;

    VariableResolver(List<VariableDefinition> definitions, ExecutionState state, FhirPathEvaluator fhirPath) {
        this.state = state;
        this.fhirPath = fhirPath;
        definitions.forEach(variable -> variables.put(variable.name(), variable));
    }

    /** Replaces every placeholder in {@code text}; text without placeholders is returned as it is. */
    String resolve(String text) {
        if (text == null || !text.contains("${")) {
            return text;
        }
        Map<String, String> used = new LinkedHashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = used.computeIfAbsent(name, this::value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        used.forEach((name, value) -> log.debug("Variable ${{}} = '{}'", name, value));
        return result.toString();
    }

    private String value(String name) {
        VariableDefinition variable = variables.get(name);
        if (variable == null) {
            throw new TestScriptException("Variable '" + name + "' is used but not defined by the TestScript");
        }
        String value = evaluate(variable);
        if (value == null) {
            if (variable.defaultValue() != null) {
                return variable.defaultValue();
            }
            throw new TestScriptException("Variable '" + name + "' has no value: " + describe(variable)
                    + " found nothing and there is no defaultValue");
        }
        return value;
    }

    private String evaluate(VariableDefinition variable) {
        if (variable.expression() == null && variable.path() == null && variable.headerField() == null) {
            return null;
        }
        Source source = variable.sourceId() != null
                ? state.require(variable.sourceId(), "sourceId of variable '" + variable.name() + "'")
                : state.lastResponse().orElseThrow(() -> new TestScriptException("Variable '" + variable.name()
                        + "' has no sourceId and no operation has been executed yet"));
        try {
            if (variable.headerField() != null) {
                if (source instanceof Source.Fixture) {
                    throw new TestScriptException("Variable '" + variable.name() + "' reads header "
                            + variable.headerField() + ", but '" + variable.sourceId() + "' is a fixture, not a response");
                }
                return source.headers().first(variable.headerField()).orElse(null);
            }
            if (variable.expression() != null) {
                return Values.single(fhirPath.evaluate(source.body(), variable.expression()),
                        "Expression '" + variable.expression() + "' of variable '" + variable.name() + "'");
            }
            return Values.single(PathEvaluator.evaluate(source.body(), variable.path()),
                    "Path '" + variable.path() + "' of variable '" + variable.name() + "'");
        } catch (AssertionFailure | IllegalArgumentException e) {
            throw new TestScriptException(e.getMessage(), e);
        }
    }

    static String describe(VariableDefinition variable) {
        String on = variable.sourceId() != null ? " on " + variable.sourceId() : " on the last response";
        if (variable.expression() != null) {
            return "expression '" + variable.expression() + "'" + on;
        }
        if (variable.path() != null) {
            return "path '" + variable.path() + "'" + on;
        }
        if (variable.headerField() != null) {
            return "headerField '" + variable.headerField() + "'" + on;
        }
        return "defaultValue '" + variable.defaultValue() + "'";
    }
}
