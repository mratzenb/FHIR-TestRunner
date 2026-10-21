package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Reads and changes the parts of a FHIR resource that look the same in every FHIR version: {@code resourceType},
 * {@code id}, {@code meta.versionId} and the {@code reference} of References. Works on JSON and XML without a FHIR
 * model, so the engine needs no version-specific code for them.
 */
public final class ResourceContent {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResourceContent() {
    }

    /** The resource type, e.g. {@code Patient}, or {@code null} if the body is no resource (e.g. a JSON Patch). */
    public static String resourceType(Body body) {
        return switch (body.format()) {
            case JSON -> json(body) instanceof ObjectNode o && o.path("resourceType").isTextual()
                    ? o.get("resourceType").asText() : null;
            case XML -> Xml.parse(body.text()).getDocumentElement().getLocalName();
            case OTHER -> null;
        };
    }

    /** The logical id of the resource, or {@code null}. */
    public static String id(Body body) {
        return switch (body.format()) {
            case JSON -> textOrNull(json(body).path("id"));
            case XML -> valueOf(Xml.child(Xml.parse(body.text()).getDocumentElement(), "id"));
            case OTHER -> null;
        };
    }

    /** {@code meta.versionId} of the resource, or {@code null}. */
    public static String versionId(Body body) {
        return switch (body.format()) {
            case JSON -> textOrNull(json(body).path("meta").path("versionId"));
            case XML -> valueOf(Xml.child(Xml.child(Xml.parse(body.text()).getDocumentElement(), "meta"), "versionId"));
            case OTHER -> null;
        };
    }

    /** Returns the body with its logical id set to {@code id}. */
    public static Body withId(Body body, String id) {
        switch (body.format()) {
            case JSON -> {
                if (!(json(body) instanceof ObjectNode resource)) {
                    throw new IllegalArgumentException("The body is no FHIR resource, it has no id to set");
                }
                ObjectNode copy = JSON.createObjectNode();
                resource.properties().forEach(field -> {
                    if (field.getKey().equals("id")) {
                        return;
                    }
                    copy.set(field.getKey(), field.getValue());
                    if (field.getKey().equals("resourceType")) {
                        copy.put("id", id); // keep the usual element order: resourceType, id, ...
                    }
                });
                if (!copy.has("id")) {
                    copy.put("id", id);
                }
                return Body.json(write(copy));
            }
            case XML -> {
                Document document = Xml.parse(body.text());
                Element root = document.getDocumentElement();
                Element idElement = Xml.child(root, "id");
                if (idElement == null) {
                    idElement = document.createElementNS(root.getNamespaceURI(), "id");
                    root.insertBefore(idElement, root.getFirstChild());
                }
                idElement.setAttribute("value", id);
                return Body.xml(Xml.write(document));
            }
            default -> throw new IllegalArgumentException("The body is no FHIR resource, it has no id to set");
        }
    }

    /** All values of {@code Reference.reference} in the resource, in document order. */
    public static Set<String> references(Body body) {
        Set<String> references = new LinkedHashSet<>();
        switch (body.format()) {
            case JSON -> collectJsonReferences(json(body), references);
            case XML -> {
                NodeList nodes = Xml.parse(body.text()).getElementsByTagNameNS("*", "reference");
                for (int i = 0; i < nodes.getLength(); i++) {
                    String value = ((Element) nodes.item(i)).getAttribute("value");
                    if (!value.isEmpty()) {
                        references.add(value);
                    }
                }
            }
            case OTHER -> {
            }
        }
        return references;
    }

    /**
     * Returns the body with every {@code Reference.reference} passed through {@code mapping}. The body is returned
     * unchanged (same text) if no reference changes.
     */
    public static Body replaceReferences(Body body, UnaryOperator<String> mapping) {
        switch (body.format()) {
            case JSON -> {
                JsonNode root = json(body).deepCopy();
                return replaceJsonReferences(root, mapping) ? Body.json(write(root)) : body;
            }
            case XML -> {
                Document document = Xml.parse(body.text());
                NodeList nodes = document.getElementsByTagNameNS("*", "reference");
                boolean changed = false;
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element element = (Element) nodes.item(i);
                    String value = element.getAttribute("value");
                    String mapped = mapping.apply(value);
                    if (!value.isEmpty() && !mapped.equals(value)) {
                        element.setAttribute("value", mapped);
                        changed = true;
                    }
                }
                return changed ? Body.xml(Xml.write(document)) : body;
            }
            default -> {
                return body;
            }
        }
    }

    /**
     * Extracts type, id and version from a {@code Location} or {@code Content-Location} header such as
     * {@code http://server/fhir/Patient/123/_history/2}.
     *
     * @return {@code type}, {@code id} and {@code version} (each may be absent)
     */
    public static Map<String, String> parseLocation(String location) {
        String path = location;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String[] segments = path.replaceAll("/+$", "").split("/");
        int history = -1;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals("_history")) {
                history = i;
            }
        }
        int idIndex = history >= 0 ? history - 1 : segments.length - 1;
        if (idIndex < 1) {
            return Map.of();
        }
        String version = history >= 0 && history + 1 < segments.length ? segments[history + 1] : null;
        return version == null
                ? Map.of("type", segments[idIndex - 1], "id", segments[idIndex])
                : Map.of("type", segments[idIndex - 1], "id", segments[idIndex], "version", version);
    }

    private static void collectJsonReferences(JsonNode node, Set<String> references) {
        if (node.isObject()) {
            node.properties().forEach(field -> {
                if (field.getKey().equals("reference") && field.getValue().isTextual()) {
                    references.add(field.getValue().asText());
                } else {
                    collectJsonReferences(field.getValue(), references);
                }
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectJsonReferences(item, references));
        }
    }

    private static boolean replaceJsonReferences(JsonNode node, UnaryOperator<String> mapping) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (var field : object.properties()) {
                if (field.getKey().equals("reference") && field.getValue().isTextual()) {
                    String value = field.getValue().asText();
                    String mapped = mapping.apply(value);
                    if (!mapped.equals(value)) {
                        field.setValue(TextNode.valueOf(mapped));
                        changed = true;
                    }
                } else {
                    changed |= replaceJsonReferences(field.getValue(), mapping);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                changed |= replaceJsonReferences(item, mapping);
            }
        }
        return changed;
    }

    static JsonNode json(Body body) {
        try {
            return JSON.readTree(body.text());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isValueNode() && !node.asText().isEmpty() ? node.asText() : null;
    }

    private static String valueOf(Node element) {
        if (element instanceof Element e && e.hasAttribute("value")) {
            return e.getAttribute("value");
        }
        return null;
    }

}
