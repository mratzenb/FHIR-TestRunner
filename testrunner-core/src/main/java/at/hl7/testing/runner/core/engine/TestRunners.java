package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.http.JdkHttpTransport;
import at.hl7.testing.runner.core.report.HtmlReportWriter;
import at.hl7.testing.runner.core.report.JUnitXmlReportWriter;
import at.hl7.testing.runner.core.report.JsonReportWriter;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.report.SummaryReportWriter;
import at.hl7.testing.runner.core.report.TestReportWriter;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import jakarta.annotation.Nonnull;

import java.time.Clock;
import java.util.List;

/** Creates a {@link TestRunner} with the default collaborators, for use without Spring. */
public final class TestRunners {

    private TestRunners() {
    }

    /**
     * The built-in report writers.
     *
     * @param versions the FHIR versions, for the TestReports in the version of each TestScript
     */
    @Nonnull
    public static List<ReportWriter> defaultReportWriters(@Nonnull FhirVersionRegistry versions) {
        return List.of(new JUnitXmlReportWriter(), new SummaryReportWriter(), new JsonReportWriter(),
                new TestReportWriter(versions), new HtmlReportWriter());
    }

    /**
     * A runner with the FHIR version modules found on the classpath, the JDK HTTP client and the built-in report
     * writers.
     */
    @Nonnull
    public static TestRunner create(@Nonnull HttpConfig http, @Nonnull ProfileValidator validator) {
        FhirVersionRegistry versions = FhirVersionRegistry.fromServiceLoader();
        return new TestRunner(versions, new JdkHttpTransport(http), validator, defaultReportWriters(versions),
                List.of(), Clock.systemDefaultZone());
    }
}
