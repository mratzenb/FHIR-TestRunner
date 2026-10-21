package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Assertion;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Operation;

import java.util.ArrayList;
import java.util.List;

/** Short descriptions of actions for the log and the reports. */
final class Describe {

    private Describe() {
    }

    /** e.g. {@code create Patient (POST, sourceId patient-create, responseId create-response)} */
    static String operation(Operation operation, String method) {
        String type = operation.typeCode() != null ? operation.typeCode() : "operation";
        String text = (type + " " + (operation.resource() == null ? "" : operation.resource())).trim();
        List<String> details = new ArrayList<>();
        details.add(method);
        add(details, "sourceId", operation.sourceId());
        add(details, "targetId", operation.targetId());
        add(details, "params", operation.params());
        add(details, "responseId", operation.responseId());
        return text + " (" + String.join(", ", details) + ")";
    }

    /** e.g. {@code responseCode in '200,201'} or {@code expression 'Patient.id' equals 'abc' on read-response} */
    static String assertion(Assertion assertion) {
        String operator = assertion.operator() == null ? null : assertion.operator().code();
        String text = switch (assertion.check()) {
            case Check.ResponseCode c -> join("responseCode", operator, quote(c.codes()));
            case Check.Response c -> join("response", operator, quote(c.code()));
            case Check.ContentType c -> join("contentType", operator, quote(c.expected()));
            case Check.Resource c -> join("resource", operator, quote(c.type()));
            case Check.ValidateProfile c -> "validateProfileId " + quote(c.profileId());
            case Check.HeaderField c -> join("headerField " + quote(c.name()), operator, expected(assertion));
            case Check.Expression c -> join("expression " + quote(c.expression()), operator, expected(assertion));
            case Check.Path c -> join("path " + quote(c.path()), operator, expected(assertion));
            case Check.MinimumId c -> "minimumId " + quote(c.fixtureId());
            case Check.NavigationLinks c -> "navigationLinks " + c.expected();
            case Check.RequestMethod c -> "requestMethod " + quote(c.method());
            case Check.RequestUrl c -> "requestURL " + quote(c.url());
        };
        return assertion.sourceId() != null ? text + " on " + assertion.sourceId() : text;
    }

    private static String expected(Assertion assertion) {
        if (assertion.value() != null) {
            return quote(assertion.value());
        }
        return assertion.compareToSourceId();
    }

    private static String join(String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part);
            }
        }
        return String.join(" ", present);
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }

    private static void add(List<String> details, String name, String value) {
        if (value != null) {
            details.add(name + " " + value);
        }
    }
}
