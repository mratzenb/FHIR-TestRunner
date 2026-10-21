package at.hl7.testing.runner.core.engine;

import java.util.List;

/** The configuration cannot be used to start a run. */
public class ConfigurationException extends RuntimeException {

    private final List<String> problems;

    public ConfigurationException(List<String> problems) {
        super("Invalid test runner configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
