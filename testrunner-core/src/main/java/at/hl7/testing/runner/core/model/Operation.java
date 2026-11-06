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
 * @param destination      index of the {@code TestScript.destination} receiving the request, {@code null} if not set
 */
public record Operation(OperationType type, String typeCode, String resource, String label, String description,
                        String accept, String contentType, boolean encodeRequestUrl, HttpMethod method,
                        String params, List<RequestHeader> requestHeaders, String requestId, String responseId,
                        String sourceId, String targetId, String url, Integer destination) implements Action {

    public Operation {
        requestHeaders = requestHeaders == null ? List.of() : List.copyOf(requestHeaders);
    }

    /** A builder of an operation; nothing is set, except {@code encodeRequestUrl = true} (the FHIR default). */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder with every value of this operation, to make a copy that differs in a few. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns a copy with every value passed through {@code values}, which replaces {@code ${variable}} placeholders.
     * Like the Python proof of concept, every text value may use variables, not only {@code params} and {@code url}.
     */
    public Operation withValues(UnaryOperator<String> values) {
        return toBuilder().resource(apply(values, resource)).accept(apply(values, accept))
                .contentType(apply(values, contentType)).params(apply(values, params))
                .requestHeaders(requestHeaders.stream().map(h -> h.map(values)).toList())
                .requestId(apply(values, requestId)).responseId(apply(values, responseId))
                .sourceId(apply(values, sourceId)).targetId(apply(values, targetId)).url(apply(values, url))
                .build();
    }

    static String apply(UnaryOperator<String> values, String value) {
        return value == null ? null : values.apply(value);
    }

    /**
     * Builds an {@link Operation} by name rather than by the position of its 17 components, so that a new element
     * does not change every place that creates one. Not thread-safe; the operation built is immutable.
     */
    public static final class Builder {
        private OperationType type;
        private String typeCode;
        private String resource;
        private String label;
        private String description;
        private String accept;
        private String contentType;
        private boolean encodeRequestUrl = true;
        private HttpMethod method;
        private String params;
        private List<RequestHeader> requestHeaders = List.of();
        private String requestId;
        private String responseId;
        private String sourceId;
        private String targetId;
        private String url;
        private Integer destination;

        private Builder() {
        }

        private Builder(Operation operation) {
            type = operation.type;
            typeCode = operation.typeCode;
            resource = operation.resource;
            label = operation.label;
            description = operation.description;
            accept = operation.accept;
            contentType = operation.contentType;
            encodeRequestUrl = operation.encodeRequestUrl;
            method = operation.method;
            params = operation.params;
            requestHeaders = operation.requestHeaders;
            requestId = operation.requestId;
            responseId = operation.responseId;
            sourceId = operation.sourceId;
            targetId = operation.targetId;
            url = operation.url;
            destination = operation.destination;
        }

        /** The type from its code, e.g. {@code read}: {@link OperationType#fromCode}; the code is kept for messages. */
        public Builder type(String code) {
            this.type = OperationType.fromCode(code);
            this.typeCode = code;
            return this;
        }

        public Builder resource(String value) {
            this.resource = value;
            return this;
        }

        public Builder label(String value) {
            this.label = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder accept(String value) {
            this.accept = value;
            return this;
        }

        public Builder contentType(String value) {
            this.contentType = value;
            return this;
        }

        public Builder encodeRequestUrl(boolean value) {
            this.encodeRequestUrl = value;
            return this;
        }

        public Builder method(HttpMethod value) {
            this.method = value;
            return this;
        }

        public Builder params(String value) {
            this.params = value;
            return this;
        }

        public Builder requestHeaders(List<RequestHeader> value) {
            this.requestHeaders = value;
            return this;
        }

        public Builder requestId(String value) {
            this.requestId = value;
            return this;
        }

        public Builder responseId(String value) {
            this.responseId = value;
            return this;
        }

        public Builder sourceId(String value) {
            this.sourceId = value;
            return this;
        }

        public Builder targetId(String value) {
            this.targetId = value;
            return this;
        }

        public Builder url(String value) {
            this.url = value;
            return this;
        }

        public Builder destination(Integer value) {
            this.destination = value;
            return this;
        }

        public Operation build() {
            return new Operation(type, typeCode, resource, label, description, accept, contentType, encodeRequestUrl,
                    method, params, requestHeaders, requestId, responseId, sourceId, targetId, url, destination);
        }
    }
}
