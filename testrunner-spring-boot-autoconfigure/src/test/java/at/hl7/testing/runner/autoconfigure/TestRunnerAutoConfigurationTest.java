package at.hl7.testing.runner.autoconfigure;

import at.hl7.testing.runner.core.config.OAuth2Config;
import at.hl7.testing.runner.core.config.ReferenceRewriting;
import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.config.ValidatorMode;
import at.hl7.testing.runner.core.engine.RunInfo;
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
                "testrunner.suites[0].igs=hl7.at.fhir.core.r4#2.0.0",
                "testrunner.suites[1].base-dir=testdata/r5",
                "testrunner.suites[1].fhir-server.base-url=http://r5/fhir",
                "testrunner.suites[1].fhir-server.oauth2.token-url=https://auth.example/token",
                "testrunner.suites[1].fhir-server.oauth2.client-id=runner",
                "testrunner.suites[1].fhir-server.oauth2.client-secret=secret",
                "testrunner.suites[1].fhir-server.oauth2.client-authentication=post",
                "testrunner.suites[1].parallel=false",
                "testrunner.suites[2].package-id=hl7.at.fhir.core.r4#dev",
                "testrunner.suites[2].fhir-servers.1.base-url=http://one/fhir",
                "testrunner.suites[2].fhir-servers.2.base-url=http://two/fhir",
                "testrunner.suites[2].fhir-servers.2.headers.X-Api-Key=k",
                "testrunner.packages.cache-dir=/tmp/fhir-packages",
                "testrunner.packages.registry=https://registry.example",
                "testrunner.packages.ci-build=https://ci.example",
                "testrunner.execution.parallelism=4",
                "testrunner.execution.rewrite-references=all",
                "testrunner.execution.timeout=30m",
                "testrunner.execution.test-script-timeout=90s",
                "testrunner.execution.fail-fast=true",
                "testrunner.http.request-timeout=2m",
                "testrunner.validation.validator-jar=/opt/validator_cli.jar",
                "testrunner.reporting.formats=junit,json",
                "testrunner.reporting.output-dir=/tmp/results",
                "testrunner.reporting.redact-headers=Authorization,X-Api-Key",
                "testrunner.select.test-scripts=*patient*,TestScriptOrg",
                "testrunner.select.tests[0]=read-*")
                .run(context -> {
                    RunConfiguration config = context.getBean(RunConfiguration.class);
                    assertThat(config.fhirServer().base()).contains("http://server/fhir");
                    assertThat(config.fhirServer().headers()).containsEntry("Authorization", "Bearer x");
                    assertThat(config.suites()).hasSize(3);
                    assertThat(config.suites().get(2).packageId()).isEqualTo("hl7.at.fhir.core.r4#dev");
                    assertThat(config.suites().get(2).fhirServers()).containsOnlyKeys(1, 2);
                    assertThat(config.suites().get(2).fhirServers().get(2).base()).contains("http://two/fhir");
                    assertThat(config.suites().get(2).fhirServers().get(2).headers()).containsEntry("X-Api-Key", "k");
                    assertThat(config.suites().getFirst().fhirServers()).as("default").isEmpty();
                    assertThat(config.packages().cacheDir()).isEqualTo(Path.of("/tmp/fhir-packages"));
                    assertThat(config.packages().registry()).hasToString("https://registry.example");
                    assertThat(config.packages().ciBuild()).hasToString("https://ci.example");
                    assertThat(config.suites().getFirst().baseDir()).isEqualTo(Path.of("testdata/r4"));
                    assertThat(config.suites().getFirst().pinnedFhirVersion()).contains(FhirVersion.R4);
                    assertThat(config.suites().getFirst().include()).containsExactly("TestScript-*.json");
                    assertThat(config.suites().getFirst().igs()).containsExactly("hl7.at.fhir.core.r4#2.0.0");
                    assertThat(config.suites().get(1).pinnedFhirVersion()).isEmpty();
                    assertThat(config.suites().get(1).igs()).isEmpty();
                    assertThat(config.suites().getFirst().parallel()).as("default").isTrue();
                    assertThat(config.suites().get(1).parallel()).isFalse();
                    assertThat(config.fhirServerOf(config.suites().get(1)).base()).contains("http://r5/fhir");
                    assertThat(config.fhirServerOf(config.suites().get(1)).oauth2()).isEqualTo(new OAuth2Config(
                            java.net.URI.create("https://auth.example/token"), "runner", "secret", null,
                            OAuth2Config.ClientAuthentication.POST));
                    assertThat(config.fhirServer().oauth2()).isNull();
                    assertThat(config.execution().parallelism()).isEqualTo(4);
                    assertThat(config.execution().rewriteReferences()).isEqualTo(ReferenceRewriting.ALL);
                    assertThat(config.execution().timeout()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(config.execution().testScriptTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(config.execution().failFast()).isTrue();
                    assertThat(config.http().requestTimeout()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(config.http().connectTimeout()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(config.validation().validatorJar()).isEqualTo(Path.of("/opt/validator_cli.jar"));
                    assertThat(config.validation().terminologyServer()).isEqualTo("n/a");
                    assertThat(config.reporting().formats()).containsExactly("junit", "json");
                    assertThat(config.reporting().outputDir()).isEqualTo(Path.of("/tmp/results"));
                    assertThat(config.reporting().redactHeaders()).containsExactly("Authorization", "X-Api-Key");
                    assertThat(config.select().testScripts()).containsExactly("*patient*", "TestScriptOrg");
                    assertThat(config.select().tests()).containsExactly("read-*");
                });
    }

    @Test
    void wiresTheRunnerWithTheValidatorServersWhenValidationIsEnabled() {
        contexts.run(context -> {
            assertThat(context).hasSingleBean(TestRunner.class).hasSingleBean(RunLogFileListener.class);
            assertThat(context.getBean(ProfileValidator.class).getClass().getSimpleName())
                    .isEqualTo("ValidatorServerProfileValidator");
        });
    }

    @Test
    void processModeIsSelectedByProperty() {
        contexts.withPropertyValues("testrunner.validation.mode=process")
                .run(context -> assertThat(context.getBean(ProfileValidator.class).getClass().getSimpleName())
                        .isEqualTo("ValidatorCliProfileValidator"));
    }

    @Test
    void serverSettingsAreBound() {
        contexts.withPropertyValues("testrunner.validation.server.startup-timeout=2m",
                        "testrunner.validation.server.fallback-to-process=false",
                        "testrunner.validation.jvm-arguments=-Xmx2g")
                .run(context -> {
                    RunConfiguration config = context.getBean(RunConfiguration.class);
                    assertThat(config.validation().mode()).isEqualTo(ValidatorMode.SERVER);
                    assertThat(config.validation().server().startupTimeout()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(config.validation().server().fallbackToProcess()).isFalse();
                    assertThat(config.validation().jvmArguments()).containsExactly("-Xmx2g");
                    assertThat(context.getBean(ProfileValidator.class).getClass().getSimpleName())
                            .isEqualTo("ValidatorServerProfileValidator");
                    assertThat(context.getBean(ProfileValidator.class)).isInstanceOf(AutoCloseable.class);
                });
    }

    @Test
    void theFormerRunWideIgsAreRejectedInsteadOfIgnored() {
        contexts.withPropertyValues("testrunner.validation.igs=hl7.at.fhir.core.r4#2.0.0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("testrunner.validation.igs")
                        .hasMessageContaining("testrunner.suites[n].igs"));
    }

    @Test
    void serverModeWithFallbackIsTheDefault() {
        contexts.run(context -> {
            RunConfiguration config = context.getBean(RunConfiguration.class);
            assertThat(config.validation().mode()).isEqualTo(ValidatorMode.SERVER);
            assertThat(config.validation().server().fallbackToProcess()).isTrue();
        });
    }

    @Test
    void validationCanBeSwitchedOff() {
        contexts.withPropertyValues("testrunner.validation.enabled=false")
                .run(context -> assertThat(context.getBean(ProfileValidator.class)).isSameAs(ProfileValidator.DISABLED));
    }

    @Test
    void everyBeanBacksOffForTheApplicationsOwn() {
        HttpTransport transport = request -> FhirResponse.text(200, "OK", "HTTP/1.1", null, "");
        RunConfiguration own = new RunConfiguration(null, null, null, null, null, null, null, null);
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
            public void runFinished(RunInfo run, RunResult result) {
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
