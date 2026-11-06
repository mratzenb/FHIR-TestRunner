package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nullable;

/** One step of a setup, test or teardown: either an operation or an assertion. */
public sealed interface Action permits Operation, Assertion {

    /** The label or description for the log, or {@code null}. */
    @Nullable
    String description();
}
