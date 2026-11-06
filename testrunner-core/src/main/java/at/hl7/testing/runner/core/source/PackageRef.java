package at.hl7.testing.runner.core.source;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A FHIR package and a version, {@code name#version}, as a suite's {@code package-id} or a dependency of a package
 * names it. The version is exact ({@code 2.1.0}), a patch wildcard ({@code 2.1.x}), {@code dev} (built locally by the
 * IG Publisher), {@code current} (the CI build of the default branch) or {@code current$<branch>} (the CI build of a
 * branch).
 *
 * <p>Name and version become the folder {@code <name>#<version>} of the package cache, so neither may contain a path
 * separator or start with a dot.
 *
 * @param name    the package name, e.g. {@code hl7.at.fhir.core.r4}
 * @param version the version as written
 */
record PackageRef(String name, String version) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*|current\\$[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern WILDCARD = Pattern.compile("([0-9]+)\\.([0-9]+)\\.x");
    private static final Pattern CORE = Pattern.compile("hl7\\.fhir\\.(r[0-9]+[a-z]?\\.)?core");

    /** {@code name#version}, if both parts are valid. */
    static Optional<PackageRef> parse(String id) {
        int hash = id.indexOf('#');
        if (hash < 0) {
            return Optional.empty();
        }
        return of(id.substring(0, hash), id.substring(hash + 1));
    }

    /** A dependency as {@code package.json} lists it, if name and version are valid. */
    static Optional<PackageRef> of(String name, String version) {
        if (!NAME.matcher(name).matches() || !VERSION.matcher(version).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PackageRef(name, version));
    }

    /** {@code name#version}, the folder name in the package cache. */
    String id() {
        return name + "#" + version;
    }

    boolean isDev() {
        return version.equals("dev");
    }

    /** A CI build: {@code current} or {@code current$<branch>}. */
    boolean isCurrent() {
        return version.equals("current") || version.startsWith("current$");
    }

    /** The branch of a {@code current$<branch>} build; empty for the default branch and every other version. */
    Optional<String> branch() {
        return version.startsWith("current$") ? Optional.of(version.substring("current$".length())) : Optional.empty();
    }

    /** {@code 2.1.x}: any patch release of 2.1, the highest one is used. */
    boolean isWildcard() {
        return WILDCARD.matcher(version).matches();
    }

    /** Whether {@code candidate} is a release this wildcard version stands for ({@code 2.1.x}: {@code 2.1.3}). */
    boolean matches(String candidate) {
        Matcher wildcard = WILDCARD.matcher(version);
        return wildcard.matches() && candidate.matches(Pattern.quote(wildcard.group(1) + "." + wildcard.group(2) + ".")
                + "[0-9]+");
    }

    /** The patch number of a release {@code major.minor.patch}, for comparing the matches of a wildcard. */
    static long patch(String release) {
        return Long.parseLong(release.substring(release.lastIndexOf('.') + 1));
    }

    /**
     * The core package of a FHIR version ({@code hl7.fhir.r4.core}). The validator always has it; it is neither a
     * suite's IG nor downloaded by the runner.
     */
    boolean isCore() {
        return CORE.matcher(name).matches();
    }

    /** Same package, other version, e.g. a wildcard resolved to a release. */
    PackageRef withVersion(String newVersion) {
        return new PackageRef(name, newVersion);
    }

    @Override
    public String toString() {
        return id();
    }
}
