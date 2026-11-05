package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Run control (M2.3 of docs/wiki/next-steps.md, ADR-0013): run and TestScript timeouts, fail-fast and cancelling a run.
 * Deadlines are checked between actions; teardown and autodelete always run.
 */
class RunControlTest extends EngineTestBase {

    /** Answers take this long; the limits below are shorter, but long enough for loading on a slow machine. */
    private static final Duration SLOW = Duration.ofMillis(400);
    private static final Duration LIMIT = Duration.ofMillis(250);

    /** Two tests of two requests each, and a teardown that cleans up. */
    private void slowTestScript(String name) {
        harness.add(name, script()
                .test(test("first", op("search", "Patient", null, null, "?t=" + name + "-1", null),
                        op("search", "Patient", null, null, "?t=" + name + "-2", null)))
                .test(test("second", op("search", "Patient", null, null, "?t=" + name + "-3", null)))
                .teardown(op("search", "Patient", null, null, "?t=" + name + "-teardown", null))
                .build());
    }

    @Test
    void testScriptTimeoutStopsBetweenActionsSkipsTheRestAndStillTearsDown() {
        server.delay(SLOW);
        slowTestScript("ts");

        TestScriptResult result = harness.limits(null, LIMIT, false).runOne();

        assertThat(result.phases()).extracting(PhaseResult::name, PhaseResult::outcome).containsExactly(
                tuple("Setup", Outcome.PASS),
                tuple("first", Outcome.ERROR),
                tuple("second", Outcome.SKIP),
                tuple("Teardown", Outcome.PASS));
        assertThat(phase(result, "first").messages().getFirst())
                .isEqualTo("Stopped before the next action: the TestScript timeout of 0.25s has passed");
        assertThat(phase(result, "second").messages().getFirst())
                .isEqualTo("Skipped: the TestScript timeout of 0.25s has passed");
        assertThat(server.requests()).extracting(r -> r.query())
                .as("the request running when the time passed is not interrupted")
                .containsExactly("t=ts-1", "t=ts-teardown");
    }

    @Test
    void testScriptTimeoutAppliesToEachTestScriptOnItsOwn() {
        server.delay(SLOW);
        slowTestScript("a");
        slowTestScript("b");

        RunResult run = harness.limits(null, LIMIT, false).run();

        assertThat(run.testScripts()).extracting(TestScriptResult::outcome).containsExactly(Outcome.ERROR, Outcome.ERROR);
        assertThat(server.requests()).extracting(r -> r.query())
                .containsExactly("t=a-1", "t=a-teardown", "t=b-1", "t=b-teardown");
    }

    @Test
    void runTimeoutStopsTheRunningTestScriptAndSkipsTheOthers() {
        server.delay(SLOW);
        slowTestScript("a");
        slowTestScript("b");

        RunResult run = harness.limits(LIMIT, null, false).run();

        TestScriptResult a = run.testScripts().get(0);
        TestScriptResult b = run.testScripts().get(1);
        assertThat(a.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(phase(a, "first").messages().getFirst()).endsWith("the run timeout of 0.25s has passed");
        assertThat(b.outcome()).isEqualTo(Outcome.SKIP);
        assertThat(b.phases()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(PhaseKind.LOAD);
            assertThat(p.messages()).containsExactly("Not started: the run timeout of 0.25s has passed");
        });
        assertThat(server.requests()).extracting(r -> r.query()).containsExactly("t=a-1", "t=a-teardown");
    }

    @Test
    void failFastSkipsWhatHasNotStartedAfterTheFirstFailure() {
        harness.add("a", script().test(test("t", op("capabilities"), responseCode("200"))).build())
                .add("b", script().test(test("t", op("capabilities"), responseCode("500"))).build())
                .add("c", script().test(test("t", op("capabilities"), responseCode("200"))).build());

        RunResult run = harness.limits(null, null, true).run();

        assertThat(run.testScripts()).extracting(TestScriptResult::outcome)
                .containsExactly(Outcome.PASS, Outcome.FAIL, Outcome.SKIP);
        assertThat(run.testScripts().get(2).phases().getFirst().messages())
                .containsExactly("Not started: fail-fast: TestScript 02_b ended with FAIL");
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void withoutFailFastEveryTestScriptRuns() {
        harness.add("a", script().test(test("t", op("capabilities"), responseCode("500"))).build())
                .add("b", script().test(test("t", op("capabilities"), responseCode("200"))).build());

        assertThat(harness.run().testScripts()).extracting(TestScriptResult::outcome)
                .containsExactly(Outcome.FAIL, Outcome.PASS);
    }

    @Test
    void failFastLetsTheRunningTestScriptsFinishInParallel() {
        server.delay(Duration.ofMillis(100));
        // discovery sorts by file name: a-fails comes first
        harness.add("a-fails", script().test(test("t", op("capabilities"), responseCode("500"))).build());
        for (String name : List.of("b", "c", "d")) {
            harness.add(name, script().test(test("t", op("capabilities"), op("capabilities"))).build());
        }

        RunResult run = harness.parallelism(2).limits(null, null, true).run();

        assertThat(run.testScripts().getFirst().outcome()).isEqualTo(Outcome.FAIL);
        assertThat(run.testScripts().get(1).outcome()).as("running when the first one failed").isEqualTo(Outcome.PASS);
        assertThat(run.testScripts().subList(2, 4)).extracting(TestScriptResult::outcome)
                .containsExactly(Outcome.SKIP, Outcome.SKIP);
    }

    @Test
    void cancelledRunEndsTheRunningTestScriptAndReportsTheOthersAsSkipped() throws Exception {
        server.delay(SLOW);
        slowTestScript("a");
        slowTestScript("b");
        RunControl control = new RunControl();
        Thread canceller = Thread.ofPlatform().start(() -> {
            while (server.requests().isEmpty()) {
                Thread.onSpinWait();
            }
            control.cancel("the runner was stopped");
        });

        RunResult run = harness.control(control).run();
        canceller.join();

        assertThat(control.isCancelled()).isTrue();
        assertThat(run.testScripts()).extracting(TestScriptResult::outcome).containsExactly(Outcome.ERROR, Outcome.SKIP);
        assertThat(phase(run.testScripts().getFirst(), "first").messages())
                .containsExactly("Stopped before the next action: the runner was stopped");
        assertThat(server.requests()).extracting(r -> r.query()).containsExactly("t=a-1", "t=a-teardown");
        assertThat(run.reportFiles()).as("reports are written").containsKeys("junit", "summary", "json");
    }

    @Test
    void aRunControlControlsOneRun() {
        RunControl control = new RunControl();
        harness.add("ts", script().test(test("t", op("capabilities"))).build()).control(control).run();

        assertThatThrownBy(() -> harness.run()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one run");
    }
}
