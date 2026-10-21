package at.hl7.testing.runner.fhir.r4;

import at.hl7.testing.runner.fhir.hapi.TestScriptDialect;

/**
 * What sets R4 TestScripts apart, beyond what the HAPI R4 model already enforces:
 * <ul>
 *   <li>{@code assert.stopTestOnFail} does not exist, so a failed assertion never stops the test
 *       (the default of {@link TestScriptDialect#stopTestOnFailDefault()}).</li>
 *   <li>{@code TestScript.profile} is a {@code Reference}; handled generically by the mapper.</li>
 * </ul>
 */
final class R4Dialect implements TestScriptDialect {
}
