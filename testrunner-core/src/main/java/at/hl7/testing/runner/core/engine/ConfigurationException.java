package at.hl7.testing.runner.core.engine;

import jakarta.annotation.Nonnull;

import java.util.List;

/** The configuration cannot be used to start a run. */
public class ConfigurationException extends RuntimeException {

    private final List<String> problems;

    public ConfigurationException(@Nonnull List<String> problems) {
        super("Invalid test runner configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    @Nonnull
    public List<String> problems() {
        return problems;
    }
}
