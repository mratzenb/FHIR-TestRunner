package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.http.CommunicationLog;
import at.hl7.testing.runner.core.http.FhirHttpClient;
import at.hl7.testing.runner.core.http.HttpHeaders;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private final HttpTransport transport;
    private final ProfileValidator validator;
    private final List<ReportWriter> reportWriters;
    private final List<RunListener> listeners;
    private final Clock clock;
    private final Function<RunConfiguration, PackageSuites> packages;

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
        this.transport = transport;
        this.validator = validator;
        this.reportWriters = List.copyOf(reportWriters);
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
        this.packages = packages;
    }

    /**
     * Runs the configured TestScripts.
     *
     * @throws ConfigurationException if the configuration cannot be used, e.g. a package of a suite cannot be found;
     *                                nothing is run then
     */
    public RunResult run(RunConfiguration configuration) {
        List<String> problems = new ArrayList<>(configuration.problems());
        if (problems.isEmpty()) {
            try {
                configuration = configuration.withSuites(packages.apply(configuration).resolve(configuration.suites()));
            } catch (PackageSuites.PackageException e) {
                problems.add(e.getMessage());
            }
        }
        problems.addAll(configuration.unsupportedVersions(versions.versions()));
        problems.addAll(configuration.unknownFormats(reportWriters.stream().map(ReportWriter::format)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new))));
        if (validator == ProfileValidator.DISABLED) {
            problems.removeIf(p -> p.startsWith("testrunner.validation.validator-jar"));
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
            List<TestScriptResult> results = runAll(run, sources, new CapabilityCheck());
            RunResult result = new RunResult(run.runId(), run.runDirectory(), run.started(),
                    Duration.between(run.started(), clock.instant()), results, Map.of());
            result = result.withReportFiles(writeReports(run, result));
            logSummary(result);
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

    /**
     * Runs the TestScripts, in parallel if configured. The TestScripts of suites with {@code parallel = false} run
     * first, one after the other, while nothing else runs; then the others. The results keep the discovery order.
     */
    private List<TestScriptResult> runAll(RunContext run, List<TestScriptSource> sources, CapabilityCheck capabilities) {
        List<Integer> serial = new ArrayList<>();
        List<Integer> parallel = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            (sources.get(i).suite().parallel() ? parallel : serial).add(i);
        }
        int parallelism = Math.min(run.configuration().execution().parallelism(), Math.max(1, parallel.size()));
        TestScriptResult[] results = new TestScriptResult[sources.size()];
        if (parallelism == 1) {
            for (int i = 0; i < sources.size(); i++) {
                results[i] = runOne(run, i + 1, sources.size(), sources.get(i), capabilities);
            }
            return List.of(results);
        }
        if (!serial.isEmpty()) {
            log.info("Running {} TestScript(s) of suites with parallel=false one after the other first", serial.size());
        }
        for (int i : serial) {
            results[i] = runOne(run, i + 1, sources.size(), sources.get(i), capabilities);
        }
        log.info("Running {} TestScripts with parallelism {}", parallel.size(), parallelism);
        try (ExecutorService executor = Executors.newFixedThreadPool(parallelism,
                Thread.ofPlatform().name("testscript-", 1).factory())) {
            Map<Integer, Future<TestScriptResult>> futures = new LinkedHashMap<>();
            for (int i : parallel) {
                TestScriptSource source = sources.get(i);
                futures.put(i, executor.submit(() -> runOne(run, i + 1, sources.size(), source, capabilities)));
            }
            for (var future : futures.entrySet()) {
                results[future.getKey()] = future.getValue().get();
            }
            return List.of(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running the TestScripts", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A TestScript execution failed unexpectedly", e.getCause());
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

        List<String> problems = new ArrayList<>(TestScriptChecks.problems(testScript));
        problems.addAll(validateTestScript(run, loaded));
        if (!problems.isEmpty()) {
            problems.forEach(p -> log.error("{}", p));
            List<PhaseResult> phases = new ArrayList<>();
            phases.add(PhaseResult.error(PhaseKind.LOAD, "Load", Duration.between(started, clock.instant()),
                    "TestScript is not valid: " + String.join("; ", problems)));
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

        CommunicationLog communication = run.configuration().reporting().communicationLog()
                ? CommunicationLog.toFolder(run.communicationDirectory(), label, server.base())
                : CommunicationLog.none();
        HttpHeaders defaultHeaders = HttpHeaders.of(server.headers().entrySet().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), List.of(e.getValue())), Map::putAll));
        FhirHttpClient http = new FhirHttpClient(transport, run.requestCounter(), communication, defaultHeaders);
        List<PhaseResult> phases = new TestScriptExecution(loaded, http, server.base(), validator,
                suite.resolvedProfilesDir(), suite.igs(), capabilities, builtInValues(run, index, started)).run();
        return result(index, loaded, server.base(), started, phases);
    }

    /**
     * The values TestScripts and their fixtures can use as {@code ${testrunner.<name>}}, e.g. to give the resources
     * of each TestScript execution identifiers of their own, so that TestScripts running in parallel, or again on the
     * same server, do not find each other's data.
     */
    static Map<String, String> builtInValues(RunContext run, int index, Instant started) {
        String uuid = UUID.randomUUID().toString();
        return Map.of(
                "runId", run.runId(),
                "testScriptId", run.runId().replaceAll("[^A-Za-z0-9.-]", "-") + "-" + "%02d".formatted(index),
                "uuid", uuid,
                "timestamp", DateTimeFormatter.ISO_INSTANT.format(started.truncatedTo(ChronoUnit.MILLIS)),
                "date", DateTimeFormatter.ISO_LOCAL_DATE.format(started.atZone(ZoneOffset.UTC)));
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

    private Map<String, java.nio.file.Path> writeReports(RunContext run, RunResult result) {
        Map<String, java.nio.file.Path> files = new LinkedHashMap<>();
        for (String format : run.configuration().reporting().formats()) {
            ReportWriter writer = reportWriters.stream().filter(w -> w.format().equals(format)).findFirst().orElseThrow();
            try {
                files.put(format, writer.write(result, run.runDirectory()));
            } catch (IOException | RuntimeException e) {
                log.error("Could not write the {} report: {}", format, e.toString());
            }
        }
        return files;
    }

    private void logStart(RunContext run, List<TestScriptSource> sources) {
        RunConfiguration c = run.configuration();
        log.info("FHIR TestScript Runner - {}", run.runId());
        log.info("  FHIR server:        {}", c.fhirServer().base() == null ? "- (TestScripts will be skipped)" : c.fhirServer().base());
        log.info("  FHIR versions:      {}", versions.versions());
        log.info("  Results:            {}", run.runDirectory().toAbsolutePath());
        log.info("  Parallelism:        {}", c.execution().parallelism());
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

    private static void logSummary(RunResult result) {
        Map<Outcome, Long> counts = result.countByOutcome();
        log.info("===== Summary {}: {} TestScript(s), {} passed, {} failed, {} errors, {} skipped -> {}", result.runId(),
                result.testScripts().size(), counts.get(Outcome.PASS), counts.get(Outcome.FAIL),
                counts.get(Outcome.ERROR), counts.get(Outcome.SKIP), result.outcome());
        result.reportFiles().forEach((format, file) -> log.info("  {} report: {}", format, file.toAbsolutePath()));
    }
}
