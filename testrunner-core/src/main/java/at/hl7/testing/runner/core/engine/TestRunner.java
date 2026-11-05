package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.http.HttpTransport;
import at.hl7.testing.runner.core.model.TestDefinition;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseKind;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.source.LoadedTestScript;
import at.hl7.testing.runner.core.source.PackageSuites;
import at.hl7.testing.runner.core.source.TestScriptDiscovery;
import at.hl7.testing.runner.core.source.TestScriptLoader;
import at.hl7.testing.runner.core.source.TestScriptSource;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.TestScriptFormatException;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs the TestScripts of a {@link RunConfiguration} and writes the reports. The entry point of the engine.
 *
 * <p>Stateless between runs and thread-safe: everything about a run lives in its {@link RunContext}. The
 * collaborators are passed in, so the runner works without any framework; the Spring Boot auto-configuration
 * merely wires them.
 */
public final class TestRunner {

    /** MDC key holding {@code <NN>_<TestScript>} while a TestScript runs, to tell parallel log lines apart. */
    public static final String MDC_TEST_SCRIPT = "testScript";

    private static final Logger log = LoggerFactory.getLogger(TestRunner.class);

    private final FhirVersionRegistry versions;
    private final ProfileValidator validator;
    private final List<RunListener> listeners;
    private final Clock clock;
    private final Function<RunConfiguration, PackageSuites> packages;
    private final RunScheduler scheduler = new RunScheduler();
    private final ExecutionFactory executions;
    private final ReportPublisher reports;

    public TestRunner(FhirVersionRegistry versions, HttpTransport transport, ProfileValidator validator,
                      List<ReportWriter> reportWriters, List<RunListener> listeners, Clock clock) {
        this(versions, transport, validator, reportWriters, listeners, clock, c -> new PackageSuites(c.packages()));
    }

    /**
     * @param packages resolves the suites read from FHIR packages, given the configuration of the run
     */
    public TestRunner(FhirVersionRegistry versions, HttpTransport transport, ProfileValidator validator,
                      List<ReportWriter> reportWriters, List<RunListener> listeners, Clock clock,
                      Function<RunConfiguration, PackageSuites> packages) {
        this.versions = versions;
        this.validator = validator;
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
        this.packages = packages;
        this.executions = new ExecutionFactory(transport, validator, clock);
        this.reports = new ReportPublisher(reportWriters);
    }

    /**
     * Runs the configured TestScripts.
     *
     * @throws ConfigurationException if the configuration cannot be used, e.g. a package of a suite cannot be found;
     *                                nothing is run then
     */
    public RunResult run(RunConfiguration configuration) {
        List<String> problems = new ArrayList<>(configuration.problems());
        problems.addAll(configuration.unsupportedVersions(versions.versions()));
        problems.addAll(configuration.unknownFormats(reports.formats()));
        if (validator == ProfileValidator.DISABLED) {
            problems.removeIf(p -> p.startsWith("testrunner.validation.validator-jar"));
        }
        if (problems.isEmpty()) {
            try {
                configuration = configuration.withSuites(packages.apply(configuration).resolve(configuration.suites()));
                problems.addAll(configuration.unsupportedVersions(versions.versions())); // versions of the packages
            } catch (PackageSuites.PackageException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        RunContext run = RunContext.start(configuration, clock.instant());
        try {
            Files.createDirectories(run.runDirectory());
        } catch (IOException e) {
            throw new ConfigurationException(List.of("Cannot create the result folder " + run.runDirectory() + ": " + e));
        }
        listeners.forEach(l -> l.runStarted(run));
        try {
            List<TestScriptSource> sources = TestScriptDiscovery.discover(configuration.suites());
            logStart(run, sources);
            CapabilityCheck capabilities = new CapabilityCheck();
            List<TestScriptResult> results = scheduler.runAll(run, sources,
                    (index, source) -> runOne(run, index, sources.size(), source, capabilities));
            RunResult result = reports.publish(run, new RunResult(run.runId(), run.runDirectory(), run.started(),
                    Duration.between(run.started(), clock.instant()), results, Map.of()));
            RunResult finished = result;
            listeners.forEach(l -> l.runFinished(run, finished));
            return result;
        } catch (RuntimeException e) {
            log.error("The run failed: {}", e.toString(), e);
            RunResult failed = new RunResult(run.runId(), run.runDirectory(), run.started(),
                    Duration.between(run.started(), clock.instant()), List.of(), Map.of());
            listeners.forEach(l -> l.runFinished(run, failed));
            throw e;
        }
    }

    /** Runs one TestScript; never throws, every problem ends up in the result. */
    private TestScriptResult runOne(RunContext run, int index, int total, TestScriptSource source,
                                    CapabilityCheck capabilities) {
        String label = "%02d_%s".formatted(index, source.baseName());
        Instant started = clock.instant();
        MDC.put(MDC_TEST_SCRIPT, label);
        try {
            log.info("===== TestScript {}/{}: {}", index, total, source.file());
            TestScriptResult result;
            try {
                LoadedTestScript loaded = new TestScriptLoader(versions).load(source);
                result = execute(run, index, label, loaded, started, capabilities);
            } catch (TestScriptFormatException | ValidatorUnavailableException e) {
                log.error("TestScript {} could not be loaded: {}", source.file().getFileName(), e.getMessage());
                var version = e instanceof TestScriptFormatException format ? format.fhirVersion() : null;
                result = new TestScriptResult(index, source.suite().name(), source.baseName(), source.file().toString(),
                        source.file(), version, null, started, Duration.between(started, clock.instant()),
                        List.of(PhaseResult.error(PhaseKind.LOAD, "Load", Duration.between(started, clock.instant()),
                                "TestScript could not be loaded: " + e.getMessage())));
            } catch (RuntimeException e) {
                log.error("Unexpected error in TestScript {}: {}", source.file().getFileName(), e.toString(), e);
                result = new TestScriptResult(index, source.suite().name(), source.baseName(), source.file().toString(),
                        source.file(), null, null, started, Duration.between(started, clock.instant()),
                        List.of(PhaseResult.error(PhaseKind.LOAD, "Unexpected error",
                                Duration.between(started, clock.instant()), "Unexpected error: " + e)));
            }
            logFinished(result);
            TestScriptResult finished = result;
            listeners.forEach(l -> l.testScriptFinished(run, finished));
            return result;
        } finally {
            MDC.remove(MDC_TEST_SCRIPT);
        }
    }

    private TestScriptResult execute(RunContext run, int index, String label, LoadedTestScript loaded, Instant started,
                                     CapabilityCheck capabilities) {
        var testScript = loaded.testScript();
        var suite = loaded.source().suite();
        log.info("TestScript '{}' ({}, FHIR {})", testScript.displayName(),
                testScript.url() == null ? "no url" : testScript.url(), testScript.fhirVersion());
        log.debug("{} fixture(s), {} variable(s), {} profile(s), {} test(s)", testScript.fixtures().size(),
                testScript.variables().size(), testScript.profiles().size(), testScript.tests().size());
        loaded.readWarnings().forEach(w -> log.warn("Ignored while reading the TestScript: {}", w));

        Map<String, String> builtIn = ExecutionFactory.builtInValues(run, index, started);
        List<String> unsupported = TestScriptChecks.unsupportedWarnings(testScript);
        unsupported.forEach(w -> log.warn("{}", w));
        List<String> problems = new ArrayList<>(TestScriptChecks.problems(testScript, loaded.fixtureResources(),
                builtIn.keySet()));
        problems.addAll(validateTestScript(run, loaded));
        if (!problems.isEmpty()) {
            problems.forEach(p -> log.error("{}", p));
            List<String> messages = new ArrayList<>();
            messages.add("TestScript is not valid: " + String.join("; ", problems));
            unsupported.forEach(w -> messages.add("WARNING: " + w));
            List<PhaseResult> phases = new ArrayList<>();
            phases.add(new PhaseResult(PhaseKind.LOAD, "Load", Outcome.ERROR, Duration.between(started, clock.instant()),
                    0, List.of(), messages));
            skipAll(testScript.tests(), "Skipped: the TestScript is not valid", phases);
            return result(index, loaded, null, started, phases);
        }

        FhirServerConfig server = run.configuration().fhirServerOf(suite);
        if (server.base() == null) {
            log.warn("TestScript '{}' skipped: no FHIR server configured", testScript.displayName());
            List<PhaseResult> phases = new ArrayList<>();
            phases.add(PhaseResult.skipped(PhaseKind.SETUP, "Setup", 0, "Skipped: no FHIR server configured"));
            skipAll(testScript.tests(), "Skipped: no FHIR server configured", phases);
            return result(index, loaded, null, started, phases);
        }

        List<String> orderWarnings = TestScriptChecks.orderWarnings(testScript, loaded.fixtureResources());
        orderWarnings.forEach(w -> log.warn("{}", w));
        List<String> warnings = new ArrayList<>(unsupported);
        warnings.addAll(orderWarnings);
        List<PhaseResult> phases = new TestScriptExecution(loaded,
                executions.create(run, label, suite, server, capabilities, builtIn, warnings)).run();
        return result(index, loaded, server.base(), started, phases);
    }

    /** Validates the TestScript itself against the base specification of its version. */
    private List<String> validateTestScript(RunContext run, LoadedTestScript loaded) {
        if (!run.configuration().validation().validateTestScripts() || !validator.isEnabled()) {
            log.debug("TestScript validation skipped (disabled)");
            return List.of();
        }
        // the suite's profiles folder and IGs are passed although a TestScript needs none of them: in server mode,
        // validations with the same FHIR version, folder and IGs share one validator server
        var suite = loaded.source().suite();
        ValidationResult result = validator.validate(new ValidationRequest(loaded.original(),
                loaded.testScript().fhirVersion(), null, suite.resolvedProfilesDir(), suite.igs()));
        if (!result.isValid()) {
            return List.of("TestScript is not valid FHIR " + loaded.testScript().fhirVersion() + ":\n"
                    + ValidationResult.describe(result.errors()));
        }
        log.info("TestScript is valid FHIR {}", loaded.testScript().fhirVersion());
        return List.of();
    }

    private TestScriptResult result(int index, LoadedTestScript loaded, String fhirServer, Instant started,
                                    List<PhaseResult> phases) {
        var testScript = loaded.testScript();
        return new TestScriptResult(index, loaded.source().suite().name(), testScript.displayName(),
                testScript.url() != null ? testScript.url() : loaded.source().file().toString(), loaded.source().file(),
                testScript.fhirVersion(), fhirServer, started, Duration.between(started, clock.instant()), phases);
    }

    private static void skipAll(List<TestDefinition> tests, String reason, List<PhaseResult> phases) {
        tests.forEach(t -> phases.add(PhaseResult.skipped(PhaseKind.TEST, t.name(), t.assertionCount(), reason)));
    }

    private void logStart(RunContext run, List<TestScriptSource> sources) {
        RunConfiguration c = run.configuration();
        log.info("FHIR TestScript Runner - {}", run.runId());
        log.info("  FHIR server:        {}", c.fhirServer().base() == null ? "- (TestScripts will be skipped)" : c.fhirServer().base());
        if (c.fhirServer().oauth2() != null) {
            log.info("  OAuth2:             client {} at {}", c.fhirServer().oauth2().clientId(),
                    c.fhirServer().oauth2().tokenUrl());
        }
        log.info("  FHIR versions:      {}", versions.versions());
        log.info("  Results:            {}", run.runDirectory().toAbsolutePath());
        log.info("  Parallelism:        {}", c.execution().parallelism());
        log.info("  Rewrite references: {}", c.execution().rewriteReferences().name().toLowerCase(java.util.Locale.ROOT));
        log.info("  Profile validation: {}", validator.description());
        log.info("  Communication logs: {}", c.reporting().communicationLog() ? run.communicationDirectory() : "disabled");
        c.suites().forEach(s -> log.info("  Suite '{}': {}{} (FHIR {})", s.name(),
                s.packageId() == null ? "" : "package " + s.packageId() + " in ", s.baseDir().toAbsolutePath(),
                s.fhirVersion()));
        c.suites().stream().filter(s -> !s.igs().isEmpty())
                .forEach(s -> log.info("  Suite '{}' validates with IGs {}", s.name(), s.igs()));
        log.info("{} TestScript(s) to run", sources.size());
        for (int i = 0; i < sources.size(); i++) {
            log.info("  {}. {}", i + 1, sources.get(i).file());
        }
        if (sources.isEmpty()) {
            log.warn("No TestScripts found in the configured suites");
        }
    }

    private static void logFinished(TestScriptResult result) {
        String line = "TestScript '{}' finished: {} ({} ms)";
        Object[] args = {result.name(), result.outcome(), result.duration().toMillis()};
        if (result.outcome() == Outcome.PASS) {
            log.info(line, args);
        } else if (result.outcome() == Outcome.SKIP) {
            log.warn(line, args);
        } else {
            log.error(line, args);
        }
    }
}
