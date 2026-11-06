package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.HttpMethod;
import at.hl7.testing.runner.core.model.Operation;
import at.hl7.testing.runner.core.model.OperationType;
import at.hl7.testing.runner.core.model.Operator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The rules an operation or an assertion must follow whatever values its variables have: the operators a check
 * allows, and the combinations of type, method, URL parts and ids an operation can be built from.
 *
 * <p>Every rule here depends only on which elements an action has, never on their values, so a TestScript breaking
 * one fails in every run. {@link TestScriptChecks} therefore applies them before anything is sent; {@link
 * AssertionEvaluator} and {@link RequestFactory} use the same rules when an action runs.
 */
final class ActionRules {

    /** The operators that compare the value found with an expected value, for headers and paths. */
    private static final List<Operator> COMPARISONS = List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.IN,
            Operator.NOT_IN, Operator.GREATER_THAN, Operator.LESS_THAN, Operator.EMPTY, Operator.NOT_EMPTY,
            Operator.CONTAINS, Operator.NOT_CONTAINS);

    private ActionRules() {
    }

    /** The operator a check uses when the assertion names none. */
    static Operator defaultOperator(Check check) {
        return switch (check) {
            case Check.ContentType c -> Operator.CONTAINS;
            case Check.Expression c -> Operator.EVAL;
            case Check.HeaderField c -> Operator.EQUALS;
            case Check.MinimumId c -> Operator.EQUALS;
            case Check.NavigationLinks c -> Operator.EQUALS;
            case Check.Path c -> Operator.EQUALS;
            case Check.RequestMethod c -> Operator.EQUALS;
            case Check.RequestUrl c -> Operator.EQUALS;
            case Check.Resource c -> Operator.EQUALS;
            case Check.Response c -> Operator.EQUALS;
            case Check.ResponseCode c -> Operator.EQUALS;
            case Check.ValidateProfile c -> Operator.EQUALS;
        };
    }

    /**
     * The operators a check can be used with; empty for the checks that ignore the operator
     * ({@code validateProfileId}) and those the runner does not support ({@code requestMethod}, {@code requestURL}).
     */
    static List<Operator> allowedOperators(Check check) {
        return switch (check) {
            case Check.ContentType c -> List.of(Operator.CONTAINS, Operator.NOT_CONTAINS, Operator.EQUALS,
                    Operator.NOT_EQUALS);
            case Check.Expression c -> List.of(Operator.values());
            case Check.HeaderField c -> COMPARISONS;
            case Check.MinimumId c -> List.of(Operator.EQUALS);
            case Check.NavigationLinks c -> List.of(Operator.EQUALS);
            case Check.Path c -> COMPARISONS;
            case Check.RequestMethod c -> List.of();
            case Check.RequestUrl c -> List.of();
            case Check.Resource c -> List.of(Operator.EQUALS, Operator.NOT_EQUALS);
            case Check.Response c -> List.of(Operator.EQUALS, Operator.NOT_EQUALS);
            case Check.ResponseCode c -> List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.IN, Operator.NOT_IN,
                    Operator.GREATER_THAN, Operator.LESS_THAN);
            case Check.ValidateProfile c -> List.of();
        };
    }

    /**
     * The operator of an assertion: the one it names, else the default of its check.
     *
     * @throws TestScriptException if the check cannot be used with that operator
     */
    static Operator operator(Assertion assertion) {
        Check check = assertion.check();
        Operator op = assertion.operator() != null ? assertion.operator() : defaultOperator(check);
        List<Operator> allowed = allowedOperators(check);
        if (!allowed.isEmpty() && !allowed.contains(op)) {
            throw new TestScriptException("Operator " + op.code() + " cannot be used with " + checkName(check)
                    + " (allowed: " + allowed.stream().map(Operator::code).collect(Collectors.joining(", ")) + ")");
        }
        return op;
    }

    /**
     * Whether a check compares with the expected value of {@code value} or {@code compareToSourceId}. The others
     * carry what they expect themselves, e.g. {@code responseCode} its codes.
     */
    static boolean comparesWithValue(Check check) {
        return check instanceof Check.HeaderField || check instanceof Check.Path || check instanceof Check.Expression;
    }

    /**
     * Whether the runner can evaluate a check. {@code requestMethod} and {@code requestURL} check a request that the
     * system under test received – they test a client, and the runner is the client itself.
     */
    static boolean supported(Check check) {
        return switch (check) {
            case Check.ContentType c -> true;
            case Check.Expression c -> true;
            case Check.HeaderField c -> true;
            case Check.MinimumId c -> true;
            case Check.NavigationLinks c -> true;
            case Check.Path c -> true;
            case Check.RequestMethod c -> false;
            case Check.RequestUrl c -> false;
            case Check.Resource c -> true;
            case Check.Response c -> true;
            case Check.ResponseCode c -> true;
            case Check.ValidateProfile c -> true;
        };
    }

    /**
     * Why the runner cannot evaluate an assertion, whatever its values: its check is not {@link #supported}, or its
     * operator is {@code manualEval}, which needs a human tester. Such an assertion is an {@code error} when it runs, as
     * documented; {@link TestScriptChecks#unsupportedWarnings} announces it before anything is sent.
     *
     * @return the reason, or {@code null} if the runner can evaluate the assertion
     */
    static String unsupported(Assertion assertion) {
        if (!supported(assertion.check())) {
            return checkName(assertion.check()) + " is not supported – it checks a request received by the system"
                    + " under test, and the runner sends every request itself";
        }
        if (assertion.operator() == Operator.MANUAL_EVAL) {
            return "Operator manualEval is not supported – it needs a human tester";
        }
        return null;
    }

    /**
     * Why an assertion cannot be evaluated in any run: its check does not allow its operator, or the operator needs an
     * expected value and the assertion has none.
     *
     * @return the problems, empty if there are none
     */
    static List<String> problems(Assertion assertion) {
        if (unsupported(assertion) != null) {
            return List.of(); // a warning before the run, an error when it runs, whatever its operator
        }
        Operator op;
        try {
            op = operator(assertion);
        } catch (TestScriptException e) {
            return List.of(e.getMessage());
        }
        if (comparesWithValue(assertion.check()) && Operators.needsValue(op) && assertion.value() == null
                && assertion.compareToSourceId() == null) {
            return List.of("Operator " + op.code() + " needs a value or compareToSourceId");
        }
        return List.of();
    }

    /**
     * Why an operation cannot be built in any run: an element its type forbids, no method, nothing to send, or no way
     * to determine the URL.
     *
     * @return the problems, empty if there are none
     */
    static List<String> problems(Operation operation) {
        List<String> problems = new ArrayList<>();
        OperationType type = operation.type();
        if (operation.targetId() != null && (type == OperationType.SEARCH || type == OperationType.CREATE)) {
            problems.add(typeName(operation) + " must not have a targetId");
        }
        if (operation.params() != null && (type == OperationType.CREATE || type == OperationType.TRANSACTION
                || type == OperationType.BATCH)) {
            problems.add(typeName(operation) + " must not have params");
        }
        HttpMethod method = method(operation);
        if (method == null) {
            problems.add("The HTTP method cannot be determined: the operation has no method and "
                    + (type == null ? "no type" : "the unknown type '" + operation.typeCode() + "'"));
        }
        boolean nothingToSend = method != null && method.sendsBody() && operation.sourceId() == null
                && !sendsWithoutSource(type);
        if (nothingToSend) {
            problems.add("The " + method + " of operation '" + typeName(operation) + "' has no sourceId, so there is"
                    + " nothing to send");
        }
        if (operation.url() == null && operation.params() != null) {
            if (operation.resource() == null && needsResource(type)) {
                problems.add("Operation '" + typeName(operation) + "' with params needs a resource type");
            }
        } else if (operation.url() == null && type == null) {
            problems.add("The URL cannot be determined: the operation has no url, params or type");
        } else if (operation.url() == null && !fixedUrl(type) && operation.targetId() == null
                && operation.resource() == null && operation.sourceId() == null && !nothingToSend) {
            // with a sourceId, the resource type comes from the body
            problems.add("The URL cannot be determined for operation '" + typeName(operation) + "': it has no"
                    + " resource, params, url or targetId");
        }
        return problems;
    }

    /** The method of an operation: the one it names, else the default of its type; {@code null} if neither is known. */
    static HttpMethod method(Operation operation) {
        if (operation.method() != null) {
            return operation.method();
        }
        return operation.type() == null ? null : operation.type().defaultMethod().orElse(null);
    }

    /** Whether an operation of this type sends without {@code sourceId}: its parameters are in the URL. */
    static boolean sendsWithoutSource(OperationType type) {
        return type == OperationType.SEARCH || type == OperationType.OPERATION || type == OperationType.CAPABILITIES;
    }

    /** Whether the URL of an operation of this type needs no resource type: {@code [base]} or {@code [base]/metadata}. */
    static boolean fixedUrl(OperationType type) {
        return type == OperationType.TRANSACTION || type == OperationType.BATCH || type == OperationType.CAPABILITIES;
    }

    /** Whether an operation of this type with {@code params} needs a resource type for {@code [base]/[type][params]}. */
    static boolean needsResource(OperationType type) {
        return type == OperationType.READ || type == OperationType.VREAD || type == OperationType.UPDATE
                || type == OperationType.DELETE || type == OperationType.PATCH;
    }

    /** The type code of an operation for messages, e.g. {@code create}. */
    static String typeName(Operation operation) {
        return operation.typeCode() != null ? operation.typeCode() : "operation";
    }

    /** The name of a check as written in a TestScript, e.g. {@code validateProfileId}. */
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
}
