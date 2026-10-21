package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.config.ReportingConfig;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Writes {@code junit.xml}: one {@code testsuite} per TestScript, one {@code testcase} per phase. A failed
 * assertion is a {@code failure}, an action that could not be executed an {@code error}.
 */
public final class JUnitXmlReportWriter implements ReportWriter {

    @Override
    public String format() {
        return ReportingConfig.JUNIT;
    }

    @Override
    public Path write(RunResult result, Path runDirectory) throws IOException {
        Path file = runDirectory.resolve("junit.xml");
        try (OutputStream out = Files.newOutputStream(file)) {
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            newline(xml, 0);
            xml.writeStartElement("testsuites");
            xml.writeAttribute("name", result.runId());
            xml.writeAttribute("time", seconds(result.duration()));
            for (TestScriptResult testScript : result.testScripts()) {
                writeSuite(xml, testScript);
            }
            newline(xml, 0);
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IOException("Could not write " + file, e);
        }
        return file;
    }

    private static void writeSuite(XMLStreamWriter xml, TestScriptResult testScript) throws XMLStreamException {
        List<PhaseResult> phases = testScript.phases();
        newline(xml, 1);
        xml.writeStartElement("testsuite");
        xml.writeAttribute("name", testScript.name());
        xml.writeAttribute("tests", String.valueOf(phases.size()));
        xml.writeAttribute("failures", String.valueOf(count(phases, Outcome.FAIL)));
        xml.writeAttribute("errors", String.valueOf(count(phases, Outcome.ERROR)));
        xml.writeAttribute("skipped", String.valueOf(count(phases, Outcome.SKIP)));
        xml.writeAttribute("time", seconds(testScript.duration()));
        xml.writeAttribute("timestamp", DateTimeFormatter.ISO_INSTANT.format(testScript.started()));
        newline(xml, 2);
        xml.writeStartElement("properties");
        property(xml, "url", testScript.url());
        property(xml, "source", testScript.source().toString());
        property(xml, "suite", testScript.suite());
        property(xml, "fhirVersion", testScript.fhirVersion() == null ? "" : testScript.fhirVersion().name());
        newline(xml, 2);
        xml.writeEndElement();
        for (PhaseResult phase : phases) {
            writeCase(xml, phase);
        }
        newline(xml, 1);
        xml.writeEndElement();
    }

    private static void writeCase(XMLStreamWriter xml, PhaseResult phase) throws XMLStreamException {
        newline(xml, 2);
        xml.writeStartElement("testcase");
        xml.writeAttribute("name", phase.name());
        xml.writeAttribute("classname", phase.kind().label());
        xml.writeAttribute("assertions", String.valueOf(phase.assertionCount()));
        xml.writeAttribute("time", seconds(phase.duration()));
        List<String> messages = phase.messages();
        String first = messages.isEmpty() ? "" : messages.getFirst();
        String rest = String.join("\n", messages.size() > 1 ? messages.subList(1, messages.size()) : List.of());
        switch (phase.outcome()) {
            case FAIL -> element(xml, "failure", first, "AssertionFailure", rest);
            case ERROR -> element(xml, "error", first, "ExecutionError", rest);
            case SKIP -> element(xml, "skipped", first, null, rest);
            case PASS -> {
                newline(xml, 3);
                xml.writeStartElement("system-out");
                xml.writeCharacters(messages.isEmpty() ? phase.kind().label() + " passed" : String.join("\n", messages));
                xml.writeEndElement();
            }
        }
        newline(xml, 2);
        xml.writeEndElement();
    }

    private static void element(XMLStreamWriter xml, String name, String message, String type, String text)
            throws XMLStreamException {
        newline(xml, 3);
        xml.writeStartElement(name);
        xml.writeAttribute("message", message);
        if (type != null) {
            xml.writeAttribute("type", type);
        }
        if (!text.isEmpty()) {
            xml.writeCharacters(text);
        }
        xml.writeEndElement();
    }

    private static void property(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        newline(xml, 3);
        xml.writeEmptyElement("property");
        xml.writeAttribute("name", name);
        xml.writeAttribute("value", value == null ? "" : value);
    }

    private static long count(List<PhaseResult> phases, Outcome outcome) {
        return phases.stream().filter(p -> p.outcome() == outcome).count();
    }

    private static String seconds(Duration duration) {
        return String.format(Locale.ROOT, "%.3f", duration.toNanos() / 1e9);
    }

    private static void newline(XMLStreamWriter xml, int depth) throws XMLStreamException {
        xml.writeCharacters("\n" + "  ".repeat(depth));
    }
}
