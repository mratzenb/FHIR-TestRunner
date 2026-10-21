package at.hl7.testing.runner.core.report;

/** The parts of a TestScript execution reported on their own. */
public enum PhaseKind {
    /** Loading and checking the TestScript before anything is sent to the server. */
    LOAD("Load"),
    SETUP("Setup"),
    TEST("Test"),
    TEARDOWN("Teardown");

    private final String label;

    PhaseKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
