package at.hl7.testing.runner.core.model;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * An operation of a setup, test or teardown ({@code action.operation}): a request sent to the FHIR server.
 *
 * <p>Absent values are {@code null}.
 *
 * @param type             the kind of operation, {@code null} if the operation names no type
 * @param typeCode         the code {@code type} was read from, for messages
 * @param resource         resource type, e.g. {@code Patient}
 * @param label            label for the log
 * @param description      description for the log
 * @param accept           value of the {@code Accept} header ({@code json} and {@code xml} are short for the FHIR types)
 * @param contentType      value of the {@code Content-Type} header ({@code json} and {@code xml} as for accept)
 * @param encodeRequestUrl whether the URL is percent-encoded
 * @param method           explicit HTTP method, otherwise derived from {@code type}
 * @param params           path and query after {@code [base]/[type]}, e.g. {@code /123} or {@code ?name=x}
 * @param requestHeaders   further request headers
 * @param requestId        id under which the request is kept
 * @param responseId       id under which the response is kept for later operations and assertions
 * @param sourceId         fixture or response sent as body
 * @param targetId         fixture or response the id, type and version of the request URL are taken from
 * @param url              complete request URL, overrides everything else
 */
public record Operation(OperationType type, String typeCode, String resource, String label, String description,
                        String accept, String contentType, boolean encodeRequestUrl, HttpMethod method,
                        String params, List<RequestHeader> requestHeaders, String requestId, String responseId,
                        String sourceId, String targetId, String url) implements Action {

    public Operation {
        requestHeaders = requestHeaders == null ? List.of() : List.copyOf(requestHeaders);
    }

    /**
     * Returns a copy with every value passed through {@code values}, which replaces {@code ${variable}} placeholders.
     * Like the Python proof of concept, every text value may use variables, not only {@code params} and {@code url}.
     */
    public Operation withValues(UnaryOperator<String> values) {
        return new Operation(type, typeCode, apply(values, resource), label, description, apply(values, accept),
                apply(values, contentType), encodeRequestUrl, method, apply(values, params),
                requestHeaders.stream().map(h -> h.map(values)).toList(), apply(values, requestId),
                apply(values, responseId), apply(values, sourceId), apply(values, targetId), apply(values, url));
    }

    static String apply(UnaryOperator<String> values, String value) {
        return value == null ? null : values.apply(value);
    }
}
