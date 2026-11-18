package com.thenathe.litelights.overlays.generation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Every thread made for overlay builds, so {@link BuildCost} can read their counters without a thread dump. */
    private static final Set<Thread> MADE = ConcurrentHashMap.newKeySet();
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

    /** The build threads that are still running. */
    static List<Thread> threads() {
        MADE.removeIf(thread -> !thread.isAlive());
        return List.copyOf(MADE);
    }

    private static ThreadFactory threadFactory(String role) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, THREAD_PREFIX + role + " " + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            MADE.add(thread);
            return thread;
        };
    }
}
