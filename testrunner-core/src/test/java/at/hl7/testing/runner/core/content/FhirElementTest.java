package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FhirElementTest {

    private static FhirElement json(String json) {
        return FhirElement.of(Body.json(json));
    }

    private static java.util.List<String> differences(String minimum, String actual) {
        return json(minimum).notContainedIn(json(actual), "Patient");
    }

    @Test
    void orderOfElementsAndOfRepeatedElementsDoesNotMatter() {
        assertThat(differences("""
                {"name":[{"given":["hello","goodbye"]}],"gender":"male"}""", """
                {"gender":"male","name":[{"given":["goodbye","hello"]}]}""")).isEmpty();
    }

    @Test
    void moreElementsAndMoreRepetitionsAreAllowed() {
        String minimum = """
                {"name":[{"given":["hello","goodbye"]}]}""";

        assertThat(differences(minimum, """
                {"id":"1","name":[{"given":["hello","greeting","goodbye"]}],"active":true}""")).isEmpty();
        assertThat(differences(minimum, """
                {"name":[{"given":["greeting","hello","goodbye"]}]}""")).isEmpty();
        assertThat(differences(minimum, """
                {"name":[{"given":["hello","goodbye","greeting"]}]}""")).isEmpty();
    }

    @Test
    void duplicatedElementsNeedACounterpartEach() {
        assertThat(differences("""
                {"name":[{"given":["hello","hello"]}]}""", """
                {"name":[{"given":["hello"]}]}""")).containsExactly("Patient.name.given[1]: no match for hello");
    }

    @Test
    void everyDifferenceIsReported() {
        assertThat(differences("""
                {"gender":"male","birthDate":"1974-12-25","name":[{"family":"Chalmers"}]}""", """
                {"gender":"female","name":[{"family":"Chalmers"}]}""")).containsExactly(
                "Patient.gender: expected 'male', found 'female'",
                "Patient.birthDate: missing, expected 1974-12-25");
    }

    @Test
    void aRepeatedElementIsComparedWithTheCandidateThatHasTheSameKeys() {
        assertThat(json("""
                {"rest":[{"resource":[{"type":"Patient","interaction":[{"code":"read"},{"code":"delete"}]}]}]}""")
                .notContainedIn(json("""
                        {"rest":[{"resource":[{"type":"Organization","interaction":[{"code":"delete"}]},
                          {"type":"Patient","interaction":[{"code":"read"}]}]}]}"""), "CapabilityStatement"))
                .containsExactly("CapabilityStatement.rest.resource.interaction[1]: no match for {code=delete}");
    }

    @Test
    void jsonAndXmlReadAlike() {
        FhirElement fromJson = json("""
                {"resourceType":"Patient","id":"p1","active":true,
                 "birthDate":"1974-12-25","_birthDate":{"extension":[{"url":"http://x","valueString":"y"}]},
                 "name":[{"family":"Chalmers","given":["Peter","James"]}]}""");
        FhirElement fromXml = FhirElement.of(Body.xml("""
                <Patient xmlns="http://hl7.org/fhir">
                  <id value="p1"/>
                  <active value="true"/>
                  <name><family value="Chalmers"/><given value="Peter"/><given value="James"/></name>
                  <birthDate value="1974-12-25"><extension url="http://x"><valueString value="y"/></extension></birthDate>
                </Patient>"""));

        assertThat(fromJson.notContainedIn(fromXml, "Patient")).isEmpty();
        assertThat(fromXml.notContainedIn(fromJson, "Patient")).isEmpty();
    }

    @Test
    void containedResourcesAndBundleEntriesKeepTheirTypeInBothEncodings() {
        FhirElement fromJson = json("""
                {"resourceType":"Bundle","entry":[{"resource":{"resourceType":"Patient","id":"p1"}}]}""");
        FhirElement fromXml = FhirElement.of(Body.xml("""
                <Bundle xmlns="http://hl7.org/fhir"><entry><resource><Patient><id value="p1"/></Patient></resource></entry></Bundle>"""));

        assertThat(fromJson.notContainedIn(fromXml, "Bundle")).isEmpty();
        assertThat(fromXml.all("entry").getFirst().all("resource").getFirst().valueOf("resourceType")).contains("Patient");
    }

    @Test
    void onlyKeepsTheGivenPathsAndWithoutDropsAnElement() {
        FhirElement statement = json("""
                {"resourceType":"CapabilityStatement","id":"x","name":"Mine","rest":[{"mode":"server",
                 "documentation":"text","resource":[{"type":"Patient","profile":"http://p"}]}]}""");

        assertThat(statement.only(Set.of("rest.mode", "rest.resource.type")).toString())
                .isEqualTo("{rest={mode=server, resource={type=Patient}}}");
        assertThat(statement.without("id").all("id")).isEmpty();
    }
}
