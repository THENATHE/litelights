package com.thenathe.litelights.overlays.generation;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.function.Supplier;

/**
 * What the last overlay build cost: wall time, and the memory the build threads allocated while it
 * ran. Kept for the log, the settings page and the performance notes in docs.
 *
 * <p>Allocation is read from the JVM's per-thread counters ({@code com.sun.management}), summed
 * over the coordinator and worker threads, so the game's own allocations in the same moment don't
 * count. Where the JVM doesn't offer the counters, allocation reads as -1.
 *
 * @param millis          wall time of the build
 * @param allocatedBytes  heap allocated by the build threads during the build, or -1
 * @param outputBytes     size of the files the build produced (what the pack holds afterwards)
 * @param thread          the thread the build ran on, never the render thread
 */
public record BuildCost(long millis, long allocatedBytes, long outputBytes, String thread) {
    public static final BuildCost NONE = new BuildCost(0, -1, 0, "");

    private static volatile BuildCost last = NONE;

    public static BuildCost last() {
        return last;
    }

    public static GeneratedFiles measure(Supplier<GeneratedFiles> build) {
        long allocatedBefore = buildThreadAllocation();
        long started = System.nanoTime();
        GeneratedFiles files = build.get();
        long millis = (System.nanoTime() - started) / 1_000_000;
        long allocatedAfter = buildThreadAllocation();
        long allocated = allocatedBefore < 0 || allocatedAfter < 0 ? -1 : allocatedAfter - allocatedBefore;
        last = new BuildCost(millis, allocated, files.report().bytes(), Thread.currentThread().getName());
        return files;
    }

    public String summary() {
        String allocation = allocatedBytes < 0 ? "unknown" : allocatedBytes / (1024 * 1024) + " MiB";
        return millis + " ms, " + allocation + " allocated, " + outputBytes / 1024 + " KiB of files kept";
    }

    /** Bytes allocated so far by every overlay build thread, or -1 if the JVM can't say. */
    private static long buildThreadAllocation() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean threads)
                || !threads.isThreadAllocatedMemorySupported() || !threads.isThreadAllocatedMemoryEnabled()) {
            return -1;
        }
        long total = 0;
        for (ThreadInfo info : threads.dumpAllThreads(false, false)) {
            if (info.getThreadName().startsWith(Workers.THREAD_PREFIX)) {
                long bytes = threads.getThreadAllocatedBytes(info.getThreadId());
                if (bytes > 0) {
                    total += bytes;
                }
            }
        }
        return total;
    }
}
