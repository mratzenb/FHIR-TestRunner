package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.xpath.XPathEvaluationResult;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the {@code path} of assertions and variables: JSONPath on a JSON body, XPath on an XML body.
 *
 * <p>The kind of path is chosen by the format of the body, not guessed from the path. XPath is evaluated without
 * namespaces, so FHIR paths are written without prefixes ({@code Patient/name/family}). An element with a
 * {@code value} attribute (the FHIR XML form of primitives) evaluates to that attribute.
 */
public final class PathEvaluator {

    private static final Configuration JSON_PATH = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private PathEvaluator() {
    }

    /**
     * @return the values found, as text
     * @throws IllegalArgumentException if the path is invalid or the body is neither JSON nor XML
     */
    public static List<String> evaluate(Body body, String path) {
        return switch (body.format()) {
            case JSON -> jsonPath(body.text(), path);
            case XML -> xPath(body.text(), path);
            case OTHER -> throw new IllegalArgumentException("A path can only be evaluated on JSON or XML content");
        };
    }

    private static List<String> jsonPath(String json, String path) {
        List<?> values;
        try {
            values = JsonPath.using(JSON_PATH).parse(json).read(path);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("'" + path + "' is no valid JSONPath (the content is JSON): "
                    + e.getMessage(), e);
        }
        List<String> result = new ArrayList<>();
        for (Object value : values == null ? List.of() : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                try {
                    result.add(JSON.writeValueAsString(value));
                } catch (JsonProcessingException e) {
                    result.add(value.toString());
                }
            } else {
                result.add(value.toString());
            }
        }
        return result;
    }

    private static List<String> xPath(String xml, String path) {
        Node document = Xml.parse(xml, false);
        XPathEvaluationResult<?> evaluated;
        try {
            evaluated = XPathFactory.newInstance().newXPath().evaluateExpression(path, document);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("'" + path + "' is no valid XPath (the content is XML): "
                    + e.getMessage(), e);
        }
        List<String> result = new ArrayList<>();
        switch (evaluated.type()) {
            case NODESET -> {
                for (Node node : (XPathNodes) evaluated.value()) {
                    result.add(text(node));
                }
            }
            case NODE -> result.add(text((Node) evaluated.value()));
            case NUMBER -> result.add(number((Number) evaluated.value()));
            default -> result.add(String.valueOf(evaluated.value()));
        }
        return result;
    }

    /** XPath numbers are doubles; {@code count()} = 1 should compare equal to the TestScript value "1". */
    private static String number(Number value) {
        double d = value.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String text(Node node) {
        if (node instanceof Attr attribute) {
            return attribute.getValue();
        }
        if (node instanceof Element element && element.hasAttribute("value")) {
            return element.getAttribute("value");
        }
        return node.getTextContent().trim();
    }
}
