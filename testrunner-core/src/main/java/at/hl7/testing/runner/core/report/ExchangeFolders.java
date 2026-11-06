package at.hl7.testing.runner.core.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the folder of each exchange in the communication log of a run, so that a report can point from a request id
 * to the bytes exchanged. The folders are {@code communication/<NN>_<TestScript>/<REQ-id>_<METHOD>_<path>}.
 */
final class ExchangeFolders {

    /** The folder of the communication log in the run folder. */
    static final String COMMUNICATION = "communication";

    private static final Logger log = LoggerFactory.getLogger(ExchangeFolders.class);

    private ExchangeFolders() {
    }

    /**
     * The folder of every exchange written, relative to the run folder with {@code /}, by request id; empty if the
     * communication log is off or nothing was sent.
     */
    static Map<String, String> of(Path runDirectory) {
        Map<String, String> folders = new HashMap<>();
        Path communication = runDirectory.resolve(COMMUNICATION);
        if (!Files.isDirectory(communication)) {
            return folders;
        }
        try (Stream<Path> paths = Files.walk(communication, 2)) {
            paths.filter(Files::isDirectory).forEach(folder -> {
                String name = folder.getFileName().toString();
                int end = name.indexOf('_');
                if (name.startsWith("REQ-") && end > 0) {
                    folders.put(name.substring(0, end), runDirectory.relativize(folder).toString().replace('\\', '/'));
                }
            });
        } catch (IOException e) {
            log.debug("Cannot list {}: {}", communication, e.toString());
        }
        return folders;
    }
}
