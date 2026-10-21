package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.spi.ValidationResult;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the OperationOutcome written by the validator. The issue elements used are the same in R4 and R5. With
 * several input files the validator writes a Bundle of OperationOutcomes; all their issues are collected.
 */
final class OperationOutcomeReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OperationOutcomeReader() {
    }

    static ValidationResult read(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ValidatorUnavailableException("The FHIR validator wrote an unreadable result: " + e.getOriginalMessage(), e);
        }
        List<ValidationResult.Issue> issues = new ArrayList<>();
        switch (root.path("resourceType").asText()) {
            case "OperationOutcome" -> collect(root, issues);
            case "Bundle" -> root.path("entry").forEach(entry -> collect(entry.path("resource"), issues));
            default -> throw new ValidatorUnavailableException("The FHIR validator wrote no OperationOutcome but a "
                    + root.path("resourceType").asText("document without resourceType"));
        }
        return new ValidationResult(issues);
    }

    private static void collect(JsonNode outcome, List<ValidationResult.Issue> issues) {
        for (JsonNode issue : outcome.path("issue")) {
            String text = issue.path("details").path("text").asText(issue.path("diagnostics").asText(""));
            if (isAllOk(issue, text)) {
                continue;
            }
            String location = issue.path("expression").path(0).asText(issue.path("location").path(0).asText(null));
            issues.add(new ValidationResult.Issue(severity(issue.path("severity").asText()), location, text));
        }
    }

    /** The validator reports a clean result as a single information issue "All OK". */
    private static boolean isAllOk(JsonNode issue, String text) {
        return "information".equals(issue.path("severity").asText()) && "All OK".equalsIgnoreCase(text.trim());
    }

    private static ValidationResult.Severity severity(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "fatal" -> ValidationResult.Severity.FATAL;
            case "error" -> ValidationResult.Severity.ERROR;
            case "warning" -> ValidationResult.Severity.WARNING;
            default -> ValidationResult.Severity.INFORMATION;
        };
    }
}
