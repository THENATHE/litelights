package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.LiteLightsClient;
import java.util.concurrent.Semaphore;

/**
 * One background thread that runs the particle step between client ticks. The client thread
 * starts a step after it has queued the tick's spawns and waits for it at the start of the next
 * tick; the step normally takes well under a millisecond, so the wait is nearly always zero.
 *
 * <p>No allocation per step: the task is fixed and the handshake is two semaphores.
 */
final class SimulationWorker {
    private final Runnable task;
    private final Semaphore start = new Semaphore(0);
    private final Semaphore done = new Semaphore(0);
    private volatile boolean running;
    private volatile Throwable failure;
    private boolean busy;
    private Thread thread;

    SimulationWorker(Runnable task) {
        this.task = task;
    }

    private void ensureStarted() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "LiteLights particles");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    private void loop() {
        while (running) {
            start.acquireUninterruptibly();
            if (!running) {
                break;
            }
            try {
                task.run();
            } catch (Throwable error) {
                failure = error;
            }
            done.release();
        }
    }

    /** Starts a step. The caller must not touch the step's state until {@link #await} returns. */
    void launch() {
        ensureStarted();
        busy = true;
        start.release();
    }

    /**
     * Waits for the step started last, if any. Returns the error it threw, or null; a failed step
     * leaves the next state as it was, and the caller decides what to do.
     */
    Throwable await() {
        if (!busy) {
            return null;
        }
        done.acquireUninterruptibly();
        busy = false;
        Throwable error = failure;
        failure = null;
        return error;
    }

    void stop() {
        await();
        if (thread != null) {
            running = false;
            start.release();
            thread = null;
            LiteLightsClient.LOG.debug("Particle worker stopped");
        }
    }
}
