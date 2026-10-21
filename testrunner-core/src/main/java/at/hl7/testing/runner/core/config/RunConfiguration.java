package at.hl7.testing.runner.core.config;

import at.hl7.testing.runner.core.model.FhirVersion;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The complete configuration of a test run. Immutable; absent values get their defaults.
 *
 * <p>This is the single input of the engine. It has no dependency on any framework, so it can be created in code
 * or bound from external properties (the Spring Boot auto-configuration binds it from {@code testrunner.*}).
 *
 * @param fhirServer the FHIR server the TestScripts are run against
 * @param suites     the TestScripts to run
 * @param http       HTTP client settings
 * @param execution  execution settings, e.g. parallelism
 * @param validation profile validation settings
 * @param reporting  result and log settings
 */
public record RunConfiguration(FhirServerConfig fhirServer, List<SuiteConfig> suites, HttpConfig http,
                               ExecutionConfig execution, ValidationConfig validation, ReportingConfig reporting) {

    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    public RunConfiguration {
        fhirServer = fhirServer == null ? new FhirServerConfig(null, null) : fhirServer;
        suites = suites == null ? List.of() : List.copyOf(suites);
        http = http == null ? HttpConfig.defaults() : http;
        execution = execution == null ? ExecutionConfig.defaults() : execution;
        validation = validation == null ? ValidationConfig.defaults() : validation;
        reporting = reporting == null ? ReportingConfig.defaults() : reporting;
    }

    /** The FHIR server of a suite: its own if configured, otherwise the one of the run. */
    public FhirServerConfig fhirServerOf(SuiteConfig suite) {
        return suite.fhirServer() != null && suite.fhirServer().baseUrl() != null ? suite.fhirServer() : fhirServer;
    }

    /**
     * Checks the configuration for problems that keep the run from starting.
     *
     * @return a description of each problem, empty if the configuration is usable
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (suites.isEmpty()) {
            problems.add("No suite configured (testrunner.suites): there are no TestScripts to run");
        }
        for (int i = 0; i < suites.size(); i++) {
            SuiteConfig suite = suites.get(i);
            String prefix = "testrunner.suites[" + i + "]";
            if (suite.baseDir() == null) {
                problems.add(prefix + ".base-dir is not set");
            } else if (!Files.isDirectory(suite.resolvedTestScriptsDir())) {
                problems.add(prefix + ": TestScript folder " + suite.resolvedTestScriptsDir().toAbsolutePath()
                        + " does not exist");
            }
            try {
                suite.pinnedFhirVersion();
            } catch (IllegalArgumentException e) {
                problems.add(prefix + ".fhir-version: " + e.getMessage() + " or AUTO");
            }
        }
        if (execution.parallelism() < 1) {
            problems.add("testrunner.execution.parallelism must be at least 1, is " + execution.parallelism());
        }
        if (!LEVELS.contains(reporting.logFileLevel())) {
            problems.add("testrunner.reporting.log-file-level: unknown level '" + reporting.logFileLevel()
                    + "', known: " + LEVELS);
        }
        if (validation.enabled() && validation.validatorJar() == null) {
            problems.add("testrunner.validation.validator-jar is not set; set it to the location of validator_cli.jar"
                    + " or disable validation with testrunner.validation.enabled=false");
        }
        return problems;
    }

    /** The report formats that no writer in {@code available} produces. */
    public List<String> unknownFormats(Set<String> available) {
        return reporting.formats().stream().filter(f -> !available.contains(f))
                .map(f -> "testrunner.reporting.formats: unknown format '" + f + "', available: " + available)
                .toList();
    }

    /** Whether the FHIR versions of the configured suites are all supported by {@code supported}. */
    public List<String> unsupportedVersions(Set<FhirVersion> supported) {
        List<String> problems = new ArrayList<>();
        for (SuiteConfig suite : suites) {
            try {
                suite.pinnedFhirVersion()
                        .filter(v -> !supported.contains(v))
                        .ifPresent(v -> problems.add("Suite '" + suite.name() + "' needs FHIR " + v
                                + ", but only " + supported + " are available; add the module testrunner-fhir-"
                                + v.name().toLowerCase(Locale.ROOT) + " to the classpath"));
            } catch (IllegalArgumentException ignored) {
                // reported by problems()
            }
        }
        return problems;
    }
}
