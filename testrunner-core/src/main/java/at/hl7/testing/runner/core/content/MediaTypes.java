package at.hl7.testing.runner.core.content;

import java.util.Locale;

/** Mime type handling for the short forms TestScripts use. */
public final class MediaTypes {

    private MediaTypes() {
    }

    /** Expands the short forms {@code json}, {@code xml} and {@code ttl} to the FHIR mime types; others unchanged. */
    public static String expand(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        return switch (mimeType.trim().toLowerCase(Locale.ROOT)) {
            case "json" -> "application/fhir+json";
            case "xml" -> "application/fhir+xml";
            case "ttl" -> "application/fhir+turtle";
            case "none" -> null;
            default -> mimeType.trim();
        };
    }

    /** The media type of a header value without parameters, lower case: {@code application/fhir+json;charset=UTF-8} -> {@code application/fhir+json}. */
    public static String withoutParameters(String headerValue) {
        if (headerValue == null) {
            return "";
        }
        int semicolon = headerValue.indexOf(';');
        String type = semicolon >= 0 ? headerValue.substring(0, semicolon) : headerValue;
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
