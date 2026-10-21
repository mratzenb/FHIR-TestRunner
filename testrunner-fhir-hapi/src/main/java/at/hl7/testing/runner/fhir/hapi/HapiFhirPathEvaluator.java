package at.hl7.testing.runner.fhir.hapi;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.spi.FhirPathEvaluator;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FHIRPath with HAPI's engine for the {@link FhirContext} of one version. Works on JSON and XML alike, since both
 * are parsed into the same model.
 *
 * <p>Compatibility: {@code resourceType} is no element of the FHIR model, so {@code Patient.resourceType} is empty
 * in a spec-conformant engine such as HAPI's. JSON-based engines (fhirpath.js, the fhirpathpy of the Python proof of
 * concept) return the JSON property instead, and existing TestScripts rely on that. The step {@code .resourceType}
 * is therefore evaluated as {@code .type().name}, which yields the same value in every engine.
 */
public final class HapiFhirPathEvaluator implements FhirPathEvaluator {

    private static final Pattern RESOURCE_TYPE_STEP = Pattern.compile("\\.resourceType\\b(?!\\s*\\()");

    private final FhirContext context;

    public HapiFhirPathEvaluator(FhirContext context) {
        this.context = context;
    }

    @Override
    public List<Object> evaluate(Body body, String expression) {
        IBaseResource resource = parse(body);
        IFhirPath fhirPath = context.newFhirPath();
        List<IBase> values;
        try {
            values = fhirPath.evaluate(resource, compatible(expression), IBase.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("FHIRPath '" + expression + "': " + e.getMessage(), e);
        }
        List<Object> result = new ArrayList<>(values.size());
        for (IBase value : values) {
            if (value instanceof IPrimitiveType<?> primitive) {
                Object v = primitive.getValue();
                result.add(v instanceof Boolean || v instanceof Number ? v : primitive.getValueAsString());
            } else {
                result.add(context.newJsonParser().encodeToString(value));
            }
        }
        return result;
    }

    static String compatible(String expression) {
        return RESOURCE_TYPE_STEP.matcher(expression).replaceAll(".type().name");
    }

    private IBaseResource parse(Body body) {
        IParser parser = switch (body.format()) {
            case JSON -> context.newJsonParser();
            case XML -> context.newXmlParser();
            case OTHER -> throw new IllegalArgumentException("FHIRPath needs a FHIR resource in JSON or XML");
        };
        parser.setParserErrorHandler(new LenientErrorHandler(false));
        try {
            return parser.parseResource(body.text());
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("The content is no FHIR " + context.getVersion().getVersion().getFhirVersionString()
                    + " resource: " + e.getMessage(), e);
        }
    }
}
