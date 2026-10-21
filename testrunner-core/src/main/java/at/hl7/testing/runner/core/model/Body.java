package at.hl7.testing.runner.core.model;

import java.util.Objects;

/**
 * The content of a fixture, a request or a response, together with its encoding.
 *
 * @param text   the content as text
 * @param format the encoding of {@code text}
 */
public record Body(String text, ContentFormat format) {

    public Body {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(format, "format");
    }

    public static Body json(String text) {
        return new Body(text, ContentFormat.JSON);
    }

    public static Body xml(String text) {
        return new Body(text, ContentFormat.XML);
    }

    /**
     * Creates a body whose format is taken from the {@code Content-Type} header, falling back to the content itself.
     *
     * @param contentType the {@code Content-Type} header value, may be {@code null}
     */
    public static Body of(String text, String contentType) {
        ContentFormat format = ContentFormat.fromMimeType(contentType);
        return new Body(text, format != null ? format : ContentFormat.sniff(text));
    }

    public boolean isEmpty() {
        return text.isBlank();
    }
}
