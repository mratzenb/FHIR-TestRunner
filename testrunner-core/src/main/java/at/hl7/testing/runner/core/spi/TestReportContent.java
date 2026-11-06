package at.hl7.testing.runner.core.spi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The content of a FHIR {@code TestReport} for one TestScript, in the runner's terms. The core decides what goes into
 * it ({@link at.hl7.testing.runner.core.report.TestReportWriter}); the FHIR version module turns it into the
 * {@code TestReport} resource of its version ({@link FhirVersionSupport#testReport}).
 *
 * @param id         the resource id
 * @param identifier the run id and the TestScript's position in the run, e.g. {@code run_2026-10-26_10-00-00-000/03}
 * @param name       the name of the TestScript
 * @param testScript canonical URL of the TestScript, or the URI of its file if it has none
 * @param result     {@code pass}, {@code fail} or {@code pending} (not run)
 * @param score      percentage of the executed assertions that passed, {@code null} if none was executed
 * @param tester     who ran the TestScript
 * @param issued     when the TestScript was run
 * @param servers    base URL of each server tested, one {@code participant} each; empty if nothing was sent
 * @param setup      the setup actions, empty if there are none
 * @param tests      the tests
 * @param teardown   the teardown actions, empty if there are none
 */
public record TestReportContent(@Nonnull String id, @Nonnull String identifier, @Nonnull String name,
                                @Nonnull String testScript, @Nonnull String result, @Nullable BigDecimal score,
                                @Nonnull String tester, @Nonnull Instant issued, @Nonnull List<String> servers,
                                @Nonnull List<Action> setup, @Nonnull List<Test> tests,
                                @Nonnull List<Action> teardown) {

    public TestReportContent {
        servers = List.copyOf(servers);
        setup = List.copyOf(setup);
        tests = List.copyOf(tests);
        teardown = List.copyOf(teardown);
    }

    /**
     * A test of the report.
     *
     * @param name    the name
     * @param actions the actions, at least one
     */
    public record Test(@Nonnull String name, @Nonnull List<Action> actions) {

        public Test {
            actions = List.copyOf(actions);
        }
    }

    /**
     * An action of the report ({@code action.operation} or {@code action.assert}).
     *
     * @param assertion whether it is an assertion, else an operation
     * @param result    {@code pass}, {@code skip}, {@code fail}, {@code warning} or {@code error}
     * @param message   what was done and, if it did not pass, why
     * @param detail    where to find more: the communication log folder of the request, relative to the run folder,
     *                  or the request id; {@code null} if no request is involved
     */
    public record Action(boolean assertion, @Nonnull String result, @Nonnull String message, @Nullable String detail) {
    }
}
