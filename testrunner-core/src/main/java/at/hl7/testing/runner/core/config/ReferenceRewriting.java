package at.hl7.testing.runner.core.config;

/**
 * Which references in fixture resources the runner rewrites to the ids the server assigned
 * ({@code testrunner.execution.rewrite-references}).
 *
 * <p>The FHIR TestScript specification does not rewrite references: a fixture is sent as written. Rewriting is a
 * convenience of this runner. A TestScript that relies on it may fail on another test engine, see
 * docs/adr/0012-reference-rewriting.md.
 */
public enum ReferenceRewriting {

    /** Fixtures are sent as written, as the specification says; for checking that TestScripts are portable. */
    NONE,

    /**
     * References to autocreated fixtures are rewritten, in other autocreated fixtures and in fixtures sent by
     * operations (default; the Python proof of concept rewrote them between autocreated fixtures).
     */
    AUTOCREATE,

    /**
     * Also references to fixtures that a {@code create} or {@code update} operation sent, once the server answered
     * with their id.
     */
    ALL
}
