package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceContentTest {

    static final Body JSON = Body.json("""
            {"resourceType":"Patient","id":"p1","meta":{"versionId":"3"},
             "link":[{"other":{"reference":"Patient/p2"}}],"managingOrganization":{"reference":"Organization/o1"}}""");
    static final Body XML = Body.xml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <Patient xmlns="http://hl7.org/fhir"><id value="p1"/><meta><versionId value="3"/></meta>
              <managingOrganization><reference value="Organization/o1"/></managingOrganization></Patient>""");

    @Test
    void readsTypeIdAndVersionOfJsonAndXml() {
        for (Body body : new Body[] {JSON, XML}) {
            assertThat(ResourceContent.resourceType(body)).isEqualTo("Patient");
            assertThat(ResourceContent.id(body)).isEqualTo("p1");
            assertThat(ResourceContent.versionId(body)).isEqualTo("3");
        }
    }

    @Test
    void containedResourcesAreBodiesOfTheirOwn() {
        Body json = Body.json("""
                {"resourceType":"TestScript","contained":[{"resourceType":"Organization","id":"org","name":"A"},
                 {"resourceType":"Patient","id":"pat","name":[{"family":"B"}]}]}""");
        Body xml = Body.xml("""
                <TestScript xmlns="http://hl7.org/fhir"><contained><Organization><id value="org"/></Organization></contained>
                  <contained><Patient><id value="pat"/><name><family value="B"/></name></Patient></contained></TestScript>""");

        for (Body body : new Body[] {json, xml}) {
            assertThat(ResourceContent.containedIds(body)).containsExactly("org", "pat");
            Body patient = ResourceContent.contained(body, "pat").orElseThrow();
            assertThat(patient.format()).isEqualTo(body.format());
            assertThat(ResourceContent.resourceType(patient)).isEqualTo("Patient");
            assertThat(ResourceContent.id(patient)).isEqualTo("pat");
            assertThat(ResourceContent.contained(body, "nope")).isEmpty();
        }
        assertThat(ResourceContent.contained(xml, "pat").orElseThrow().text())
                .contains("<Patient xmlns=\"http://hl7.org/fhir\">").doesNotContain("TestScript");
    }

    @Test
    void jsonPatchIsNoResource() {
        assertThat(ResourceContent.resourceType(Body.json("[{\"op\":\"replace\",\"path\":\"/active\",\"value\":false}]"))).isNull();
    }

    @Test
    void setsTheIdKeepingItFirst() {
        Body json = ResourceContent.withId(JSON, "new");
        assertThat(ResourceContent.id(json)).isEqualTo("new");
        assertThat(json.text().indexOf("\"id\"")).isLessThan(json.text().indexOf("\"meta\""));
        assertThat(ResourceContent.id(ResourceContent.withId(XML, "new"))).isEqualTo("new");
        assertThat(ResourceContent.id(ResourceContent.withId(Body.json("{\"resourceType\":\"Patient\"}"), "x"))).isEqualTo("x");
    }

    @Test
    void findsAndReplacesReferences() {
        assertThat(ResourceContent.references(JSON)).containsExactly("Patient/p2", "Organization/o1");
        assertThat(ResourceContent.references(XML)).containsExactly("Organization/o1");

        Body replaced = ResourceContent.replaceReferences(XML, r -> r.equals("Organization/o1") ? "Organization/99" : r);
        assertThat(ResourceContent.references(replaced)).containsExactly("Organization/99");
        assertThat(ResourceContent.replaceReferences(JSON, r -> r)).isSameAs(JSON);
    }

    @Test
    void replacesTextValuesButNotNamesOrStructure() {
        Body json = Body.json("""
                {"resourceType":"Patient","${key}":"x","name":[{"family":"A-${v}","given":["${v}","${v}"]}],"active":true}""");
        Body xml = Body.xml("""
                <Patient xmlns="http://hl7.org/fhir"><name><family value="A-${v}"/></name><text><div>${v}</div></text></Patient>""");

        Body replacedJson = ResourceContent.replaceText(json, t -> t.replace("${v}", "\"quoted\"").replace("${key}", "k"));
        Body replacedXml = ResourceContent.replaceText(xml, t -> t.replace("${v}", "<b>&</b>"));

        assertThat(ResourceContent.json(replacedJson).path("${key}").asText()).isEqualTo("x");
        assertThat(ResourceContent.json(replacedJson).path("name").get(0).path("family").asText()).isEqualTo("A-\"quoted\"");
        assertThat(ResourceContent.json(replacedJson).path("name").get(0).path("given").get(1).asText()).isEqualTo("\"quoted\"");
        assertThat(ResourceContent.json(replacedJson).path("active").asBoolean()).isTrue();
        assertThat(replacedXml.text()).contains("<family value=\"A-&lt;b&gt;&amp;&lt;/b&gt;\"/>", "<div>&lt;b&gt;&amp;&lt;/b&gt;</div>");
        assertThat(ResourceContent.replaceText(JSON, t -> t)).isSameAs(JSON);
    }

    @Test
    void rewritingKeepsDecimalsAsWritten() {
        Body json = Body.json("""
                {"resourceType":"Observation","id":"${x}","valueQuantity":{"value":1.50},
                 "component":[{"valueDecimal":12345678901234567890.123},{"valueInteger":7}]}""");

        String rewritten = ResourceContent.replaceText(json, t -> t.replace("${x}", "o1")).text();
        String withId = ResourceContent.withId(json, "o2").text();

        for (String text : List.of(rewritten, withId)) {
            assertThat(text).contains("1.50", "12345678901234567890.123", "7").doesNotContain("E19");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "http://s/fhir/Patient/123/_history/2, Patient, 123, 2",
            "http://s/fhir/Patient/123, Patient, 123, ",
            "Patient/abc/_history/7, Patient, abc, 7"})
    void parsesLocations(String location, String type, String id, String version) {
        Map<String, String> parsed = ResourceContent.parseLocation(location);
        assertThat(parsed.get("type")).isEqualTo(type);
        assertThat(parsed.get("id")).isEqualTo(id);
        assertThat(parsed.get("version")).isEqualTo(version);
    }
}
