package at.hl7.testing.runner.core.model;

/**
 * A fixture declared by a TestScript ({@code TestScript.fixture}).
 *
 * @param id         the fixture id, referenced by {@code sourceId}, {@code targetId} and {@code compareToSourceId}
 * @param autocreate whether the runner creates the fixture on the server during setup
 * @param autodelete whether the runner deletes the fixture from the server after the teardown
 * @param reference  {@code fixture.resource.reference}, or {@code null} for a fixture without a static resource
 */
public record FixtureDefinition(String id, boolean autocreate, boolean autodelete, String reference) {
}
