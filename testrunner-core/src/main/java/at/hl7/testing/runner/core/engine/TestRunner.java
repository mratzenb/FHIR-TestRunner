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
        return run(configuration, new RunControl());
    }

    /**
     * Runs the configured TestScripts under a {@link RunControl}, through which the run can be stopped from another
     * thread, e.g. by a shutdown hook.
     *
     * <p>Once the run has started, the reports are always written, whatever happens: after a normal end, a stop, an
     * unexpected exception (which is then rethrown) or {@link RunControl#reportNow()}. A TestScript that did not end is
     * {@code error}, one that did not start {@code skip}, both with the reason.
     *
     * @param control controls this run only; a new one is needed for every run
     * @throws ConfigurationException if the configuration cannot be used; nothing is run then
     */
    public RunResult run(RunConfiguration configuration, RunControl control) {
        try {
            return start(configuration, control);
        } finally {
            control.finish();
        }
    }

    private RunResult start(RunConfiguration configuration, RunControl control) {
        List<String> problems = new ArrayList<>(configuration.problems());
        problems.addAll(configuration.unsupportedVersions(versions.versions()));
        problems.addAll(configuration.unknownFormats(reports.formats()));
        problems.addAll(validator.configurationProblems());
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
        RunProgress progress = new RunProgress();
        Duration timeout = configuration.execution().timeout();
        control.begin(clock, timeout == null ? null : run.started().plus(timeout),
                "the run timeout of " + (timeout == null ? "" : RunControl.describe(timeout)) + " has passed",
                () -> publish(run, progress, "the run was aborted before it ended"));
        notify(listener -> listener.runStarted(run));
        Throwable failure = null;
        try {
            List<TestScriptSource> sources = TestScriptDiscovery.discover(configuration.suites());
            progress.found(sources);
            logStart(run, sources);
            CapabilityCheck capabilities = new CapabilityCheck();
            scheduler.runAll(run, sources, control,
                    (index, source) -> {
                        progress.started(index);
                        TestScriptResult result = runOne(run, control, index, sources.size(), source, capabilities);
                        progress.finished(result);
                        return result;
                    },
                    (index, source, reason) -> {
                        TestScriptResult result = notRun(run, index, source, reason);
                        progress.finished(result);
                        return result;
                    });
            if (control.stopReason() != null) {
                log.warn("The run ended early: {}", control.stopReason());
            }
        } catch (RuntimeException | Error e) {
            log.error("The run failed: {}", e.toString(), e);
            failure = e;
        }
        // without a failure every TestScript has a result, and the reason is never used
        RunResult result = publish(run, progress, failure == null ? "the run ended" : "the run failed: " + failure);
        switch (failure) {
            case null -> {
                return result;
            }
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new IllegalStateException(failure);
        }
    }

    /**
     * Writes the reports and tells the listeners, once per run: at its end, after an unexpected exception, or when a
     * shutdown cannot wait ({@link RunControl#reportNow()}). A TestScript without a result is {@code error} if it had
     * started, {@code skip} if not.
     *
     * @param reason why a TestScript has no result, for its message
     */
    private RunResult publish(RunContext run, RunProgress progress, String reason) {
        return progress.publishOnce(() -> {
            List<TestScriptResult> results = new ArrayList<>();
            List<TestScriptSource> sources = progress.sources();
            for (int index = 1; index <= sources.size(); index++) {
                TestScriptResult result = progress.result(index);
                results.add(result != null ? result : unfinished(index, sources.get(index - 1),
                        progress.isStarted(index), reason));
            }
            RunResult published = reports.publish(run, new RunResult(run.runId(), run.runDirectory(), run.started(),
                    Duration.between(run.started(), clock.instant()), results, Map.of()));
            notify(listener -> listener.runFinished(run, published));
            return published;
        });
    }

    /** Tells every listener; a listener that throws is logged and does not stop the run or the other listeners. */
    private void notify(java.util.function.Consumer<RunListener> event) {
        for (RunListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                log.error("The run listener {} failed: {}", listener.getClass().getName(), e.toString(), e);
            }
        }
    }

    /** The result of a TestScript that has no result of its own when the reports are written. */
    private TestScriptResult unfinished(int index, TestScriptSource source, boolean started, String reason) {
        PhaseResult phase = started
                ? PhaseResult.error(PhaseKind.LOAD, "Load", Duration.ZERO, "Did not end: " + reason)
                : PhaseResult.skipped(PhaseKind.LOAD, "Load", 0, "Not started: " + reason);
        return new TestScriptResult(index, source.suite().name(), source.baseName(), source.file().toString(),
                source.file(), null, null, clock.instant(), Duration.ZERO, List.of(phase));
    }

    /**
     * The result of a TestScript that did not start: its tests are {@code skip} with the reason. Its listeners are told
     * like for every other TestScript.
     */
    private TestScriptResult notRun(RunContext run, int index, TestScriptSource source, String reason) {
        log.warn("TestScript {} not started: {}", source.file().getFileName(), reason);
        TestScriptResult result = new TestScriptResult(index, source.suite().name(), source.baseName(),
                source.file().toString(), source.file(), null, null, clock.instant(), Duration.ZERO,
                List.of(PhaseResult.skipped(PhaseKind.LOAD, "Load", 0, "Not started: " + reason)));
        notify(listener -> listener.testScriptFinished(run, result));
        return result;
    }

    /** Runs one TestScript; never throws, every problem ends up in the result. */
    private TestScriptResult runOne(RunContext run, RunControl control, int index, int total,
                                    TestScriptSource source, CapabilityCheck capabilities) {
        String label = "%02d_%s".formatted(index, source.baseName());
        Instant started = clock.instant();
        MDC.put(MDC_TEST_SCRIPT, label);
        try {
            log.info("===== TestScript {}/{}: {}", index, total, source.file());
            TestScriptResult result;
            try {
                LoadedTestScript loaded = new TestScriptLoader(versions).load(source);
                result = execute(run, control, index, label, loaded, started, capabilities);
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
            if (run.configuration().execution().failFast()
                    && (result.outcome() == Outcome.FAIL || result.outcome() == Outcome.ERROR)) {
                control.stopStarting("fail-fast: TestScript " + label + " ended with " + result.outcome());
            }
            TestScriptResult finished = result;
            notify(listener -> listener.testScriptFinished(run, finished));
            return result;
        } finally {
            MDC.remove(MDC_TEST_SCRIPT);
        }
    }

    private TestScriptResult execute(RunContext run, RunControl control, int index, String label,
                                     LoadedTestScript loaded, Instant started, CapabilityCheck capabilities) {
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
        Duration testScriptTimeout = run.configuration().execution().testScriptTimeout();
        Deadline deadline = new Deadline(control, clock, testScriptTimeout == null ? null : started.plus(testScriptTimeout),
                "the TestScript timeout of " + (testScriptTimeout == null ? "" : RunControl.describe(testScriptTimeout))
                        + " has passed");
        List<PhaseResult> phases = new TestScriptExecution(loaded,
                executions.create(run, label, suite, server, capabilities, builtIn, warnings, deadline)).run();
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
