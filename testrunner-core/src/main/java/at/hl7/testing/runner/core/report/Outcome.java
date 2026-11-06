package at.hl7.testing.runner.core.report;

import jakarta.annotation.Nonnull;

/**
 * The result of a phase, a TestScript or a run.
 *
 * <ul>
 *   <li>{@link #FAIL}: an assertion failed, the server under test did not behave as expected. Server developers act.</li>
 *   <li>{@link #ERROR}: something could not be executed: invalid TestScript, unsupported feature, server not
 *       reachable, validator problem. TestScript authors or the operators of the runner act.</li>
 *   <li>{@link #SKIP}: not executed, because an earlier failure stopped the TestScript or no server is configured.</li>
 * </ul>
 *
 * Combined results take the most severe outcome: error &gt; fail &gt; skip &gt; pass.
 */
public enum Outcome {
    PASS, SKIP, FAIL, ERROR;

    /** The more severe of the two. */
    @Nonnull
    public Outcome and(@Nonnull Outcome other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
