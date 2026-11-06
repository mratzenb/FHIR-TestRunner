package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FixtureDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the file behind {@code fixture.resource.reference} in the Example Instances folder of a suite.
 *
 * <p>Example Instance files are named {@code <ResourceType>-<id>.json|xml}. Two spellings of the reference occur
 * in practice and both resolve:
 * <ul>
 *   <li>the IG publisher page name, {@code Patient-HL7ATCorePatientExample.html}</li>
 *   <li>a relative reference, {@code Patient/HL7ATCorePatientExample}, as SUSHI writes it for
 *       {@code Reference(HL7ATCorePatientExample)}</li>
 * </ul>
 * Absolute URLs work too; only the last two path segments are used.
 */
public final class ExampleInstanceResolver {

    private static final List<String> EXTENSIONS = List.of(".json", ".xml");

    private final List<Path> folders;

    public ExampleInstanceResolver(Path folder) {
        this(List.of(folder));
    }

    /** Looks in several folders, in this order, e.g. the Example Instances, then the conformance resources. */
    public ExampleInstanceResolver(List<Path> folders) {
        this.folders = List.copyOf(folders);
    }

    /**
     * Loads the resource of a fixture.
     *
     * @throws FixtureNotFoundException naming every file tried, if there is none
     */
    public Body load(FixtureDefinition fixture) {
        return find(fixture.reference()).orElseThrow(() -> new FixtureNotFoundException("No Example Instance found"
                + " for fixture '" + fixture.id() + "' referencing '" + fixture.reference() + "'. Tried: "
                + files(fixture.reference())));
    }

    /** Loads the resource a reference or URL points to, e.g. {@code CapabilityStatement/example}, if there is one. */
    public Optional<Body> find(String reference) {
        return files(reference).stream().filter(Files::isRegularFile).findFirst().map(ExampleInstanceResolver::read);
    }

    /** The files a reference may be stored in, most specific first, folder by folder. */
    private List<Path> files(String reference) {
        List<Path> files = new ArrayList<>();
        for (Path folder : folders) {
            for (String stem : candidates(reference)) {
                for (String extension : EXTENSIONS) {
                    files.add(folder.resolve(stem + extension));
                }
            }
        }
        return files;
    }

    /** The file stems to look for, most specific first. */
    static List<String> candidates(String reference) {
        int version = reference.indexOf('|'); // a canonical URL may name a version: http://…/Type/id|1.0
        String[] segments = (version >= 0 ? reference.substring(0, version) : reference).split("[/\\\\]");
        List<String> parts = new ArrayList<>();
        for (String segment : segments) {
            if (!segment.isBlank()) {
                parts.add(segment);
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        String last = parts.getLast();
        int dot = last.lastIndexOf('.');
        String stem = dot > 0 ? last.substring(0, dot) : last;
        Set<String> candidates = new LinkedHashSet<>();
        if (parts.size() >= 2) {
            candidates.add(parts.get(parts.size() - 2) + "-" + stem); // Patient/Foo -> Patient-Foo
        }
        candidates.add(stem); // Patient-Foo.html -> Patient-Foo, or the bare id of Patient/Foo
        return List.copyOf(candidates);
    }

    private static Body read(Path file) {
        try {
            return Body.ofFile(file);
        } catch (IOException e) {
            throw new FixtureNotFoundException("Could not read " + file + ": " + e.getMessage());
        }
    }

    /** No Example Instance could be found or read for a fixture. */
    public static final class FixtureNotFoundException extends RuntimeException {
        public FixtureNotFoundException(String message) {
            super(message);
        }
    }
}
