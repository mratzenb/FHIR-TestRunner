package at.hl7.testing.runner.core.report;

/**
 * The result of one operation or assertion.
 *
 * @param kind        {@code operation} or {@code assert}
 * @param description what was done, e.g. {@code responseCode in '200,201'}
 * @param outcome     the outcome; a failed {@code warningOnly} assertion passes with a warning
 * @param message     why it failed, or a warning; {@code null} if there is nothing to say
 * @param requestId   the request sent or checked, e.g. {@code REQ-0003}, or {@code null}
 * @param warning     whether {@code message} is a warning
 */
public record ActionResult(String kind, String description, Outcome outcome, String message, String requestId,
                           boolean warning) {

    public static final String OPERATION = "operation";
    public static final String ASSERT = "assert";
}
