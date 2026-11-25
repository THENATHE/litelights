package com.thenathe.litelights.render.feature.particles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client thread never waits for the particle worker once a tick: a step still running is reported, not waited for. */
class SimulationWorkerTest {
    @Test
    void aStepStillRunningIsReportedWithoutWaiting() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        SimulationWorker worker = new SimulationWorker(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(worker.poll(), "nothing started yet: done");
        worker.launch();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long before = System.nanoTime();
        for (int tick = 0; tick < 5; tick++) {
            assertFalse(worker.poll(), "a catch-up tick finds the step still running");
        }
        assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(200), "and doesn't wait for it");
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!worker.poll() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(worker.poll(), "the next tick takes the finished step");
        assertNull(worker.takeFailure());
    }

    @Test
    void aFailedStepIsReportedOnce() {
        IllegalStateException broken = new IllegalStateException("broken step");
        SimulationWorker worker = new SimulationWorker(() -> {
            throw broken;
        });
        worker.launch();
        assertSame(broken, worker.await());
        assertNull(worker.takeFailure());
    }
}
