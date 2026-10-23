package at.hl7.testing.runner.core.engine;

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
import java.util.List;
import java.util.Map;
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
        if ("request".equals(assertion.direction())) {
            throw new TestScriptException("Assertions on the request (direction request) need a test engine acting as"
                    + " server and are not supported");
        }
        Source source = assertion.sourceId() != null
                ? state.require(assertion.sourceId(), "sourceId")
                : state.lastResponse().orElseThrow(() -> new TestScriptException(
                        "There is no response to check: no operation has been executed yet"));
        String warning = switch (assertion.check()) {
            case Check.ContentType c -> {
                Operator op = operator(assertion, Operator.CONTAINS, Operator.CONTAINS, Operator.NOT_CONTAINS,
                        Operator.EQUALS, Operator.NOT_EQUALS);
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
                Operator op = operator(assertion, Operator.EQUALS, Operator.EQUALS, Operator.NOT_EQUALS, Operator.IN,
                        Operator.NOT_IN, Operator.GREATER_THAN, Operator.LESS_THAN);
                Operators.apply(op, String.valueOf(response(source, "responseCode").exchange().response().status()),
                        c.codes().replace(" ", ""));
                yield null;
            }
            case Check.Response c -> {
                Operator op = operator(assertion, Operator.EQUALS, Operator.EQUALS, Operator.NOT_EQUALS);
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
                Operator op = operator(assertion, Operator.EQUALS, Operator.EQUALS, Operator.NOT_EQUALS);
                Operators.apply(op, resourceType(source.body()), c.type());
                yield null;
            }
            case Check.HeaderField c -> {
                Operator op = operator(assertion, Operator.EQUALS, Operator.EQUALS, Operator.NOT_EQUALS, Operator.IN,
                        Operator.NOT_IN, Operator.GREATER_THAN, Operator.LESS_THAN, Operator.EMPTY,
                        Operator.NOT_EMPTY, Operator.CONTAINS, Operator.NOT_CONTAINS);
                String actual = source.headers().first(c.name()).orElse(null);
                Operators.apply(op, actual, expected(assertion, op));
                yield null;
            }
            case Check.Expression c -> {
                Operator op = operator(assertion, Operator.EVAL, Operator.values());
                List<Object> result = evaluateFhirPath(source.body(), c.expression());
                if (op == Operator.EVAL) {
                    Operators.apply(op, Values.single(result, "Expression '" + c.expression() + "'"), null);
                } else {
                    String actual = op == Operator.EMPTY || op == Operator.NOT_EMPTY
                            ? (result.isEmpty() ? null : String.valueOf(result.size()))
                            : Values.single(result, "Expression '" + c.expression() + "'");
                    applyWithContext(op, actual, expected(assertion, op), "expression '" + c.expression() + "'");
                }
                yield null;
            }
            case Check.Path c -> {
                Operator op = operator(assertion, Operator.EQUALS, Operator.EQUALS, Operator.NOT_EQUALS, Operator.IN,
                        Operator.NOT_IN, Operator.GREATER_THAN, Operator.LESS_THAN, Operator.EMPTY,
                        Operator.NOT_EMPTY, Operator.CONTAINS, Operator.NOT_CONTAINS);
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
            case Check.MinimumId c -> throw unsupported("minimumId");
            case Check.NavigationLinks c -> throw unsupported("navigationLinks");
            case Check.RequestMethod c -> throw unsupported("requestMethod");
            case Check.RequestUrl c -> throw unsupported("requestURL");
        };
        return new Evaluation(source, warning);
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

    /** The expected value: {@code value}, or the value found by {@code compareToSourceExpression/Path}. */
    private String expected(Assertion assertion, Operator op) {
        if (assertion.value() != null) {
            return assertion.value();
        }
        if (assertion.compareToSourceId() != null) {
            Source compareTo = state.require(assertion.compareToSourceId(), "compareToSourceId");
            if (assertion.compareToSourceExpression() != null) {
                return Values.single(evaluateFhirPath(compareTo.body(), assertion.compareToSourceExpression()),
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

    private static void applyWithContext(Operator op, String actual, String expected, String what) {
        try {
            Operators.apply(op, actual, expected);
        } catch (AssertionFailure e) {
            throw new AssertionFailure("[" + what + "] " + e.getMessage());
        }
    }

    private static Operator operator(Assertion assertion, Operator defaultOperator, Operator... allowed) {
        Operator op = assertion.operator() != null ? assertion.operator() : defaultOperator;
        for (Operator candidate : allowed) {
            if (candidate == op) {
                return op;
            }
        }
        throw new TestScriptException("Operator " + op.code() + " cannot be used with "
                + checkName(assertion.check()) + " (allowed: "
                + java.util.Arrays.stream(allowed).map(Operator::code).collect(Collectors.joining(", ")) + ")");
    }

    private static Source.Response response(Source source, String check) {
        if (source instanceof Source.Response response) {
            return response;
        }
        throw new TestScriptException(check + " can only be checked on a response, not on a fixture");
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

    private static TestScriptException unsupported(String check) {
        return new TestScriptException("Assertions with " + check + " are not supported yet");
    }

    static String checkName(Check check) {
        return switch (check) {
            case Check.ContentType c -> "contentType";
            case Check.Expression c -> "expression";
            case Check.HeaderField c -> "headerField";
            case Check.MinimumId c -> "minimumId";
            case Check.NavigationLinks c -> "navigationLinks";
            case Check.Path c -> "path";
            case Check.RequestMethod c -> "requestMethod";
            case Check.RequestUrl c -> "requestURL";
            case Check.Resource c -> "resource";
            case Check.Response c -> "response";
            case Check.ResponseCode c -> "responseCode";
            case Check.ValidateProfile c -> "validateProfileId";
        };
    }

    private static String describe(Source source) {
        return switch (source) {
            case Source.Fixture f -> "fixture '" + f.id() + "'";
            case Source.Response r -> "the response of " + r.requestId();
        };
    }

    /** Profile validation is switched off; the assertion is reported as warning, not as pass. */
    static final class ValidationSkipped extends RuntimeException {
        ValidationSkipped(String message) {
            super(message);
        }
    }
}
