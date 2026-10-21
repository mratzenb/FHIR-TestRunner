package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.config.SuiteConfig;

import java.nio.file.Path;

/**
 * A TestScript file found in a suite.
 *
 * @param suite the suite it belongs to
 * @param file  the file
 */
public record TestScriptSource(SuiteConfig suite, Path file) {

    /** The file name without extension. */
    public String baseName() {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
