package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public Body(@Nonnull String text, @Nonnull ContentFormat format) {
        this(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, format);
        this.text = text;
    }

    @Nonnull
    public static Body json(@Nonnull String text) {
        return new Body(text, ContentFormat.JSON);
    }

    @Nonnull
    public static Body xml(@Nonnull String text) {
        return new Body(text, ContentFormat.XML);
    }

    /**
     * A text body; its format as for {@link #of(byte[], String)}.
     *
     * @param contentType the {@code Content-Type} header value, may be {@code null}
     */
    @Nonnull
    public static Body of(@Nonnull String text, @Nullable String contentType) {
        return new Body(text, format(contentType, text));
    }

    /**
     * The content of a JSON or XML file, read as UTF-8; the format from the extension ({@code .json}, {@code .xml}),
     * else from the content.
     *
     * @throws IOException if the file cannot be read
     */
    @Nonnull
    public static Body ofFile(@Nonnull Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return new Body(text, ContentFormat.fromFileName(file.getFileName().toString())
                .orElseGet(() -> ContentFormat.sniff(text)));
    }

    /**
     * A body as received or read from a file. The charset comes from the {@code Content-Type}, else UTF-8; the format
     * from the {@code Content-Type} if it names JSON or XML, else from the content – unless the media type names
     * something else (a PDF, an image), which is {@link ContentFormat#OTHER} whatever the bytes look like.
     *
     * @param contentType the {@code Content-Type} header value, may be {@code null}
     */
    @Nonnull
    public static Body of(@Nullable byte[] bytes, @Nullable String contentType) {
        byte[] copy = bytes == null ? EMPTY : bytes.clone();
        Charset charset = charset(contentType);
        // the first characters decide; decoding a large binary in full is not needed for that
        String start = new String(copy, 0, Math.min(copy.length, 64), charset);
        return new Body(copy, charset, format(contentType, start));
    }

    /** The bytes, as exchanged. */
    @Nonnull
    public byte[] bytes() {
        return bytes.clone();
    }

    /** The number of bytes. */
    public int length() {
        return bytes.length;
    }

    /** The charset the text is decoded with: the one the {@code Content-Type} named, else UTF-8. */
    @Nonnull
    public Charset charset() {
        return charset;
    }

    /** The encoding of the content. */
    @Nonnull
    public ContentFormat format() {
        return format;
    }

    /** The content as text, decoded with {@link #charset()}; bytes that are no text become U+FFFD. */
    @Nonnull
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

    /** JSON or XML if the media type names it; else other media as they are; else what the content starts with. */
    private static ContentFormat format(String contentType, String start) {
        return ContentFormat.fromMimeType(contentType).orElseGet(() ->
                namesOtherMedia(contentType) ? ContentFormat.OTHER : ContentFormat.sniff(start));
    }

    /** Equal if the format, charset and bytes are equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Body other && format == other.format && charset.equals(other.charset)
                && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, charset, Arrays.hashCode(bytes));
    }

    @Override
    @Nonnull
    public String toString() {
        return "Body[" + format + ", " + bytes.length + " bytes" + (isText() ? ": " + abbreviated() : "") + "]";
    }

    private String abbreviated() {
        String value = text();
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }
}
