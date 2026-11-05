package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.source.TestScriptSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What one run has done so far: the TestScripts found, started and finished. Lets the reports be written at any time –
 * at the end of the run, after an unexpected exception, or when a shutdown cannot wait for the run any longer – and
 * exactly once.
 */
final class RunProgress {

    private volatile List<TestScriptSource> sources = List.of();
    private final Set<Integer> started = ConcurrentHashMap.newKeySet();
    private final Map<Integer, TestScriptResult> finished = new ConcurrentHashMap<>();
    private RunResult published;

    void found(List<TestScriptSource> found) {
        sources = List.copyOf(found);
    }

    List<TestScriptSource> sources() {
        return sources;
    }

    /** @param index the position of the TestScript in the run, starting at 1 */
    void started(int index) {
        started.add(index);
    }

    boolean isStarted(int index) {
        return started.contains(index);
    }

    void finished(TestScriptResult result) {
        finished.put(result.index(), result);
    }

    /** The result of a finished TestScript, {@code null} if it has not finished. */
    TestScriptResult result(int index) {
        return finished.get(index);
    }

    /**
     * Publishes the result of the run the first time it is called; later calls return that result.
     *
     * @param publish writes the reports and returns the result
     */
    synchronized RunResult publishOnce(Supplier<RunResult> publish) {
        if (published == null) {
            published = publish.get();
        }
        return published;
    }
}
