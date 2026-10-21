package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.http.JdkHttpTransport;
import at.hl7.testing.runner.core.report.JUnitXmlReportWriter;
import at.hl7.testing.runner.core.report.JsonReportWriter;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.report.SummaryReportWriter;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;

import java.time.Clock;
import java.util.List;

/** Creates a {@link TestRunner} with the default collaborators, for use without Spring. */
public final class TestRunners {

    private TestRunners() {
    }

    /** The built-in report writers. */
    public static List<ReportWriter> defaultReportWriters() {
        return List.of(new JUnitXmlReportWriter(), new SummaryReportWriter(), new JsonReportWriter());
    }

    /**
     * A runner with the FHIR version modules found on the classpath, the JDK HTTP client and the built-in report
     * writers.
     */
    public static TestRunner create(HttpConfig http, ProfileValidator validator) {
        return new TestRunner(FhirVersionRegistry.fromServiceLoader(), new JdkHttpTransport(http), validator,
                defaultReportWriters(), List.of(), Clock.systemDefaultZone());
    }
}
