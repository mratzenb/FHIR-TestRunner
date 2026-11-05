package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.ActionResult;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Collects the action results of one phase while it runs. */
final class PhaseRecorder {

    private final PhaseKind kind;
    private final String name;
    private final int assertionCount;
    private final long start = System.nanoTime();
    private final List<ActionResult> actions = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Outcome outcome = Outcome.PASS;

    PhaseRecorder(PhaseKind kind, String name, int assertionCount) {
        this.kind = kind;
        this.name = name;
        this.assertionCount = assertionCount;
    }

    void action(ActionResult result) {
        actions.add(result);
        outcome = outcome.and(result.outcome());
        if (result.message() != null) {
            if (result.warning()) {
                warnings.add(result.message());
            } else if (result.outcome() != Outcome.PASS) {
                problems.add(result.message());
            }
        }
    }

    /** A problem outside of an action, e.g. a fixture that could not be created. */
    void problem(Outcome problemOutcome, String message) {
        outcome = outcome.and(problemOutcome);
        problems.add(message);
    }

    void warning(String message) {
        warnings.add(message);
    }

    Outcome outcome() {
        return outcome;
    }

    PhaseKind kind() {
        return kind;
    }

    String name() {
        return name;
    }

    PhaseResult finish() {
        List<String> messages = new ArrayList<>(problems);
        warnings.forEach(w -> messages.add("WARNING: " + w));
        return new PhaseResult(kind, name, outcome, Duration.ofNanos(System.nanoTime() - start), assertionCount,
                actions, messages);
    }
}
