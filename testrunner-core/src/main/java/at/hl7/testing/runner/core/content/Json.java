package at.hl7.testing.runner.core.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Reading and writing FHIR JSON without changing values. A FHIR decimal is exact as written: {@code 1.50} has two
 * decimal places and {@code 12345678901234567890.123} more digits than a {@code double} holds. Read with Jackson's
 * defaults, they would come back as {@code 1.5} and {@code 1.2345678901234567E19}.
 */
final class Json {

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private Json() {
    }

    static JsonNode read(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The value of a JSON scalar as text, numbers exactly as written ({@code 100.0} stays {@code 100.0}). */
    static String text(JsonNode node) {
        return node.isNumber() ? node.decimalValue().toPlainString() : node.asText();
    }
}
