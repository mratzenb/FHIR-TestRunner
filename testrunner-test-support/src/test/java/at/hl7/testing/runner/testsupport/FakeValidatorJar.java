package at.hl7.testing.runner.testsupport;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Builds a stand-in for {@code validator_cli.jar}: a real, executable jar that answers like the HL7 validator, so
 * the validation adapter can be tested end to end (process start, arguments, {@code -output}) without the 200 MB
 * validator and without network access. The jar holds only a manifest; it starts {@link FakeValidator} from the class
 * path of the test.
 *
 * <p>The fake reports one error per occurrence of the marker {@value #INVALID_MARKER} in the validated file, and
 * "All OK" otherwise. It writes nothing with {@code -output} when the file contains {@value #SILENT_MARKER}, to
 * simulate a validator that did not run.
 *
 * <p>{@code server <port>} runs it as HTTP server like {@code validator_cli.jar server}: {@code POST
 * /validateResource[?profile=..]} answers the same OperationOutcomes, {@code POST /stop} stops it. It writes one line
 * per server start and per validation to the file named by the system property or environment variable
 * {@code FAKE_VALIDATOR_LOG}, if set, so tests can count starts and calls. Further markers:
 * {@value #CRASH_MARKER} ends the server process (simulates a crash), {@value #HANG_MARKER} makes the server never
 * answer (simulates a hanging validation), {@value #UNRESOLVABLE_PROFILE} as profile is
 * answered with HTTP 500 like an unknown profile. {@code FAKE_VALIDATOR_SERVER_FAILS} (system property or environment)
 * makes the server exit at start-up, {@code FAKE_VALIDATOR_STARTUP_MILLIS} delays its start.
 */
public final class FakeValidatorJar {

    public static final String INVALID_MARKER = "FAKE-VALIDATOR-INVALID";
    public static final String SILENT_MARKER = "FAKE-VALIDATOR-SILENT";
    public static final String CRASH_MARKER = "FAKE-VALIDATOR-CRASH";
    public static final String HANG_MARKER = "FAKE-VALIDATOR-HANG";
    public static final String UNRESOLVABLE_PROFILE = "http://example.org/StructureDefinition/unresolvable";

    /** One class of each jar (or classes folder) the fake needs: this module's test classes and jetty-server. */
    private static final List<Class<?>> CLASS_PATH = List.of(FakeValidator.class, Server.class, HttpField.class,
            Content.class, Callback.class, LoggerFactory.class);

    private FakeValidatorJar() {
    }

    /** Writes the fake validator to {@code folder/validator_cli.jar}. */
    public static Path create(Path folder) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, FakeValidator.class.getName());
        attributes.put(Attributes.Name.CLASS_PATH, classPath());
        Path jar = folder.resolve("validator_cli.jar");
        try (OutputStream file = Files.newOutputStream(jar)) {
            new JarOutputStream(file, manifest).finish();
            return jar;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the fake validator", e);
        }
    }

    /** The manifest's {@code Class-Path}: absolute {@code file:} URLs, folders ending with a slash. */
    private static String classPath() {
        return CLASS_PATH.stream().map(FakeValidatorJar::location).distinct().collect(Collectors.joining(" "));
    }

    private static String location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toUri().toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not locate " + type.getName(), e);
        }
    }
}
