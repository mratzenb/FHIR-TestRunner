package at.hl7.testing.runner.core.config;

import java.time.Duration;

/**
 * How the TestScripts are executed.
 *
 * @param parallelism       number of TestScripts run at the same time, default 1 (one after the other). The
 *                          actions of a single TestScript always run in order. Only raise it for TestScripts that
 *                          do not interfere with each other on the server, see docs/adr/0008-parallel-execution.md.
 * @param rewriteReferences which references in fixture resources are rewritten to the ids the server assigned,
 *                          default {@link ReferenceRewriting#AUTOCREATE}; a convenience of this runner, see
 *                          docs/adr/0012-reference-rewriting.md
 * @param timeout           how long the whole run may take; default none. Checked between actions: once it has
 *                          passed, no further TestScript starts and the running ones stop their tests, see
 *                          docs/adr/0013-run-control.md
 * @param testScriptTimeout how long one TestScript may take; default none. Checked between its actions: once it
 *                          has passed, its remaining tests are skipped; teardown and autodelete still run
 * @param failFast          stop the run after the first TestScript that failed or had an error; default false.
 *                          The running TestScripts finish, those not started yet are skipped
 */
public record ExecutionConfig(Integer parallelism, ReferenceRewriting rewriteReferences, Duration timeout,
                              Duration testScriptTimeout, Boolean failFast) {

    public ExecutionConfig {
        parallelism = parallelism == null ? 1 : parallelism;
        rewriteReferences = rewriteReferences == null ? ReferenceRewriting.AUTOCREATE : rewriteReferences;
        failFast = failFast != null && failFast;
    }

    public static ExecutionConfig defaults() {
        return new ExecutionConfig(null, null, null, null, null);
    }

    /** Settings with {@code parallelism} TestScripts at the same time and otherwise the defaults. */
    public static ExecutionConfig parallel(int parallelism) {
        return new ExecutionConfig(parallelism, null, null, null, null);
    }

    /** A copy with another reference rewriting. */
    public ExecutionConfig withRewriteReferences(ReferenceRewriting newRewriteReferences) {
        return new ExecutionConfig(parallelism, newRewriteReferences, timeout, testScriptTimeout, failFast);
    }

    /** A copy with other limits: for the run, for each TestScript, and whether to stop after the first failure. */
    public ExecutionConfig withLimits(Duration newTimeout, Duration newTestScriptTimeout, boolean newFailFast) {
        return new ExecutionConfig(parallelism, rewriteReferences, newTimeout, newTestScriptTimeout, newFailFast);
    }
}
