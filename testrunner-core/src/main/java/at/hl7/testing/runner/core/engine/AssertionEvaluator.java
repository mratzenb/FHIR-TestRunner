package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.FhirElement;
import at.hl7.testing.runner.core.content.MediaTypes;
import at.hl7.testing.runner.core.content.PathEvaluator;
import at.hl7.testing.runner.core.content.ResourceContent;
import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.ContentFormat;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.ProfileDeclaration;
import at.hl7.testing.runner.core.spi.FhirPathEvaluator;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates assertions against the last response, or against the fixture or response named by {@code sourceId}.
 *
 * <p>A check that does not hold throws {@link AssertionFailure}; an assertion that cannot be evaluated (invalid,
 * unsupported, unknown source or profile, validator not available) throws {@link TestScriptException} or
 * {@link at.hl7.testing.runner.core.spi.ValidatorUnavailableException}.
 */
final class AssertionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AssertionEvaluator.class);
    /** The links {@code navigationLinks} requires, as defined by the specification. */
    private static final List<String> NAVIGATION_LINKS = List.of("first", "last", "next");

    private final ExecutionState state;
    private final FhirPathEvaluator fhirPath;
    private final ProfileValidator validator;
    private final Map<String, String> profiles;
    private final FhirVersion fhirVersion;
    private final Path profilesDir;
    private final List<String> igs;

    AssertionEvaluator(ExecutionState state, FhirPathEvaluator fhirPath, ProfileValidator validator,
                       List<ProfileDeclaration> profiles, FhirVersion fhirVersion, Path profilesDir, List<String> igs) {
        this.state = state;
        this.fhirPath = fhirPath;
        this.validator = validator;
        this.profiles = profiles.stream()
                .collect(Collectors.toMap(ProfileDeclaration::id, ProfileDeclaration::canonical, (a, b) -> a));
        this.fhirVersion = fhirVersion;
        this.profilesDir = profilesDir;
        this.igs = igs;
    }

    /**
     * Evaluates an assertion whose variables have been resolved.
     *
     * @return the source checked, so the caller can name its request in the log
     */
    Evaluation evaluate(Assertion assertion) {
        String unsupported = ActionRules.unsupported(assertion);
        if (unsupported != null) {
            throw new TestScriptException(unsupported);
        }
        Source source;
        if (assertion.sourceId() != null) {
            source = state.require(assertion.sourceId(), "sourceId");
        } else if (onRequest(assertion)) {
            source = state.lastRequest().orElseThrow(() -> new TestScriptException(
                    "There is no request to check: no operation has been executed yet"));
        } else {
            source = state.lastResponse().orElseThrow(() -> new TestScriptException(
                    "There is no response to check: no operation has been executed yet"));
        }
        Operator op = ActionRules.operator(assertion);
        String warning = switch (assertion.check()) {
            case Check.ContentType c -> {
                String actual = source.headers().first("Content-Type").orElse(null);
                String expected = MediaTypes.expand(c.expected());
                if (op == Operator.EQUALS || op == Operator.NOT_EQUALS) {
                    // compare the media types; parameters such as charset are not part of what is asserted
                    actual = actual == null ? null : MediaTypes.withoutParameters(actual);
                    expected = MediaTypes.withoutParameters(expected);
                }
                Operators.apply(op, actual, expected);
                yield null;
            }
            case Check.ResponseCode c -> {
                Operators.apply(op, String.valueOf(response(source, "responseCode").exchange().response().status()),
                        c.codes().replace(" ", ""));
                yield null;
            }
            case Check.Response c -> {
                int status = response(source, "response").exchange().response().status();
                try {
                    Operators.apply(op, String.valueOf(status), String.valueOf(c.status()));
                } catch (AssertionFailure e) {
                    throw new AssertionFailure("Expected response " + (op == Operator.NOT_EQUALS ? "other than " : "")
                            + "'" + c.code() + "' (" + c.status() + "), got " + status);
                }
                yield null;
            }
            case Check.Resource c -> {
                Operators.apply(op, resourceType(source.body()), c.type());
                yield null;
            }
            case Check.HeaderField c -> {
                String actual = source.headers().first(c.name()).orElse(null);
                Operators.apply(op, actual, expected(assertion, op));
                yield null;
            }
            case Check.Expression c -> {
                List<Object> result = evaluateFhirPath(source.body(), c.expression());
                if (op == Operator.EVAL) {
                    Operators.apply(op, Values.single(result, "Expression '" + c.expression() + "'"), null);
                } else {
                    // the value keeps its FHIRPath type, so that it is compared as a number, date or boolean
                    Object actual = op == Operator.EMPTY || op == Operator.NOT_EMPTY
                            ? (result.isEmpty() ? null : String.valueOf(result.size()))
                            : Values.value(result, "Expression '" + c.expression() + "'");
                    applyWithContext(op, actual, expected(assertion, op), "expression '" + c.expression() + "'");
                }
                yield null;
            }
            case Check.Path c -> {
                if (source.body().isEmpty()) {
                    throw new AssertionFailure("The body is empty, path '" + c.path() + "' cannot be evaluated");
                }
                List<String> result;
                try {
                    result = PathEvaluator.evaluate(source.body(), c.path());
                } catch (IllegalArgumentException e) {
                    throw new TestScriptException(e.getMessage(), e);
                }
                String actual = op == Operator.EMPTY || op == Operator.NOT_EMPTY
                        ? (result.isEmpty() ? null : String.join(",", result))
                        : Values.single(result, "Path '" + c.path() + "'");
                applyWithContext(op, actual, expected(assertion, op), "path '" + c.path() + "'");
                yield null;
            }
            case Check.ValidateProfile c -> validateProfile(source, c.profileId());
            case Check.MinimumId c -> {
                minimumId(source, c.fixtureId());
                yield null;
            }
            case Check.NavigationLinks c -> {
                navigationLinks(source, c.expected());
                yield null;
            }
            // never reached: ActionRules.unsupported rejected them above
            case Check.RequestMethod c -> throw new TestScriptException(ActionRules.unsupported(assertion));
            case Check.RequestUrl c -> throw new TestScriptException(ActionRules.unsupported(assertion));
        };
        return new Evaluation(source, warning);
    }

    /** {@code direction = request}: without {@code sourceId}, the assertion checks the last request sent. */
    static boolean onRequest(Assertion assertion) {
        return "request".equals(assertion.direction());
    }

    /**
     * The result of an assertion that holds.
     *
     * @param source  the fixture or response checked
     * @param warning something worth reporting although the assertion holds, or {@code null}
     */
    record Evaluation(Source source, String warning) {
    }

    private String validateProfile(Source source, String profileId) {
        String canonical = profiles.get(profileId);
        if (canonical == null) {
            throw new TestScriptException("validateProfileId '" + profileId + "' is not declared in TestScript.profile"
                    + (profiles.isEmpty() ? " (the TestScript declares no profiles)" : " (declared: " + profiles.keySet() + ")"));
        }
        if (!validator.isEnabled()) {
            throw new ValidationSkipped("Profile validation is disabled, " + canonical + " was not checked");
        }
        Body body = source.body();
        if (body.isEmpty() || body.format() == ContentFormat.OTHER) {
            throw new AssertionFailure("The body is no FHIR resource and cannot be validated against " + canonical);
        }
        log.info("Validating {} against profile {}", describe(source), canonical);
        ValidationResult result = validator.validate(new ValidationRequest(body, fhirVersion, canonical, profilesDir,
                igs));
        if (!result.isValid()) {
            throw new AssertionFailure("Not conformant to " + canonical + ":\n" + ValidationResult.describe(result.errors()));
        }
        if (!result.warnings().isEmpty()) {
            log.debug("Validator warnings and information:\n{}", ValidationResult.describe(result.warnings()));
        }
        return null;
    }

    /**
     * {@code assert.minimumId}: the source contains every element and value of the fixture, by the rules of the FHIR
     * testing specification (order free, more allowed, duplicates need a counterpart each). The resource id is
     * ignored, as the server assigns its own.
     */
    private void minimumId(Source source, String fixtureId) {
        Source minimum = state.require(fixtureId, "minimumId");
        FhirElement expected = element(minimum.body(), "minimumId '" + fixtureId + "'").without("id");
        FhirElement actual = element(source.body(), describe(source));
        String type = expected.valueOf("resourceType").orElse("Resource");
        List<String> differences = expected.notContainedIn(actual, type);
        if (!differences.isEmpty()) {
            throw new AssertionFailure(describe(source) + " does not contain everything of minimumId '" + fixtureId
                    + "':\n  " + String.join("\n  ", differences));
        }
    }

    /** {@code assert.navigationLinks}: whether the Bundle has a first, last and next link, as the spec defines it. */
    private static void navigationLinks(Source source, boolean expected) {
        FhirElement bundle = element(source.body(), describe(source));
        if (!bundle.valueOf("resourceType").filter("Bundle"::equals).isPresent()) {
            throw new AssertionFailure("navigationLinks needs a Bundle, but " + describe(source) + " is "
                    + bundle.valueOf("resourceType").map(t -> "a " + t).orElse("no resource"));
        }
        Set<String> relations = new LinkedHashSet<>();
        bundle.all("link").forEach(link -> link.valueOf("relation").ifPresent(relations::add));
        List<String> missing = NAVIGATION_LINKS.stream().filter(r -> !relations.contains(r)).toList();
        if (expected && !missing.isEmpty()) {
            throw new AssertionFailure("Expected the navigation links first, last and next; missing: "
                    + String.join(", ", missing) + " (links: " + relations + ")");
        }
        if (!expected && missing.isEmpty()) {
            throw new AssertionFailure("Expected the Bundle not to have all navigation links, but it has first, last"
                    + " and next");
        }
    }

    private static FhirElement element(Body body, String what) {
        if (body.isEmpty()) {
            throw new AssertionFailure(what + " is empty");
        }
        try {
            return FhirElement.of(body);
        } catch (IllegalArgumentException e) {
            throw new AssertionFailure(what + " is no FHIR resource: " + e.getMessage());
        }
    }

    private List<Object> evaluateFhirPath(Body body, String expression) {
        if (body.isEmpty()) {
            throw new AssertionFailure("The body is empty, expression '" + expression + "' cannot be evaluated");
        }
        try {
            return fhirPath.evaluate(body, expression);
        } catch (IllegalArgumentException e) {
            throw new TestScriptException("Expression '" + expression + "' cannot be evaluated: " + e.getMessage(), e);
        }
    }

    /**
     * The expected value: {@code value} (text), or the value found by {@code compareToSourceExpression} (with its
     * FHIRPath type) or {@code compareToSourcePath} (text).
     */
    private Object expected(Assertion assertion, Operator op) {
        if (assertion.value() != null) {
            return assertion.value();
        }
        if (assertion.compareToSourceId() != null) {
            Source compareTo = state.require(assertion.compareToSourceId(), "compareToSourceId");
            if (assertion.compareToSourceExpression() != null) {
                return Values.value(evaluateFhirPath(compareTo.body(), assertion.compareToSourceExpression()),
                        "compareToSourceExpression '" + assertion.compareToSourceExpression() + "'");
            }
            try {
                return Values.single(PathEvaluator.evaluate(compareTo.body(), assertion.compareToSourcePath()),
                        "compareToSourcePath '" + assertion.compareToSourcePath() + "'");
            } catch (IllegalArgumentException e) {
                throw new TestScriptException(e.getMessage(), e);
            }
        }
        if (Operators.needsValue(op)) {
            throw new TestScriptException("Operator " + op.code() + " needs a value or compareToSourceId");
        }
        return null;
    }

    private static void applyWithContext(Operator op, Object actual, Object expected, String what) {
        try {
            Operators.apply(op, actual, expected);
        } catch (AssertionFailure e) {
            throw new AssertionFailure("[" + what + "] " + e.getMessage());
        }
    }

    private static Source.Response response(Source source, String check) {
        if (source instanceof Source.Response response) {
            return response;
        }
        throw new TestScriptException(check + " can only be checked on a response, not on " + describe(source));
    }

    private static String resourceType(Body body) {
        if (body.isEmpty() || body.format() == ContentFormat.OTHER) {
            return null;
        }
        try {
            return ResourceContent.resourceType(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(Source source) {
        return switch (source) {
            case Source.Fixture f -> "fixture '" + f.id() + "'";
            case Source.Response r -> "the response of " + r.requestId();
            case Source.Request r -> "the request " + r.requestId();
        };
    }

    /** Profile validation is switched off; the assertion is reported as warning, not as pass. */
    static final class ValidationSkipped extends RuntimeException {
        ValidationSkipped(String message) {
            super(message);
        }
    }
}
