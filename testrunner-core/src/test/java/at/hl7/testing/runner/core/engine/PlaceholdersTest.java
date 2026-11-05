package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.VariableDefinition;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static at.hl7.testing.runner.core.engine.Scripts.check;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code ${…}} placeholders: variables in actions and in fixture resources, and the runner's own values. */
class PlaceholdersTest extends EngineTestBase {

    @Test
    void variablesWithDefaultValueAndHeaderField() {
        harness.add("ts", script()
                .variable(new VariableDefinition("type", "Patient", null, null, null, null, null))
                .variable(new VariableDefinition("ctype", null, null, null, "Content-Type", "caps", null))
                .test(test("t",
                        op("capabilities", null, null, null, null, "caps"),
                        op("search", "${type}", null, null, null, null),
                        check(new Check.ContentType("${ctype}"), Operator.EQUALS, null)))
                .build());

        TestScriptResult result = harness.runOne();

        assertThat(server.requestLines()).containsExactly("GET /metadata", "GET /Patient");
        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void fixturesGetAnIdentifierOfTheirOwnPerExecution() {
        String patient = """
                {"resourceType":"Patient","identifier":[{"system":"urn:test","value":"${testrunner.testScriptId}"}],
                 "name":[{"family":"${family}"}]}""";
        for (String name : List.of("a", "b")) {
            harness.resource("p-" + name, patient)
                    .add(name, script().fixture("p-" + name, true, false)
                            .variable(new VariableDefinition("family", "Bucher", null, null, null, null, null))
                            .test(test("t", op("search", "Patient", null, null,
                                    "?identifier=${testrunner.testScriptId}", null),
                                    check(new Check.Expression("Bundle.total"), Operator.EQUALS, "1")))
                            .build());
        }

        var run = harness.parallelism(2).run();

        assertThat(run.testScripts()).allMatch(r -> r.outcome() == Outcome.PASS);
        assertThat(server.resource("Patient/1").path("identifier").get(0).path("value").asText())
                .startsWith(run.runId().replace('_', '-')).matches(".*-0[12]");
        assertThat(server.resource("Patient/1").path("name").get(0).path("family").asText()).isEqualTo("Bucher");
        assertThat(server.resource("Patient/1").path("identifier").get(0).path("value").asText())
                .isNotEqualTo(server.resource("Patient/2").path("identifier").get(0).path("value").asText());
    }

    @Test
    void placeholdersInXmlFixturesAreReplacedInValues() {
        harness.exampleInstance("file-of-xml.xml", """
                        <Patient xmlns="http://hl7.org/fhir"><id value="x"/><name><family value="${testrunner.uuid}"/></name></Patient>""")
                .add("ts", script().fixture("xml", false, false)
                        .test(test("t", op("create", "Patient", "xml", null, null, null), responseCode("201")))
                        .build());

        harness.runOne();

        assertThat(server.requests().getFirst().body()).matches("(?s).*<family value=\"[0-9a-f-]{36}\"/>.*");
    }

    @Test
    void placeholderInAnAutocreatedFixtureThatFindsNothingIsAnErrorOfTheSetup() {
        harness.resource("p", """
                        {"resourceType":"Patient","name":[{"family":"${family}"}]}""")
                .resource("source", """
                        {"resourceType":"Patient"}""")
                .add("ts", script().fixture("p", true, false).fixture("source", false, false)
                        .variable(new VariableDefinition("family", null, "Patient.name.family", null, null, "source", null))
                        .test(test("t", op("capabilities"))).build());

        TestScriptResult result = harness.runOne();

        assertThat(phase(result, "Setup").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(phase(result, "Setup").messages().getFirst()).contains("Fixture 'p'", "Variable 'family' has no value");
        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.SKIP);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void fixtureSentInATestUsesAVariableOnAnEarlierResponse() {
        harness.resource("patient", PATIENT.replace("Organization/org-1", "Organization/none"))
                .resource("obs", """
                        {"resourceType":"Observation","status":"final","subject":{"reference":"Patient/${pid}"}}""")
                .add("ts", script().fixture("patient", false, false).fixture("obs", false, false)
                        .variable(new VariableDefinition("pid", null, "Patient.id", null, null, "created", null))
                        .test(test("t",
                                op("create", "Patient", "patient", null, null, "created"),
                                op("create", "Observation", "obs", null, null, null),
                                responseCode("201")))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.resource("Observation/2").path("subject").path("reference").asText())
                .isEqualTo("Patient/1");
    }

    @Test
    void fixtureSentInATestReferencesTheServerIdOfAnAutocreatedFixture() {
        harness.resource("patient", PATIENT.replace("Organization/org-1", "Organization/none"))
                .resource("obs", """
                        {"resourceType":"Observation","status":"final","subject":{"reference":"Patient/pat-1"}}""")
                .add("ts", script().fixture("patient", true, false).fixture("obs", false, false)
                        .test(test("t",
                                op("create", "Observation", "obs", null, null, null),
                                responseCode("201"),
                                check(new Check.MinimumId("obs"))))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.resource("Observation/2").path("subject").path("reference").asText())
                .isEqualTo("Patient/1");
    }

    @Test
    void failingPlaceholderInAFixtureIsAnErrorWhereTheFixtureIsUsed() {
        harness.resource("p", """
                        {"resourceType":"Patient","name":[{"family":"${missing}"}]}""")
                .add("ts", script().fixture("p", false, false)
                        .variable(new VariableDefinition("missing", null, null, null, "X-Missing", null, null))
                        .test(test("t", op("capabilities"), op("create", "Patient", "p", null, null, null)))
                        .build());

        TestScriptResult result = harness.runOne();

        assertThat(phase(result, "Setup").outcome()).isEqualTo(Outcome.PASS);
        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(phase(result, "t").messages().getFirst()).contains("Fixture 'p'", "Variable 'missing' has no value");
        assertThat(server.requestLines()).containsExactly("GET /metadata");
    }

    @Test
    void variableThatReadsTheFixtureItIsUsedInIsAnError() {
        harness.resource("p", """
                        {"resourceType":"Patient","id":"x","name":[{"family":"${self}"}]}""")
                .add("ts", script().fixture("p", false, false)
                        .variable(new VariableDefinition("self", null, "Patient.id", null, null, "p", null))
                        .test(test("t", op("create", "Patient", "p", null, null, null)))
                        .build());

        PhaseResult t = phase(harness.runOne(), "t");

        assertThat(t.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(t.messages().getFirst()).contains("Fixture 'p'", "reads the fixture it is used in");
    }
}
