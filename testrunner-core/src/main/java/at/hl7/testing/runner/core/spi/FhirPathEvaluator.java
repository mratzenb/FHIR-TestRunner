package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.Body;
import jakarta.annotation.Nonnull;

import java.util.List;

/** Evaluates FHIRPath expressions on resources of one FHIR version. Implementations must be thread-safe. */
public interface FhirPathEvaluator {

    /**
     * Evaluates {@code expression} on the resource in {@code resource} (JSON or XML).
     *
     * @return the result collection; primitives as {@link Boolean}, {@link java.math.BigDecimal} or {@link Integer},
     *         {@code date}, {@code dateTime}, {@code instant} and {@code time} as
     *         {@link at.hl7.testing.runner.core.model.FhirTemporal} (precision and offset as written), others as
     *         {@link String}; other elements as their JSON representation
     * @throws IllegalArgumentException if the body is no resource of this version or the expression is invalid
     */
    @Nonnull
    List<Object> evaluate(@Nonnull Body resource, @Nonnull String expression);
}
