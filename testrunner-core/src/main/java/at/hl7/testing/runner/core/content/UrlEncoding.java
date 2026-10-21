package at.hl7.testing.runner.core.content;

import java.nio.charset.StandardCharsets;

/** Percent-encoding of request URLs ({@code operation.encodeRequestUrl}). */
public final class UrlEncoding {

    private static final String ALLOWED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            + "-._~" // unreserved
            + ":/?#[]@!$&'()*+,;="; // reserved, kept so that the structure of the URL stays intact

    private UrlEncoding() {
    }

    /**
     * Percent-encodes every character that may not appear in a URL, e.g. spaces and non-ASCII letters. Delimiters
     * and existing {@code %XX} escapes are kept, so encoding twice changes nothing.
     */
    public static String encode(String url) {
        StringBuilder result = new StringBuilder(url.length());
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '%' && i + 2 < url.length() && isHex(url.charAt(i + 1)) && isHex(url.charAt(i + 2))) {
                result.append(c);
            } else if (c < 128 && ALLOWED.indexOf(c) >= 0) {
                result.append(c);
            } else {
                int codePoint = url.codePointAt(i);
                if (Character.isSupplementaryCodePoint(codePoint)) {
                    i++;
                }
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    result.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        }
        return result.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }
}
