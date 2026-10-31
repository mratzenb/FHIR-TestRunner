package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Reads and changes the parts of a FHIR resource that look the same in every FHIR version: {@code resourceType},
 * {@code id}, {@code meta.versionId}, the {@code reference} of References and the {@code contained} resources. Works
 * on JSON and XML without a FHIR model, so the engine needs no version-specific code for them.
 */
public final class ResourceContent {


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
                ObjectNode copy = Json.MAPPER.createObjectNode();
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

    /**
     * A resource contained in this one ({@code contained}), as a body of its own in the same format.
     *
     * @param id the id of the contained resource, without the {@code #} of a reference to it
     */
    public static Optional<Body> contained(Body body, String id) {
        switch (body.format()) {
            case JSON -> {
                for (JsonNode resource : json(body).path("contained")) {
                    if (id.equals(textOrNull(resource.path("id")))) {
                        return Optional.of(Body.json(write(resource)));
                    }
                }
            }
            case XML -> {
                for (Element resource : containedXml(Xml.parse(body.text()))) {
                    if (id.equals(valueOf(Xml.child(resource, "id")))) {
                        Document document = Xml.parse("<x/>");
                        document.replaceChild(document.importNode(resource, true), document.getDocumentElement());
                        return Optional.of(Body.xml(Xml.write(document)));
                    }
                }
            }
            case OTHER -> {
            }
        }
        return Optional.empty();
    }

    /** The ids of the resources contained in this one, in document order. */
    public static List<String> containedIds(Body body) {
        List<String> ids = new ArrayList<>();
        switch (body.format()) {
            case JSON -> json(body).path("contained").forEach(r -> ids.add(textOrNull(r.path("id"))));
            case XML -> containedXml(Xml.parse(body.text())).forEach(r -> ids.add(valueOf(Xml.child(r, "id"))));
            case OTHER -> {
            }
        }
        ids.removeIf(java.util.Objects::isNull);
        return ids;
    }

    /** The resource element inside each {@code <contained>} of the root. */
    private static List<Element> containedXml(Document document) {
        List<Element> resources = new ArrayList<>();
        for (Node node = document.getDocumentElement().getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element wrapper && "contained".equals(wrapper.getLocalName())) {
                for (Node inner = wrapper.getFirstChild(); inner != null; inner = inner.getNextSibling()) {
                    if (inner instanceof Element resource) {
                        resources.add(resource);
                        break;
                    }
                }
            }
        }
        return resources;
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
     * Returns the body with every text value passed through {@code mapping}: the string values of JSON, the attribute
     * values and text of XML, the whole text otherwise. Names and the structure are left alone, so a value cannot
     * break the document. The body is returned unchanged (same text) if no value changes.
     */
    public static Body replaceText(Body body, UnaryOperator<String> mapping) {
        switch (body.format()) {
            case JSON -> {
                JsonNode root = json(body).deepCopy();
                return replaceJsonText(root, mapping) ? Body.json(write(root)) : body;
            }
            case XML -> {
                Document document = Xml.parse(body.text());
                return replaceXmlText(document.getDocumentElement(), mapping) ? Body.xml(Xml.write(document)) : body;
            }
            default -> {
                String mapped = mapping.apply(body.text());
                return mapped.equals(body.text()) ? body : new Body(mapped, body.format());
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

    private static boolean replaceJsonText(JsonNode node, UnaryOperator<String> mapping) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (var field : object.properties()) {
                if (field.getValue().isTextual()) {
                    String value = field.getValue().asText();
                    String mapped = mapping.apply(value);
                    if (!mapped.equals(value)) {
                        field.setValue(TextNode.valueOf(mapped));
                        changed = true;
                    }
                } else {
                    changed |= replaceJsonText(field.getValue(), mapping);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isTextual()) {
                    String value = array.get(i).asText();
                    String mapped = mapping.apply(value);
                    if (!mapped.equals(value)) {
                        array.set(i, TextNode.valueOf(mapped));
                        changed = true;
                    }
                } else {
                    changed |= replaceJsonText(array.get(i), mapping);
                }
            }
        }
        return changed;
    }

    private static boolean replaceXmlText(Node node, UnaryOperator<String> mapping) {
        boolean changed = false;
        if (node.getAttributes() != null) {
            for (int i = 0; i < node.getAttributes().getLength(); i++) {
                Node attribute = node.getAttributes().item(i);
                String mapped = mapping.apply(attribute.getNodeValue());
                if (!mapped.equals(attribute.getNodeValue())) {
                    attribute.setNodeValue(mapped);
                    changed = true;
                }
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String mapped = mapping.apply(child.getNodeValue());
                if (!mapped.equals(child.getNodeValue())) {
                    child.setNodeValue(mapped);
                    changed = true;
                }
            } else {
                changed |= replaceXmlText(child, mapping);
            }
        }
        return changed;
    }

    static JsonNode json(Body body) {
        return Json.read(body.text());
    }

    private static String write(JsonNode node) {
        return Json.write(node);
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
