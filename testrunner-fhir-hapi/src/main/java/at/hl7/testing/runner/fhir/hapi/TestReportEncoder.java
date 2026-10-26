package at.hl7.testing.runner.fhir.hapi;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.spi.TestReportContent;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.util.FhirTerser;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.util.List;

/**
 * Builds the {@code TestReport} resource of any FHIR version from {@link TestReportContent}, by element name like
 * {@link TestScriptMapper} reads TestScripts, so it exists once for all versions.
 *
 * <p>The one difference between R4 and R5 is a type difference, absorbed here: {@code TestReport.testScript} is a
 * {@code Reference} in R4 and a {@code canonical} in R5.
 */
final class TestReportEncoder {

    private final FhirContext context;

    TestReportEncoder(FhirContext context) {
        this.context = context;
    }

    Body encode(TestReportContent content) {
        FhirTerser terser = context.newTerser();
        IBaseResource report = context.getResourceDefinition("TestReport").newInstance();
        report.setId(content.id());
        terser.setElement(report, "identifier.system", "urn:ietf:rfc:3986");
        terser.setElement(report, "identifier.value", "urn:testrunner:" + content.identifier());
        terser.setElement(report, "name", content.name());
        terser.setElement(report, "status", "completed");
        IBase testScript = terser.addElement(report, "testScript");
        if (testScript instanceof IPrimitiveType<?> canonical) {
            canonical.setValueAsString(content.testScript());
        } else {
            terser.setElement(testScript, "reference", content.testScript());
            terser.setElement(testScript, "display", content.name());
        }
        terser.setElement(report, "result", content.result());
        if (content.score() != null) {
            terser.setElement(report, "score", content.score().toPlainString());
        }
        terser.setElement(report, "tester", content.tester());
        terser.setElement(report, "issued", content.issued().toString());
        if (content.server() != null) {
            IBase participant = terser.addElement(report, "participant");
            terser.setElement(participant, "type", "server");
            terser.setElement(participant, "uri", content.server());
        }
        if (!content.setup().isEmpty()) {
            actions(terser, terser.addElement(report, "setup"), content.setup(), true);
        }
        for (TestReportContent.Test test : content.tests()) {
            IBase element = terser.addElement(report, "test");
            terser.setElement(element, "name", test.name());
            actions(terser, element, test.actions(), true);
        }
        if (!content.teardown().isEmpty()) {
            actions(terser, terser.addElement(report, "teardown"), content.teardown(), false);
        }
        return Body.json(context.newJsonParser().setPrettyPrint(true).encodeResourceToString(report));
    }

    /**
     * Adds the actions to a setup, test or teardown.
     *
     * @param asserts whether the parent can hold assertions; a teardown has operations only
     */
    private static void actions(FhirTerser terser, IBase parent, List<TestReportContent.Action> actions,
                                boolean asserts) {
        for (TestReportContent.Action action : actions) {
            IBase element = terser.addElement(parent, "action");
            IBase result = terser.addElement(element, action.assertion() && asserts ? "assert" : "operation");
            terser.setElement(result, "result", action.result());
            if (action.message() != null) {
                terser.setElement(result, "message", action.message());
            }
            if (action.detail() != null) {
                terser.setElement(result, "detail", action.detail());
            }
        }
    }
}
