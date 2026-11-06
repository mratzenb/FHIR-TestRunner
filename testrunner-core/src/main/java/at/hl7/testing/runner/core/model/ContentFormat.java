package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Locale;
import java.util.Optional;

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
    @Nonnull
    public String fhirMimeType() {
        return fhirMimeType;
    }

    /** The format a {@code Content-Type} header value names; empty if it names neither JSON nor XML, or is absent. */
    @Nonnull
    public static Optional<ContentFormat> fromMimeType(@Nullable String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String value = contentType.toLowerCase(Locale.ROOT);
        if (value.contains("json")) {
            return Optional.of(JSON);
        }
        if (value.contains("xml")) {
            return Optional.of(XML);
        }
        return Optional.empty();
    }

    /** The format a file name's extension names; empty if it is neither {@code .json} nor {@code .xml}. */
    @Nonnull
    public static Optional<ContentFormat> fromFileName(@Nonnull String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return Optional.of(JSON);
        }
        if (name.endsWith(".xml")) {
            return Optional.of(XML);
        }
        return Optional.empty();
    }

    /** Guesses the format from the content itself: JSON starts with '{' or '[', XML with '<'. */
    @Nonnull
    public static ContentFormat sniff(@Nonnull String content) {
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
