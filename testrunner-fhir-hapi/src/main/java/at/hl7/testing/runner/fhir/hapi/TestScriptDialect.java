package at.hl7.testing.runner.fhir.hapi;

import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * The differences of one FHIR version's TestScript that the generic {@link TestScriptMapper} cannot see from the
 * HAPI model alone. Everything that only differs in type (e.g. {@code TestScript.profile} is a {@code Reference}
 * in R4 and a {@code canonical} in R5) is handled generically and needs no entry here.
 *
 * <p>A new FHIR version provides its dialect; most methods keep their defaults. Keep every difference here, with a
 * comment naming the element and the versions concerned, so this interface stays the one place to look.
 */
public interface TestScriptDialect {

    /**
     * Checks done on top of HAPI's parsing, to tell apart versions whose TestScripts parse alike.
     *
     * @param testScript the TestScript
     * @param warnings   receives content that does not fit this version
     */
    default void check(@Nonnull ElementView testScript, @Nonnull List<String> warnings) {
    }

    /**
     * The value of {@code assert.stopTestOnFail} when it is absent. The element only exists from R5 on, where it is
     * mandatory; an R4 assertion therefore never stops the test.
     */
    default boolean stopTestOnFailDefault() {
        return false;
    }
}
