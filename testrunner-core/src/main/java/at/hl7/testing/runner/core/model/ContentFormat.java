package at.hl7.testing.runner.core.model;

import java.util.Locale;

/** Encoding of a FHIR resource or message body. */
public enum ContentFormat {

    JSON("application/fhir+json"),
    XML("application/fhir+xml"),
    OTHER("application/octet-stream");

    private final String fhirMimeType;

    ContentFormat(String fhirMimeType) {
        this.fhirMimeType = fhirMimeType;
    }

    /** The FHIR mime type of this format, e.g. {@code application/fhir+json}. */
    public String fhirMimeType() {
        return fhirMimeType;
    }

    /** Determines the format from a {@code Content-Type} header value, or {@code null} if it names neither JSON nor XML. */
    public static ContentFormat fromMimeType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String value = contentType.toLowerCase(Locale.ROOT);
        if (value.contains("json")) {
            return JSON;
        }
        if (value.contains("xml")) {
            return XML;
        }
        return null;
    }

    /** Determines the format from a file name, or {@code null} if the extension is neither {@code .json} nor {@code .xml}. */
    public static ContentFormat fromFileName(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return JSON;
        }
        if (name.endsWith(".xml")) {
            return XML;
        }
        return null;
    }

    /** Guesses the format from the content itself: JSON starts with '{' or '[', XML with '<'. */
    public static ContentFormat sniff(String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c) || c == '﻿') {
                continue;
            }
            return switch (c) {
                case '{', '[' -> JSON;
                case '<' -> XML;
                default -> OTHER;
            };
        }
        return OTHER;
    }
}
