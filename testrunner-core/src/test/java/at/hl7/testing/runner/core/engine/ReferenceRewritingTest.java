package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.ReferenceRewriting;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.TestScriptResult;
import org.junit.jupiter.api.Test;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code execution.rewrite-references}: references in fixture resources to fixtures created on the server are
 * rewritten to the server's ids – a convenience of this runner, configurable for portable TestScripts.
 */
class ReferenceRewritingTest extends EngineTestBase {

    /** A Patient referencing {@code Organization/org-1}, both created by setup actions, the Organization first. */
    private void organizationThenPatientInTheSetup() {
        harness.resource("org", ORGANIZATION).resource("patient", PATIENT)
                .add("ts", script().fixture("org", false, false).fixture("patient", false, false)
                        .setup(op("create", "Organization", "org", null, null, null), responseCode("201"),
                                op("create", "Patient", "patient", null, null, null), responseCode("201"))
                        .test(test("t", op("read", "Patient", null, null, "/2", null), responseCode("200")))
                        .build());
    }

    private String managingOrganizationOnTheServer() {
        return server.resource("Patient/2").path("managingOrganization").path("reference").asText();
    }

    @Test
    void allRewritesReferencesToFixturesCreatedByOperations() {
        organizationThenPatientInTheSetup();

        TestScriptResult result = harness.rewriteReferences(ReferenceRewriting.ALL)
                .runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(managingOrganizationOnTheServer()).isEqualTo("Organization/1");
    }

    @Test
    void byDefaultOnlyReferencesToAutocreatedFixturesAreRewritten() {
        organizationThenPatientInTheSetup();

        harness.runOne();

        assertThat(managingOrganizationOnTheServer()).as("as written, like the specification")
                .isEqualTo("Organization/org-1");
    }

    @Test
    void allAlsoUsesTheIdOfAnUpdate() {
        harness.resource("org", ORGANIZATION).resource("patient", PATIENT)
                .add("ts", script().fixture("org", false, false).fixture("patient", false, false)
                        .test(test("t",
                                op("update", "Organization", "org", null, "/burgers", null),
                                op("create", "Patient", "patient", null, null, null), responseCode("201")))
                        .build());

        harness.rewriteReferences(ReferenceRewriting.ALL).runOne();

        assertThat(server.resource("Patient/1").path("managingOrganization").path("reference").asText())
                .isEqualTo("Organization/burgers");
    }

    @Test
    void noneSendsAutocreatedFixturesAsWrittenInDeclarationOrder() {
        harness.resource("patient", PATIENT).resource("org", ORGANIZATION)
                .add("ts", script().fixture("patient", true, false).fixture("org", true, false)
                        .test(test("t", op("read", "Patient", null, "patient", null, null), responseCode("200")))
                        .build());

        TestScriptResult result = harness.rewriteReferences(ReferenceRewriting.NONE)
                .runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).startsWith("POST /Patient", "POST /Organization");
        assertThat(server.resource("Patient/1").path("managingOrganization").path("reference").asText())
                .isEqualTo("Organization/org-1");
    }

    @Test
    void fixtureSentBeforeTheFixtureItReferencesIsAWarning() {
        harness.resource("org", ORGANIZATION).resource("patient", PATIENT)
                .add("ts", script().fixture("org", false, false).fixture("patient", false, false)
                        .setup(op("create", "Patient", "patient", null, null, null),
                                op("create", "Organization", "org", null, null, null))
                        .test(test("t", op("capabilities")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).as("a warning, the server may have the Organization already")
                .isEqualTo(Outcome.PASS);
        assertThat(phase(result, "Setup").messages()).singleElement().asString().isEqualTo("WARNING: Setup,"
                + " action 1 sends fixture 'patient', which references Organization/org-1 – but fixture 'org' is"
                + " only created by Setup, action 2; create it first, or give it autocreate");
    }

    @Test
    void noWarningWhenTheReferencedFixtureIsCreatedFirstOrAutocreated() {
        harness.resource("org", ORGANIZATION).resource("patient", PATIENT)
                .add("ts", script().fixture("org", true, false).fixture("patient", false, false)
                        .test(test("t", op("create", "Patient", "patient", null, null, null)))
                        .build());

        assertThat(phase(harness.runOne(), "Setup").messages()).isEmpty();
    }
}
