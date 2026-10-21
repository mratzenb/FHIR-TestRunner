package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.model.ContentFormat;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExampleInstanceResolverTest {

    @Test
    void candidatesForBothReferenceSpellings() {
        assertThat(ExampleInstanceResolver.candidates("Patient/Foo")).containsExactly("Patient-Foo", "Foo");
        assertThat(ExampleInstanceResolver.candidates("Patient-Foo.html")).containsExactly("Patient-Foo");
        assertThat(ExampleInstanceResolver.candidates("http://x/fhir/Patient/Foo")).containsExactly("Patient-Foo", "Foo");
    }

    @Test
    void loadsJsonOrXml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Patient-A.json"), "{\"resourceType\":\"Patient\"}");
        Files.writeString(dir.resolve("Patient-B.xml"), "<Patient xmlns=\"http://hl7.org/fhir\"/>");
        ExampleInstanceResolver resolver = new ExampleInstanceResolver(dir);

        assertThat(resolver.load(new FixtureDefinition("a", false, false, "Patient/A")).format()).isEqualTo(ContentFormat.JSON);
        assertThat(resolver.load(new FixtureDefinition("b", false, false, "Patient-B.html")).format()).isEqualTo(ContentFormat.XML);
    }

    @Test
    void missingFileNamesEveryCandidate(@TempDir Path dir) {
        assertThatThrownBy(() -> new ExampleInstanceResolver(dir).load(new FixtureDefinition("f", false, false, "Patient/X")))
                .hasMessageContaining("fixture 'f'").hasMessageContaining("Patient-X.json").hasMessageContaining("X.xml");
    }
}
