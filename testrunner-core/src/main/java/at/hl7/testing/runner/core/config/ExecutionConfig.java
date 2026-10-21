package at.hl7.testing.runner.core.config;

/**
 * How the TestScripts are executed.
 *
 * @param parallelism number of TestScripts run at the same time, default 1 (one after the other). The actions
 *                    of a single TestScript always run in order. Only raise it for TestScripts that do not
 *                    interfere with each other on the server, see docs/adr/0008-parallel-execution.md.
 */
public record ExecutionConfig(Integer parallelism) {

    public ExecutionConfig {
        parallelism = parallelism == null ? 1 : parallelism;
    }

    public static ExecutionConfig defaults() {
        return new ExecutionConfig(null);
    }
}
