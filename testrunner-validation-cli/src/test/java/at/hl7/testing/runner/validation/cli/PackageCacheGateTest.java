package at.hl7.testing.runner.validation.cli;

import at.hl7.testing.runner.core.model.Body;
import at.hl7.testing.runner.core.model.FhirVersion;
import at.hl7.testing.runner.core.spi.ValidationRequest;
import at.hl7.testing.runner.core.spi.ValidatorUnavailableException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageCacheGateTest {

    static final EngineKey R4 = new EngineKey(FhirVersion.R4, Path.of("r4/Profiles"), List.of());
    static final EngineKey R5 = new EngineKey(FhirVersion.R5, Path.of("r5/Profiles"), List.of());
    static final long SETTLE_MILLIS = 300;

    final PackageCacheGate gate = new PackageCacheGate();

    @Test
    void othersWaitForTheFirstValidationAndThenRunInParallel() throws Exception {
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        CyclicBarrier threeAtOnce = new CyclicBarrier(3);

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            Future<String> first = pool.submit(() -> gate.run(R4, () -> {
                started.incrementAndGet();
                firstRunning.countDown();
                await(releaseFirst);
                return "first";
            }));
            assertThat(firstRunning.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<String>> others = List.of(1, 2, 3).stream().map(i -> pool.submit(() -> gate.run(R4, () -> {
                started.incrementAndGet();
                awaitBarrier(threeAtOnce);
                return "other";
            }))).toList();

            Thread.sleep(SETTLE_MILLIS);
            assertThat(started).as("nobody starts while the first validation runs").hasValue(1);
            assertThat(gate.isWarm(R4)).isFalse();

            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("first");
            for (Future<String> other : others) {
                assertThat(other.get(10, TimeUnit.SECONDS)).as("the three run at the same time").isEqualTo("other");
            }
            assertThat(gate.isWarm(R4)).isTrue();
        }
    }

    @Test
    void firstValidationsOfDifferentConfigurationsDoNotOverlap() throws Exception {
        CountDownLatch r4Running = new CountDownLatch(1);
        CountDownLatch releaseR4 = new CountDownLatch(1);
        AtomicInteger r5Started = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> r4 = pool.submit(() -> gate.run(R4, () -> {
                r4Running.countDown();
                await(releaseR4);
                return "r4";
            }));
            assertThat(r4Running.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> r5 = pool.submit(() -> gate.run(R5, () -> {
                r5Started.incrementAndGet();
                return "r5";
            }));

            Thread.sleep(SETTLE_MILLIS);
            assertThat(r5Started).as("they share packages, so one after the other").hasValue(0);

            releaseR4.countDown();
            assertThat(r4.get(10, TimeUnit.SECONDS)).isEqualTo("r4");
            assertThat(r5.get(10, TimeUnit.SECONDS)).isEqualTo("r5");
        }
    }

    @Test
    void aWarmConfigurationDoesNotWaitForAnotherOnesFirstValidation() throws Exception {
        gate.run(R4, () -> "warm up");
        CountDownLatch r5Running = new CountDownLatch(1);
        CountDownLatch releaseR5 = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> r5 = pool.submit(() -> gate.run(R5, () -> {
                r5Running.countDown();
                await(releaseR5);
                return "r5";
            }));
            assertThat(r5Running.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(pool.submit(() -> gate.run(R4, () -> "r4 again")).get(10, TimeUnit.SECONDS))
                    .isEqualTo("r4 again");

            releaseR5.countDown();
            assertThat(r5.get(10, TimeUnit.SECONDS)).isEqualTo("r5");
        }
    }

    @Test
    void aFailedFirstValidationLeavesTheConfigurationColdSoTheNextRunsAlone() {
        assertThatThrownBy(() -> gate.run(R4, () -> {
            throw new ValidatorUnavailableException("could not install the packages");
        })).isInstanceOf(ValidatorUnavailableException.class);

        assertThat(gate.isWarm(R4)).isFalse();
        assertThat(gate.run(R4, () -> "second try")).isEqualTo("second try");
        assertThat(gate.isWarm(R4)).isTrue();
    }

    @Test
    void interruptedWhileWaitingIsAnErrorAndKeepsTheInterrupt() throws Exception {
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Thread blocker = Thread.ofPlatform().start(() -> gate.run(R4, () -> {
            firstRunning.countDown();
            await(releaseFirst);
            return "first";
        }));
        assertThat(firstRunning.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gate.run(R4, () -> "never"))
                    .isInstanceOf(ValidatorUnavailableException.class).hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            releaseFirst.countDown();
            blocker.join(10_000);
        }
    }

    @Test
    void engineKeyNormalizesTheProfilesFolder() {
        ValidationRequest one = new ValidationRequest(Body.json("{}"), FhirVersion.R4, null,
                Path.of("a/../r4/Profiles"), List.of());
        ValidationRequest other = new ValidationRequest(Body.json("{}"), FhirVersion.R4, null,
                Path.of("r4/Profiles").toAbsolutePath(), List.of());

        assertThat(EngineKey.of(one)).isEqualTo(EngineKey.of(other));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The validations did not run in parallel", e);
        }
    }
}
