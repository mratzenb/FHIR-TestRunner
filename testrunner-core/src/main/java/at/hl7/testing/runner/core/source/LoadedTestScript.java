package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.spi.FhirVersionSupport;

import java.util.List;
import java.util.Map;

/**
 * A TestScript ready to run: the model, the original content, the version module that read it and the resources
 * of its fixtures.
 *
 * @param source           where it came from
 * @param original         the file content, as validated before the run
 * @param testScript       the model
 * @param support          the FHIR version module the TestScript was read with
 * @param fixtureResources the resource of each fixture with a {@code reference}, by fixture id
 * @param capabilities     the CapabilityStatements named by {@code metadata.capability.capabilities}, by canonical
 *                         URL, as far as they are among the Example Instances
 * @param readWarnings     content ignored while reading, e.g. elements unknown in the FHIR version
 */
public record LoadedTestScript(TestScriptSource source, Body original, TestScript testScript,
                               FhirVersionSupport support, Map<String, Body> fixtureResources,
                               Map<String, Body> capabilities, List<String> readWarnings) {

    public LoadedTestScript {
        fixtureResources = Map.copyOf(fixtureResources);
        capabilities = Map.copyOf(capabilities);
        readWarnings = List.copyOf(readWarnings);
    }
}
