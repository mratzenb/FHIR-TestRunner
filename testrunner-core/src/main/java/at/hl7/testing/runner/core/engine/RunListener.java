package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;

/**
 * Is told about the progress of a run, e.g. to open a log file per run or to report progress elsewhere.
 *
 * <ul>
 *   <li>{@link #testScriptFinished} may be called from several threads at once when TestScripts run in parallel, and
 *       is also called for a TestScript that was not started (its result is {@code skip}).</li>
 *   <li>{@link #runFinished} is called exactly once for every run that started, whatever ended it – also after an
 *       unexpected exception, or on Ctrl-C.</li>
 *   <li>A listener that throws is logged; the run and the other listeners go on.</li>
 * </ul>
 */
public interface RunListener {

    default void runStarted(RunInfo run) {
    }

    default void testScriptFinished(RunInfo run, TestScriptResult result) {
    }

    default void runFinished(RunInfo run, RunResult result) {
    }
}
