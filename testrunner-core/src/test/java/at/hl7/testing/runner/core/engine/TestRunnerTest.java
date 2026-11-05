package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.FhirServerConfig;
import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.config.PackagesConfig;
import at.hl7.testing.runner.core.config.ReportingConfig;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.config.SuiteConfig;
import at.hl7.testing.runner.core.http.JdkHttpTransport;
import at.hl7.testing.runner.core.model.Check;
import at.hl7.testing.runner.core.model.Operator;
import at.hl7.testing.runner.core.model.VariableDefinition;
import at.hl7.testing.runner.core.report.ActionResult;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static at.hl7.testing.runner.core.engine.Scripts.check;
import static at.hl7.testing.runner.core.engine.Scripts.op;
import static at.hl7.testing.runner.core.engine.Scripts.responseCode;
import static at.hl7.testing.runner.core.engine.Scripts.script;
import static at.hl7.testing.runner.core.engine.Scripts.test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The run as a whole: suites with {@code parallel = false}, parallel TestScripts, package suites. */
class TestRunnerTest extends EngineTestBase {

    @Test
    void suiteWithParallelFalseRunsAloneBeforeTheOthers() {
        for (String name : List.of("s1", "s2")) {
            harness.addSerial(name, script().test(test("t", op("search", "Patient", null, null, "?s=" + name, null)))
                    .build());
        }
        for (String name : List.of("p1", "p2", "p3")) {
            harness.add(name, script().test(test("t", op("search", "Patient", null, null, "?p=" + name, null))).build());
        }

        var run = harness.parallelism(3).run();

        assertThat(run.testScripts()).extracting(TestScriptResult::index).containsExactly(1, 2, 3, 4, 5);
        assertThat(server.requests()).extracting(InMemoryFhirServer.Request::query).startsWith("s=s1", "s=s2")
                .containsExactlyInAnyOrder("s=s1", "s=s2", "p=p1", "p=p2", "p=p3");
    }

    @Test
    void parallelRunsKeepTheirStateApartAndRequestIdsUnique() {
        for (int i = 0; i < 6; i++) {
            harness.resource("p" + i, PATIENT.replace("pat-1", "pat-" + i).replace("Organization/org-1", "Organization/none"));
            harness.add("ts" + i, script().fixture("p" + i, false, false)
                    .variable(new VariableDefinition("pid", null, "Patient.id", null, null, "created", null))
                    .test(test("t",
                            op("create", "Patient", "p" + i, null, null, "created"),
                            responseCode("201"),
                            op("read", "Patient", null, null, "/${pid}", null),
                            check(new Check.Expression("Patient.id"), Operator.EQUALS, "${pid}")))
                    .build());
        }

        var run = harness.parallelism(3).run();

        assertThat(run.testScripts()).hasSize(6).allMatch(r -> r.outcome() == Outcome.PASS);
        List<String> requestIds = run.testScripts().stream().flatMap(r -> r.phases().stream())
                .flatMap(p -> p.actions().stream()).filter(a -> a.kind().equals(ActionResult.OPERATION))
                .map(ActionResult::requestId).toList();
        assertThat(requestIds).hasSize(12).doesNotHaveDuplicates();
    }

    @Test
    void configurationProblemOfTheValidatorStopsTheRunBeforeAnythingIsSent() {
        harness.validator(new ProfileValidator() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public List<String> configurationProblems() {
                return List.of("testrunner.validation.x is not set");
            }

            @Override
            public ValidationResult validate(ValidationRequest request) {
                throw new AssertionError("never called");
            }
        }).add("ts", script().test(test("t", op("capabilities"))).build());

        assertThatThrownBy(harness::run).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("testrunner.validation.x is not set");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void packageSuiteRunsWithoutValidatorModuleAndDefaultValidationSettings() throws Exception {
        // library use without testrunner-validation-cli: validation "enabled" by default, but no jar and no validator
        Path packageDir = Files.createDirectories(dir.resolve("cache/example.ig#dev/package/example")).getParent();
        Files.writeString(packageDir.resolve("package.json"), """
                {"name":"example.ig","fhirVersions":["4.0.1"],"directories":{"example":"example"}}""");
        Files.writeString(packageDir.resolve("TestScript-ts.json"), "ts");
        var configuration = new RunConfiguration(FhirServerConfig.of(server.baseUrl()),
                List.of(new SuiteConfig(null, null, null, null, null, null, null, null, null, null, "example.ig#dev")),
                null, null, null,
                new ReportingConfig(dir.resolve("results"), null, false, false, null, null),
                new PackagesConfig(dir.resolve("cache"), null), null);
        var scripts = Map.of("ts", script().test(test("t", op("capabilities"), responseCode("200"))).build());
        var versions = new FhirVersionRegistry(List.of(StubVersionSupport.of(scripts)));

        try (var transport = new JdkHttpTransport(HttpConfig.defaults())) {
            var run = new TestRunner(versions, transport, ProfileValidator.DISABLED,
                    TestRunners.defaultReportWriters(versions), List.of(), Clock.systemDefaultZone())
                    .run(configuration);

            assertThat(run.testScripts()).singleElement().satisfies(r -> {
                assertThat(r.suite()).isEqualTo("example.ig#dev");
                assertThat(r.outcome()).isEqualTo(Outcome.PASS);
            });
        }
    }
}
