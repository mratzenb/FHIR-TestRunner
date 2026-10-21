package at.hl7.testing.runner.core.content;

import at.hl7.testing.runner.core.model.Body;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
