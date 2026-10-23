package at.hl7.testing.runner.autoconfigure;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.engine.RunListener;
import at.hl7.testing.runner.core.engine.TestRunner;
import at.hl7.testing.runner.core.engine.TestRunners;
import at.hl7.testing.runner.core.http.HttpTransport;
import at.hl7.testing.runner.core.http.JdkHttpTransport;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.FhirVersionSupport;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The pre-defined Spring configuration of the runner.
 *
 * <p>Binds the externalized properties {@code testrunner.*} to {@link RunConfiguration} and wires a
 * {@link TestRunner}. Every bean backs off when the application defines its own, so any part can be replaced or
 * extended in a regular {@code @Configuration}: e.g. another {@link HttpTransport} (proxy, mutual TLS), another
 * {@link ProfileValidator}, more {@link ReportWriter}s or {@link RunListener}s, or a {@link RunConfiguration}
 * built in code instead of bound from properties.
 */
@AutoConfiguration
public class TestRunnerAutoConfiguration {

    /** Prefix of the externalized configuration. */
    public static final String PREFIX = "testrunner";

    /**
     * The configuration of the run, bound from {@code testrunner.*}.
     *
     * <p>Bound with the {@link Binder} rather than {@code @ConfigurationProperties}: {@link RunConfiguration} is an
     * immutable record of the framework-free core, which the binder fills through its constructor, so the core needs
     * no Spring annotation. Relaxed names, profiles, environment variables and conversions (durations, paths) work
     * as for any Spring Boot property.
     */
    @Bean
    @ConditionalOnMissingBean
    public RunConfiguration testRunnerConfiguration(Environment environment) {
        Binder binder = Binder.get(environment);
        // the binder ignores unknown properties; this one moved and would otherwise silently stop loading the IGs
        binder.bind(PREFIX + ".validation.igs", Bindable.listOf(String.class)).ifBound(igs -> {
            if (!igs.isEmpty()) {
                throw new InvalidConfigurationPropertyValueException(PREFIX + ".validation.igs", igs,
                        "The IGs are set per suite now: move them to " + PREFIX + ".suites[n].igs and pin "
                                + PREFIX + ".suites[n].fhir-version");
            }
        });
        return binder.bind(PREFIX, RunConfiguration.class)
                .orElseGet(() -> new RunConfiguration(null, null, null, null, null, null));
    }

    /**
     * The FHIR versions: every {@link FhirVersionSupport} bean, plus the modules registered with
     * {@link ServiceLoader} for versions no bean covers.
     */
    @Bean
    @ConditionalOnMissingBean
    public FhirVersionRegistry fhirVersionRegistry(ObjectProvider<FhirVersionSupport> beans) {
        List<FhirVersionSupport> supports = new ArrayList<>(beans.orderedStream().toList());
        ServiceLoader.load(FhirVersionSupport.class).forEach(found -> {
            if (supports.stream().noneMatch(s -> s.version() == found.version())) {
                supports.add(found);
            }
        });
        return new FhirVersionRegistry(supports);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(HttpTransport.class)
    public JdkHttpTransport httpTransport(RunConfiguration configuration) {
        return new JdkHttpTransport(configuration.http());
    }

    /** Validation is off unless a module (e.g. testrunner-validation-cli) or the application provides a validator. */
    @Bean
    @ConditionalOnMissingBean
    public ProfileValidator profileValidator() {
        return ProfileValidator.DISABLED;
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock testRunnerClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnMissingBean
    public TestRunner testRunner(FhirVersionRegistry versions, HttpTransport transport, ProfileValidator validator,
                                 ObjectProvider<ReportWriter> reportWriters, ObjectProvider<RunListener> listeners,
                                 Clock clock) {
        List<ReportWriter> writers = new ArrayList<>(reportWriters.orderedStream().toList());
        for (ReportWriter builtIn : TestRunners.defaultReportWriters()) {
            if (writers.stream().noneMatch(w -> w.format().equals(builtIn.format()))) {
                writers.add(builtIn);
            }
        }
        return new TestRunner(versions, transport, validator, writers, listeners.orderedStream().toList(), clock);
    }

    /** Writes a log file per run into the run's folder, when Logback is the logging backend. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    static class RunLogFileConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public RunLogFileListener runLogFileListener() {
            return new RunLogFileListener();
        }
    }
}
