package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathEvaluatorTest {

    @Test
    void jsonPathOnJson() {
        Body body = Body.json("{\"resourceType\":\"Patient\",\"id\":\"p1\",\"name\":[{\"family\":\"Bucher\"}]}");
        assertThat(PathEvaluator.evaluate(body, "$.name[0].family")).containsExactly("Bucher");
        assertThat(PathEvaluator.evaluate(body, "$.missing")).isEmpty();
    }

    @Test
    void xPathOnXmlWithoutNamespacePrefixesReadsValueAttributes() {
        Body body = Body.xml("<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"p1\"/><name><family value=\"Wolfesberger\"/></name></Patient>");
        assertThat(PathEvaluator.evaluate(body, "Patient/id")).containsExactly("p1");
        assertThat(PathEvaluator.evaluate(body, "/Patient/name/family/@value")).containsExactly("Wolfesberger");
        assertThat(PathEvaluator.evaluate(body, "count(//family)")).containsExactly("1");
    }

    @Test
    void invalidPathIsReportedForTheFormatOfTheBody() {
        assertThatThrownBy(() -> PathEvaluator.evaluate(Body.xml("<Patient/>"), "$..["))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("XPath");
    }
}
