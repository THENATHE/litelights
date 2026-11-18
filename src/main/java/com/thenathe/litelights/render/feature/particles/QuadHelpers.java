package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.LiteLightsClient;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * A few helper threads that build a frame's particle quads beside the render thread. The render
 * thread starts a frame's slices as soon as the frame's camera is known ({@link #start}), long
 * before particles are drawn, and the helpers build them while it draws the sky and terrain; when it
 * reaches the particles ({@link #finish}) it builds whatever slices are left itself and waits only
 * for slices a helper is in the middle of.
 *
 * <p>Few threads, so they don't crowd out Minecraft's chunk builders: two on eight cores or more,
 * one on six or seven, none on smaller CPUs (the render thread then builds every slice, still only
 * over the worker's cull list). Daemon threads, started on the first frame that needs them and
 * ended when the world is left or the game closes.
 *
 * <p>Claims are one atomic counter holding the frame's slice count and the next slice, so a helper
 * that wakes late can't take a slice of a frame that has finished: what it reads either belongs to
 * the frame in progress or is past that frame's last slice. Every claimed slice is counted as done
 * even if it throws, so {@link #finish} can't wait forever; the error is passed on to the caller.
 */
final class QuadHelpers {
    /** Spins before {@link #finish} parks between checks for a helper's last slice. */
    private static final int SPINS = 2000;
    private static final long PARK_NANOS = 20_000L;
    private static final long JOIN_MILLIS = 1000L;

    private final IntConsumer buildSlice;
    private final int helperCount;
    private final Semaphore wake = new Semaphore(0);
    /** The frame's slice count in the high 32 bits, the next unclaimed slice in the low 32. */
    private final AtomicLong claims = new AtomicLong();
    private final AtomicInteger done = new AtomicInteger();
    /** Which set of threads may run: a helper whose set has been stopped ends, even if it woke late. */
    private volatile int generation;
    private volatile Throwable failure;
    private Thread[] threads;
    private int sliceCount;
    private boolean pending;

    QuadHelpers(IntConsumer buildSlice, int helperCount) {
        this.buildSlice = buildSlice;
        this.helperCount = helperCount;
    }

    /** How many helpers suit a CPU with this many cores (hardware threads, as Java counts them). */
    static int helpersFor(int cores) {
        if (cores >= 8) {
            return 2;
        }
        return cores >= 6 ? 1 : 0;
    }

    /** Starts a frame's slices; render thread, after the frame's inputs are in place. */
    void start(int slices) {
        finish();
        if (slices == 0) {
            return;
        }
        sliceCount = slices;
        pending = true;
        done.set(0);
        // Publishes the frame's inputs to the helpers: they read them only after claiming a slice.
        claims.set((long) slices << 32);
        if (helperCount == 0) {
            return;
        }
        ensureStarted();
        // At most one wake-up waiting per helper, however many frames a helper sleeps through.
        int toWake = Math.min(threads.length, slices) - wake.availablePermits();
        if (toWake > 0) {
            wake.release(toWake);
        }
    }

    /**
     * Finishes the frame started last, if any: builds the slices nobody has claimed and waits for
     * the rest. Render thread. Rethrows what a slice threw.
     */
    void finish() {
        if (!pending) {
            return;
        }
        pending = false;
        buildClaimed();
        int spins = 0;
        while (done.get() < sliceCount) {
            if (spins < SPINS) {
                spins++;
                Thread.onSpinWait();
            } else {
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
        Throwable error = failure;
        failure = null;
        if (error instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (error != null) {
            throw new IllegalStateException("A particle quad slice failed", error);
        }
    }

    /** Finishes any frame, then ends the threads (a world left, the game closing). Render thread. */
    void stop() {
        try {
            finish();
        } finally {
            Thread[] stopping = threads;
            threads = null;
            if (stopping != null) {
                generation++;
                wake.release(stopping.length);
                for (Thread thread : stopping) {
                    try {
                        thread.join(JOIN_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                wake.drainPermits();
                LiteLightsClient.LOG.debug("Particle quad helpers stopped");
            }
        }
    }

    /** Helper threads running now (QA). */
    int threadCount() {
        return threads == null ? 0 : threads.length;
    }

    private void ensureStarted() {
        if (threads != null) {
            return;
        }
        int mine = generation;
        threads = new Thread[helperCount];
        for (int i = 0; i < helperCount; i++) {
            Thread thread = new Thread(() -> loop(mine), "LiteLights particle quads " + (i + 1));
            thread.setDaemon(true);
            thread.start();
            threads[i] = thread;
        }
    }

    private void loop(int mine) {
        while (generation == mine) {
            wake.acquireUninterruptibly();
            if (generation != mine) {
                break;
            }
            buildClaimed();
        }
    }

    /** Claims and builds slices until none is left; any thread. */
    private void buildClaimed() {
        while (true) {
            long claim = claims.getAndIncrement();
            int slice = (int) claim;
            if (slice < 0 || slice >= (int) (claim >>> 32)) {
                return;
            }
            try {
                buildSlice.accept(slice);
            } catch (Throwable error) {
                failure = error;
            } finally {
                done.incrementAndGet();
            }
        }
    }
}
