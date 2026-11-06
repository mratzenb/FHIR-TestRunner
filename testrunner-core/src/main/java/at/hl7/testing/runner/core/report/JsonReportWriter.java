package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.config.ReportingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.nio.file.Path;

/** Writes {@code result.json}: the complete result including every action, for further processing. */
public final class JsonReportWriter implements ReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    @Nonnull
    public String format() {
        return ReportingConfig.JSON;
    }

    @Override
    @Nonnull
    public Path write(@Nonnull RunResult result, @Nonnull Path runDirectory) throws IOException {
        Path file = runDirectory.resolve("result.json");
        ObjectNode root = JSON.createObjectNode()
                .put("runId", result.runId())
                .put("started", result.started().toString())
                .put("durationMs", result.duration().toMillis())
                .put("outcome", result.outcome().name());
        ArrayNode scripts = root.putArray("testScripts");
        for (TestScriptResult testScript : result.testScripts()) {
            ObjectNode node = scripts.addObject()
                    .put("index", testScript.index())
                    .put("suite", testScript.suite())
                    .put("name", testScript.name())
                    .put("url", testScript.url())
                    .put("source", testScript.source().toString())
                    .put("fhirVersion", testScript.fhirVersion() == null ? null : testScript.fhirVersion().name())
                    .put("outcome", testScript.outcome().name())
                    .put("durationMs", testScript.duration().toMillis());
            ArrayNode phases = node.putArray("phases");
            for (PhaseResult phase : testScript.phases()) {
                ObjectNode phaseNode = phases.addObject()
                        .put("kind", phase.kind().name())
                        .put("name", phase.name())
                        .put("outcome", phase.outcome().name())
                        .put("durationMs", phase.duration().toMillis())
                        .put("assertionCount", phase.assertionCount());
                phase.messages().forEach(phaseNode.putArray("messages")::add);
                ArrayNode actions = phaseNode.putArray("actions");
                for (ActionResult action : phase.actions()) {
                    actions.addObject()
                            .put("kind", action.kind())
                            .put("description", action.description())
                            .put("outcome", action.outcome().name())
                            .put("message", action.message())
                            .put("warning", action.warning())
                            .put("requestId", action.requestId());
                }
            }
        }
        JSON.writeValue(file.toFile(), root);
        return file;
    }
}
