package at.hl7.testing.runner.core.source;

import at.hl7.testing.runner.core.config.SuiteConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finds the TestScript files of the configured suites, in a stable order (suite order, then file path). */
public final class TestScriptDiscovery {

    private TestScriptDiscovery() {
    }

    public static List<TestScriptSource> discover(List<SuiteConfig> suites) {
        List<TestScriptSource> sources = new ArrayList<>();
        for (SuiteConfig suite : suites) {
            Path folder = suite.resolvedTestScriptsDir();
            FileSystem fs = folder.getFileSystem();
            List<PathMatcher> matchers = suite.include().stream().map(glob -> fs.getPathMatcher("glob:" + glob)).toList();
            try (Stream<Path> files = Files.walk(folder)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> matchers.stream().anyMatch(m -> m.matches(folder.relativize(file))))
                        .sorted()
                        .forEach(file -> sources.add(new TestScriptSource(suite, file)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not list the TestScripts in " + folder, e);
            }
        }
        return sources;
    }
}
