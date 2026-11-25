package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.LiteLightsClient;
import java.util.concurrent.Semaphore;

/**
 * One background thread that runs the particle step between client ticks. The client thread
 * starts a step after it has queued the tick's spawns and checks at the next tick whether it has
 * finished, without waiting: at the top settings a step takes about half a tick on a fast CPU, so a
 * slower CPU, or the catch-up ticks Minecraft runs back to back after a hitch, would otherwise wait
 * for it. While a step still runs, the client thread skips that tick's particle work.
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

    /** Starts a step. The caller must not touch the step's state until {@link #poll} or {@link #await} says it is done. */
    void launch() {
        ensureStarted();
        busy = true;
        start.release();
    }

    /**
     * Whether the step started last has finished (or none was started), without ever waiting. Once
     * it has, the caller may touch the step's state again, and {@link #takeFailure} has what it threw.
     */
    boolean poll() {
        if (busy && done.tryAcquire()) {
            busy = false;
        }
        return !busy;
    }

    /**
     * Waits for the step started last, if any: only for a clear (a new world, everything switched
     * off, a failure), never once a tick. Returns the error it threw, or null.
     */
    Throwable await() {
        if (busy) {
            done.acquireUninterruptibly();
            busy = false;
        }
        return takeFailure();
    }

    /** The error the last finished step threw, once, or null; a failed step leaves the next state as it was. */
    Throwable takeFailure() {
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
