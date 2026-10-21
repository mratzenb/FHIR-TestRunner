package at.hl7.testing.runner.core.content;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlEncodingTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "http://s/fhir/Patient?family=Müller Meier|http://s/fhir/Patient?family=M%C3%BCller%20Meier",
            "http://s/fhir/Patient?family=M%C3%BCller|http://s/fhir/Patient?family=M%C3%BCller",
            "http://s/fhir/Patient?a=1&b=x,y|http://s/fhir/Patient?a=1&b=x,y"})
    void encodesOnlyWhatIsNotAllowedAndNeverTwice(String url, String encoded) {
        assertThat(UrlEncoding.encode(url)).isEqualTo(encoded);
        assertThat(UrlEncoding.encode(encoded)).isEqualTo(encoded);
    }
}
