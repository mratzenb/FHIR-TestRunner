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

    /** A builder of a TestScript of {@code fhirVersion}; every list is empty until set. */
    public static Builder builder(FhirVersion fhirVersion) {
        return new Builder(fhirVersion);
    }

    /** A builder with every value of this TestScript, to make a copy that differs in a few. */
    public Builder toBuilder() {
        return new Builder(fhirVersion).id(id).url(url).name(name).title(title).destinations(destinations)
                .capabilities(capabilities).fixtures(fixtures).profiles(profiles).variables(variables).setup(setup)
                .tests(tests).teardown(teardown);
    }

    /** A copy with only these tests, e.g. those selected to run; setup and teardown stay. */
    public TestScript withTests(List<TestDefinition> newTests) {
        return toBuilder().tests(newTests).build();
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

    /**
     * Builds a {@link TestScript} by name rather than by the position of its 13 components. Not thread-safe; the
     * TestScript built is immutable.
     */
    public static final class Builder {
        private final FhirVersion fhirVersion;
        private String id;
        private String url;
        private String name;
        private String title;
        private List<Destination> destinations = List.of();
        private List<CapabilityRequirement> capabilities = List.of();
        private List<FixtureDefinition> fixtures = List.of();
        private List<ProfileDeclaration> profiles = List.of();
        private List<VariableDefinition> variables = List.of();
        private List<Action> setup = List.of();
        private List<TestDefinition> tests = List.of();
        private List<Action> teardown = List.of();

        private Builder(FhirVersion fhirVersion) {
            this.fhirVersion = fhirVersion;
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder url(String value) {
            this.url = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder destinations(List<Destination> value) {
            this.destinations = value;
            return this;
        }

        public Builder capabilities(List<CapabilityRequirement> value) {
            this.capabilities = value;
            return this;
        }

        public Builder fixtures(List<FixtureDefinition> value) {
            this.fixtures = value;
            return this;
        }

        public Builder profiles(List<ProfileDeclaration> value) {
            this.profiles = value;
            return this;
        }

        public Builder variables(List<VariableDefinition> value) {
            this.variables = value;
            return this;
        }

        public Builder setup(List<Action> value) {
            this.setup = value;
            return this;
        }

        public Builder tests(List<TestDefinition> value) {
            this.tests = value;
            return this;
        }

        public Builder teardown(List<Action> value) {
            this.teardown = value;
            return this;
        }

        public TestScript build() {
            return new TestScript(fhirVersion, id, url, name, title, destinations, capabilities, fixtures, profiles,
                    variables, setup, tests, teardown);
        }
    }
}
