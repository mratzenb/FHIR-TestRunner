package at.hl7.testing.runner.core.report;

import at.hl7.testing.runner.core.config.ReportingConfig;
import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes {@code report.html}: one self-contained page per run for readers who do not open {@code junit.xml} – every
 * TestScript with its phases, actions and messages, and every request id as a link to its folder in
 * {@code communication/}.
 *
 * <p>Plain Java, no template engine. Every text is escaped: names, descriptions and messages carry what TestScript
 * authors wrote and what servers answered. The page has no script, and its Content-Security-Policy allows none, so
 * text that slipped through the escaping could not run either. A TestScript's url is shown, never linked. Links are
 * relative, so the run folder can be moved or archived as a whole.
 */
public final class HtmlReportWriter implements ReportWriter {

    /** The file in the run folder. */
    public static final String FILE = "report.html";

    private static final String STYLE = """
            :root{--fg:#1d2329;--muted:#5b6670;--bg:#fff;--line:#d9dee3;--soft:#f4f6f8;
              --pass:#1f7a4d;--fail:#b3261e;--error:#8a4b00;--skip:#5b6670}
            @media (prefers-color-scheme:dark){:root{--fg:#e3e7eb;--muted:#9aa5ae;--bg:#15191d;--line:#2e353c;
              --soft:#1d2329;--pass:#5cc58f;--fail:#ff8a80;--error:#f0b35a;--skip:#9aa5ae}}
            *{box-sizing:border-box}
            body{margin:0 auto;max-width:1200px;padding:16px;font:14px/1.45 system-ui,sans-serif;color:var(--fg);
              background:var(--bg)}
            h1{font-size:20px;margin:0 0 4px}
            h2{font-size:16px;display:inline}
            h3{font-size:14px;margin:12px 0 4px}
            p.meta,dl,.muted{color:var(--muted)}
            table{border-collapse:collapse;width:100%;margin:8px 0}
            th,td{text-align:left;vertical-align:top;padding:4px 8px;border-bottom:1px solid var(--line)}
            th{font-weight:600;background:var(--soft)}
            .num{text-align:right;white-space:nowrap}
            .badge{display:inline-block;min-width:52px;text-align:center;padding:1px 6px;border-radius:4px;
              font-weight:600;font-size:12px;color:var(--bg)}
            .badge.pass{background:var(--pass)}.badge.fail{background:var(--fail)}
            .badge.error{background:var(--error)}.badge.skip{background:var(--skip)}
            section.ts{border:1px solid var(--line);border-radius:6px;margin:12px 0;padding:0 12px}
            summary{cursor:pointer;padding:10px 0}
            dl{display:grid;grid-template-columns:max-content 1fr;gap:2px 12px;margin:0 0 8px}
            dt{font-weight:600}dd{margin:0;overflow-wrap:anywhere}
            pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.4 ui-monospace,monospace}
            ul.messages{margin:4px 0;padding-left:18px}
            .warning{color:var(--error)}
            a{color:inherit}
            label{display:block;margin:8px 0}
            body:has(#problems:checked) .only-problems-hidden{display:none}
            @media (max-width:700px){.wide{display:none}}
            """;

    @Override
    @Nonnull
    public String format() {
        return ReportingConfig.HTML;
    }

    @Override
    @Nonnull
    public Path write(@Nonnull RunResult result, @Nonnull Path runDirectory) throws IOException {
        Path file = runDirectory.resolve(FILE);
        Files.writeString(file, html(result, runDirectory), StandardCharsets.UTF_8);
        return file;
    }

    static String html(RunResult result, Path runDirectory) {
        Map<String, String> exchanges = ExchangeFolders.of(runDirectory);
        StringBuilder html = new StringBuilder(16_384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
                .append("<title>").append(escape(result.runId())).append(" – ").append(result.outcome())
                .append("</title>\n<style>\n").append(STYLE).append("</style>\n</head>\n<body>\n");
        header(html, result);
        overview(html, result);
        for (TestScriptResult testScript : result.testScripts()) {
            testScript(html, testScript, exchanges, runDirectory);
        }
        return html.append("</body>\n</html>\n").toString();
    }

    private static void header(StringBuilder html, RunResult result) {
        Map<Outcome, Long> counts = result.countByOutcome();
        html.append("<header>\n<h1>").append(escape(result.runId())).append(' ').append(badge(result.outcome()))
                .append("</h1>\n<p class=\"meta\">Started ")
                .append(DateTimeFormatter.ISO_INSTANT.format(result.started())).append(" · ")
                .append(duration(result.duration())).append(" · ").append(result.testScripts().size())
                .append(" TestScript(s): ").append(counts.get(Outcome.PASS)).append(" passed, ")
                .append(counts.get(Outcome.FAIL)).append(" failed, ").append(counts.get(Outcome.ERROR))
                .append(" errors, ").append(counts.get(Outcome.SKIP)).append(" skipped</p>\n")
                .append("<label><input type=\"checkbox\" id=\"problems\"> only what did not pass</label>\n</header>\n");
    }

    private static void overview(StringBuilder html, RunResult result) {
        html.append("<table>\n<tr><th class=\"num\">#</th><th>TestScript</th><th class=\"wide\">Suite</th>")
                .append("<th class=\"wide\">File</th><th>FHIR</th><th class=\"num\">Tests passed</th>")
                .append("<th class=\"num\">Duration</th><th>Outcome</th></tr>\n");
        for (TestScriptResult testScript : result.testScripts()) {
            List<PhaseResult> tests = testScript.phases().stream().filter(p -> p.kind() == PhaseKind.TEST).toList();
            long passed = tests.stream().filter(p -> p.outcome() == Outcome.PASS).count();
            html.append("<tr").append(hiddenIfPassed(testScript.outcome())).append("><td class=\"num\">")
                    .append(testScript.index()).append("</td><td><a href=\"#").append(anchor(testScript)).append("\">")
                    .append(escape(testScript.name())).append("</a></td><td class=\"wide\">")
                    .append(escape(testScript.suite())).append("</td><td class=\"wide\">")
                    .append(escape(String.valueOf(testScript.source().getFileName()))).append("</td><td>")
                    .append(testScript.fhirVersion() == null ? "" : testScript.fhirVersion().name())
                    .append("</td><td class=\"num\">").append(passed).append(" / ").append(tests.size())
                    .append("</td><td class=\"num\">").append(duration(testScript.duration())).append("</td><td>")
                    .append(badge(testScript.outcome())).append("</td></tr>\n");
        }
        html.append("</table>\n");
    }

    private static void testScript(StringBuilder html, TestScriptResult testScript, Map<String, String> exchanges,
                                   Path runDirectory) {
        Outcome outcome = testScript.outcome();
        html.append("<section class=\"ts").append(outcome == Outcome.PASS ? " only-problems-hidden" : "")
                .append("\" id=\"").append(anchor(testScript)).append("\">\n<details")
                .append(outcome == Outcome.PASS ? "" : " open").append(">\n<summary>").append(badge(outcome))
                .append(" <h2>").append(testScript.index()).append(". ").append(escape(testScript.name()))
                .append("</h2> <span class=\"muted\">").append(duration(testScript.duration()))
                .append("</span></summary>\n<dl>\n");
        definition(html, "Suite", testScript.suite());
        definition(html, "File", String.valueOf(testScript.source()));
        definition(html, "URL", testScript.url());
        definition(html, "FHIR version", testScript.fhirVersion() == null ? null : testScript.fhirVersion().name());
        definition(html, "Server", testScript.fhirServer());
        definition(html, "Started", DateTimeFormatter.ISO_INSTANT.format(testScript.started()));
        html.append("</dl>\n");
        for (PhaseResult phase : testScript.phases()) {
            phase(html, phase, exchanges, runDirectory);
        }
        html.append("</details>\n</section>\n");
    }

    private static void phase(StringBuilder html, PhaseResult phase, Map<String, String> exchanges, Path runDirectory) {
        html.append("<div").append(hiddenIfPassed(phase.outcome())).append(">\n<h3>").append(badge(phase.outcome()))
                .append(' ').append(phase.kind().label());
        if (!phase.name().equals(phase.kind().label())) {
            html.append(" '").append(escape(phase.name())).append('\'');
        }
        html.append(" <span class=\"muted\">").append(duration(phase.duration())).append("</span></h3>\n");
        // messages of the phase itself; those of an action are shown next to the action
        Set<String> ofActions = new HashSet<>();
        for (ActionResult action : phase.actions()) {
            if (action.message() != null) {
                ofActions.add(action.message());
                ofActions.add("WARNING: " + action.message());
            }
        }
        List<String> own = phase.messages().stream().filter(m -> !ofActions.contains(m)).toList();
        if (!own.isEmpty()) {
            html.append("<ul class=\"messages\">\n");
            for (String message : own) {
                html.append("<li").append(message.startsWith("WARNING: ") ? " class=\"warning\"" : "").append("><pre>")
                        .append(escape(message)).append("</pre></li>\n");
            }
            html.append("</ul>\n");
        }
        if (!phase.actions().isEmpty()) {
            html.append("<table>\n<tr><th>Action</th><th>Outcome</th><th>Request</th></tr>\n");
            for (ActionResult action : phase.actions()) {
                html.append("<tr><td><pre>").append(escape(action.kind())).append(": ")
                        .append(escape(action.description())).append("</pre>");
                if (action.message() != null) {
                    html.append("<pre").append(action.warning() ? " class=\"warning\">WARNING: " : ">")
                            .append(escape(action.message())).append("</pre>");
                }
                html.append("</td><td>").append(badge(action.outcome())).append("</td><td>");
                request(html, action.requestId(), exchanges, runDirectory);
                html.append("</td></tr>\n");
            }
            html.append("</table>\n");
        }
        html.append("</div>\n");
    }

    /** The request id, linked to its folder and files in {@code communication/} if the exchange was written. */
    private static void request(StringBuilder html, String requestId, Map<String, String> exchanges,
                                Path runDirectory) {
        if (requestId == null) {
            return;
        }
        String folder = exchanges.get(requestId);
        if (folder == null) {
            html.append(escape(requestId));
            return;
        }
        html.append("<a href=\"").append(href(folder)).append("/\">").append(escape(requestId)).append("</a>");
        StringBuilder files = new StringBuilder();
        for (String name : exchangeFiles(runDirectory.resolve(folder))) {
            files.append(files.isEmpty() ? "" : " · ").append("<a href=\"").append(href(folder + "/" + name))
                    .append("\">").append(escape(name.endsWith(".http") || name.endsWith(".txt")
                            ? name.substring(0, name.indexOf('.')) : name)).append("</a>");
        }
        if (!files.isEmpty()) {
            html.append("<br><span class=\"muted\">").append(files).append("</span>");
        }
    }

    /**
     * The files of an exchange folder: {@code request.http}, {@code response.http} or {@code error.txt}, then the
     * bodies that are no text ({@code request.body.pdf}, …).
     */
    private static List<String> exchangeFiles(Path folder) {
        List<String> names = new ArrayList<>();
        for (String name : List.of("request.http", "response.http", "error.txt")) {
            if (Files.isRegularFile(folder.resolve(name))) {
                names.add(name);
            }
        }
        try (Stream<Path> files = Files.list(folder)) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.contains(".body.")).sorted().forEach(names::add);
        } catch (IOException e) {
            // the folder cannot be listed: the report links the .http files found above, without the bodies
        }
        return names;
    }

    private static void definition(StringBuilder html, String term, String value) {
        if (value != null && !value.isBlank()) {
            html.append("<dt>").append(term).append("</dt><dd>").append(escape(value)).append("</dd>\n");
        }
    }

    private static String badge(Outcome outcome) {
        return "<span class=\"badge " + outcome.name().toLowerCase(Locale.ROOT) + "\">" + outcome + "</span>";
    }

    private static String hiddenIfPassed(Outcome outcome) {
        return outcome == Outcome.PASS ? " class=\"only-problems-hidden\"" : "";
    }

    private static String anchor(TestScriptResult testScript) {
        return "ts-" + testScript.index();
    }

    private static String duration(Duration duration) {
        long millis = duration.toMillis();
        return millis < 1000 ? millis + " ms" : String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    /** Text as HTML text or attribute value; characters HTML does not allow become U+FFFD. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' ? '�' : c);
            }
        }
        return out.toString();
    }

    /** A relative path as URL: every character but letters, digits, {@code . _ - ~ /} percent-encoded. */
    static String href(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || ".-_~/".indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
        }
        return out.toString();
    }
}
