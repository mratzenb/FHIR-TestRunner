package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.spi.FhirPathEvaluator;
import at.hl7.testing.runner.core.spi.FhirVersionSupport;
import at.hl7.testing.runner.core.spi.ReadCheck;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import at.hl7.testing.runner.core.spi.TestScriptRead;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A {@link FhirVersionSupport} for core tests that hands out prepared models instead of parsing. */
final class StubVersionSupport implements FhirVersionSupport {

    private final Function<Body, TestScript> scripts;

    StubVersionSupport(Function<Body, TestScript> scripts) {
        this.scripts = scripts;
    }

    /** Reads a TestScript file whose content is the key of {@code scripts}. */
    static StubVersionSupport of(Map<String, TestScript> scripts) {
        return new StubVersionSupport(body -> scripts.get(body.text().trim()));
    }

    @Override
    public FhirVersion version() {
        return FhirVersion.R4;
    }

    @Override
    public ReadCheck check(Body content) {
        return scripts.apply(content) != null ? new ReadCheck(List.of(), List.of())
                : new ReadCheck(List.of("unknown script"), List.of());
    }

    /** @throws TestScriptFormatException for content that is no prepared model, like a file that is no TestScript */
    @Override
    public TestScriptRead readTestScript(Body content) {
        TestScript testScript = scripts.apply(content);
        if (testScript == null) {
            throw new TestScriptFormatException("'" + content.text().trim() + "' is no TestScript");
        }
        return new TestScriptRead(testScript, List.of());
    }

    @Override
    public FhirPathEvaluator fhirPath() {
        return new StubFhirPath();
    }
}
