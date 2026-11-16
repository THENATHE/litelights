package com.thenathe.litelights.render.post;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GPU time spent in each post slot (and in the enchantment outline), from timestamp queries. Off
 * unless the game runs with {@code -Dlitelights.gpuTimings=true} or the QA harness switches it on,
 * so normal play pays nothing. Results are read four frames late, when the GPU has finished them.
 */
public final class PostTimings {
    private static volatile boolean enabled = Boolean.getBoolean("litelights.gpuTimings");
    /** Section index of the enchantment outline, after the post slots. */
    public static final int OUTLINE = PostSlot.values().length;
    /** Section index of TAA's copy of the opaque depth, taken during the level. */
    public static final int TAA_OPAQUE_DEPTH = OUTLINE + 1;

    private static final int SECTIONS = TAA_OPAQUE_DEPTH + 1;
    private static final String[] EXTRA_NAMES = {"ENCHANTMENT_OUTLINE", "TAA_OPAQUE_DEPTH"};

    private static final GpuTimer TIMER = new GpuTimer(SECTIONS);
    private static final GpuTimer.Results ADD = PostTimings::add;
    private static final double[] totalMillis = new double[SECTIONS];
    private static final long[] sampleCounts = new long[SECTIONS];

    private PostTimings() {
    }

    /** Called once per frame, before the first post pass. */
    static void startFrame() {
        if (enabled) {
            TIMER.nextFrame(ADD);
        }
    }

    public static void begin(int section) {
        if (enabled) {
            TIMER.begin(section);
        }
    }

    public static void end(int section) {
        if (enabled) {
            TIMER.end(section);
        }
    }

    private static synchronized void add(int section, double millis) {
        totalMillis[section] += millis;
        sampleCounts[section]++;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static synchronized void reset() {
        Arrays.fill(totalMillis, 0.0);
        Arrays.fill(sampleCounts, 0L);
    }

    /** Average milliseconds per frame each section ran, by name, for the sections that ran. */
    public static synchronized Map<String, Object> averages() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int section = 0; section < SECTIONS; section++) {
            if (sampleCounts[section] > 0) {
                String name = section >= OUTLINE ? EXTRA_NAMES[section - OUTLINE] : PostSlot.values()[section].name();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("averageMs", totalMillis[section] / sampleCounts[section]);
                entry.put("frames", sampleCounts[section]);
                result.put(name, entry);
            }
        }
        return result;
    }
}
