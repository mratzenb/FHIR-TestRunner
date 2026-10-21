package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.TestScript;

import java.util.List;

/**
 * A TestScript read by a {@link FhirVersionSupport}.
 *
 * @param testScript the model
 * @param warnings   content that was ignored while reading, e.g. elements unknown in the FHIR version
 */
public record TestScriptRead(TestScript testScript, List<String> warnings) {

    public TestScriptRead {
        warnings = List.copyOf(warnings);
    }
}
