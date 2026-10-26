package at.hl7.testing.runner.core.model;

import java.util.List;

/**
 * Capabilities a TestScript expects of the server ({@code TestScript.metadata.capability}).
 *
 * <p>Absent values are {@code null}.
 *
 * @param required     whether the TestScript can only run on a server with these capabilities; if the server lacks
 *                     them, every test is skipped
 * @param validated    whether the TestScript itself tests these capabilities; informational for the runner
 * @param description  description for the log
 * @param capabilities canonical URL of the CapabilityStatement the server's CapabilityStatement must contain
 * @param links        links to the specification of the capabilities, for the log
 */
public record CapabilityRequirement(boolean required, boolean validated, String description, String capabilities,
                                    List<String> links) {

    public CapabilityRequirement {
        links = links == null ? List.of() : List.copyOf(links);
    }
}
