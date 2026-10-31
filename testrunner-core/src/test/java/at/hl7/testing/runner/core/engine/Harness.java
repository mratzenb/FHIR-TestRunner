package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.ExecutionConfig;
import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.config.ReportingConfig;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.config.ValidationConfig;
import at.hl7.testing.runner.core.http.JdkHttpTransport;
import at.hl7.testing.runner.core.model.TestScript;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs TestScript models through the real {@link TestRunner}: each model is written as a file whose content is
 * its key, the {@link StubVersionSupport} hands the model back. Fixture resources go to Example_Instances.
 *
 * <p>The TestScripts added with {@link #add} form the suite {@code suite}; those added with {@link #addSerial} the
 * suite {@code serial}, which has {@code parallel = false} and comes first.
 */
final class Harness {

    private final Path dir;
    private final Map<String, TestScript> scripts = new LinkedHashMap<>();
    private ProfileValidator validator = ProfileValidator.DISABLED;
    private int parallelism = 1;
    private boolean serialSuite;
    private String fhirBase;

    Harness(Path dir, String fhirBase) {
        this.dir = dir;
        this.fhirBase = fhirBase;
        try {
            Files.createDirectories(dir.resolve("suite/Test_Scripts"));
            Files.createDirectories(dir.resolve("suite/Example_Instances"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Harness add(String name, TestScript script) {
        scripts.put(name, script);
        write("suite/Test_Scripts/" + name + ".json", name);
        return this;
    }

    /** Adds a TestScript to the suite with {@code parallel = false}; its fixtures are in the same folder. */
    Harness addSerial(String name, TestScript script) {
        serialSuite = true;
        scripts.put(name, script);
        try {
            Files.createDirectories(dir.resolve("serial/Test_Scripts"));
            Files.createDirectories(dir.resolve("serial/Example_Instances"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        write("serial/Test_Scripts/" + name + ".json", name);
        return this;
    }

    /** Writes the resource of fixture {@code fixtureId} (see {@link Scripts.Builder#fixture}). */
    Harness resource(String fixtureId, String json) {
        write("suite/Example_Instances/file-of-" + fixtureId + ".json", json);
        return this;
    }

    /** Writes an Example Instance under its own file name, e.g. {@code CapabilityStatement-needs-read.json}. */
    Harness exampleInstance(String fileName, String json) {
        write("suite/Example_Instances/" + fileName, json);
        return this;
    }

    Harness validator(ProfileValidator validator) {
        this.validator = validator;
        return this;
    }

    Harness parallelism(int parallelism) {
        this.parallelism = parallelism;
        return this;
    }

    Harness noServer() {
        this.fhirBase = null;
        return this;
    }

    RunResult run() {
        List<SuiteConfig> suites = new java.util.ArrayList<>();
        if (serialSuite) {
            suites.add(new SuiteConfig(null, "R4", dir.resolve("serial"), null, Path.of("../suite/Example_Instances"),
                    null, null, null, null, false, null));
        }
        suites.add(SuiteConfig.of(dir.resolve("suite"), "R4"));
        RunConfiguration configuration = new RunConfiguration(FhirServerConfig.of(fhirBase), suites, HttpConfig.defaults(),
                new ExecutionConfig(parallelism),
                ValidationConfig.disabled(),
                new ReportingConfig(dir.resolve("results"), List.of("junit", "summary", "json"), true, false, null, null),
                null);
        try (JdkHttpTransport transport = new JdkHttpTransport(HttpConfig.defaults())) {
            FhirVersionRegistry versions = new FhirVersionRegistry(List.of(StubVersionSupport.of(scripts)));
            TestRunner runner = new TestRunner(versions, transport, validator, TestRunners.defaultReportWriters(versions),
                    List.of(), Clock.systemDefaultZone());
            return runner.run(configuration);
        }
    }

    /** Runs and returns the result of the only TestScript. */
    TestScriptResult runOne() {
        RunResult result = run();
        return result.testScripts().getFirst();
    }

    private void write(String file, String content) {
        try {
            Files.writeString(dir.resolve(file), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
