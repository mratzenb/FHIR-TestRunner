package at.hl7.testing.runner.core.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes every exchange with the FHIR server to a folder of its own:
 *
 * <pre>
 * communication/&lt;NN&gt;_&lt;TestScript&gt;/REQ-0001_POST_Patient/
 *     request.http    request line, headers and body as sent
 *     response.http   status line, headers and body as received
 *     error.txt       instead of response.http when no response was received
 * </pre>
 *
 * <p>One instance per TestScript execution. The full exchange also goes to the logger
 * {@code at.hl7.testing.runner.communication} at DEBUG, so it can be routed to the console or a file through the
 * logging configuration alone.
 *
 * <p>The values of the headers set with {@link #redacting} are masked in both, e.g. {@code Authorization: Bearer ***};
 * an authentication scheme stays visible. Everything else is written as it was exchanged.
 */
public final class CommunicationLog {

    /** Logger that receives every exchange in full at DEBUG. */
    public static final String LOGGER_NAME = "at.hl7.testing.runner.communication";

    private static final Logger log = LoggerFactory.getLogger(CommunicationLog.class);
    private static final Logger wire = LoggerFactory.getLogger(LOGGER_NAME);
    private static final int MAX_FOLDER_NAME = 80;
    private static final String MASK = "***";
    /** An authentication scheme in front of the credentials, e.g. {@code Bearer} or {@code Basic}. */
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9-]*) \\S");

    private final Path runFolder;
    private final Path folder;
    private final String fhirBase;
    private final Set<String> redacted;

    /**
     * @param runFolder folder of the run's communication logs, {@code null} to write no files
     * @param folder    folder of this TestScript below {@code runFolder}
     * @param fhirBase  base URL of the FHIR server, left out of the folder names
     * @param redacted  lower-case names of the headers whose values are masked
     */
    private CommunicationLog(Path runFolder, Path folder, String fhirBase, Set<String> redacted) {
        this.runFolder = runFolder;
        this.folder = folder;
        this.fhirBase = fhirBase == null ? "" : fhirBase;
        this.redacted = redacted;
    }

    /** A log that writes files below {@code runFolder/<testScriptFolder>}. */
    public static CommunicationLog toFolder(Path runFolder, String testScriptFolder, String fhirBase) {
        return new CommunicationLog(runFolder, runFolder.resolve(safeName(testScriptFolder)), fhirBase, Set.of());
    }

    /** A log that writes no files; exchanges still go to the {@value #LOGGER_NAME} logger. */
    public static CommunicationLog none() {
        return new CommunicationLog(null, null, null, Set.of());
    }

    /** A copy that masks the values of these headers (names compared case-insensitively). */
    public CommunicationLog redacting(Collection<String> headers) {
        Set<String> names = headers.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        return new CommunicationLog(runFolder, folder, fhirBase, names);
    }

    /**
     * Records an exchange.
     *
     * @return where the exchange was written, relative to the run's communication folder, or {@code null}
     */
    public String record(String requestId, FhirRequest request, FhirResponse response) {
        String requestText = requestMessage(request);
        String responseText = responseMessage(response);
        logExchange(requestId, requestText, responseText);
        return write(requestId, request, Map.of("request.http", requestText, "response.http", responseText));
    }

    /** Records a request for which no response was received. */
    public String recordFailure(String requestId, FhirRequest request, Exception failure) {
        String requestText = requestMessage(request);
        String errorText = "No response received.\n" + failure.getClass().getName() + ": " + failure.getMessage() + "\n";
        logExchange(requestId, requestText, errorText);
        return write(requestId, request, Map.of("request.http", requestText, "error.txt", errorText));
    }

    private String write(String requestId, FhirRequest request, Map<String, String> files) {
        if (folder == null) {
            return null;
        }
        Path exchangeFolder = folder.resolve(folderName(requestId, request));
        try {
            Files.createDirectories(exchangeFolder);
            for (Map.Entry<String, String> file : files.entrySet()) {
                Files.writeString(exchangeFolder.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Could not write the communication log of {} to {}: {}", requestId, exchangeFolder, e.toString());
            return null;
        }
        return runFolder.relativize(exchangeFolder).toString().replace('\\', '/');
    }

    private static void logExchange(String requestId, String request, String response) {
        if (wire.isDebugEnabled()) {
            wire.debug("----- {} request -----\n{}\n----- {} response -----\n{}\n----- end of {} -----",
                    requestId, request, requestId, response, requestId);
        }
    }

    /** {@code REQ-0001_POST_Patient_123}: request id, method and the URL path below the FHIR base. */
    String folderName(String requestId, FhirRequest request) {
        String url = request.uri().toString();
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        String path = !fhirBase.isEmpty() && url.startsWith(fhirBase) ? url.substring(fhirBase.length())
                : request.uri().getRawPath();
        String name = requestId + "_" + request.method();
        String safePath = safeName(path == null ? "" : path);
        String full = safePath.isEmpty() ? name : name + "_" + safePath;
        return full.length() > MAX_FOLDER_NAME ? full.substring(0, MAX_FOLDER_NAME) : full;
    }

    /** Replaces everything but letters, digits, '.', '_' and '-'. */
    public static String safeName(String text) {
        String name = text.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        return name.length() > MAX_FOLDER_NAME ? name.substring(0, MAX_FOLDER_NAME) : name;
    }

    String requestMessage(FhirRequest request) {
        StringBuilder text = new StringBuilder()
                .append(request.method()).append(' ').append(request.uri()).append(" HTTP/1.1\n");
        appendHeaders(text, request.headers());
        text.append('\n');
        if (request.body() != null) {
            text.append(request.body());
        }
        return text.toString();
    }

    String responseMessage(FhirResponse response) {
        StringBuilder text = new StringBuilder()
                .append(response.httpVersion()).append(' ').append(response.status());
        if (!response.reason().isEmpty()) {
            text.append(' ').append(response.reason());
        }
        text.append('\n');
        appendHeaders(text, response.headers());
        return text.append('\n').append(response.body()).toString();
    }

    private void appendHeaders(StringBuilder text, HttpHeaders headers) {
        for (Map.Entry<String, List<String>> field : headers.asMap().entrySet()) {
            boolean mask = redacted.contains(field.getKey().toLowerCase(Locale.ROOT));
            for (String value : field.getValue()) {
                text.append(field.getKey()).append(": ").append(mask ? mask(value) : value).append('\n');
            }
        }
    }

    /** {@code Bearer eyJ…} -> {@code Bearer ***}, anything else -> {@code ***}. */
    static String mask(String value) {
        Matcher scheme = SCHEME.matcher(value);
        return scheme.find() ? scheme.group(1) + " " + MASK : MASK;
    }
}
