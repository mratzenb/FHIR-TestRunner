package at.hl7.testing.runner.core.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops a run from outside and tells when it has ended, e.g. for a shutdown hook on Ctrl-C. One per run: pass it to
 * {@link TestRunner#run(at.hl7.testing.runner.core.config.RunConfiguration, RunControl)}. Thread-safe.
 *
 * <p>Nothing is killed (docs/adr/0013-run-control.md). A stopped run starts no further TestScript; the running ones
 * stop before their next action, and their teardown and autodelete still run, so the server is left as after a normal
 * run. Then the reports are written. The run timeout ({@code execution.timeout}) and fail-fast
 * ({@code execution.fail-fast}) use the same mechanism.
 *
 * <p>A shutdown that cannot wait for the run to end calls {@link #reportNow()}: the reports are written at once with
 * what is known.
 */
public final class RunControl {

    private final AtomicBoolean used = new AtomicBoolean();
    private final AtomicReference<String> abort = new AtomicReference<>();
    private final AtomicReference<String> noNewStarts = new AtomicReference<>();
    private final AtomicReference<String> applied = new AtomicReference<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Runnable reportNow = () -> {
    };
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

    /** Whether the run has ended: its reports are written, or it did not start (invalid configuration). */
    public boolean isFinished() {
        return finished.getCount() == 0;
    }

    /**
     * Waits until the run has ended.
     *
     * @return whether it has, {@code false} if the time ran out first
     */
    public boolean awaitFinished(Duration timeout) throws InterruptedException {
        return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Writes the reports now with what is known, unless they are written already: for a shutdown that cannot wait any
     * longer. A finished TestScript is reported as it ended, one still running as {@code error} (its teardown may not
     * have run), one not started as {@code skip}. The run itself goes on until the JVM ends, but does not write the
     * reports again. Does nothing before the run has started.
     */
    public void reportNow() {
        reportNow.run();
    }

    /**
     * Starts controlling a run.
     *
     * @param deadline       when the run timeout passes, {@code null} for none
     * @param deadlineReason the reason reported once the deadline has passed
     * @param publishNow     writes the reports with what is known, once
     * @throws IllegalStateException if this control has controlled a run before
     */
    void begin(Clock runClock, Instant deadline, String deadlineReason, Runnable publishNow) {
        if (!used.compareAndSet(false, true)) {
            throw new IllegalStateException("A RunControl controls one run; create a new one for every run");
        }
        this.clock = runClock;
        this.deadline = deadline;
        this.deadlineReason = deadlineReason;
        this.reportNow = publishNow;
    }

    /** The run has ended: its reports are written, or it will not start. */
    void finish() {
        finished.countDown();
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
    public static String describe(Duration duration) {
        return duration.toString().substring(2).toLowerCase(Locale.ROOT);
    }
}
