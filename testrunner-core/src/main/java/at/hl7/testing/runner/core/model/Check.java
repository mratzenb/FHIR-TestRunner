package at.hl7.testing.runner.core.model;

/**
 * What an assertion checks. A FHIR assertion carries exactly one of these (invariants tst-5 and tst-6),
 * which is why they are modelled as alternatives of a sealed type rather than as optional fields.
 */
public sealed interface Check {

    /** {@code assert.contentType}: the {@code Content-Type} header. */
    record ContentType(String expected) implements Check {
    }

    /** {@code assert.expression}: a FHIRPath expression on the response or fixture. */
    record Expression(String expression) implements Check {
    }

    /** {@code assert.headerField}: a response header compared with {@code assert.value}. */
    record HeaderField(String name) implements Check {
    }

    /** {@code assert.minimumId}: the response contains at least the content of a fixture. */
    record MinimumId(String fixtureId) implements Check {
    }

    /** {@code assert.navigationLinks}: whether the Bundle has first, last and next links. */
    record NavigationLinks(boolean expected) implements Check {
    }

    /** {@code assert.path}: an XPath or JSONPath on the response or fixture. */
    record Path(String path) implements Check {
    }

    /** {@code assert.requestMethod}: the method of a request received by the system under test. */
    record RequestMethod(String method) implements Check {
    }

    /** {@code assert.requestURL}: the URL of a request received by the system under test. */
    record RequestUrl(String url) implements Check {
    }

    /** {@code assert.resource}: the resource type of the body. */
    record Resource(String type) implements Check {
    }

    /**
     * {@code assert.response}: the response status as code, e.g. {@code okay}.
     *
     * @param code   the code as written in the TestScript
     * @param status the HTTP status the code stands for in the FHIR version of the TestScript
     */
    record Response(String code, int status) implements Check {
    }

    /** {@code assert.responseCode}: the response status, a comma separated list for {@code in} and {@code notIn}. */
    record ResponseCode(String codes) implements Check {
    }

    /** {@code assert.validateProfileId}: the body conforms to a profile declared by the TestScript. */
    record ValidateProfile(String profileId) implements Check {
    }
}
