package at.hl7.testing.runner.autoconfigure;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.engine.RunContext;
import at.hl7.testing.runner.core.engine.RunListener;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.http.FhirResponse;
import at.hl7.testing.runner.core.http.HttpTransport;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.report.RunResult;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunnerAutoConfigurationTest {

    final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidatorCliAutoConfiguration.class, TestRunnerAutoConfiguration.class));

    @Test
    void bindsTheExternalizedProperties() {
        contexts.withPropertyValues(
                "testrunner.fhir-server.base-url=http://server/fhir",
                "testrunner.fhir-server.headers.Authorization=Bearer x",
                "testrunner.suites[0].base-dir=testdata/r4",
                "testrunner.suites[0].fhir-version=R4",
                "testrunner.suites[0].include=TestScript-*.json",
                "testrunner.suites[1].base-dir=testdata/r5",
                "testrunner.suites[1].fhir-server.base-url=http://r5/fhir",
                "testrunner.execution.parallelism=4",
                "testrunner.http.request-timeout=2m",
                "testrunner.validation.validator-jar=/opt/validator_cli.jar",
                "testrunner.validation.igs=hl7.at.fhir.core.r4#2.0.0",
                "testrunner.reporting.formats=junit,json",
                "testrunner.reporting.output-dir=/tmp/results")
                .run(context -> {
                    RunConfiguration config = context.getBean(RunConfiguration.class);
                    assertThat(config.fhirServer().base()).isEqualTo("http://server/fhir");
                    assertThat(config.fhirServer().headers()).containsEntry("Authorization", "Bearer x");
                    assertThat(config.suites()).hasSize(2);
                    assertThat(config.suites().getFirst().baseDir()).isEqualTo(Path.of("testdata/r4"));
                    assertThat(config.suites().getFirst().pinnedFhirVersion()).contains(FhirVersion.R4);
                    assertThat(config.suites().getFirst().include()).containsExactly("TestScript-*.json");
                    assertThat(config.suites().get(1).pinnedFhirVersion()).isEmpty();
                    assertThat(config.fhirServerOf(config.suites().get(1)).base()).isEqualTo("http://r5/fhir");
                    assertThat(config.execution().parallelism()).isEqualTo(4);
                    assertThat(config.http().requestTimeout()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(config.http().connectTimeout()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(config.validation().validatorJar()).isEqualTo(Path.of("/opt/validator_cli.jar"));
                    assertThat(config.validation().igs()).containsExactly("hl7.at.fhir.core.r4#2.0.0");
                    assertThat(config.validation().terminologyServer()).isEqualTo("n/a");
                    assertThat(config.reporting().formats()).containsExactly("junit", "json");
                    assertThat(config.reporting().outputDir()).isEqualTo(Path.of("/tmp/results"));
                });
    }

    @Test
    void wiresTheRunnerWithTheValidatorCliWhenValidationIsEnabled() {
        contexts.run(context -> {
            assertThat(context).hasSingleBean(TestRunner.class).hasSingleBean(RunLogFileListener.class);
            assertThat(context.getBean(ProfileValidator.class).getClass().getSimpleName())
                    .isEqualTo("ValidatorCliProfileValidator");
        });
    }

    @Test
    void validationCanBeSwitchedOff() {
        contexts.withPropertyValues("testrunner.validation.enabled=false")
                .run(context -> assertThat(context.getBean(ProfileValidator.class)).isSameAs(ProfileValidator.DISABLED));
    }

    @Test
    void everyBeanBacksOffForTheApplicationsOwn() {
        HttpTransport transport = request -> new FhirResponse(200, "OK", "HTTP/1.1", null, "");
        RunConfiguration own = new RunConfiguration(null, null, null, null, null, null);
        contexts.withBean(HttpTransport.class, () -> transport)
                .withBean(RunConfiguration.class, () -> own)
                .withBean(ProfileValidator.class, () -> ProfileValidator.DISABLED)
                .run(context -> {
                    assertThat(context.getBean(HttpTransport.class)).isSameAs(transport);
                    assertThat(context.getBean(RunConfiguration.class)).isSameAs(own);
                    assertThat(context.getBean(ProfileValidator.class)).isSameAs(ProfileValidator.DISABLED);
                });
    }

    @Test
    void applicationsAddReportWritersAndListeners(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("suite/Test_Scripts"));
        List<String> events = new CopyOnWriteArrayList<>();
        ReportWriter custom = new ReportWriter() {
            @Override
            public String format() {
                return "custom";
            }

            @Override
            public Path write(RunResult result, Path runDirectory) throws IOException {
                return Files.writeString(runDirectory.resolve("custom.txt"), result.runId());
            }
        };
        RunListener listener = new RunListener() {
            @Override
            public void runFinished(RunContext run, RunResult result) {
                events.add("finished " + result.runId());
            }
        };
        contexts.withBean(ReportWriter.class, () -> custom)
                .withBean(RunListener.class, () -> listener)
                .withPropertyValues("testrunner.validation.enabled=false",
                        "testrunner.suites[0].base-dir=" + dir.resolve("suite"),
                        "testrunner.reporting.output-dir=" + dir.resolve("out"),
                        "testrunner.reporting.formats=custom,junit")
                .run(context -> {
                    RunResult result = context.getBean(TestRunner.class).run(context.getBean(RunConfiguration.class));

                    assertThat(result.reportFiles()).containsOnlyKeys("custom", "junit");
                    assertThat(events).containsExactly("finished " + result.runId());
                });
    }

    @Test
    void registryIsEmptyWithoutVersionModules() {
        contexts.run(context -> assertThat(context.getBean(FhirVersionRegistry.class).versions()).isEmpty());
    }
}
