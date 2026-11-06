package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * A server a TestScript sends requests to ({@code TestScript.destination}).
 *
 * <p>The runner sends every request to the one FHIR server configured for the suite, so a TestScript may declare at
 * most one destination. Origins are not modelled: the runner plays every client ({@code TestScript.origin}) itself.
 *
 * @param index   the number operations refer to ({@code operation.destination})
 * @param profile the code of the profile, e.g. {@code FHIR-Server}, may be {@code null}
 */
public record Destination(int index, @Nullable String profile) {

    /** e.g. {@code 1 (FHIR-Server)} */
    @Override
    @Nonnull
    public String toString() {
        return profile == null ? String.valueOf(index) : index + " (" + profile + ")";
    }
}
