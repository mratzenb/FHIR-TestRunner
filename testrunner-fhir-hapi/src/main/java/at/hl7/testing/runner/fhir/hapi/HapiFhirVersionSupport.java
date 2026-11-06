package at.hl7.testing.runner.fhir.hapi;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.spi.FhirPathEvaluator;
import at.hl7.testing.runner.core.spi.FhirVersionSupport;
import at.hl7.testing.runner.core.spi.ReadCheck;
import at.hl7.testing.runner.core.spi.TestReportContent;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import at.hl7.testing.runner.core.spi.TestScriptRead;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import jakarta.annotation.Nonnull;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link FhirVersionSupport} on top of HAPI FHIR. A version module only supplies its {@link FhirContext} and its
 * {@link TestScriptDialect}; parsing, the mapping to the model, FHIRPath and writing TestReports are the same for all
 * versions.
 *
 * <p>The context must be one of its own ({@code FhirContext.forR4()}, not {@code forR4Cached()}): it is given a
 * {@link ModelOnlyValidationSupport}, which must not leak into contexts shared with other code.
 */
public abstract class HapiFhirVersionSupport implements FhirVersionSupport {

    private final FhirVersion version;
    private final FhirContext context;
    private final TestScriptDialect dialect;
    private final TestScriptMapper mapper;
    private final HapiFhirPathEvaluator fhirPath;
    private final TestReportEncoder testReports;

    protected HapiFhirVersionSupport(@Nonnull FhirVersion version, @Nonnull FhirContext context,
                                     @Nonnull TestScriptDialect dialect) {
        this.version = version;
        this.context = ModelOnlyValidationSupport.install(context);
        this.dialect = dialect;
        this.mapper = new TestScriptMapper(version, dialect);
        this.fhirPath = new HapiFhirPathEvaluator(context);
        this.testReports = new TestReportEncoder(context);
    }

    @Override
    @Nonnull
    public final FhirVersion version() {
        return version;
    }

    @Override
    @Nonnull
    public ReadCheck check(@Nonnull Body content) {
        return parse(content).check();
    }

    @Override
    @Nonnull
    public TestScriptRead readTestScript(@Nonnull Body content) {
        Parsed parsed = parse(content);
        if (!parsed.check().readable()) {
            throw new TestScriptFormatException("Not a valid FHIR " + version + " TestScript: "
                    + String.join("; ", parsed.check().errors()));
        }
        return new TestScriptRead(mapper.map(ElementView.of(context, parsed.resource())), parsed.check().warnings());
    }

    @Override
    @Nonnull
    public FhirPathEvaluator fhirPath() {
        return fhirPath;
    }

    @Override
    @Nonnull
    public Body testReport(@Nonnull TestReportContent content) {
        return testReports.encode(content);
    }

    private Parsed parse(Body content) {
        IParser parser = switch (content.format()) {
            case JSON -> context.newJsonParser();
            case XML -> context.newXmlParser();
            case OTHER -> null;
        };
        if (parser == null) {
            return new Parsed(null, new ReadCheck(List.of("the content is neither JSON nor XML"), List.of()));
        }
        CollectingErrorHandler handler = new CollectingErrorHandler();
        parser.setParserErrorHandler(handler);
        IBaseResource resource;
        try {
            resource = parser.parseResource(content.text());
        } catch (DataFormatException | IllegalArgumentException e) {
            return new Parsed(null, new ReadCheck(List.of(e.getMessage()), List.of()));
        }
        List<String> errors = new ArrayList<>(handler.errors);
        List<String> warnings = new ArrayList<>(handler.warnings);
        if (!"TestScript".equals(context.getResourceType(resource))) {
            errors.add("the content is a " + context.getResourceType(resource) + ", not a TestScript");
        } else {
            dialect.check(ElementView.of(context, resource), warnings);
        }
        return new Parsed(resource, new ReadCheck(errors, warnings));
    }

    private record Parsed(IBaseResource resource, ReadCheck check) {
    }
}
