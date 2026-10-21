package at.hl7.testing.runner.fhir.r4;

import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.fhir.hapi.HapiFhirVersionSupport;
import ca.uhn.fhir.context.FhirContext;

/** FHIR R4 (4.0.1). Registered through {@code META-INF/services}. */
public final class R4Support extends HapiFhirVersionSupport {

    public R4Support() {
        super(FhirVersion.R4, FhirContext.forR4(), new R4Dialect());
    }
}
