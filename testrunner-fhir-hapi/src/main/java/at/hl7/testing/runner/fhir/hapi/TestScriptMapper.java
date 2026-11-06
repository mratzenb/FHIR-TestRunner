package at.hl7.testing.runner.fhir.hapi;

import at.hl7.testing.runner.core.model.Action;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.CapabilityRequirement;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Destination;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.FixtureDefinition;
import at.hl7.testing.runner.core.model.HttpMethod;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.ProfileDeclaration;
import at.hl7.testing.runner.core.model.RequestHeader;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.model.VariableDefinition;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Maps a HAPI {@code TestScript} of any FHIR version to the runner's version-neutral model.
 *
 * <p>The mapper reads elements by name through {@link ElementView}, so it does not depend on the version-specific
 * HAPI classes. The few semantic differences between versions come from the {@link TestScriptDialect}; differences
 * in type only (Reference vs. canonical, code vs. uri) are absorbed here, see {@link #profiles}.
 */
public final class TestScriptMapper {

    private final FhirVersion version;
    private final TestScriptDialect dialect;

    public TestScriptMapper(@Nonnull FhirVersion version, @Nonnull TestScriptDialect dialect) {
        this.version = version;
        this.dialect = dialect;
    }

    @Nonnull
    public TestScript map(@Nonnull ElementView testScript) {
        return TestScript.builder(version)
                .id(testScript.string("id"))
                .url(testScript.string("url"))
                .name(testScript.string("name"))
                .title(testScript.string("title"))
                .destinations(testScript.children("destination").stream().map(TestScriptMapper::destination).toList())
                .capabilities(testScript.child("metadata").map(m -> m.children("capability")).orElse(List.of())
                        .stream().map(TestScriptMapper::capability).toList())
                .fixtures(testScript.children("fixture").stream().map(this::fixture).toList())
                .profiles(profiles(testScript))
                .variables(testScript.children("variable").stream().map(this::variable).toList())
                .setup(testScript.child("setup").map(this::actions).orElse(List.of()))
                .tests(testScript.children("test").stream().map(this::test).toList())
                .teardown(testScript.child("teardown").map(this::actions).orElse(List.of()))
                .build();
    }

    /** {@code TestScript.destination}: an index and a profile {@code Coding}. */
    private static Destination destination(ElementView destination) {
        String index = destination.string("index");
        if (index == null) {
            throw new TestScriptFormatException("A TestScript.destination has no index");
        }
        return new Destination(Integer.parseInt(index), destination.child("profile").map(p -> p.string("code")).orElse(null));
    }

    private static CapabilityRequirement capability(ElementView capability) {
        // required and validated are mandatory in R4 and R5; absent means false
        return new CapabilityRequirement(capability.bool("required", false), capability.bool("validated", false),
                capability.string("description"), capability.string("capabilities"),
                capability.children("link").stream().map(ElementView::primitiveValue).toList(),
                integer(capability.string("destination")));
    }

    private FixtureDefinition fixture(ElementView fixture) {
        String id = fixture.elementId();
        if (id == null || id.isBlank()) {
            throw new TestScriptFormatException("A fixture has no id, so it cannot be referenced");
        }
        String reference = fixture.child("resource").map(r -> r.string("reference")).orElse(null);
        // autocreate and autodelete are mandatory in R4 and R5; absent means false
        return new FixtureDefinition(id, fixture.bool("autocreate", false), fixture.bool("autodelete", false), reference);
    }

    /**
     * {@code TestScript.profile}: in R4 a {@code Reference} with the id on the Reference element and the canonical
     * in {@code reference}; in R5 a {@code canonical} with the id on the primitive ({@code _profile[n].id}). An R5
     * canonical without id is addressed by the last segment of its URL.
     */
    private List<ProfileDeclaration> profiles(ElementView testScript) {
        List<ProfileDeclaration> profiles = new ArrayList<>();
        for (ElementView profile : testScript.children("profile")) {
            String canonical = profile.isPrimitive() ? profile.primitiveValue() : profile.string("reference");
            if (canonical == null || canonical.isBlank()) {
                throw new TestScriptFormatException("A TestScript.profile has no canonical URL");
            }
            String id = profile.elementId();
            if (id == null || id.isBlank()) {
                id = canonical.substring(canonical.lastIndexOf('/') + 1);
                int bar = id.indexOf('|');
                id = bar >= 0 ? id.substring(0, bar) : id;
            }
            profiles.add(new ProfileDeclaration(id, canonical));
        }
        return profiles;
    }

    private VariableDefinition variable(ElementView variable) {
        String name = variable.string("name");
        if (name == null || name.isBlank()) {
            throw new TestScriptFormatException("A variable has no name");
        }
        return new VariableDefinition(name, variable.string("defaultValue"), variable.string("expression"),
                variable.string("path"), variable.string("headerField"), variable.string("sourceId"),
                variable.string("hint"));
    }

    private TestDefinition test(ElementView test) {
        String name = Optional.ofNullable(test.string("name")).orElse(test.elementId());
        return new TestDefinition(name == null ? "Unnamed test" : name, test.string("description"), actions(test));
    }

    private List<Action> actions(ElementView phase) {
        List<Action> actions = new ArrayList<>();
        for (ElementView action : phase.children("action")) {
            Optional<ElementView> operation = action.child("operation");
            Optional<ElementView> assertion = action.child("assert");
            if (operation.isPresent() == assertion.isPresent()) {
                throw new TestScriptFormatException("An action must have either an operation or an assert");
            }
            actions.add(operation.isPresent() ? operation(operation.get()) : assertion(assertion.get()));
        }
        return actions;
    }

    private Operation operation(ElementView operation) {
        String typeCode = operation.child("type").map(t -> t.string("code")).orElse(null);
        String method = operation.string("method");
        List<RequestHeader> headers = operation.children("requestHeader").stream()
                .map(h -> new RequestHeader(h.string("field"), h.string("value")))
                .toList();
        return Operation.builder()
                .type(typeCode)
                .resource(resourceType(operation.string("resource")))
                .label(operation.string("label"))
                .description(operation.string("description"))
                .accept(operation.string("accept"))
                .contentType(operation.string("contentType"))
                .encodeRequestUrl(operation.bool("encodeRequestUrl", true))
                .method(method == null ? null : HttpMethod.parse(method))
                .params(operation.string("params"))
                .requestHeaders(headers)
                .requestId(operation.string("requestId"))
                .responseId(operation.string("responseId"))
                .sourceId(operation.string("sourceId"))
                .targetId(operation.string("targetId"))
                .url(operation.string("url"))
                .destination(integer(operation.string("destination")))
                .build();
    }

    private Assertion assertion(ElementView assertion) {
        List<Check> checks = new ArrayList<>();
        add(checks, assertion.string("contentType"), Check.ContentType::new);
        add(checks, assertion.string("expression"), Check.Expression::new);
        add(checks, assertion.string("headerField"), Check.HeaderField::new);
        add(checks, assertion.string("minimumId"), Check.MinimumId::new);
        add(checks, assertion.string("navigationLinks"), v -> new Check.NavigationLinks(Boolean.parseBoolean(v)));
        add(checks, assertion.string("path"), Check.Path::new);
        add(checks, assertion.string("requestMethod"), Check.RequestMethod::new);
        add(checks, assertion.string("requestURL"), Check.RequestUrl::new);
        add(checks, resourceType(assertion.string("resource")), Check.Resource::new);
        add(checks, assertion.string("response"), this::response);
        add(checks, assertion.string("responseCode"), Check.ResponseCode::new);
        add(checks, assertion.string("validateProfileId"), Check.ValidateProfile::new);
        String label = Optional.ofNullable(assertion.string("label")).orElse(assertion.string("description"));
        if (checks.size() != 1) {
            throw new TestScriptFormatException("Assert '" + (label == null ? "?" : label) + "' must check exactly one"
                    + " of contentType, expression, headerField, minimumId, navigationLinks, path, requestMethod,"
                    + " requestURL, resource, response, responseCode, validateProfileId, but checks " + checks.size());
        }
        String compareToSourceId = assertion.string("compareToSourceId");
        String compareExpression = assertion.string("compareToSourceExpression");
        String comparePath = assertion.string("compareToSourcePath");
        if ((compareToSourceId == null) != (compareExpression == null && comparePath == null)
                || (compareExpression != null && comparePath != null)) {
            throw new TestScriptFormatException("Assert '" + (label == null ? "?" : label) + "' must have"
                    + " compareToSourceId together with exactly one of compareToSourceExpression and"
                    + " compareToSourcePath, or none of them");
        }
        Operator operator;
        try {
            operator = Operator.fromCode(assertion.string("operator"));
        } catch (IllegalArgumentException e) {
            throw new TestScriptFormatException(e.getMessage(), e);
        }
        return Assertion.builder(checks.getFirst())
                .label(assertion.string("label"))
                .description(assertion.string("description"))
                .direction(assertion.string("direction"))
                .operator(operator)
                .value(assertion.string("value"))
                .sourceId(assertion.string("sourceId"))
                .compareToSourceId(compareToSourceId)
                .compareToSourceExpression(compareExpression)
                .compareToSourcePath(comparePath)
                .warningOnly(assertion.bool("warningOnly", false))
                .stopTestOnFail(assertion.bool("stopTestOnFail", dialect.stopTestOnFailDefault()))
                .build();
    }

    private Check response(String code) {
        Integer status = ResponseCodes.status(code);
        if (status == null) {
            throw new TestScriptFormatException("Unknown assert.response code '" + code + "'");
        }
        return new Check.Response(code, status);
    }

    /** {@code resource} is a {@code code} (R4) or a {@code uri} (R5); for core types both are the type name. */
    private static String resourceType(String value) {
        if (value == null) {
            return null;
        }
        return value.substring(value.lastIndexOf('/') + 1);
    }

    private static Integer integer(String value) {
        return value == null ? null : Integer.valueOf(value);
    }

    private static void add(List<Check> checks, String value, Function<String, Check> factory) {
        if (value != null) {
            checks.add(factory.apply(value));
        }
    }
}
