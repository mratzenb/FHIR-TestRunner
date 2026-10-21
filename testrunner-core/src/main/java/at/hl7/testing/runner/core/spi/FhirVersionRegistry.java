package at.hl7.testing.runner.core.spi;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/** The {@link FhirVersionSupport} modules available, by version. Immutable and thread-safe. */
public final class FhirVersionRegistry {

    private final Map<FhirVersion, FhirVersionSupport> supports;

    public FhirVersionRegistry(Collection<? extends FhirVersionSupport> supports) {
        Map<FhirVersion, FhirVersionSupport> byVersion = new EnumMap<>(FhirVersion.class);
        for (FhirVersionSupport support : supports) {
            FhirVersionSupport previous = byVersion.putIfAbsent(support.version(), support);
            if (previous != null && previous != support) {
                throw new IllegalStateException("Two modules support FHIR " + support.version() + ": "
                        + previous.getClass().getName() + " and " + support.getClass().getName());
            }
        }
        this.supports = Map.copyOf(byVersion);
    }

    /** The modules registered with {@link ServiceLoader}. */
    public static FhirVersionRegistry fromServiceLoader() {
        List<FhirVersionSupport> found = new ArrayList<>();
        ServiceLoader.load(FhirVersionSupport.class).forEach(found::add);
        return new FhirVersionRegistry(found);
    }

    public Set<FhirVersion> versions() {
        return supports.keySet();
    }

    public Optional<FhirVersionSupport> get(FhirVersion version) {
        return Optional.ofNullable(supports.get(version));
    }

    /**
     * Detects the FHIR version of a TestScript.
     *
     * <p>A version in which the TestScript is valid as it is wins; if there are several, the newest, since such a
     * TestScript only uses elements that mean the same in all of them. Otherwise the version that has to ignore
     * the fewest elements wins. A version in which the content has errors (e.g. values of the wrong type) is never
     * chosen.
     *
     * @return the version and the check of the content against it, empty if no version can read the content
     */
    public Optional<Detection> detect(Body content) {
        Detection best = null;
        for (FhirVersion version : FhirVersion.values()) {
            FhirVersionSupport support = supports.get(version);
            if (support == null) {
                continue;
            }
            ReadCheck check = support.check(content);
            if (check.readable() && (best == null || check.warnings().size() <= best.check().warnings().size())) {
                best = new Detection(support, check);
            }
        }
        return Optional.ofNullable(best);
    }

    /** The checks of {@code content} against every available version, for error messages. */
    public Map<FhirVersion, ReadCheck> checkAll(Body content) {
        Map<FhirVersion, ReadCheck> checks = new EnumMap<>(FhirVersion.class);
        supports.forEach((version, support) -> checks.put(version, support.check(content)));
        return checks;
    }

    /** The version detected for a TestScript and how well the TestScript fits it. */
    public record Detection(FhirVersionSupport support, ReadCheck check) {
    }
}
