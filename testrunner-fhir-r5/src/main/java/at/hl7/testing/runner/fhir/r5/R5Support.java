package at.hl7.testing.runner.fhir.r5;

import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.fhir.hapi.HapiFhirVersionSupport;
import ca.uhn.fhir.context.FhirContext;

/** FHIR R5 (5.0.0). Registered through {@code META-INF/services}. */
public final class R5Support extends HapiFhirVersionSupport {

    public R5Support() {
        super(FhirVersion.R5, FhirContext.forR5(), new R5Dialect());
    }
}
