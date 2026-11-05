package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.CapabilityRequirement;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Destination;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.OperationType;
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
        return new Operation(OperationType.fromCode(type), type, null, null, null, "json", null, true, null, null,
                List.of(), null, null, null, null, null, null);
    }

    static Operation op(String type, String resource, String sourceId, String targetId, String params, String responseId) {
        return new Operation(OperationType.fromCode(type), type, resource, null, null, "json", null, true, null, params,
                List.of(), null, responseId, sourceId, targetId, null, null);
    }

    static Operation toDestination(Operation o, int destination) {
        return new Operation(o.type(), o.typeCode(), o.resource(), o.label(), o.description(), o.accept(),
                o.contentType(), o.encodeRequestUrl(), o.method(), o.params(), o.requestHeaders(), o.requestId(),
                o.responseId(), o.sourceId(), o.targetId(), o.url(), destination);
    }

    static Operation withHeaders(Operation o, RequestHeader... headers) {
        return new Operation(o.type(), o.typeCode(), o.resource(), o.label(), o.description(), o.accept(),
                o.contentType(), o.encodeRequestUrl(), o.method(), o.params(), List.of(headers), o.requestId(),
                o.responseId(), o.sourceId(), o.targetId(), o.url(), o.destination());
    }

    /** {@code encodeRequestUrl = false}: the URL is sent as built. */
    static Operation noEncoding(Operation o) {
        return new Operation(o.type(), o.typeCode(), o.resource(), o.label(), o.description(), o.accept(),
                o.contentType(), false, o.method(), o.params(), o.requestHeaders(), o.requestId(),
                o.responseId(), o.sourceId(), o.targetId(), o.url(), o.destination());
    }

    static Operation withRequestId(Operation o, String requestId) {
        return new Operation(o.type(), o.typeCode(), o.resource(), o.label(), o.description(), o.accept(),
                o.contentType(), o.encodeRequestUrl(), o.method(), o.params(), o.requestHeaders(), requestId,
                o.responseId(), o.sourceId(), o.targetId(), o.url(), o.destination());
    }

    static Assertion check(Check check) {
        return new Assertion(check, null, null, null, null, null, null, null, null, null, false, false);
    }

    static Assertion check(Check check, Operator operator, String value) {
        return new Assertion(check, null, null, null, operator, value, null, null, null, null, false, false);
    }

    static Assertion warningOnly(Assertion a) {
        return new Assertion(a.check(), a.label(), a.description(), a.direction(), a.operator(), a.value(),
                a.sourceId(), a.compareToSourceId(), a.compareToSourceExpression(), a.compareToSourcePath(), true,
                a.stopTestOnFail());
    }

    static Assertion stopTestOnFail(Assertion a) {
        return new Assertion(a.check(), a.label(), a.description(), a.direction(), a.operator(), a.value(),
                a.sourceId(), a.compareToSourceId(), a.compareToSourceExpression(), a.compareToSourcePath(),
                a.warningOnly(), true);
    }

    /** {@code direction = request}: the assertion checks the request sent, not the response. */
    static Assertion onRequest(Assertion a) {
        return new Assertion(a.check(), a.label(), a.description(), "request", a.operator(), a.value(),
                a.sourceId(), a.compareToSourceId(), a.compareToSourceExpression(), a.compareToSourcePath(),
                a.warningOnly(), a.stopTestOnFail());
    }

    static Assertion on(Assertion a, String sourceId) {
        return new Assertion(a.check(), a.label(), a.description(), a.direction(), a.operator(), a.value(),
                sourceId, a.compareToSourceId(), a.compareToSourceExpression(), a.compareToSourcePath(),
                a.warningOnly(), a.stopTestOnFail());
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
            return new TestScript(FhirVersion.R4, "ts", url, name, null, destinations,
                    capabilities, fixtures, profiles, variables, setup, tests, teardown);
        }
    }
}
