package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Capabilities a TestScript expects of a server ({@code TestScript.metadata.capability}).
 *
 * <p>Absent values are {@code null}.
 *
 * @param required     whether the TestScript can only run on a server with these capabilities; if the server lacks
 *                     them, every test is skipped
 * @param validated    whether the TestScript itself tests these capabilities; informational for the runner
 * @param description  description for the log
 * @param capabilities canonical URL of the CapabilityStatement the server's CapabilityStatement must contain
 * @param links        links to the specification of the capabilities, for the log
 * @param destination  the {@code TestScript.destination} index of the server that must have them; {@code null} for
 *                     every server of the TestScript
 */
public record CapabilityRequirement(boolean required, boolean validated, @Nullable String description,
                                    @Nullable String capabilities, @Nonnull List<String> links,
                                    @Nullable Integer destination) {

    public CapabilityRequirement {
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** A requirement for every server of the TestScript. */
    public CapabilityRequirement(boolean required, boolean validated, @Nullable String description,
                                 @Nullable String capabilities, @Nonnull List<String> links) {
        this(required, validated, description, capabilities, links, null);
    }
}
