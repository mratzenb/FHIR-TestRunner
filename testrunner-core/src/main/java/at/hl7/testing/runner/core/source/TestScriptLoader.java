package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.CapabilityRequirement;
import at.hl7.testing.runner.core.model.ContentFormat;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.FhirVersionSupport;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import at.hl7.testing.runner.core.spi.TestScriptRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a TestScript file: determines its FHIR version, maps it to the model with the matching version module and
 * loads the resources of its fixtures and the CapabilityStatements it requires from the Example Instances.
 */
public final class TestScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(TestScriptLoader.class);

    private final FhirVersionRegistry registry;

    public TestScriptLoader(FhirVersionRegistry registry) {
        this.registry = registry;
    }

    /**
     * @throws TestScriptFormatException if the file cannot be read, its version cannot be determined or a fixture
     *                                   resource is missing
     */
    public LoadedTestScript load(TestScriptSource source) {
        Body content = read(source);
        FhirVersionSupport support = support(source, content);
        TestScriptRead read = support.readTestScript(content);
        TestScript testScript = read.testScript();
        ExampleInstanceResolver resolver = new ExampleInstanceResolver(source.suite().resolvedExampleInstancesDir());
        Map<String, Body> resources = new LinkedHashMap<>();
        for (FixtureDefinition fixture : testScript.fixtures()) {
            if (fixture.reference() != null) {
                try {
                    resources.put(fixture.id(), resolver.load(fixture));
                } catch (ExampleInstanceResolver.FixtureNotFoundException e) {
                    throw new TestScriptFormatException(e.getMessage(), e, testScript.fhirVersion());
                }
            }
        }
        // a CapabilityStatement is an example, or a conformance resource next to the TestScripts (as in a package)
        ExampleInstanceResolver statements = new ExampleInstanceResolver(List.of(
                source.suite().resolvedExampleInstancesDir(), source.suite().resolvedTestScriptsDir()));
        Map<String, Body> capabilities = new LinkedHashMap<>();
        for (CapabilityRequirement capability : testScript.capabilities()) {
            if (capability.capabilities() != null) {
                statements.find(capability.capabilities())
                        .ifPresent(body -> capabilities.put(capability.capabilities(), body));
            }
        }
        return new LoadedTestScript(source, content, testScript, support, resources, capabilities, read.warnings());
    }

    private static Body read(TestScriptSource source) {
        try {
            String text = Files.readString(source.file(), StandardCharsets.UTF_8);
            ContentFormat format = ContentFormat.fromFileName(source.file().getFileName().toString());
            return new Body(text, format != null ? format : ContentFormat.sniff(text));
        } catch (IOException e) {
            throw new TestScriptFormatException("Could not read " + source.file() + ": " + e.getMessage(), e);
        }
    }

    private FhirVersionSupport support(TestScriptSource source, Body content) {
        Optional<FhirVersion> pinned = source.suite().pinnedFhirVersion();
        if (pinned.isPresent()) {
            return registry.get(pinned.get()).orElseThrow(() -> new TestScriptFormatException(
                    "FHIR " + pinned.get() + " is configured for suite '" + source.suite().name()
                            + "', but no module supports it (available: " + registry.versions() + ")"));
        }
        Optional<FhirVersionRegistry.Detection> detected = registry.detect(content);
        if (detected.isEmpty()) {
            StringBuilder message = new StringBuilder("The FHIR version of ").append(source.file().getFileName())
                    .append(" could not be determined, it is no valid TestScript in any available version:");
            registry.checkAll(content).forEach((version, check) -> message.append("\n  ").append(version)
                    .append(": ").append(String.join("; ", check.errors())));
            throw new TestScriptFormatException(message.toString());
        }
        log.debug("{} detected as FHIR {}", source.file().getFileName(), detected.get().support().version());
        return detected.get().support();
    }
}
