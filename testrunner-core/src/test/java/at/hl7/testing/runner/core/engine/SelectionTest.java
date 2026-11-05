package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.SelectConfig;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Selecting what runs (F2, M3.2 of docs/wiki/next-steps.md): {@code select.test-scripts} by file name,
 * {@code TestScript.name} or url, {@code select.tests} by test name; what is not selected is neither run nor reported.
 */
class SelectionTest extends EngineTestBase {

    private void threeTestScripts() {
        harness.add("TestScript-patient-read", script().named("PatientRead", "http://example.org/TestScript/read")
                        .test(test("read", op("capabilities"), responseCode("200"))).build())
                .add("TestScript-patient-search", script().named("PatientSearch", "http://example.org/TestScript/search")
                        .test(test("search", op("capabilities"), responseCode("200"))).build())
                .add("TestScript-organization", script().named("Organization", "http://example.org/TestScript/org")
                        .test(test("org", op("capabilities"), responseCode("200"))).build());
    }

    private static List<String> files(RunResult result) {
        return result.testScripts().stream().map(r -> r.source().getFileName().toString()).toList();
    }

    @Test
    void testScriptsByFileNameWithOrWithoutExtension() {
        threeTestScripts();

        RunResult result = harness.select(List.of("testscript-patient-*"), null).run();

        assertThat(files(result)).containsExactly("TestScript-patient-read.json", "TestScript-patient-search.json");
        assertThat(result.testScripts()).extracting(TestScriptResult::index).as("numbered in the run").containsExactly(1, 2);
        assertThat(server.requests()).hasSize(2);
        assertThat(harness.select(List.of("TestScript-organization.json"), null).run().testScripts()).hasSize(1);
    }

    @Test
    void testScriptsByNameOrUrl() {
        threeTestScripts();

        assertThat(files(harness.select(List.of("PatientSearch", "*/TestScript/org"), null).run()))
                .as("in discovery order").containsExactly("TestScript-organization.json", "TestScript-patient-search.json");
    }

    @Test
    void testsByNameSetupAndTeardownStillRun() {
        harness.add("ts", script()
                .setup(op("capabilities"))
                .test(test("create-patient", op("search", "Patient", null, null, "?name=a", null)))
                .test(test("read-patient", op("search", "Patient", null, null, "?name=b", null)))
                .test(test("delete-patient", op("search", "Patient", null, null, "?name=c", null)))
                .teardown(op("search", "Organization", null, null, null, null))
                .build());

        TestScriptResult result = harness.select(null, List.of("read-*")).runOne();

        assertThat(result.phases()).as("the other tests are neither run nor reported")
                .extracting(PhaseResult::name).containsExactly("Setup", "read-patient", "Teardown");
        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).containsExactly("GET /metadata", "GET /Patient", "GET /Organization");
        assertThat(server.requests().get(1).query()).isEqualTo("name=b");
    }

    @Test
    void testScriptsWithoutASelectedTestAreLeftOut() {
        threeTestScripts();

        RunResult result = harness.select(null, List.of("SEARCH")).run();

        assertThat(files(result)).as("matched without regard to case").containsExactly("TestScript-patient-search.json");
    }

    @Test
    void selectedTestNeedingAResponseOfALeftOutTestIsALoadErrorNamingIt() {
        harness.add("ts", script()
                .variable(new at.hl7.testing.runner.core.model.VariableDefinition("id", null, "Patient.id", null, null,
                        "created", null))
                .test(test("create", op("capabilities", null, null, null, null, "created")))
                .test(test("read", op("read", null, null, "created", null, null),
                        op("search", "Patient", null, null, "?_id=${id}", null)))
                .teardown(op("search", "Patient", null, null, "?_id=${id}", null))
                .build());

        PhaseResult load = harness.select(null, List.of("read")).runOne().phases().getFirst();

        assertThat(load.kind()).isEqualTo(PhaseKind.LOAD);
        assertThat(load.messages().getFirst()).as("named once, though read twice").isEqualTo("TestScript is not valid:"
                + " Test 'read', action 1: targetId 'created', which is kept by Test 'create', action 1, a test that is"
                + " not selected (testrunner.select.tests); select that test too");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void checkModeChecksOnlyWhatIsSelected() {
        harness.add("ts", script()
                .test(test("broken", op("read", null, null, "nope", null, null)))
                .test(test("fine", op("capabilities")))
                .build());

        assertThat(harness.select(null, List.of("fine")).check().outcome()).isEqualTo(Outcome.PASS);
        assertThat(harness.select(null, List.of("broken")).check().outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    void unreadableFileIsRunWhenItsFileNameIsSelectedSoItsErrorIsReported() {
        threeTestScripts();
        harness.add("TestScript-unreadable", null);

        assertThat(harness.select(List.of("*unreadable*"), null).run().testScripts()).singleElement()
                .satisfies(r -> assertThat(r.phases().getFirst().messages().getFirst())
                        .contains("could not be loaded", "is no TestScript"));
        assertThat(harness.select(List.of("Patient*"), null).run().testScripts()).as("by name: it has none")
                .hasSize(2);
    }

    @Test
    void nothingSelectedIsARunWithoutTestScripts() {
        threeTestScripts();

        RunResult result = harness.select(List.of("nope"), null).run();

        assertThat(result.testScripts()).isEmpty();
        assertThat(result.outcome()).as("a run that tested nothing").isEqualTo(Outcome.ERROR);
    }

    @Test
    void listSaysWhatWouldRunAndSendsNothing() {
        threeTestScripts();
        harness.add("TestScript-patient-two", script().named("PatientTwo", null)
                .test(test("first", op("capabilities"))).test(test("second", op("capabilities"))).build());

        List<TestRunner.Listed> listed = harness.select(List.of("Patient*"), List.of("s*")).list();

        assertThat(listed).extracting(TestRunner.Listed::name).containsExactly("PatientSearch", "PatientTwo");
        assertThat(listed.get(1).tests()).containsExactly("second");
        assertThat(listed.get(1).testCount()).isEqualTo(2);
        assertThat(listed.get(1).index()).isEqualTo(2);
        assertThat(server.requests()).isEmpty();
        assertThat(dir.resolve("results")).as("nothing written").doesNotExist();
    }

    @Test
    void emptyPatternIsAConfigurationProblem() {
        threeTestScripts();

        assertThatThrownBy(() -> harness.select(List.of(" "), null).run()).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("testrunner.select.test-scripts[0] is empty");
    }

    @Test
    void globsMatchTheWholeTextWithoutRegardToCase() {
        assertThat(Selection.glob("read").matcher("READ").matches()).isTrue();
        assertThat(Selection.glob("read").matcher("read-patient").matches()).isFalse();
        assertThat(Selection.glob("re?d*").matcher("ready-steady").matches()).isTrue();
        assertThat(Selection.glob("a.b(c)").matcher("a.b(c)").matches()).as("no regex").isTrue();
        assertThat(Selection.glob("a.b").matcher("axb").matches()).isFalse();
        assertThat(new SelectConfig(null, null).selectsAll()).isTrue();
    }
}
