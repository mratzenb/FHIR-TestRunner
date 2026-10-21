package at.hl7.testing.runner.core.model;

import java.util.List;

/**
 * A test of a TestScript ({@code TestScript.test}).
 *
 * @param name        the name of the test, used in reports
 * @param description the description, or {@code null}
 * @param actions     the operations and assertions, in order
 */
public record TestDefinition(String name, String description, List<Action> actions) {

    public TestDefinition {
        actions = List.copyOf(actions);
    }

    /** Number of assertions declared by this test. */
    public int assertionCount() {
        return (int) actions.stream().filter(Assertion.class::isInstance).count();
    }
}
