package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The common ground of the engine tests: an {@link InMemoryFhirServer} per test, a {@link Harness} that runs
 * TestScript models through the real {@link TestRunner} against it, and the resources most tests use.
 *
 * <p>One subclass per concern, e.g. {@code OperationsTest} or {@code ChecksBeforeSendingTest}; the table of the
 * skill {@code testrunner-testing} says which covers what.
 */
abstract class EngineTestBase {

    static final String PATIENT = """
            {"resourceType":"Patient","id":"pat-1","name":[{"family":"Bucher"}],
             "managingOrganization":{"reference":"Organization/org-1"}}""";
    static final String ORGANIZATION = """
            {"resourceType":"Organization","id":"org-1","name":"Burgers"}""";

    @TempDir
    Path dir;
    InMemoryFhirServer server;
    Harness harness;

    @BeforeEach
    void start() {
        server = InMemoryFhirServer.start("4.0.1");
        harness = new Harness(dir, server.baseUrl());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** The phase with that name, e.g. {@code Setup} or the name of a test. */
    static PhaseResult phase(TestScriptResult result, String name) {
        return result.phases().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }
}
