package at.hl7.testing.runner.core.engine;

import java.time.Clock;
import java.time.Instant;

/**
 * When one TestScript execution must stop: the run was stopped ({@link RunControl}) or the TestScript's own timeout
 * ({@code execution.test-script-timeout}) has passed. Asked between two actions; nothing is interrupted.
 */
final class Deadline {

    private final RunControl run;
    private final Clock clock;
    private final Instant testScriptDeadline;
    private final String testScriptReason;

    /**
     * @param testScriptDeadline when the TestScript's own timeout passes, {@code null} for none
     * @param testScriptReason   the reason reported once it has passed
     */
    Deadline(RunControl run, Clock clock, Instant testScriptDeadline, String testScriptReason) {
        this.run = run;
        this.clock = clock;
        this.testScriptDeadline = testScriptDeadline;
        this.testScriptReason = testScriptReason;
    }

    /**
     * Why the execution must stop before its next action.
     *
     * @return the reason, {@code null} to go on
     */
    String reason() {
        String reason = run.abortReason();
        if (reason != null) {
            run.applied(reason);
            return reason;
        }
        if (testScriptDeadline != null && !clock.instant().isBefore(testScriptDeadline)) {
            return testScriptReason;
        }
        return null;
    }
}
