package at.hl7.testing.runner.core.config;

/**
 * How the TestScripts are executed.
 *
 * @param parallelism       number of TestScripts run at the same time, default 1 (one after the other). The
 *                          actions of a single TestScript always run in order. Only raise it for TestScripts that
 *                          do not interfere with each other on the server, see docs/adr/0008-parallel-execution.md.
 * @param rewriteReferences which references in fixture resources are rewritten to the ids the server assigned,
 *                          default {@link ReferenceRewriting#AUTOCREATE}; a convenience of this runner, see
 *                          docs/adr/0012-reference-rewriting.md
 */
public record ExecutionConfig(Integer parallelism, ReferenceRewriting rewriteReferences) {

    public ExecutionConfig {
        parallelism = parallelism == null ? 1 : parallelism;
        rewriteReferences = rewriteReferences == null ? ReferenceRewriting.AUTOCREATE : rewriteReferences;
    }

    public static ExecutionConfig defaults() {
        return new ExecutionConfig(null, null);
    }

    /** Settings with {@code parallelism} TestScripts at the same time and otherwise the defaults. */
    public static ExecutionConfig parallel(int parallelism) {
        return new ExecutionConfig(parallelism, null);
    }
}
