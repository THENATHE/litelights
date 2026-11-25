package com.thenathe.litelights.render.post;

import com.thenathe.litelights.render.timing.AbTiming;
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
    /** Section index of the far-terrain pass (with its copy of the target), during the level. */
    public static final int FAR_TERRAIN = TAA_OPAQUE_DEPTH + 1;
    /** The far terrain module's land, drawn at the split before translucent terrain. */
    public static final int FAR_LAND = FAR_TERRAIN + 1;

    private static final int SECTIONS = FAR_LAND + 1;
    private static final String[] EXTRA_NAMES = {"ENCHANTMENT_OUTLINE", "TAA_OPAQUE_DEPTH", "FAR_TERRAIN", "FAR_LAND"};

    private static final GpuTimer TIMER = new GpuTimer(SECTIONS);
    private static final GpuTimer.Results ADD = PostTimings::add;
    /** The sections' names as the A/B timing reports them. */
    private static final String[] AB_METRICS = abMetricNames();
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

    private static synchronized void add(int section, double millis, long frame) {
        totalMillis[section] += millis;
        sampleCounts[section]++;
        if (AbTiming.running()) {
            AbTiming.addGpu(AB_METRICS[section], frame, millis);
        }
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    private static String name(int section) {
        return section >= OUTLINE ? EXTRA_NAMES[section - OUTLINE] : PostSlot.values()[section].name();
    }

    private static String[] abMetricNames() {
        String[] names = new String[SECTIONS];
        for (int section = 0; section < SECTIONS; section++) {
            names[section] = "post." + name(section);
        }
        return names;
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
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("averageMs", totalMillis[section] / sampleCounts[section]);
                entry.put("frames", sampleCounts[section]);
                result.put(name(section), entry);
            }
        }
        return result;
    }
}
