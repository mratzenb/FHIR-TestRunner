package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.Body;

import java.util.List;

/** Evaluates FHIRPath expressions on resources of one FHIR version. Implementations must be thread-safe. */
public interface FhirPathEvaluator {

    /**
     * Evaluates {@code expression} on the resource in {@code resource} (JSON or XML).
     *
     * @return the result collection; primitives as {@link String}, {@link Boolean}, {@link java.math.BigDecimal} or
     *         {@link Integer}, other elements as their JSON representation
     * @throws IllegalArgumentException if the body is no resource of this version or the expression is invalid
     */
    List<Object> evaluate(Body resource, String expression);
}
