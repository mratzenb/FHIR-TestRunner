package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.content.FhirElement;
import at.hl7.testing.runner.core.http.Exchange;
import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.http.FhirRequest;
import at.hl7.testing.runner.core.http.HttpHeaders;
import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.CapabilityRequirement;
import at.hl7.testing.runner.core.model.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks the {@code metadata.capability} of a TestScript against the CapabilityStatement of the server, before the
 * setup runs.
 *
 * <p>A capability with {@code required = true} names a CapabilityStatement ({@code capabilities}) whose content the
 * server's CapabilityStatement must contain; if it does not, the TestScript cannot run on that server and every test
 * is skipped. The comparison is {@link FhirElement#notContainedIn} restricted to what describes the RESTful
 * interface ({@link #COMPARED}): a server is not expected to share the name, status or date of the CapabilityStatement
 * of a TestScript author.
 *
 * <p>The required CapabilityStatement comes from the suite's Example Instances (loaded with the TestScript); if it is
 * not there, the requirement cannot be checked, which is a warning, not a reason to skip. The server's
 * CapabilityStatement is fetched once per server and run ({@code GET [base]/metadata}, in the communication log of
 * the first TestScript that needs it) and shared by all TestScripts of the run, also when they run in parallel.
 */
final class CapabilityCheck {

    private static final Logger log = LoggerFactory.getLogger(CapabilityCheck.class);

    /** The elements of a CapabilityStatement compared; together they describe what the server supports. */
    static final Set<String> COMPARED = Set.of("fhirVersion", "format", "patchFormat", "implementationGuide",
            "rest.mode", "rest.resource.type", "rest.resource.supportedProfile", "rest.resource.interaction.code",
            "rest.resource.versioning", "rest.resource.readHistory", "rest.resource.updateCreate",
            "rest.resource.conditionalCreate", "rest.resource.conditionalRead", "rest.resource.conditionalUpdate",
            "rest.resource.conditionalDelete", "rest.resource.searchParam.name", "rest.resource.searchParam.type",
            "rest.resource.operation.name", "rest.interaction.code", "rest.searchParam.name", "rest.operation.name");

    /** The CapabilityStatement of each server, by base URL; shared by the TestScripts of a run. */
    private final Map<String, ServerStatement> serverStatements = new ConcurrentHashMap<>();

    /**
     * What {@code GET [base]/metadata} returned.
     *
     * @param requestId the request, to point to it in the communication log
     * @param statement the CapabilityStatement, {@code null} if the server returned none
     */
    private record ServerStatement(String requestId, FhirElement statement) {
    }

    /**
     * The result of the check.
     *
     * @param unmet    why the server does not meet a required capability; empty if it meets all
     * @param warnings requirements that could not be checked
     */
    record Result(List<String> unmet, List<String> warnings) {
    }

    /**
     * Checks the requirements of one TestScript.
     *
     * @param statements the CapabilityStatements the requirements name, by canonical URL, as far as they were found
     * @param http       the client of the TestScript, so the request appears in its communication log
     * @param base       the base URL of the server
     * @throws FhirHttpClient.ExchangeFailedException if the server did not answer {@code GET [base]/metadata}
     */
    Result check(List<CapabilityRequirement> requirements, Map<String, Body> statements, FhirHttpClient http,
                 String base) {
        List<String> unmet = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (CapabilityRequirement requirement : requirements) {
            if (!requirement.required()) {
                continue;
            }
            String name = name(requirement);
            if (requirement.capabilities() == null) {
                warnings.add("Required capability " + name + " names no CapabilityStatement and was not checked");
                continue;
            }
            Body required = statements.get(requirement.capabilities());
            if (required == null) {
                warnings.add("Required capability " + name + " was not checked: CapabilityStatement "
                        + requirement.capabilities() + " is not among the Example Instances");
                continue;
            }
            ServerStatement server = serverStatements.computeIfAbsent(base, b -> fetch(http, b));
            if (server.statement() == null) {
                warnings.add("Required capability " + name + " was not checked: the server returned no"
                        + " CapabilityStatement [" + server.requestId() + "]");
                continue;
            }
            List<String> missing;
            try {
                missing = FhirElement.of(required).only(COMPARED).notContainedIn(server.statement(), "CapabilityStatement");
            } catch (IllegalArgumentException e) {
                warnings.add("Required capability " + name + " was not checked: " + requirement.capabilities()
                        + " cannot be read: " + e.getMessage());
                continue;
            }
            if (!missing.isEmpty()) {
                unmet.add("The server [" + server.requestId() + "] does not support required capability " + name
                        + " (" + requirement.capabilities() + "):\n  " + String.join("\n  ", missing));
            } else {
                log.info("The server supports required capability {} [{}]", name, server.requestId());
            }
        }
        return new Result(unmet, warnings);
    }

    private static ServerStatement fetch(FhirHttpClient http, String base) {
        Exchange exchange = http.send(new FhirRequest(HttpMethod.GET, URI.create(base + "/metadata"),
                HttpHeaders.of(Map.of("Accept", List.of("application/fhir+json"))), null));
        if (!exchange.response().isSuccessful()) {
            log.warn("GET [base]/metadata returned HTTP {} [{}]", exchange.response().status(), exchange.requestId());
            return new ServerStatement(exchange.requestId(), null);
        }
        try {
            FhirElement statement = FhirElement.of(exchange.response().asBody());
            boolean capabilityStatement = statement.valueOf("resourceType").filter("CapabilityStatement"::equals).isPresent();
            return new ServerStatement(exchange.requestId(), capabilityStatement ? statement : null);
        } catch (IllegalArgumentException e) {
            log.warn("The CapabilityStatement of the server cannot be read [{}]: {}", exchange.requestId(), e.getMessage());
            return new ServerStatement(exchange.requestId(), null);
        }
    }

    private static String name(CapabilityRequirement requirement) {
        return requirement.description() != null ? "'" + requirement.description() + "'" : requirement.capabilities();
    }
}
