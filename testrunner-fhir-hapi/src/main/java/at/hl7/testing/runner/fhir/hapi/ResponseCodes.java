package at.hl7.testing.runner.fhir.hapi;

import java.util.Map;

/**
 * The HTTP status of every {@code assert.response} code. R4 and R5 use different code lists (R4 {@code bad},
 * {@code unprocessable}; R5 {@code badRequest}, {@code unprocessableContent} and many more), but no code means
 * different statuses in different versions, so one table serves all. Whether a code is allowed in a version is
 * checked by HAPI when the TestScript is parsed.
 */
final class ResponseCodes {

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry("continue", 100), Map.entry("switchingProtocols", 101),
            Map.entry("okay", 200), Map.entry("created", 201), Map.entry("accepted", 202),
            Map.entry("nonAuthoritativeInformation", 203), Map.entry("noContent", 204),
            Map.entry("resetContent", 205), Map.entry("partialContent", 206),
            Map.entry("multipleChoices", 300), Map.entry("movedPermanently", 301), Map.entry("found", 302),
            Map.entry("seeOther", 303), Map.entry("notModified", 304), Map.entry("useProxy", 305),
            Map.entry("temporaryRedirect", 307), Map.entry("permanentRedirect", 308),
            Map.entry("bad", 400), Map.entry("badRequest", 400), Map.entry("unauthorized", 401),
            Map.entry("paymentRequired", 402), Map.entry("forbidden", 403), Map.entry("notFound", 404),
            Map.entry("methodNotAllowed", 405), Map.entry("notAcceptable", 406),
            Map.entry("proxyAuthenticationRequired", 407), Map.entry("requestTimeout", 408),
            Map.entry("conflict", 409), Map.entry("gone", 410), Map.entry("lengthRequired", 411),
            Map.entry("preconditionFailed", 412), Map.entry("contentTooLarge", 413), Map.entry("uriTooLong", 414),
            Map.entry("unsupportedMediaType", 415), Map.entry("rangeNotSatisfiable", 416),
            Map.entry("expectationFailed", 417), Map.entry("misdirectedRequest", 421),
            Map.entry("unprocessable", 422), Map.entry("unprocessableContent", 422),
            Map.entry("upgradeRequired", 426), Map.entry("internalServerError", 500),
            Map.entry("notImplemented", 501), Map.entry("badGateway", 502), Map.entry("serviceUnavailable", 503),
            Map.entry("gatewayTimeout", 504), Map.entry("httpVersionNotSupported", 505));

    private ResponseCodes() {
    }

    /** The status for a code, or {@code null} if the code is unknown. */
    static Integer status(String code) {
        return STATUS.get(code);
    }
}
