package at.hl7.testing.runner.core.engine;

import at.hl7.testing.runner.core.config.HttpConfig;
import at.hl7.testing.runner.core.http.JdkHttpTransport;
import at.hl7.testing.runner.core.report.JUnitXmlReportWriter;
import at.hl7.testing.runner.core.report.JsonReportWriter;
import at.hl7.testing.runner.core.report.ReportWriter;
import at.hl7.testing.runner.core.report.SummaryReportWriter;
import at.hl7.testing.runner.core.report.TestReportWriter;
import at.hl7.testing.runner.core.spi.FhirVersionRegistry;
import at.hl7.testing.runner.core.spi.ProfileValidator;

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
    public static List<ReportWriter> defaultReportWriters(FhirVersionRegistry versions) {
        return List.of(new JUnitXmlReportWriter(), new SummaryReportWriter(), new JsonReportWriter(),
                new TestReportWriter(versions));
    }

    /**
     * A runner with the FHIR version modules found on the classpath, the JDK HTTP client and the built-in report
     * writers.
     */
    public static TestRunner create(HttpConfig http, ProfileValidator validator) {
        FhirVersionRegistry versions = FhirVersionRegistry.fromServiceLoader();
        return new TestRunner(versions, new JdkHttpTransport(http), validator, defaultReportWriters(versions),
                List.of(), Clock.systemDefaultZone());
    }
}
