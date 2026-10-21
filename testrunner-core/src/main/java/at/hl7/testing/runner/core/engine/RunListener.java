package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;

/**
 * Is told about the progress of a run, e.g. to open a log file per run or to report progress elsewhere.
 * The TestScript callbacks may be called from several threads at once when TestScripts run in parallel.
 */
public interface RunListener {

    default void runStarted(RunContext run) {
    }

    default void testScriptFinished(RunContext run, TestScriptResult result) {
    }

    default void runFinished(RunContext run, RunResult result) {
    }
}
