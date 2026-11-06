package at.hl7.testing.runner.core.report;

import jakarta.annotation.Nonnull;

import java.time.Duration;
import java.util.List;

/**
 * The result of a phase: the load check, the setup, one test or the teardown.
 *
 * @param kind           which phase
 * @param name           the name, e.g. the name of the test
 * @param outcome        the combined outcome of the actions
 * @param duration       how long it took
 * @param assertionCount number of assertions declared in the phase
 * @param actions        the result of every action executed
 * @param messages       failures, errors and warnings, the first one being the main reason for the outcome
 */
public record PhaseResult(@Nonnull PhaseKind kind, @Nonnull String name, @Nonnull Outcome outcome,
                          @Nonnull Duration duration, int assertionCount, @Nonnull List<ActionResult> actions,
                          @Nonnull List<String> messages) {

    public PhaseResult {
        actions = List.copyOf(actions);
        messages = List.copyOf(messages);
    }

    /** A phase that was not executed. */
    @Nonnull
    public static PhaseResult skipped(@Nonnull PhaseKind kind, @Nonnull String name, int assertionCount,
                                      @Nonnull String reason) {
        return new PhaseResult(kind, name, Outcome.SKIP, Duration.ZERO, assertionCount, List.of(), List.of(reason));
    }

    /** A phase that could not be executed at all. */
    @Nonnull
    public static PhaseResult error(@Nonnull PhaseKind kind, @Nonnull String name, @Nonnull Duration duration,
                                    @Nonnull String message) {
        return new PhaseResult(kind, name, Outcome.ERROR, duration, 0, List.of(), List.of(message));
    }
}
