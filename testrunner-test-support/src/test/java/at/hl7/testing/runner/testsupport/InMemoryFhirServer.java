package at.hl7.testing.runner.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small FHIR server on a free local port (Jetty), answering from memory. Enough FHIR for the runner's tests; no
 * FHIR version specifics, no validation.
 *
 * <ul>
 *   <li>{@code POST [type]} stores a JSON resource under a new id (201 + Location); XML bodies get an id but are
 *       not stored</li>
 *   <li>{@code PUT [type]/[id]} stores (200 if it existed, else 201); {@code PUT [type]?[search]} is a conditional
 *       update of a JSON resource (201 for no match, 200 for one, 412 for several)</li>
 *   <li>{@code GET [type]/[id]}, {@code GET [type]/[id]/_history(/[vid])}, {@code DELETE [type]/[id]}</li>
 *   <li>{@code GET [type]?name=value} returns a searchset Bundle of the stored resources of the type whose JSON
 *       contains every value (a crude stand-in for real search); {@code DELETE [type]?..} deletes them</li>
 *   <li>{@code GET metadata} returns a CapabilityStatement announcing these interactions for Patient and
 *       Organization (the others work as well, but are not announced)</li>
 *   <li>{@code POST [base]} returns a batch/transaction-response Bundle</li>
 * </ul>
 * {@link #on} replaces the answer to a request, {@link #requests()} lists the requests received.
 */
public final class InMemoryFhirServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FHIR_JSON = "application/fhir+json;charset=UTF-8";
    private static final Pattern XML_ID = Pattern.compile("<id\\s+value=\"([^\"]*)\"\\s*/>");

    /** A request as received. */
    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {

        /** {@code METHOD path}, e.g. {@code POST /Patient}. */
        public String line() {
            return method + " " + path;
        }

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    /** A fixed answer. */
    public record Answer(int status, String body, Map<String, String> headers) {

        public static Answer json(int status, String body) {
            return new Answer(status, body, Map.of("Content-Type", FHIR_JSON));
        }
    }

    private final Server server = new Server();
    private final ServerConnector connector = new ServerConnector(server);
    private final String fhirVersion;
    private final Map<String, ObjectNode> resources = new ConcurrentHashMap<>();
    private final Map<String, List<ObjectNode>> history = new ConcurrentHashMap<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Duration delay = Duration.ZERO;
    private final AtomicInteger nextId = new AtomicInteger(1);

    private InMemoryFhirServer(String fhirVersion) throws Exception {
        this.fhirVersion = fhirVersion;
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) {
                return InMemoryFhirServer.this.handle(request, response, callback);
            }
        });
        server.start();
    }

    /** Starts a server announcing {@code fhirVersion} (e.g. {@code 4.0.1}) in its CapabilityStatement. */
    public static InMemoryFhirServer start(String fhirVersion) {
        try {
            return new InMemoryFhirServer(fhirVersion);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the in-memory FHIR server", e);
        }
    }

    /** Base URL, e.g. {@code http://127.0.0.1:53412/fhir}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + connector.getLocalPort() + "/fhir";
    }

    /** Answers {@code METHOD path} (path below the base, without query) with a fixed response. */
    public InMemoryFhirServer on(String method, String path, Answer answer) {
        answers.put(method.toUpperCase() + " " + path, answer);
        return this;
    }

    /**
     * Answers every request only after {@code delay}, like a slow server; for tests of timeouts. The request is
     * recorded when it arrives.
     */
    public InMemoryFhirServer delay(Duration newDelay) {
        this.delay = newDelay;
        return this;
    }

    /** The requests received, in order. */
    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** {@code METHOD path} of every request received. */
    public List<String> requestLines() {
        return requests().stream().map(Request::line).toList();
    }

    /** A stored resource, e.g. {@code Patient/1}. */
    public JsonNode resource(String key) {
        return resources.get(key);
    }

    private boolean handle(org.eclipse.jetty.server.Request exchange, Response response, Callback callback) {
        String fullPath = exchange.getHttpURI().getDecodedPath();
        if (!fullPath.equals("/fhir") && !fullPath.startsWith("/fhir/")) {
            return false;
        }
        try {
            String method = exchange.getMethod();
            String path = fullPath.substring("/fhir".length());
            String query = exchange.getHttpURI().getQuery();
            String body = Content.Source.asString(exchange, StandardCharsets.UTF_8);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (HttpField field : exchange.getHeaders()) {
                headers.computeIfAbsent(field.getName(), name -> new ArrayList<>()).add(field.getValue());
            }
            requests.add(new Request(method, path.isEmpty() ? "/" : path, query, headers, body));
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
            Answer answer = answers.get(method + " " + path);
            if (answer == null) {
                answer = answer(method, path, query, body, headers);
            }
            response.setStatus(answer.status());
            answer.headers().forEach((name, value) -> response.getHeaders().add(name, value));
            boolean noBody = answer.body() == null || answer.body().isEmpty() || method.equals("HEAD");
            if (noBody) {
                response.write(true, null, callback);
            } else {
                Content.Sink.write(response, true, answer.body(), callback);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.failed(e);
            return true;
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            callback.failed(e);
            return true;
        }
    }

    private Answer answer(String method, String path, String query, String body, Map<String, List<String>> headers) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (method.equals("GET") && parts.equals(List.of("metadata"))) {
            // what this server does for every resource type; announced for Patient and Organization
            String interactions = "[{\"code\":\"read\"},{\"code\":\"vread\"},{\"code\":\"update\"},"
                    + "{\"code\":\"delete\"},{\"code\":\"history-instance\"},{\"code\":\"history-type\"},"
                    + "{\"code\":\"create\"},{\"code\":\"search-type\"}]";
            return Answer.json(200, "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\",\"fhirVersion\":\""
                    + fhirVersion + "\",\"kind\":\"instance\",\"format\":[\"json\",\"xml\"],\"rest\":[{\"mode\":\"server\","
                    + "\"resource\":[{\"type\":\"Patient\",\"interaction\":" + interactions + "},"
                    + "{\"type\":\"Organization\",\"interaction\":" + interactions + "}],"
                    + "\"interaction\":[{\"code\":\"transaction\"},{\"code\":\"batch\"}]}]}");
        }
        if (method.equals("POST") && parts.isEmpty()) {
            return Answer.json(200, "{\"resourceType\":\"Bundle\",\"type\":\"batch-response\"}");
        }
        if (method.equals("GET") && (parts.size() == 1 || (parts.size() == 2 && parts.get(1).equals("_history")))) {
            ObjectNode bundle = JSON.createObjectNode().put("resourceType", "Bundle")
                    .put("type", parts.size() == 1 ? "searchset" : "history");
            ArrayNode entries = bundle.putArray("entry");
            matching(parts.getFirst(), parts.size() == 1 ? query : null)
                    .forEach(resource -> entries.addObject().set("resource", resource));
            bundle.put("total", entries.size());
            return Answer.json(200, bundle.toString());
        }
        if (method.equals("DELETE") && parts.size() == 1 && query != null) {
            matching(parts.getFirst(), query).forEach(r -> resources.remove(parts.getFirst() + "/" + r.path("id").asText()));
            return new Answer(204, "", Map.of());
        }
        if (method.equals("POST") && parts.size() == 1) {
            return store(parts.getFirst(), String.valueOf(nextId.getAndIncrement()), body, headers, 201);
        }
        if (method.equals("PUT") && parts.size() == 1 && query != null) {
            return conditionalUpdate(parts.getFirst(), query, body, headers);
        }
        if (parts.size() >= 2) {
            String key = parts.get(0) + "/" + parts.get(1);
            switch (method) {
                case "PUT" -> {
                    return store(parts.get(0), parts.get(1), body, headers, resources.containsKey(key) ? 200 : 201);
                }
                case "GET", "HEAD" -> {
                    ObjectNode resource = resources.get(key);
                    if (resource == null) {
                        return Answer.json(404, outcome("not-found", key + " is not known"));
                    }
                    if (parts.size() == 3 && parts.get(2).equals("_history")) {
                        ObjectNode bundle = JSON.createObjectNode().put("resourceType", "Bundle").put("type", "history");
                        ArrayNode entries = bundle.putArray("entry");
                        List<ObjectNode> versions = new ArrayList<>(history.getOrDefault(key, List.of()));
                        Collections.reverse(versions);
                        versions.forEach(v -> entries.addObject().set("resource", v));
                        return Answer.json(200, bundle.toString());
                    }
                    if (parts.size() == 4 && parts.get(2).equals("_history")) {
                        return history.getOrDefault(key, List.of()).stream()
                                .filter(v -> v.path("meta").path("versionId").asText().equals(parts.get(3)))
                                .findFirst().map(v -> Answer.json(200, v.toString()))
                                .orElse(Answer.json(404, outcome("not-found", key + " has no version " + parts.get(3))));
                    }
                    String version = resource.path("meta").path("versionId").asText("1");
                    return new Answer(200, resource.toString(), Map.of("Content-Type", FHIR_JSON,
                            "ETag", "W/\"" + version + "\"", "Location", location(parts.get(0), parts.get(1), version)));
                }
                case "DELETE" -> {
                    resources.remove(key);
                    return new Answer(204, "", Map.of());
                }
                case "PATCH" -> {
                    ObjectNode resource = resources.get(key);
                    return resource == null ? Answer.json(404, outcome("not-found", key + " is not known"))
                            : Answer.json(200, resource.toString());
                }
                default -> {
                }
            }
        }
        return Answer.json(400, outcome("not-supported", method + " " + path + " is not supported"));
    }

    private Answer store(String type, String id, String body, Map<String, List<String>> headers, int status) {
        String contentType = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
                .map(e -> e.getValue().getFirst()).findFirst().orElse("");
        if (contentType.contains("xml")) {
            Matcher matcher = XML_ID.matcher(body);
            String xml = matcher.find() ? matcher.replaceFirst("<id value=\"" + id + "\"/>") : body;
            return new Answer(status, xml, Map.of("Content-Type", "application/fhir+xml;charset=UTF-8",
                    "Location", location(type, id, "1")));
        }
        ObjectNode resource;
        try {
            resource = (ObjectNode) JSON.readTree(body);
        } catch (IOException | ClassCastException e) {
            return Answer.json(400, outcome("invalid", "The body is no JSON resource"));
        }
        String key = type + "/" + id;
        int version = resources.containsKey(key)
                ? resources.get(key).path("meta").path("versionId").asInt(1) + 1 : 1;
        resource.put("id", id);
        ObjectNode meta = resource.has("meta") ? (ObjectNode) resource.get("meta") : resource.putObject("meta");
        meta.put("versionId", String.valueOf(version));
        resources.put(key, resource);
        history.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(resource.deepCopy());
        return new Answer(status, resource.toString(), Map.of("Content-Type", FHIR_JSON,
                "Location", location(type, id, String.valueOf(version)), "ETag", "W/\"" + version + "\""));
    }

    /**
     * {@code PUT [type]?[search]} by the rules of the FHIR specification: no match creates the resource (under the id
     * of the body, if it has one), one match updates it unless the body names another id (400), several matches are
     * 412.
     */
    private Answer conditionalUpdate(String type, String query, String body, Map<String, List<String>> headers) {
        List<ObjectNode> matches = matching(type, query);
        if (matches.size() > 1) {
            return Answer.json(412, outcome("multiple-matches", matches.size() + " resources match " + query));
        }
        String bodyId;
        try {
            bodyId = JSON.readTree(body).path("id").asText(null);
        } catch (IOException e) {
            return Answer.json(400, outcome("invalid", "The body is no JSON resource"));
        }
        if (matches.isEmpty()) {
            return store(type, bodyId != null ? bodyId : String.valueOf(nextId.getAndIncrement()), body, headers, 201);
        }
        String id = matches.getFirst().path("id").asText();
        if (bodyId != null && !bodyId.equals(id)) {
            return Answer.json(400, outcome("invalid", "The body has the id " + bodyId + ", but " + query
                    + " matches " + type + "/" + id));
        }
        return store(type, id, body, headers, 200);
    }

    /**
     * Stored resources of a type whose JSON contains the value of every search parameter not starting with '_'. A
     * token {@code system|value} needs both parts.
     */
    private List<ObjectNode> matching(String type, String query) {
        List<String> values = new ArrayList<>();
        if (query != null) {
            for (String parameter : query.split("&")) {
                int eq = parameter.indexOf('=');
                if (eq > 0 && !parameter.startsWith("_")) {
                    String value = java.net.URLDecoder.decode(parameter.substring(eq + 1), StandardCharsets.UTF_8);
                    for (String part : value.split("\\|")) {
                        if (!part.isEmpty()) {
                            values.add(part);
                        }
                    }
                }
            }
        }
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(type + "/"))
                .map(Map.Entry::getValue)
                .filter(r -> values.stream().allMatch(v -> r.toString().contains(v)))
                .toList();
    }

    private String location(String type, String id, String version) {
        return baseUrl() + "/" + type + "/" + id + "/_history/" + version;
    }

    private static String outcome(String code, String text) {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"" + code
                + "\",\"diagnostics\":\"" + text + "\"}]}";
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("Could not stop the in-memory FHIR server", e);
        }
    }
}
