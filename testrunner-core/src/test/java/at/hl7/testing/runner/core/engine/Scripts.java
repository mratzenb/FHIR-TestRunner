package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.CapabilityRequirement;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Destination;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.ProfileDeclaration;
import at.hl7.testing.runner.core.model.RequestHeader;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.model.VariableDefinition;

import java.util.ArrayList;
import java.util.List;

/** Builders for TestScript models in tests, so the engine can be tested without any FHIR version module. */
final class Scripts {

    private Scripts() {
    }

    static Operation op(String type) {
        return Operation.builder().type(type).accept("json").build();
    }

    static Operation op(String type, String resource, String sourceId, String targetId, String params, String responseId) {
        return Operation.builder().type(type).resource(resource).accept("json").sourceId(sourceId).targetId(targetId)
                .params(params).responseId(responseId).build();
    }

    static Operation toDestination(Operation o, int destination) {
        return o.toBuilder().destination(destination).build();
    }

    static Operation withHeaders(Operation o, RequestHeader... headers) {
        return o.toBuilder().requestHeaders(List.of(headers)).build();
    }

    /** {@code encodeRequestUrl = false}: the URL is sent as built. */
    static Operation noEncoding(Operation o) {
        return o.toBuilder().encodeRequestUrl(false).build();
    }

    static Operation withRequestId(Operation o, String requestId) {
        return o.toBuilder().requestId(requestId).build();
    }

    static Assertion check(Check check) {
        return Assertion.builder(check).build();
    }

    static Assertion check(Check check, Operator operator, String value) {
        return Assertion.builder(check).operator(operator).value(value).build();
    }

    static Assertion warningOnly(Assertion a) {
        return a.toBuilder().warningOnly(true).build();
    }

    static Assertion stopTestOnFail(Assertion a) {
        return a.toBuilder().stopTestOnFail(true).build();
    }

    /** {@code direction = request}: the assertion checks the request sent, not the response. */
    static Assertion onRequest(Assertion a) {
        return a.toBuilder().direction("request").build();
    }

    static Assertion on(Assertion a, String sourceId) {
        return a.toBuilder().sourceId(sourceId).build();
    }

    static Assertion responseCode(String codes) {
        return check(new Check.ResponseCode(codes), codes.contains(",") ? Operator.IN : null, null);
    }

    static TestDefinition test(String name, Action... actions) {
        return new TestDefinition(name, null, List.of(actions));
    }

    /** Fluent builder of a TestScript. */
    static Builder script() {
        return new Builder();
    }

    static final class Builder {
        private final List<Destination> destinations = new ArrayList<>();
        private final List<CapabilityRequirement> capabilities = new ArrayList<>();
        private final List<FixtureDefinition> fixtures = new ArrayList<>();
        private final List<ProfileDeclaration> profiles = new ArrayList<>();
        private final List<VariableDefinition> variables = new ArrayList<>();
        private final List<Action> setup = new ArrayList<>();
        private final List<TestDefinition> tests = new ArrayList<>();
        private final List<Action> teardown = new ArrayList<>();
        private String name = "TS";
        private String url = "http://example.org/TestScript/ts";

        /** {@code TestScript.name} and {@code TestScript.url}; default {@code TS} and {@code …/TestScript/ts}. */
        Builder named(String newName, String newUrl) {
            this.name = newName;
            this.url = newUrl;
            return this;
        }

        Builder destination(int index) {
            destinations.add(new Destination(index, "FHIR-Server"));
            return this;
        }

        /** A required capability: the server must support what CapabilityStatement {@code canonical} describes. */
        Builder requires(String canonical) {
            capabilities.add(new CapabilityRequirement(true, false, "needs " + canonical, canonical, List.of()));
            return this;
        }

        /** A required capability of the server of one destination. */
        Builder requires(String canonical, int destination) {
            capabilities.add(new CapabilityRequirement(true, false, "needs " + canonical, canonical, List.of(),
                    destination));
            return this;
        }

        Builder fixture(String id, boolean autocreate, boolean autodelete) {
            fixtures.add(new FixtureDefinition(id, autocreate, autodelete, "file-of-" + id));
            return this;
        }

        Builder profile(String id, String canonical) {
            profiles.add(new ProfileDeclaration(id, canonical));
            return this;
        }

        Builder variable(VariableDefinition variable) {
            variables.add(variable);
            return this;
        }

        Builder setup(Action... actions) {
            setup.addAll(List.of(actions));
            return this;
        }

        Builder test(TestDefinition test) {
            tests.add(test);
            return this;
        }

        Builder teardown(Action... actions) {
            teardown.addAll(List.of(actions));
            return this;
        }

        TestScript build() {
            return TestScript.builder(FhirVersion.R4).id("ts").url(url).name(name).destinations(destinations)
                    .capabilities(capabilities).fixtures(fixtures).profiles(profiles).variables(variables)
                    .setup(setup).tests(tests).teardown(teardown).build();
        }
    }
}
