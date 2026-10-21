package at.hl7.testing.runner.core.config;

/** How the HL7 FHIR Validator CLI is run. */
public enum ValidatorMode {

    /** One validator process per validation. Slow (packages are loaded every time), but nothing is shared. */
    PROCESS,

    /**
     * One long-running validator server ({@code validator_cli.jar server}) per FHIR version and profiles folder,
     * started on first use and stopped with the application. Packages are loaded once.
     */
    SERVER
}
