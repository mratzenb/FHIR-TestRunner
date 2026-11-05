package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.source.TestScriptSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Decides when each TestScript of a run runs, see docs/adr/0008-parallel-execution.md: one after the other, or with
 * {@code execution.parallelism > 1} on a fixed pool of threads {@code testscript-N}. The TestScripts of suites with
 * {@code parallel = false} run first, one after the other, while nothing else runs. The results keep the discovery
 * order.
 *
 * <p>Before a TestScript starts, the {@link RunControl} is asked: once the run is stopped (cancelled, run timeout) or
 * fail-fast has ended it, the TestScripts not started yet are not run but reported by {@link NotRun}.
 */
final class RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduler.class);

    /** Runs one TestScript; never throws, every problem ends up in its result. */
    @FunctionalInterface
    interface Task {
        /**
         * @param index the position of the TestScript in the run, starting at 1
         */
        TestScriptResult run(int index, TestScriptSource source);
    }

    /** The result of a TestScript that is not started; never throws. */
    @FunctionalInterface
    interface NotRun {
        TestScriptResult result(int index, TestScriptSource source, String reason);
    }

    /** Runs every TestScript of the run with {@code task}, unless {@code control} says not to start it. */
    List<TestScriptResult> runAll(RunContext run, List<TestScriptSource> sources, RunControl control, Task task,
                                  NotRun notRun) {
        Task guarded = (index, source) -> {
            String reason = control.startReason();
            if (reason != null) {
                control.applied(reason);
                return notRun.result(index, source, reason);
            }
            return task.run(index, source);
        };
        List<Integer> serial = new ArrayList<>();
        List<Integer> parallel = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            (sources.get(i).suite().parallel() ? parallel : serial).add(i);
        }
        int parallelism = Math.min(run.configuration().execution().parallelism(), Math.max(1, parallel.size()));
        TestScriptResult[] results = new TestScriptResult[sources.size()];
        if (parallelism == 1) {
            for (int i = 0; i < sources.size(); i++) {
                results[i] = guarded.run(i + 1, sources.get(i));
            }
            return List.of(results);
        }
        if (!serial.isEmpty()) {
            log.info("Running {} TestScript(s) of suites with parallel=false one after the other first", serial.size());
        }
        for (int i : serial) {
            results[i] = guarded.run(i + 1, sources.get(i));
        }
        log.info("Running {} TestScripts with parallelism {}", parallel.size(), parallelism);
        try (ExecutorService executor = Executors.newFixedThreadPool(parallelism,
                Thread.ofPlatform().name("testscript-", 1).factory())) {
            Map<Integer, Future<TestScriptResult>> futures = new LinkedHashMap<>();
            for (int i : parallel) {
                TestScriptSource source = sources.get(i);
                int index = i + 1;
                futures.put(i, executor.submit(() -> guarded.run(index, source)));
            }
            for (var future : futures.entrySet()) {
                results[future.getKey()] = future.getValue().get();
            }
            return List.of(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running the TestScripts", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A TestScript execution failed unexpectedly", e.getCause());
        }
    }
}
