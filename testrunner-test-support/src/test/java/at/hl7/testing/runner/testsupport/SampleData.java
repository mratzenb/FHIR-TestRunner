package at.hl7.testing.runner.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The sample TestScripts, Example Instances and profiles of the project ({@code testdata/} in the project root,
 * taken from the Python proof of concept).
 *
 * <ul>
 *   <li>{@code r4}: AT Core R4 TestScripts and the negative control</li>
 *   <li>{@code r5}: AT Core R5 TestScripts</li>
 *   <li>{@code samples}: TestScripts covering every operation type and many assertion types; mostly R4, one
 *       ({@code TestScript-testscript-delete.json}) is R5, so the folder exercises version detection</li>
 * </ul>
 */
public final class SampleData {

    private SampleData() {
    }

    /** The {@code testdata} folder, found by walking up from the working directory of the test. */
    public static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("testdata");
            if (Files.isDirectory(candidate.resolve("r4"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("testdata folder not found above " + Path.of("").toAbsolutePath());
    }

    public static Path r4() {
        return root().resolve("r4");
    }

    public static Path r5() {
        return root().resolve("r5");
    }

    public static Path samples() {
        return root().resolve("samples");
    }

    /** A TestScript file of a sample folder. */
    public static Path testScript(Path suite, String fileName) {
        return suite.resolve("Test_Scripts").resolve(fileName);
    }
}
