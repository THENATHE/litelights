package com.thenathe.litelights.render.timing;

import com.google.gson.GsonBuilder;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.LiteLightsConfig;
import com.thenathe.litelights.config.Option;
import com.thenathe.litelights.render.frame.FrameClock;
import com.thenathe.litelights.render.post.GpuTimer;
import com.thenathe.litelights.render.post.PostTimings;
import com.thenathe.litelights.render.shadow.RedrawTimings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Interleaved A/B timing, a developer tool: flips one option (or a named switch in the code) every
 * few frames in the same scene and reports frame time and the GPU timers for each side. Both sides
 * share the scene, the weather and the machine's state, so a short run gives a trustworthy
 * difference without a full benchmark.
 *
 * <p>Started by {@code -Dlitelights.ab=<option id or switch name>} (see docs/ARCHITECTURE.md, Debug
 * view, for every property), or from QA code through {@link #start}. While nothing runs, every hook
 * returns after reading one flag.
 *
 * <p>What it measures, per side: {@code frame} (wall time from one frame's start to the next),
 * {@code gpu.frame} (GPU time from the frame's start to the end of the final post stage),
 * {@code gpu.level} (the level alone), every post slot ({@code post.<SLOT>}, from
 * {@link PostTimings}), sun shadow redraws ({@code redraw.cpu}, {@code redraw.gpu}, from
 * {@link RedrawTimings}), and the frame times again for redraw frames only ({@code frame.redraw},
 * {@code gpu.frame.redraw}), so shadow options compare redraw frames with redraw frames. Other code
 * can add its own metrics with {@link #addCpu} and {@link #addGpu}.
 *
 * <p>Render thread only.
 */
public final class AbTiming {
    private static final String TARGET_PROPERTY = "litelights.ab";
    private static final int SECTION_FRAME = 0;
    private static final int SECTION_LEVEL = 1;
    /** Longer than any GPU timer's delay, so late results still find their frame's redraw flag. */
    private static final int RECENT_FRAMES = 64;
    private static final String REPORT_FILE = "litelights-ab-timing.json";

    private static volatile boolean running;
    private static boolean pendingFromProperties = System.getProperty(TARGET_PROPERTY) != null;
    private static long firstWorldFrameNanos;

    private static final GpuTimer GPU = new GpuTimer(2);
    private static final GpuTimer.Results ADD_GPU_SECTION = AbTiming::addGpuSection;
    private static final AbStatistics STATISTICS = new AbStatistics();
    private static final boolean[] REDRAW_FRAMES = new boolean[RECENT_FRAMES];

    private static AbSchedule schedule = new AbSchedule(8, 2, Long.MAX_VALUE);
    private static AbSchedule.Side side = AbSchedule.Side.A;
    private static String target = "";
    private static @Nullable AbOptionFlip optionFlip;
    private static long frameStartNanos;
    private static long currentFrame = -1L;
    private static long startedNanos;
    private static long lastReportNanos;
    private static double stopAfterSeconds;
    private static double reportEverySeconds;
    private static boolean postTimingsWereOn;
    private static boolean redrawTimingsWereOn;

    private AbTiming() {
    }

    public static boolean running() {
        return running;
    }

    /**
     * Whether code behind the named switch should take its B path this frame. Always false unless a
     * run on that name is going, so a switch can stay in the code for good.
     */
    public static boolean flipped(String name) {
        return running && side == AbSchedule.Side.B && optionFlip == null && target.equals(name);
    }

    /**
     * Starts a run from the next frame on.
     *
     * @param name an option id ({@code shadows.entities}) or a named switch read with {@link #flipped}
     * @param values for an option that isn't a switch, its A and B values ("fancy,off"); else empty
     * @throws IllegalArgumentException when the values don't fit the option
     */
    public static synchronized void start(String name, String values, int blockFrames, int settleFrames) {
        if (running) {
            stop();
        }
        Option<?> option = LiteLightsConfig.registry().get(name);
        optionFlip = option == null ? null : AbOptionFlip.of(option, values);
        target = name;
        schedule = new AbSchedule(blockFrames, settleFrames, FrameClock.frameNumber() + 1);
        side = AbSchedule.Side.A;
        STATISTICS.clear();
        currentFrame = -1L;
        postTimingsWereOn = PostTimings.enabled();
        redrawTimingsWereOn = RedrawTimings.enabled();
        PostTimings.setEnabled(true);
        RedrawTimings.setEnabled(true);
        startedNanos = System.nanoTime();
        lastReportNanos = startedNanos;
        running = true;
        LiteLightsClient.LOG.info("A/B timing started: {} {}, {} frames a side, the first {} not measured", name,
            optionFlip == null ? "(a named switch: code asking for it takes path B on side B)" : "A=" + optionFlip.value(AbSchedule.Side.A)
                + " B=" + optionFlip.value(AbSchedule.Side.B), blockFrames, settleFrames);
    }

    /** Stops the run, puts the option back as it was, and returns the summary. */
    public static synchronized Map<String, Object> stop() {
        if (!running) {
            return report();
        }
        running = false;
        if (optionFlip != null) {
            optionFlip.restore();
        }
        PostTimings.setEnabled(postTimingsWereOn);
        RedrawTimings.setEnabled(redrawTimingsWereOn);
        Map<String, Object> summary = report();
        logReport("A/B timing stopped");
        return summary;
    }

    /** Forgets the samples so far and starts the schedule again on side A at the next frame. */
    public static synchronized void reset() {
        if (!running) {
            return;
        }
        STATISTICS.clear();
        schedule = new AbSchedule(schedule.blockFrames(), schedule.settleFrames(), FrameClock.frameNumber() + 1);
        currentFrame = -1L;
        startedNanos = System.nanoTime();
        lastReportNanos = startedNanos;
    }

    /** At the start of every frame, after {@link FrameClock#frameStarted}. */
    public static void frameStarting() {
        if (!running) {
            if (pendingFromProperties) {
                startFromPropertiesWhenDue();
            }
            return;
        }
        long now = System.nanoTime();
        long frame = FrameClock.frameNumber();
        if (currentFrame >= 0L && schedule.measured(currentFrame)) {
            double millis = (now - frameStartNanos) / 1.0e6;
            add("frame", currentFrame, millis);
            if (REDRAW_FRAMES[slot(currentFrame)]) {
                add("frame.redraw", currentFrame, millis);
            }
        }
        GPU.nextFrame(ADD_GPU_SECTION);
        REDRAW_FRAMES[slot(frame)] = false;
        if (schedule.flipsAt(frame)) {
            side = schedule.sideOf(frame);
            if (optionFlip != null) {
                optionFlip.apply(side);
            }
        }
        GPU.begin(SECTION_FRAME);
        frameStartNanos = now;
        currentFrame = frame;
        reportOrStopWhenDue(now);
    }

    /** Just before the level is drawn, with no render pass open. */
    public static void beforeLevel() {
        if (running) {
            GPU.begin(SECTION_LEVEL);
        }
    }

    /** The level is drawn, before the post chain's world stage. */
    public static void afterLevel() {
        if (running) {
            GPU.end(SECTION_LEVEL);
        }
    }

    /** The final post stage is done: the frame's world drawing is over. */
    public static void afterFinalStage() {
        if (running) {
            GPU.end(SECTION_FRAME);
        }
    }

    /** CPU time spent on something during the current frame. */
    public static void addCpu(String metric, double millis) {
        if (running) {
            add(metric, currentFrame, millis);
        }
    }

    /** A GPU time read back late, with the number of the frame it was recorded in ({@link FrameClock#frameNumber}). */
    public static void addGpu(String metric, long frame, double millis) {
        if (running) {
            add(metric, frame, millis);
        }
    }

    /** The current frame draws part of a sun shadow map redraw. */
    public static void markRedrawFrame() {
        if (running && currentFrame >= 0L) {
            REDRAW_FRAMES[slot(currentFrame)] = true;
        }
    }

    /** Everything measured so far, as a tree of maps for the QA harness or a JSON file. */
    public static synchronized Map<String, Object> report() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        if (optionFlip != null) {
            result.put("valueA", String.valueOf(optionFlip.value(AbSchedule.Side.A)));
            result.put("valueB", String.valueOf(optionFlip.value(AbSchedule.Side.B)));
        } else {
            result.put("namedSwitch", true);
        }
        result.put("framesPerSide", schedule.blockFrames());
        result.put("settlingFrames", schedule.settleFrames());
        result.put("seconds", (System.nanoTime() - startedNanos) / 1.0e9);
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (AbStatistics.Summary summary : STATISTICS.summaries()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("metric", summary.metric());
            entry.put("a", side(summary.a()));
            entry.put("b", side(summary.b()));
            entry.put("meanDifferenceMs", finite(summary.meanDifferenceMs()));
            entry.put("onePercentLowDifferenceMs", finite(summary.onePercentLowDifferenceMs()));
            entry.put("pairs", summary.pairs());
            entry.put("pairedDifferenceMs", finite(summary.pairedDifferenceMs()));
            entry.put("pairedErrorMs", finite(summary.pairedErrorMs()));
            metrics.add(entry);
        }
        result.put("metrics", metrics);
        return result;
    }

    private static Map<String, Object> side(AbStatistics.SideSummary summary) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("samples", summary.samples());
        entry.put("meanMs", finite(summary.meanMs()));
        entry.put("medianMs", finite(summary.medianMs()));
        entry.put("onePercentLowMs", finite(summary.onePercentLowMs()));
        return entry;
    }

    /** JSON has no NaN; a missing value reads better as null anyway. */
    private static @Nullable Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static void add(String metric, long frame, double millis) {
        if (frame >= 0L && schedule.measured(frame) && frame > FrameClock.frameNumber() - RECENT_FRAMES) {
            STATISTICS.add(metric, schedule.sideOf(frame), schedule.block(frame), millis);
        }
    }

    private static void addGpuSection(int section, double millis, long frame) {
        String metric = section == SECTION_FRAME ? "gpu.frame" : "gpu.level";
        add(metric, frame, millis);
        if (section == SECTION_FRAME && REDRAW_FRAMES[slot(frame)]) {
            add("gpu.frame.redraw", frame, millis);
        }
    }

    private static int slot(long frame) {
        return (int) Math.floorMod(frame, (long) RECENT_FRAMES);
    }

    /** A run asked for by system properties starts a few seconds into the first world, once. */
    private static void startFromPropertiesWhenDue() {
        if (Minecraft.getInstance().level == null) {
            firstWorldFrameNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        if (firstWorldFrameNanos == 0L) {
            firstWorldFrameNanos = now;
        }
        double delay = Double.parseDouble(System.getProperty("litelights.ab.delay", "5"));
        if ((now - firstWorldFrameNanos) / 1.0e9 < delay) {
            return;
        }
        pendingFromProperties = false;
        try {
            start(System.getProperty(TARGET_PROPERTY).trim(), System.getProperty("litelights.ab.values", ""),
                Integer.getInteger("litelights.ab.frames", 8), Integer.getInteger("litelights.ab.settle", 2));
            stopAfterSeconds = Double.parseDouble(System.getProperty("litelights.ab.seconds", "0"));
            reportEverySeconds = Double.parseDouble(System.getProperty("litelights.ab.report", "10"));
        } catch (IllegalArgumentException wrong) {
            LiteLightsClient.LOG.error("A/B timing not started: {}", wrong.getMessage());
        }
    }

    private static void reportOrStopWhenDue(long now) {
        double seconds = (now - startedNanos) / 1.0e9;
        if (stopAfterSeconds > 0.0 && seconds >= stopAfterSeconds) {
            stop();
            writeReport();
            return;
        }
        if (reportEverySeconds > 0.0 && (now - lastReportNanos) / 1.0e9 >= reportEverySeconds) {
            lastReportNanos = now;
            logReport("A/B timing");
            writeReport();
        }
    }

    private static void logReport(String heading) {
        StringBuilder text = new StringBuilder(heading).append(": ").append(target);
        if (optionFlip != null) {
            text.append(" (A ").append(optionFlip.value(AbSchedule.Side.A)).append(", B ").append(optionFlip.value(AbSchedule.Side.B)).append(')');
        }
        for (AbStatistics.Summary summary : STATISTICS.summaries()) {
            text.append(String.format(Locale.ROOT, "%n  %-22s A %8.3f ms (1%% low %8.3f, %6d)  B %8.3f ms (1%% low %8.3f, %6d)  B-A %+.3f ms ± %.3f over %d pairs",
                summary.metric(), summary.a().meanMs(), summary.a().onePercentLowMs(), summary.a().samples(),
                summary.b().meanMs(), summary.b().onePercentLowMs(), summary.b().samples(),
                summary.pairedDifferenceMs(), summary.pairedErrorMs(), summary.pairs()));
        }
        LiteLightsClient.LOG.info(text.toString());
    }

    private static void writeReport() {
        Path file = FabricLoader.getInstance().getGameDir().resolve(REPORT_FILE);
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report()));
        } catch (IOException failure) {
            LiteLightsClient.LOG.warn("Couldn't write {}", file, failure);
        }
    }
}
