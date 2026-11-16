package com.thenathe.litelights.overlays.generation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bounded pool overlays are built on: at most four threads, never more than half the cores,
 * daemon and below normal priority so a build can't starve the game or hold up shutdown.
 */
public final class Workers {
    /** Every build thread's name starts with this. */
    public static final String THREAD_PREFIX = "LiteLights overlays ";
    public static final int THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, threadFactory("worker"));
    /**
     * Runs each build's top level, which waits on tasks in {@link #pool()}. Keeping it off the pool
     * means a one-thread pool can't end up waiting on itself.
     */
    private static final ExecutorService COORDINATOR = Executors.newSingleThreadExecutor(threadFactory("builder"));

    private Workers() {
    }

    public static ExecutorService pool() {
        return POOL;
    }

    public static ExecutorService coordinator() {
        return COORDINATOR;
    }

    private static ThreadFactory threadFactory(String role) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, THREAD_PREFIX + role + " " + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }
}
