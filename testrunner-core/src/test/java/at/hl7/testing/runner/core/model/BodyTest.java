package at.hl7.testing.runner.core.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BodyTest {

    /** The start of a PDF: text-like header, then bytes that are no UTF-8. */
    static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF,
            (byte) 0xD3, 0, 1, 2, (byte) 0xFF};

    @Test
    void keepsTheBytesOfContentThatIsNoText() {
        Body pdf = Body.of(PDF, "application/pdf");

        assertThat(pdf.bytes()).isEqualTo(PDF);
        assertThat(pdf.format()).isEqualTo(ContentFormat.OTHER);
        assertThat(pdf.isText()).isFalse();
        assertThat(pdf.isEmpty()).isFalse();
        assertThat(pdf.length()).isEqualTo(PDF.length);
        // a copy goes out, the body stays as it is
        pdf.bytes()[0] = 'X';
        assertThat(pdf.bytes()).isEqualTo(PDF);
    }

    @Test
    void decodesTextWithTheCharsetOfTheContentType() {
        byte[] latin1 = "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Größe\"}]}".getBytes(StandardCharsets.ISO_8859_1);

        Body body = Body.of(latin1, "application/fhir+json; charset=ISO-8859-1");

        assertThat(body.text()).contains("Größe");
        assertThat(body.format()).isEqualTo(ContentFormat.JSON);
        assertThat(body.charset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(Body.of(latin1, "application/fhir+json").text()).as("UTF-8 by default").doesNotContain("Größe");
        assertThat(Body.of(latin1, "application/json;charset=\"no-such-charset\"").charset())
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void formatComesFromTheContentTypeElseFromTheContent() {
        byte[] json = "{\"resourceType\":\"Binary\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(Body.of(json, null).format()).isEqualTo(ContentFormat.JSON);
        assertThat(Body.of(json, "text/plain").format()).as("text is sniffed").isEqualTo(ContentFormat.JSON);
        assertThat(Body.of(json, "application/octet-stream").format()).as("a media type of its own")
                .isEqualTo(ContentFormat.OTHER);
        assertThat(Body.of("hello".getBytes(StandardCharsets.UTF_8), "text/plain").isText()).isTrue();
        assertThat(Body.of(new byte[0], "application/fhir+json").isEmpty()).isTrue();
        // a text body follows the same rule
        assertThat(Body.of("{\"resourceType\":\"Binary\"}", "application/octet-stream").format())
                .isEqualTo(ContentFormat.OTHER);
        assertThat(Body.of("{\"resourceType\":\"Binary\"}", null).format()).isEqualTo(ContentFormat.JSON);
    }

    @Test
    void equalBodiesHaveEqualBytes() {
        assertThat(Body.json("{}")).isEqualTo(Body.of("{}".getBytes(StandardCharsets.UTF_8), "application/fhir+json"))
                .isNotEqualTo(Body.xml("{}"));
        assertThat(Body.of(PDF, "application/pdf")).hasToString("Body[OTHER, 17 bytes]");
    }
}
