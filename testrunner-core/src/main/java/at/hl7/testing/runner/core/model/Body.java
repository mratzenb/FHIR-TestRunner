package at.hl7.testing.runner.core.model;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The content of a fixture, a request or a response: its bytes as exchanged, their charset and their encoding.
 *
 * <p>A body holds bytes, so a {@code Binary} in its native content type – a PDF, an image – is sent, kept and logged
 * as it is. {@link #text()} decodes them when the content is text; FHIR resources are always text (UTF-8 by default).
 * Immutable: the bytes are copied in and out.
 */
public final class Body {

    private static final Pattern CHARSET = Pattern.compile("(?i);\\s*charset\\s*=\\s*\"?([^\";\\s]+)\"?");
    private static final byte[] EMPTY = new byte[0];

    private final byte[] bytes;
    private final Charset charset;
    private final ContentFormat format;
    /** Decoded on first use; a race only decodes twice, both get equal values. */
    private String text;
    private Boolean textual;

    private Body(byte[] bytes, Charset charset, ContentFormat format) {
        this.bytes = bytes;
        this.charset = Objects.requireNonNull(charset, "charset");
        this.format = Objects.requireNonNull(format, "format");
    }

    /** A text body. */
    public Body(String text, ContentFormat format) {
        this(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, format);
        this.text = text;
    }

    public static Body json(String text) {
        return new Body(text, ContentFormat.JSON);
    }

    public static Body xml(String text) {
        return new Body(text, ContentFormat.XML);
    }

    /**
     * Creates a text body whose format is taken from the {@code Content-Type} header, falling back to the content itself.
     *
     * @param contentType the {@code Content-Type} header value, may be {@code null}
     */
    public static Body of(String text, String contentType) {
        ContentFormat format = ContentFormat.fromMimeType(contentType);
        return new Body(text, format != null ? format : ContentFormat.sniff(text));
    }

    /**
     * A body as received or read from a file. The charset comes from the {@code Content-Type}, else UTF-8; the format
     * from the {@code Content-Type} if it names JSON or XML, else from the content – unless the media type names
     * something else (a PDF, an image), which is {@link ContentFormat#OTHER} whatever the bytes look like.
     *
     * @param contentType the {@code Content-Type} header value, may be {@code null}
     */
    public static Body of(byte[] bytes, String contentType) {
        byte[] copy = bytes == null ? EMPTY : bytes.clone();
        Charset charset = charset(contentType);
        ContentFormat format = ContentFormat.fromMimeType(contentType);
        if (format == null) {
            format = namesOtherMedia(contentType) ? ContentFormat.OTHER : sniff(copy, charset);
        }
        return new Body(copy, charset, format);
    }

    /** The bytes, as exchanged. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** The number of bytes. */
    public int length() {
        return bytes.length;
    }

    /** The charset the text is decoded with: the one the {@code Content-Type} named, else UTF-8. */
    public Charset charset() {
        return charset;
    }

    /** The encoding of the content. */
    public ContentFormat format() {
        return format;
    }

    /** The content as text, decoded with {@link #charset()}; bytes that are no text become U+FFFD. */
    public String text() {
        String decoded = text;
        if (decoded == null) {
            decoded = new String(bytes, charset);
            text = decoded;
        }
        return decoded;
    }

    /** Whether the bytes are text in {@link #charset()}, i.e. can be shown and logged as text without loss. */
    public boolean isText() {
        Boolean known = textual;
        if (known == null) {
            known = format != ContentFormat.OTHER || decodesAsText();
            textual = known;
        }
        return known;
    }

    private boolean decodesAsText() {
        try {
            String decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return decoded.chars().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'
                    && c != '\f');
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Whether there is no content, or only whitespace. */
    public boolean isEmpty() {
        return bytes.length == 0 || isText() && text().isBlank();
    }

    /** A body with the same bytes and charset in another format. */
    public Body withFormat(ContentFormat newFormat) {
        return new Body(bytes, charset, newFormat);
    }

    /** The charset named by a {@code Content-Type} value; UTF-8 if none is named or it is unknown. */
    static Charset charset(String contentType) {
        if (contentType != null) {
            Matcher matcher = CHARSET.matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1));
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** A media type that is neither JSON nor XML nor text: the body is what it says, not a resource. */
    private static boolean namesOtherMedia(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT).trim();
        return !type.startsWith("text/");
    }

    private static ContentFormat sniff(byte[] bytes, Charset charset) {
        // the first characters decide; decoding a large binary in full is not needed for that
        int prefix = Math.min(bytes.length, 64);
        return ContentFormat.sniff(new String(bytes, 0, prefix, charset));
    }

    /** Equal if the format, charset and bytes are equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Body other && format == other.format && charset.equals(other.charset)
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, charset, Arrays.hashCode(bytes));
    }

    @Override
    public String toString() {
        return "Body[" + format + ", " + bytes.length + " bytes" + (isText() ? ": " + abbreviated() : "") + "]";
    }

    private String abbreviated() {
        String value = text();
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }
}
