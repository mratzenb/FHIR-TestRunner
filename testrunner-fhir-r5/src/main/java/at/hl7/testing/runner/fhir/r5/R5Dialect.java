package at.hl7.testing.runner.fhir.r5;

import at.hl7.testing.runner.fhir.hapi.ElementView;
import at.hl7.testing.runner.fhir.hapi.TestScriptDialect;

import java.util.List;

/**
 * What sets R5 TestScripts apart, beyond what the HAPI R5 model already enforces:
 * <ul>
 *   <li>{@code TestScript.profile} is a {@code canonical} whose id sits on the primitive ({@code _profile});
 *       handled generically by the mapper.</li>
 *   <li>{@code assert.stopTestOnFail} is mandatory. The spec contradicts itself about its meaning; the runner
 *       follows the definition ("If this assert fails, will the current test execution stop?").</li>
 * </ul>
 */
final class R5Dialect implements TestScriptDialect {

    /**
     * An R4 TestScript with a single profile parses in R5 too ({@code "profile": [{"id":..,"reference":..}]} is read as
     * a canonical with an id). Such a profile has no canonical value, which no R5 TestScript can have.
     */
    @Override
    public void check(ElementView testScript, List<String> warnings) {
        for (ElementView profile : testScript.children("profile")) {
            if (profile.primitiveValue() == null) {
                warnings.add("TestScript.profile has no canonical URL (an R4 Reference?)");
            }
        }
    }
}
