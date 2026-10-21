package at.hl7.testing.runner.core.spi;

import java.util.List;

/**
 * How well content fits the TestScript resource of one FHIR version.
 *
 * @param errors   problems that make the content unusable in this version, e.g. a value of the wrong type
 * @param warnings content that would be ignored, e.g. elements unknown in this version
 */
public record ReadCheck(List<String> errors, List<String> warnings) {

    public ReadCheck {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    /** Whether the content can be read in this version, possibly ignoring some of it. */
    public boolean readable() {
        return errors.isEmpty();
    }

    /** Whether the content is a TestScript of exactly this version. */
    public boolean exact() {
        return errors.isEmpty() && warnings.isEmpty();
    }
}
