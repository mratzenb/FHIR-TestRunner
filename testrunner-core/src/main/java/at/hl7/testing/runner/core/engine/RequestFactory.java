package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.MediaTypes;
import at.hl7.testing.runner.core.content.ResourceContent;
import at.hl7.testing.runner.core.content.UrlEncoding;
import at.hl7.testing.runner.core.http.FhirRequest;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.ContentFormat;
import at.hl7.testing.runner.core.model.HttpMethod;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.OperationType;
import at.hl7.testing.runner.core.model.RequestHeader;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the HTTP request of an operation whose variables have been resolved.
 *
 * <p>URL, in order of precedence:
 * <ol>
 *   <li>{@code url} as it is</li>
 *   <li>{@code [base]/[resource][params]}</li>
 *   <li>derived from the type: {@code [base]} for transaction and batch, {@code [base]/metadata} for capabilities,
 *       {@code [base]/[type]/[id](/_history(/[vid]))} from {@code targetId}, {@code [base]/[type]} otherwise</li>
 * </ol>
 */
final class RequestFactory {

    private final String base;
    private final ExecutionState state;

    RequestFactory(String base, ExecutionState state) {
        this.base = base;
        this.state = state;
    }

    FhirRequest create(Operation operation) {
        check(operation);
        HttpMethod method = method(operation);
        Body body = body(operation, method);
        String url = url(operation, body);
        if (operation.encodeRequestUrl()) {
            url = UrlEncoding.encode(url);
        }
        if (operation.type() == OperationType.UPDATE && body != null && body.format() != ContentFormat.OTHER) {
            // an update carries the id of the URL ([base]/[type]/[id]), otherwise the server rejects it; a conditional
            // update ([base]/[type]?[search]) names no id, its body is sent as it is
            String id = instanceId(url, ResourceContent.resourceType(body));
            if (id != null) {
                body = ResourceContent.withId(body, id);
            }
        }
        return new FhirRequest(method, uri(url), headers(operation, body), body == null ? null : body.text());
    }

    private static void check(Operation operation) {
        OperationType type = operation.type();
        if (type == null) {
            return;
        }
        if (operation.targetId() != null && (type == OperationType.SEARCH || type == OperationType.CREATE)) {
            throw new TestScriptException(type.name().toLowerCase() + " must not have a targetId");
        }
        if (operation.params() != null && (type == OperationType.CREATE || type == OperationType.TRANSACTION
                || type == OperationType.BATCH)) {
            throw new TestScriptException(type.name().toLowerCase() + " must not have params");
        }
    }

    private static HttpMethod method(Operation operation) {
        if (operation.method() != null) {
            return operation.method();
        }
        if (operation.type() == null || operation.type().defaultMethod() == null) {
            throw new TestScriptException("The HTTP method cannot be determined: the operation has no method and "
                    + (operation.type() == null ? "no type" : "the unknown type '" + operation.typeCode() + "'"));
        }
        return operation.type().defaultMethod();
    }

    private Body body(Operation operation, HttpMethod method) {
        if (operation.sourceId() == null) {
            if (method.sendsBody() && operation.type() != OperationType.SEARCH
                    && operation.type() != OperationType.OPERATION && operation.type() != OperationType.CAPABILITIES) {
                throw new TestScriptException("The " + method + " of operation '" + typeName(operation)
                        + "' has no sourceId, so there is nothing to send");
            }
            return null;
        }
        return state.require(operation.sourceId(), "sourceId").body();
    }

    private String url(Operation operation, Body body) {
        if (operation.url() != null) {
            return operation.url();
        }
        if (operation.params() != null) {
            String params = operation.params();
            if (operation.resource() == null && needsResource(operation.type())) {
                throw new TestScriptException("Operation '" + typeName(operation) + "' with params needs a resource type");
            }
            if (!params.startsWith("/") && !params.startsWith("?")) {
                params = "/" + params;
            }
            return base + (operation.resource() != null ? "/" + operation.resource() : "") + params;
        }
        OperationType type = operation.type();
        if (type == null) {
            throw new TestScriptException("The URL cannot be determined: the operation has no url, params or type");
        }
        if (type == OperationType.TRANSACTION || type == OperationType.BATCH) {
            return base;
        }
        if (type == OperationType.CAPABILITIES) {
            return base + "/metadata";
        }
        if (operation.targetId() != null) {
            return targetUrl(operation);
        }
        String resource = operation.resource();
        if (resource == null && body != null) {
            resource = ResourceContent.resourceType(body);
        }
        if (resource == null) {
            throw new TestScriptException("The URL cannot be determined for operation '" + typeName(operation)
                    + "': it has no resource, params, url or targetId");
        }
        if (type == OperationType.HISTORY) {
            return base + "/" + resource + "/_history";
        }
        return base + "/" + resource;
    }

    private String targetUrl(Operation operation) {
        Source target = state.require(operation.targetId(), "targetId");
        String type;
        String id;
        String version = null;
        switch (target) {
            case Source.Fixture fixture -> {
                if (fixture.serverId() == null) {
                    throw new TestScriptException("targetId '" + fixture.id()
                            + "' is a fixture that has not been created on the server");
                }
                type = fixture.resourceType();
                id = fixture.serverId();
            }
            case Source.Response response -> {
                Map<String, String> location = response.headers().first("Location")
                        .or(() -> response.headers().first("Content-Location"))
                        .map(ResourceContent::parseLocation).orElse(Map.of());
                Body body = response.body();
                boolean hasResource = !body.isEmpty() && body.format() != ContentFormat.OTHER;
                type = location.getOrDefault("type", hasResource ? ResourceContent.resourceType(body) : null);
                id = location.getOrDefault("id", hasResource ? ResourceContent.id(body) : null);
                version = location.getOrDefault("version", hasResource ? ResourceContent.versionId(body) : null);
                if (version == null) {
                    version = response.headers().first("ETag").map(RequestFactory::versionOfETag).orElse(null);
                }
            }
            case Source.Request request -> throw new TestScriptException("targetId '" + operation.targetId()
                    + "' is a request (requestId); use the responseId of that operation to address what it created");
        }
        if (operation.resource() != null) {
            type = operation.resource();
        }
        if (type == null || id == null) {
            throw new TestScriptException("targetId '" + operation.targetId()
                    + "' has no resource type and id (no Location header and no resource in the body)");
        }
        String url = base + "/" + type + "/" + id;
        return switch (operation.type()) {
            case VREAD -> {
                if (version == null) {
                    throw new TestScriptException("vread needs a version, but targetId '" + operation.targetId()
                            + "' has none (no _history in Location, no meta.versionId, no ETag)");
                }
                yield url + "/_history/" + version;
            }
            case HISTORY -> url + "/_history";
            case null, default -> url;
        };
    }

    private HttpHeaders headers(Operation operation, Body body) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String accept = MediaTypes.expand(operation.accept());
        if (accept != null) {
            headers.put("Accept", List.of(accept));
        }
        String contentType = MediaTypes.expand(operation.contentType());
        if (contentType == null && body != null && body.format() != ContentFormat.OTHER) {
            contentType = body.format().fhirMimeType();
        }
        if (contentType != null && body != null) {
            headers.put("Content-Type", List.of(contentType));
        }
        for (RequestHeader header : operation.requestHeaders()) {
            headers.put(header.field(), List.of(header.value()));
        }
        return HttpHeaders.of(headers);
    }

    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new TestScriptException("Invalid request URL '" + url + "' (set encodeRequestUrl to encode it): "
                    + e.getMessage(), e);
        }
    }

    private static boolean needsResource(OperationType type) {
        return type == OperationType.READ || type == OperationType.VREAD || type == OperationType.UPDATE
                || type == OperationType.DELETE || type == OperationType.PATCH;
    }

    /**
     * The id of an instance URL {@code [base]/[type]/[id]} of a resource of {@code type}; {@code null} for any other
     * URL, e.g. {@code [base]/[type]?[search]}, or if {@code type} is {@code null}.
     */
    private static String instanceId(String url, String type) {
        Map<String, String> location = ResourceContent.parseLocation(url);
        return type != null && type.equals(location.get("type")) ? location.get("id") : null;
    }

    /** {@code W/"3"} -> {@code 3}. */
    private static String versionOfETag(String etag) {
        String value = etag.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        return value.isEmpty() ? null : value;
    }

    private static String typeName(Operation operation) {
        return operation.typeCode() != null ? operation.typeCode() : "operation";
    }
}
