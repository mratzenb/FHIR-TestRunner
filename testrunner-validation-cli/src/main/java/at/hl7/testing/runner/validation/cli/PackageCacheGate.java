package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Lets the first validation of each {@link EngineKey} run alone, so that validators that start together do not install
 * the same FHIR packages into {@code ~/.fhir/packages} at the same time.
 *
 * <p>The validator's cache locking does not hold across processes: on an empty cache, parallel validator processes
 * that all install {@code hl7.fhir.r4.core} fail with {@code NoSuchFileException: ...#4.0.1.lock ->
 * ...lock-deletion} and exit code 1. The first validation of a configuration is the one that downloads and installs
 * its packages; the others wait for it and then only read the cache, in parallel.
 *
 * <p>First validations of different configurations also run one after the other, because they usually share packages
 * (the core package, terminology). Once a configuration is warm, its validations do not wait for anything.
 * A configuration becomes warm when its first validation returns; if that one throws, the next one is tried alone,
 * so a failure never lets the others race on a cache that may be half filled.
 *
 * <p>Thread-safe. Each validator creates its own gate; a server validator shares its gate with its process fallback.
 */
final class PackageCacheGate {

    private static final Logger log = LoggerFactory.getLogger(PackageCacheGate.class);

    private final ReentrantLock coldStart = new ReentrantLock(true);
    private final Set<EngineKey> warm = ConcurrentHashMap.newKeySet();

    /** Runs {@code validation}, alone if the packages of {@code key} may not be in the cache yet. */
    <T> T run(EngineKey key, Supplier<T> validation) {
        if (!warm.contains(key)) {
            try {
                coldStart.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ValidatorUnavailableException("Interrupted while waiting for the first validation of " + key, e);
            }
            try {
                if (!warm.contains(key)) {
                    log.info("The first validation for {} runs alone, so that validator processes do not install the"
                            + " same packages into the package cache at the same time; the others wait for it", key);
                    T result = validation.get();
                    warm.add(key);
                    return result;
                }
            } finally {
                coldStart.unlock();
            }
        }
        return validation.get();
    }

    boolean isWarm(EngineKey key) {
        return warm.contains(key);
    }
}
