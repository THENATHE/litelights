package com.thenathe.litelights.render.shadow;

import com.thenathe.litelights.render.post.GpuTimer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CPU and GPU time spent drawing sun shadow map redraws, for benchmarks. Off unless the game runs
 * with {@code -Dlitelights.casterTimings=true}, so normal play pays nothing. GPU results are read
 * a few frames late, when the GPU has finished them.
 */
public final class RedrawTimings {
    private static final boolean ENABLED = Boolean.getBoolean("litelights.casterTimings");

    private static final GpuTimer TIMER = new GpuTimer(1);
    private static final GpuTimer.Results ADD_GPU = (section, millis) -> addGpu(millis);
    private static long cpuStartNanos;
    private static boolean started;

    private static long frames;
    private static long sections;
    private static double cpuMillis;
    private static double gpuMillis;
    private static long gpuSamples;

    private RedrawTimings() {
    }

    static void begin() {
        if (!ENABLED) {
            return;
        }
        TIMER.nextFrame(ADD_GPU);
        TIMER.begin(0);
        started = true;
        cpuStartNanos = System.nanoTime();
    }

    static void end(int sectionCount) {
        if (!ENABLED || !started) {
            return;
        }
        synchronized (RedrawTimings.class) {
            cpuMillis += (System.nanoTime() - cpuStartNanos) / 1.0e6;
            frames++;
            sections += sectionCount;
        }
        TIMER.end(0);
    }

    private static synchronized void addGpu(double millis) {
        gpuMillis += millis;
        gpuSamples++;
    }

    public static synchronized void reset() {
        frames = 0;
        sections = 0;
        cpuMillis = 0.0;
        gpuMillis = 0.0;
        gpuSamples = 0;
    }

    /** Totals since the last reset: frames that drew part of a redraw, sections drawn, CPU and GPU time. */
    public static synchronized Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", ENABLED);
        result.put("redrawFrames", frames);
        result.put("sectionsDrawn", sections);
        result.put("cpuMs", cpuMillis);
        result.put("gpuMs", gpuMillis);
        result.put("gpuFramesRead", gpuSamples);
        return result;
    }
}
