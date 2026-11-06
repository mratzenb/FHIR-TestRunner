package at.hl7.testing.runner.core.spi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/** The issues found by a validation. The resource is valid if no issue is an error. */
public record ValidationResult(@Nonnull List<Issue> issues) {

    public ValidationResult {
        issues = List.copyOf(issues);
    }

    public enum Severity { FATAL, ERROR, WARNING, INFORMATION }

    /**
     * One issue.
     *
     * @param severity how severe
     * @param location where in the resource, e.g. {@code Patient.name}, or {@code null}
     * @param message  what is wrong
     */
    public record Issue(@Nonnull Severity severity, @Nullable String location, @Nonnull String message) {

        public boolean isError() {
            return severity == Severity.ERROR || severity == Severity.FATAL;
        }

        @Override
        @Nonnull
        public String toString() {
            String where = location == null || location.isBlank() ? "" : " @ " + location;
            return severity.name().toLowerCase(java.util.Locale.ROOT) + where + ": " + message;
        }
    }

    public boolean isValid() {
        return issues.stream().noneMatch(Issue::isError);
    }

    @Nonnull
    public List<Issue> errors() {
        return issues.stream().filter(Issue::isError).toList();
    }

    @Nonnull
    public List<Issue> warnings() {
        return issues.stream().filter(i -> !i.isError()).toList();
    }

    /** All issues of the given kind, one per line. */
    @Nonnull
    public static String describe(@Nonnull List<Issue> issues) {
        return issues.stream().map(Issue::toString).collect(Collectors.joining("\n"));
    }
}
