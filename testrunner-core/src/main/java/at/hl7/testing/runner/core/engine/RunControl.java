package at.hl7.testing.runner.core.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops a run from outside, e.g. from a shutdown hook on Ctrl-C. One per run: pass it to
 * {@link TestRunner#run(at.hl7.testing.runner.core.config.RunConfiguration, RunControl)}. Thread-safe.
 *
 * <p>Nothing is killed (docs/adr/0013-run-control.md). A stopped run starts no further TestScript; the running ones
 * stop before their next action, and their teardown and autodelete still run, so the server is left as after a normal
 * run. The run timeout ({@code execution.timeout}) and fail-fast ({@code execution.fail-fast}) use the same mechanism.
 */
public final class RunControl {

    private final AtomicBoolean used = new AtomicBoolean();
    private final AtomicReference<String> abort = new AtomicReference<>();
    private final AtomicReference<String> noNewStarts = new AtomicReference<>();
    private final AtomicReference<String> applied = new AtomicReference<>();
    private volatile Clock clock = Clock.systemUTC();
    private volatile Instant deadline;
    private volatile String deadlineReason;

    /**
     * Stops the run: no further TestScript starts, the running ones stop before their next action. The first reason
     * given is the one reported.
     *
     * @param reason why, for the log and the reports, e.g. {@code the runner was stopped (Ctrl-C)}
     */
    public void cancel(String reason) {
        abort.compareAndSet(null, reason);
    }

    /** Whether the run was cancelled, or its timeout has passed. */
    public boolean isCancelled() {
        return abortReason() != null;
    }

    /**
     * Starts controlling a run.
     *
     * @param deadline       when the run timeout passes, {@code null} for none
     * @param deadlineReason the reason reported once the deadline has passed
     * @throws IllegalStateException if this control has controlled a run before
     */
    void begin(Clock runClock, Instant deadline, String deadlineReason) {
        if (!used.compareAndSet(false, true)) {
            throw new IllegalStateException("A RunControl controls one run; create a new one for every run");
        }
        this.clock = runClock;
        this.deadline = deadline;
        this.deadlineReason = deadlineReason;
    }

    /** No further TestScript starts; the running ones finish. Used by fail-fast. */
    void stopStarting(String reason) {
        noNewStarts.compareAndSet(null, reason);
    }

    /**
     * Why running TestScripts stop before their next action: the run was cancelled or its timeout has passed.
     *
     * @return the reason, {@code null} to go on
     */
    String abortReason() {
        if (abort.get() == null && deadline != null && !clock.instant().isBefore(deadline)) {
            abort.compareAndSet(null, deadlineReason);
        }
        return abort.get();
    }

    /**
     * Why no further TestScript starts: the run was stopped ({@link #abortReason}) or fail-fast ended it.
     *
     * @return the reason, {@code null} to start it
     */
    String startReason() {
        String reason = abortReason();
        return reason != null ? reason : noNewStarts.get();
    }

    /** A reason of the run stopped or skipped something; it becomes {@link #stopReason}. */
    void applied(String reason) {
        applied.compareAndSet(null, reason);
    }

    /**
     * Why the run ended before every TestScript ran to its end, as reported in the result.
     *
     * @return the first reason that stopped or skipped something, {@code null} if nothing was
     */
    String stopReason() {
        return applied.get();
    }

    /** A duration as in the configuration, e.g. {@code 1m30s} or {@code 0.2s}. */
    static String describe(Duration duration) {
        return duration.toString().substring(2).toLowerCase(Locale.ROOT);
    }
}
