package at.hl7.testing.runner.app;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.report.Outcome;
import at.hl7.testing.runner.core.report.PhaseResult;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.report.TestScriptResult;
import at.hl7.testing.runner.testsupport.InMemoryFhirServer;
import at.hl7.testing.runner.testsupport.SampleData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AT Core R4 suite and the negative control with the real HL7 validator. Opt-in, because it needs the 200 MB
 * {@code validator_cli.jar} and, on the first run, internet access for the FHIR packages:
 *
 * <pre>VALIDATOR_JAR=/path/to/validator_cli.jar mvn verify</pre>
 *
 * The FHIR server is still the in-memory one. Runs once per {@code testrunner.validation.mode}, without fallback, so
 * the server mode is really exercised.
 */
@EnabledIfEnvironmentVariable(named = "VALIDATOR_JAR", matches = ".+")
class RealValidatorIT {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"process", "server"})
    void validationRunsForReal(String mode) {
        try (InMemoryFhirServer server = InMemoryFhirServer.start("4.0.1");
             ConfigurableApplicationContext context = new SpringApplicationBuilder(TestRunnerApplication.class).run(
                     "--testrunner.run-on-startup=false",
                     "--testrunner.fhir-server.base-url=" + server.baseUrl(),
                     "--testrunner.validation.validator-jar=" + System.getenv("VALIDATOR_JAR"),
                     "--testrunner.validation.mode=" + mode,
                     "--testrunner.validation.server.fallback-to-process=false",
                     "--testrunner.reporting.output-dir=" + dir,
                     "--testrunner.execution.parallelism=3",
                     "--testrunner.suites[0].base-dir=" + SampleData.r4())) {

            long started = System.nanoTime();
            RunResult run = context.getBean(TestRunner.class).run(context.getBean(RunConfiguration.class));
            System.out.printf("Real validator, mode %s: %d ms%n", mode, (System.nanoTime() - started) / 1_000_000);

            assertThat(run.testScripts()).hasSize(3);
            for (TestScriptResult result : run.testScripts()) {
                if (result.source().getFileName().toString().contains("negative")) {
                    assertThat(result.outcome()).as("the negative control must fail").isEqualTo(Outcome.FAIL);
                    assertThat(result.phases()).flatExtracting(PhaseResult::messages)
                            .anyMatch(m -> m.contains("socialSecurityNumber"));
                } else {
                    assertThat(result.outcome()).as(result.name()).isEqualTo(Outcome.PASS);
                }
            }
        }
    }
}
