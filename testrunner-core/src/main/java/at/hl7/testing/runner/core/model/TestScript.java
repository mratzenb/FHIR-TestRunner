package at.hl7.testing.runner.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A TestScript in the runner's version-neutral model.
 *
 * <p>Every supported FHIR version maps its own {@code TestScript} resource to this model (see
 * {@link at.hl7.testing.runner.core.spi.FhirVersionSupport}); the engine only ever sees this type. It holds the
 * elements the runner executes, not a full copy of the resource.
 *
 * @param fhirVersion  the FHIR version the TestScript was written in
 * @param id           {@code TestScript.id}, may be {@code null}
 * @param url          {@code TestScript.url}, may be {@code null}
 * @param name         {@code TestScript.name}, may be {@code null}
 * @param title        {@code TestScript.title}, may be {@code null}
 * @param destinations the servers requests go to ({@code TestScript.destination}); the runner supports one
 * @param capabilities the capabilities the TestScript expects of the server ({@code metadata.capability})
 * @param fixtures     the fixtures
 * @param profiles     the profiles {@code validateProfileId} can refer to
 * @param variables    the variables
 * @param setup        the setup actions, empty if there is no setup
 * @param tests        the tests
 * @param teardown     the teardown actions, empty if there is no teardown (teardown only has operations)
 */
public record TestScript(FhirVersion fhirVersion, String id, String url, String name, String title,
                         List<Destination> destinations,
                         List<CapabilityRequirement> capabilities, List<FixtureDefinition> fixtures,
                         List<ProfileDeclaration> profiles, List<VariableDefinition> variables, List<Action> setup,
                         List<TestDefinition> tests, List<Action> teardown) {

    public TestScript {
        Objects.requireNonNull(fhirVersion, "fhirVersion");
        destinations = List.copyOf(destinations);
        capabilities = List.copyOf(capabilities);
        fixtures = List.copyOf(fixtures);
        profiles = List.copyOf(profiles);
        variables = List.copyOf(variables);
        setup = List.copyOf(setup);
        tests = List.copyOf(tests);
        teardown = List.copyOf(teardown);
    }

    /** The name to show in logs and reports: name, title, id, in this order. */
    public String displayName() {
        for (String candidate : new String[] {name, title, id}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "Unnamed TestScript";
    }
}
