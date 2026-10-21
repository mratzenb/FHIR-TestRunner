package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.ResourceContent;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.spi.FhirPathEvaluator;
import com.jayway.jsonpath.JsonPath;

import java.util.List;

/**
 * A FHIRPath stand-in for the core tests: understands {@code <Type>.<path>} (evaluated as JSONPath) and
 * {@code <Type>.<path>.exists()}. The real FHIRPath engines live in the version modules and are tested there.
 */
final class StubFhirPath implements FhirPathEvaluator {

    @Override
    public List<Object> evaluate(Body resource, String expression) {
        boolean exists = expression.endsWith(".exists()");
        String path = exists ? expression.substring(0, expression.length() - ".exists()".length()) : expression;
        String type = ResourceContent.resourceType(resource);
        if (!path.startsWith(type + ".") && !path.equals(type)) {
            return exists ? List.of(false) : List.of();
        }
        String jsonPath = "$" + path.substring(type.length());
        Object value;
        try {
            value = JsonPath.read(resource.text(), jsonPath);
        } catch (RuntimeException e) {
            value = null;
        }
        if (exists) {
            return List.of(value != null);
        }
        return value == null ? List.of() : value instanceof List<?> list ? List.copyOf(list) : List.of(value);
    }
}
