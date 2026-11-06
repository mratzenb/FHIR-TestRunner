package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

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
public record ExecutionConfig(@Nonnull Integer parallelism, @Nonnull ReferenceRewriting rewriteReferences,
                              @Nullable Duration timeout, @Nullable Duration testScriptTimeout,
                              @Nonnull Boolean failFast) {

    public ExecutionConfig(@Nullable Integer parallelism, @Nullable ReferenceRewriting rewriteReferences,
                           @Nullable Duration timeout, @Nullable Duration testScriptTimeout,
                           @Nullable Boolean failFast) {
        parallelism = parallelism == null ? 1 : parallelism;
        rewriteReferences = rewriteReferences == null ? ReferenceRewriting.AUTOCREATE : rewriteReferences;
        failFast = failFast != null && failFast;
        this.parallelism = parallelism;
        this.rewriteReferences = rewriteReferences;
        this.timeout = timeout;
        this.testScriptTimeout = testScriptTimeout;
        this.failFast = failFast;
    }

    @Nonnull
    public static ExecutionConfig defaults() {
        return new ExecutionConfig(null, null, null, null, null);
    }

    /** Settings with {@code parallelism} TestScripts at the same time and otherwise the defaults. */
    @Nonnull
    public static ExecutionConfig parallel(int parallelism) {
        return new ExecutionConfig(parallelism, null, null, null, null);
    }

    /** A copy with another reference rewriting. */
    @Nonnull
    public ExecutionConfig withRewriteReferences(@Nonnull ReferenceRewriting newRewriteReferences) {
        return new ExecutionConfig(parallelism, newRewriteReferences, timeout, testScriptTimeout, failFast);
    }

    /** A copy with other limits: for the run, for each TestScript, and whether to stop after the first failure. */
    @Nonnull
    public ExecutionConfig withLimits(@Nullable Duration newTimeout, @Nullable Duration newTestScriptTimeout,
                                      boolean newFailFast) {
        return new ExecutionConfig(parallelism, rewriteReferences, newTimeout, newTestScriptTimeout, newFailFast);
    }
}
