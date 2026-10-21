package at.hl7.testing.runner.fhir.hapi;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.ArrayList;
import java.util.List;

/**
 * A validation support that provides no conformance resources.
 *
 * <p>HAPI's FHIRPath engines ask the {@link FhirContext} for a validation support. The default one loads the
 * complete specification, which for R5 and later is only available as NPM package (hapi-fhir-validation plus
 * 24 MB of resources). The runner evaluates FHIRPath on concrete resources, which needs the model alone; type
 * names ({@code Patient.id}, {@code ofType(Patient)}) are then matched by name. Profile validation is the job of
 * the {@link at.hl7.testing.runner.core.spi.ProfileValidator}, not of this support.
 */
final class ModelOnlyValidationSupport implements IValidationSupport {

    private final FhirContext context;

    ModelOnlyValidationSupport(FhirContext context) {
        this.context = context;
    }

    @Override
    public FhirContext getFhirContext() {
        return context;
    }

    @Override
    public String getName() {
        return "testrunner model-only support";
    }

    // HAPI's worker contexts iterate these lists; the interface defaults return null

    @Override
    public List<IBaseResource> fetchAllConformanceResources() {
        return new ArrayList<>();
    }

    @Override
    public <T extends IBaseResource> List<T> fetchAllStructureDefinitions() {
        return new ArrayList<>();
    }

    @Override
    public <T extends IBaseResource> List<T> fetchAllNonBaseStructureDefinitions() {
        return new ArrayList<>();
    }

    @Override
    public <T extends IBaseResource> List<T> fetchAllSearchParameters() {
        return new ArrayList<>();
    }

    /** Equips a context of its own (never a shared, cached one) with this support. */
    static FhirContext install(FhirContext context) {
        context.setValidationSupport(new ModelOnlyValidationSupport(context));
        return context;
    }
}
