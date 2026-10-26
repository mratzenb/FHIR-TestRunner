package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A FHIR resource or element read from JSON or XML into one shape, without a FHIR model: a primitive value and
 * named children, every child a list (cardinality is not known without the model).
 *
 * <p>The two encodings are brought together where they differ:
 * <ul>
 *   <li>JSON {@code "_given": [...]} (id and extensions of primitives) is merged into the {@code given} elements,
 *       as XML nests them;</li>
 *   <li>a resource has a {@code resourceType} child in both, also when it is contained or a Bundle entry (XML
 *       wraps those in an element named after the type);</li>
 *   <li>JSON numbers and booleans become text, like the {@code value} attributes of XML.</li>
 * </ul>
 * The narrative ({@code div}) is kept as text in each encoding and is not comparable across them.
 *
 * @param value    the primitive value, {@code null} for a complex element or a primitive with extensions only
 * @param children the child elements by name, in document order
 */
public record FhirElement(String value, Map<String, List<FhirElement>> children) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String XHTML = "http://www.w3.org/1999/xhtml";

    public FhirElement {
        children = children == null ? Map.of() : children;
    }

    /**
     * Reads a resource.
     *
     * @throws IllegalArgumentException if the body is neither JSON nor XML or cannot be parsed
     */
    public static FhirElement of(Body body) {
        return switch (body.format()) {
            case JSON -> {
                try {
                    yield fromJson(JSON.readTree(body.text()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
                }
            }
            case XML -> resource(Xml.parse(body.text()).getDocumentElement());
            case OTHER -> throw new IllegalArgumentException("The content is neither JSON nor XML");
        };
    }

    /** All values of a child element, empty if there is none. */
    public List<FhirElement> all(String name) {
        return children.getOrDefault(name, List.of());
    }

    /** The value of the first child element of that name. */
    public Optional<String> valueOf(String name) {
        return all(name).stream().map(FhirElement::value).filter(v -> v != null).findFirst();
    }

    /** A copy without the child elements of that name, e.g. {@code id}. */
    public FhirElement without(String name) {
        Map<String, List<FhirElement>> copy = new LinkedHashMap<>(children);
        copy.remove(name);
        return new FhirElement(value, copy);
    }

    /**
     * A copy with only the elements on the given paths, e.g. {@code rest.resource.type} keeps {@code rest},
     * {@code rest.resource} and {@code rest.resource.type}, but no other child of them.
     */
    public FhirElement only(Set<String> paths) {
        return only(paths, "");
    }

    private FhirElement only(Set<String> paths, String prefix) {
        Map<String, List<FhirElement>> kept = new LinkedHashMap<>();
        children.forEach((name, values) -> {
            String path = prefix + name;
            if (paths.stream().anyMatch(p -> p.equals(path) || p.startsWith(path + "."))) {
                kept.put(name, values.stream().map(v -> v.only(paths, path + ".")).toList());
            }
        });
        return new FhirElement(value, kept);
    }

    /**
     * What of this element is not contained in {@code actual}: every value must be equal, every child must have a
     * counterpart. The order of repeated elements does not matter and {@code actual} may have more of them, but each
     * one here needs a counterpart of its own, so {@code ["a","a"]} is not contained in {@code ["a"]}. These are the
     * rules of {@code assert.minimumId} in the FHIR testing specification.
     *
     * @param path the path of this element, prefixed to every difference, e.g. {@code Patient}
     * @return a description of every difference, empty if this element is contained in {@code actual}
     */
    public List<String> notContainedIn(FhirElement actual, String path) {
        List<String> differences = new ArrayList<>();
        if (value != null && !value.equals(actual.value)) {
            differences.add(path + ": expected '" + value + "', found "
                    + (actual.value == null ? "no value" : "'" + actual.value + "'"));
        }
        children.forEach((name, expectedValues) -> {
            List<FhirElement> actualValues = actual.all(name);
            String childPath = path + "." + name;
            if (actualValues.isEmpty()) {
                differences.add(childPath + ": missing, expected " + render(expectedValues));
                return;
            }
            int[] counterpart = match(expectedValues, actualValues);
            for (int i = 0; i < expectedValues.size(); i++) {
                if (counterpart[i] < 0) {
                    differences.addAll(unmatched(expectedValues, i, actualValues,
                            expectedValues.size() == 1 ? childPath : childPath + "[" + i + "]"));
                }
            }
        });
        return differences;
    }

    /**
     * Explains why an element has no counterpart. With a candidate that agrees on the element's own primitive
     * children (e.g. the same {@code type} or {@code code}), the differences to it are more telling than the
     * element as a whole. A candidate without differences is the counterpart of another element (a duplicate).
     */
    private static List<String> unmatched(List<FhirElement> expectedValues, int index, List<FhirElement> actualValues,
                                          String path) {
        FhirElement expected = expectedValues.get(index);
        List<String> closest = null;
        for (FhirElement candidate : actualValues) {
            boolean single = expectedValues.size() == 1 && actualValues.size() == 1;
            if (single || (!expected.children.isEmpty() && expected.sameKeys(candidate))) {
                List<String> differences = expected.notContainedIn(candidate, path);
                if (closest == null || differences.size() < closest.size()) {
                    closest = differences;
                }
            }
        }
        return closest != null && !closest.isEmpty() ? closest : List.of(path + ": no match for " + expected);
    }

    /** Whether {@code other} has every value of the primitive children of this element. */
    private boolean sameKeys(FhirElement other) {
        return children.entrySet().stream()
                .filter(e -> e.getValue().stream().allMatch(v -> v.value != null && v.children.isEmpty()))
                .allMatch(e -> other.all(e.getKey()).stream().map(FhirElement::value).collect(Collectors.toSet())
                        .containsAll(e.getValue().stream().map(FhirElement::value).toList()));
    }

    /**
     * Assigns each expected element its own actual counterpart, if possible (maximum bipartite matching; a greedy
     * assignment could use up the only counterpart of a later element).
     *
     * @return for each expected element the index of its counterpart in {@code actual}, -1 if it has none
     */
    private static int[] match(List<FhirElement> expected, List<FhirElement> actual) {
        boolean[][] contained = new boolean[expected.size()][actual.size()];
        for (int i = 0; i < expected.size(); i++) {
            for (int j = 0; j < actual.size(); j++) {
                contained[i][j] = expected.get(i).notContainedIn(actual.get(j), "").isEmpty();
            }
        }
        int[] owner = new int[actual.size()];
        Arrays.fill(owner, -1);
        for (int i = 0; i < expected.size(); i++) {
            assign(i, contained, owner, new boolean[actual.size()]);
        }
        int[] counterpart = new int[expected.size()];
        Arrays.fill(counterpart, -1);
        for (int j = 0; j < owner.length; j++) {
            if (owner[j] >= 0) {
                counterpart[owner[j]] = j;
            }
        }
        return counterpart;
    }

    private static boolean assign(int i, boolean[][] contained, int[] owner, boolean[] visited) {
        for (int j = 0; j < owner.length; j++) {
            if (contained[i][j] && !visited[j]) {
                visited[j] = true;
                if (owner[j] < 0 || assign(owner[j], contained, owner, visited)) {
                    owner[j] = i;
                    return true;
                }
            }
        }
        return false;
    }

    /** e.g. {@code {type=Patient, interaction=[{code=read}, {code=delete}]}}, shortened for messages. */
    @Override
    public String toString() {
        String text;
        if (children.isEmpty()) {
            text = String.valueOf(value);
        } else {
            text = children.entrySet().stream().map(e -> e.getKey() + "=" + render(e.getValue()))
                    .collect(Collectors.joining(", ", value == null ? "{" : "{value=" + value + ", ", "}"));
        }
        return text.length() > 300 ? text.substring(0, 297) + "..." : text;
    }

    private static String render(List<FhirElement> values) {
        return values.size() == 1 ? values.getFirst().toString()
                : values.stream().map(FhirElement::toString).collect(Collectors.joining(", ", "[", "]"));
    }

    private static FhirElement fromJson(JsonNode node) {
        if (!node.isObject()) {
            return new FhirElement(node.isNull() ? null : node.asText(), Map.of());
        }
        Map<String, List<FhirElement>> children = new LinkedHashMap<>();
        node.properties().forEach(field -> {
            if (!field.getKey().startsWith("_")) {
                children.put(field.getKey(), jsonValues(field.getValue()));
            }
        });
        // "_name" carries id and extensions of the primitive(s) "name"; nest them like XML does
        node.properties().forEach(field -> {
            if (field.getKey().startsWith("_")) {
                String name = field.getKey().substring(1);
                List<FhirElement> primitives = new ArrayList<>(children.getOrDefault(name, List.of()));
                List<FhirElement> extensions = jsonValues(field.getValue());
                for (int i = 0; i < extensions.size(); i++) {
                    FhirElement primitive = i < primitives.size() ? primitives.get(i) : new FhirElement(null, Map.of());
                    Map<String, List<FhirElement>> merged = new LinkedHashMap<>(primitive.children());
                    merged.putAll(extensions.get(i).children());
                    FhirElement withExtensions = new FhirElement(primitive.value(), merged);
                    if (i < primitives.size()) {
                        primitives.set(i, withExtensions);
                    } else {
                        primitives.add(withExtensions);
                    }
                }
                children.put(name, List.copyOf(primitives));
            }
        });
        return new FhirElement(null, children);
    }

    private static List<FhirElement> jsonValues(JsonNode node) {
        List<FhirElement> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> values.add(fromJson(item)));
        } else {
            values.add(fromJson(node));
        }
        return List.copyOf(values);
    }

    /** A resource element, e.g. {@code <Patient>}: its name becomes the {@code resourceType} child. */
    private static FhirElement resource(Element element) {
        FhirElement content = fromXml(element);
        Map<String, List<FhirElement>> children = new LinkedHashMap<>();
        children.put("resourceType", List.of(new FhirElement(element.getLocalName(), Map.of())));
        children.putAll(content.children());
        return new FhirElement(null, children);
    }

    private static FhirElement fromXml(Element element) {
        if (XHTML.equals(element.getNamespaceURI())) {
            return new FhirElement(element.getTextContent().strip(), Map.of());
        }
        Map<String, List<FhirElement>> children = new LinkedHashMap<>();
        Element onlyChild = null;
        int elements = 0;
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                elements++;
                onlyChild = child;
                children.computeIfAbsent(child.getLocalName(), n -> new ArrayList<>()).add(fromXml(child));
            }
        }
        // <resource><Patient>…</Patient></resource>: JSON has no level for the type, so drop it here too
        if (elements == 1 && isResourceName(onlyChild.getLocalName()) && !element.hasAttribute("value")) {
            return resource(onlyChild);
        }
        if (element.hasAttribute("id")) {
            children.put("id", List.of(new FhirElement(element.getAttribute("id"), Map.of())));
        }
        if (element.hasAttribute("url")) { // extension.url is an attribute in XML
            children.put("url", List.of(new FhirElement(element.getAttribute("url"), Map.of())));
        }
        children.replaceAll((name, values) -> List.copyOf(values));
        return new FhirElement(element.hasAttribute("value") ? element.getAttribute("value") : null, children);
    }

    /** FHIR element names start with a lower-case letter, resource names with an upper-case one. */
    private static boolean isResourceName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }
}
