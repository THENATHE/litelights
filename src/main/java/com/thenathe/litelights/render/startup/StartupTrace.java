package com.thenathe.litelights.render.startup;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.pipeline.ShaderLoopCaps;
import com.thenathe.litelights.render.pipeline.TerrainParts;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Breadcrumbs in the log from start-up to the first world frames, so that the log of a game that
 * froze says how far LiteLights got. 0.1.7 froze on a Mac (OpenGL 4.1 on Metal) as the world
 * appeared, and its log ended without a word (DECISIONS mac096).
 *
 * <p>Each line is written once per game session; the shader line once per resource reload. Nothing
 * is written per frame, and once the start-up is over every hook returns at its first check.
 *
 * <p>Every line's text starts with "LiteLights:": some launchers' logs stop naming the logger a few
 * seconds after start-up, and the Mac log behind this was one of them.
 *
 * <p>A watch thread runs from the first frame until 30 s after the first world frame. If no new
 * frame starts for 10 s it writes one warning naming the step the game was in and where the render
 * thread is. That is the one line a frozen game still writes. If the render thread is inside a
 * terrain draw then, it also leaves {@link TerrainStallMarker} for the next launch; a launch that
 * reaches the end of the watch without a stall removes it.
 */
public final class StartupTrace {
    static final long SLOW_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long WATCH_AFTER_FIRST_FRAME_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long WATCH_INTERVAL_MILLIS = 500;
    private static final Logger LOG = LiteLightsClient.LOG;
    /** Holds the first world frame's set-up this long, to try the watch (QA only; 0 in play). */
    private static final long DEBUG_SLOW_FIRST_FRAME_MILLIS = Long.getLong("litelights.debug.slowFirstFrameSeconds", 0L) * 1000L;

    // Read by the watch thread.
    private static volatile boolean finished;
    private static volatile long lastFrameNanos;
    private static volatile String step = "loading the game";
    private static volatile boolean stallReported;
    private static @Nullable Thread renderThread;

    // Render thread only.
    private static boolean stallEndShown;
    private static long worldJoinedNanos;
    private static long framesWhileJoining;
    private static long firstFrameStartNanos;
    private static long firstFrameDoneNanos;
    private static long frameSetUpStartNanos;
    private static long levelStartNanos;
    private static long mobMapStartNanos;
    private static boolean mobMapShown;
    private static long postChainStartNanos;
    private static boolean postChainShown;
    private static boolean sunMapShown;
    private static boolean postPipelinesShown;
    private static boolean redrawShown;
    private static long redrawStartNanos;
    private static int redrawFrames;
    private static int redrawSections;

    // Pipeline compiles, from the reload's executor and the render thread.
    private static final CompileTally RELOAD_COMPILES = new CompileTally();
    private static final CompileTally FIRST_USE_COMPILES = new CompileTally();
    private static volatile boolean reloading;
    private static volatile long reloadStartNanos;
    private static volatile long lastReloadLinkNanos;
    private static boolean reloadShaderWarningShown;

    private StartupTrace() {
    }

    /** Once, when the GPU device exists: what the game is drawing with. */
    public static void graphics(DeviceInfo device) {
        LOG.info("LiteLights: graphics: {}, {} ({}), driver {}; persistent mapping {}, clip control {}",
            device.backendName(), device.name(), device.vendorName(), device.driverInfo(),
            yesNo(device.features().persistentMapping()), yesNo(device.isZZeroToOne()));
    }

    // --- Pipelines ---

    /** A resource reload is compiling every shader again (from ShaderManager.reload). */
    public static void reloadStarting() {
        synchronized (RELOAD_COMPILES) {
            RELOAD_COMPILES.clear();
        }
        reloadStartNanos = System.nanoTime();
        lastReloadLinkNanos = reloadStartNanos;
        reloading = true;
        if (worldJoinedNanos == 0) {
            step = "loading resources";
        }
    }

    /** The reload's pipelines are in place (end of ShaderManager.apply): one line per reload. */
    public static void reloadFinished() {
        if (!reloading) {
            return;
        }
        reloading = false;
        long tookNanos = lastReloadLinkNanos - reloadStartNanos;
        String summary;
        synchronized (RELOAD_COMPILES) {
            summary = String.format(Locale.ROOT, "%d pipelines (%d with LiteLights shaders) compiled in %s; the driver linked them in %d ms%s",
                RELOAD_COMPILES.count, RELOAD_COMPILES.withLiteLightsShaders, seconds(tookNanos), millis(RELOAD_COMPILES.totalNanos),
                RELOAD_COMPILES.slowest());
        }
        String loopCaps = ShaderLoopCaps.builtState();
        if (loopCaps != null) {
            summary += "; shader loop caps " + loopCaps;
        }
        String terrainParts = TerrainParts.builtState();
        if (terrainParts != null) {
            summary += "; " + terrainParts;
        }
        if (tookNanos > SLOW_NANOS && !reloadShaderWarningShown) {
            reloadShaderWarningShown = true;
            LOG.warn("LiteLights: slow start-up step: shaders took over 10 s: {}", summary);
        } else {
            LOG.info("LiteLights: shaders ready: {}", summary);
        }
        if (worldJoinedNanos == 0) {
            step = "the title screen and menus";
        }
    }

    /**
     * Times the driver's part of a pipeline compile (the GL compile and link, or Vulkan's pipeline
     * creation), which runs when the pending pipeline is finished, and the whole compile from
     * {@code requestedNanos}, when it was asked for.
     */
    public static CompiledRenderPipeline.Pending timed(RenderPipeline pipeline, CompiledRenderPipeline.Pending pending, long requestedNanos) {
        if (pending == CompiledRenderPipeline.Pending.NULL || pending instanceof TimedPending) {
            return pending;
        }
        return new TimedPending(pipeline, pending, requestedNanos);
    }

    private record TimedPending(RenderPipeline pipeline, CompiledRenderPipeline.Pending pending, long requestedNanos)
        implements CompiledRenderPipeline.Pending {
        @Override
        public @Nullable CompiledRenderPipeline finishCompile() {
            long started = System.nanoTime();
            CompiledRenderPipeline compiled = pending.finishCompile();
            long done = System.nanoTime();
            compiledOne(pipeline, done - started, done - requestedNanos);
            return compiled;
        }
    }

    private static void compiledOne(RenderPipeline pipeline, long linkNanos, long sinceRequestNanos) {
        boolean liteLights = pipeline.getShaders().values().stream()
            .anyMatch(shader -> LiteLightsClient.MOD_ID.equals(shader.getNamespace()));
        if (reloading) {
            synchronized (RELOAD_COMPILES) {
                RELOAD_COMPILES.add(pipeline, linkNanos, liteLights);
            }
            lastReloadLinkNanos = System.nanoTime();
        } else if (!finished) {
            // Compiled on first use, on the render thread: the whole wait counts, the translation
            // to the driver's shading language included.
            synchronized (FIRST_USE_COMPILES) {
                FIRST_USE_COMPILES.add(pipeline, sinceRequestNanos, liteLights);
            }
        }
    }

    /** Pipelines compiled on first use so far in the start-up (none are counted after it). */
    public static int firstUseCompiles() {
        synchronized (FIRST_USE_COMPILES) {
            return FIRST_USE_COMPILES.count;
        }
    }

    // --- Frames ---

    /** First thing in every frame (GameRenderer.render): the heartbeat the watch thread listens to. */
    public static void frameStarting() {
        if (finished) {
            return;
        }
        long now = System.nanoTime();
        long previousFrameNanos = lastFrameNanos;
        lastFrameNanos = now;
        if (renderThread == null) {
            renderThread = Thread.currentThread();
            startWatch();
        }
        if (stallReported && !stallEndShown) {
            stallEndShown = true;
            LOG.info("LiteLights: the slow frame ended after {}", seconds(now - previousFrameNanos));
        }
        if (firstFrameStartNanos == 0) {
            waitForFirstWorldFrame(now);
        } else if (firstFrameDoneNanos == 0) {
            firstFrameDoneNanos = now;
            String firstUse;
            synchronized (FIRST_USE_COMPILES) {
                firstUse = String.format(Locale.ROOT, "%d pipelines compiled on first use so far, %d ms%s",
                    FIRST_USE_COMPILES.count, millis(FIRST_USE_COMPILES.totalNanos), FIRST_USE_COMPILES.slowest());
            }
            LOG.info("LiteLights: first world frame done in {} ms; {}", millis(now - firstFrameStartNanos), firstUse);
            step = "the first frames after the world appeared";
        } else if (now - firstFrameDoneNanos > WATCH_AFTER_FIRST_FRAME_NANOS) {
            finished = true;
            if (!stallReported) {
                TerrainStallMarker.get().launchWentWell();
            }
        }
    }

    private static void waitForFirstWorldFrame(long now) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (worldJoinedNanos == 0) {
            worldJoinedNanos = now;
            step = "joining the world (waiting for chunks)";
        }
        if (!minecraft.gameRenderer.gameRenderState().shouldRenderLevel) {
            framesWhileJoining++;
            return;
        }
        firstFrameStartNanos = now;
        step = "first world frame, before LiteLights' frame set-up";
        LOG.info("LiteLights: first world frame starting, {} and {} frames after the world loaded",
            seconds(now - worldJoinedNanos), framesWhileJoining);
    }

    private static boolean inFirstWorldFrame() {
        return firstFrameStartNanos != 0 && firstFrameDoneNanos == 0;
    }

    /** LiteLights' work before the level starts (LiteLightsRenderer.beforeLevel). */
    public static void frameSetUpStarting() {
        if (inFirstWorldFrame() && frameSetUpStartNanos == 0) {
            frameSetUpStartNanos = System.nanoTime();
            step = "first world frame: LiteLights' frame set-up";
            if (DEBUG_SLOW_FIRST_FRAME_MILLIS > 0) {
                holdForDebugging();
            }
        }
    }

    /** Only with {@code -Dlitelights.debug.slowFirstFrameSeconds}: lets QA see the watch's warning. */
    private static void holdForDebugging() {
        try {
            Thread.sleep(DEBUG_SLOW_FIRST_FRAME_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** LiteLights' work before the level is done; the level is drawn next. */
    public static void frameSetUpDone() {
        if (inFirstWorldFrame() && frameSetUpStartNanos != 0 && levelStartNanos == 0) {
            levelStartNanos = System.nanoTime();
            LOG.info("LiteLights: first world frame: LiteLights' set-up done in {} ms", millis(levelStartNanos - frameSetUpStartNanos));
            step = "first world frame: drawing the level, before the mob shadow map";
        }
    }

    /**
     * The mob shadow map is drawn by replaying the frame's entity draws with depth-only variants of
     * their pipelines, which are compiled on first use (LiteLightsRenderer.onFeaturesPrepared).
     */
    public static void mobShadowMapStarting() {
        if (inFirstWorldFrame() && mobMapStartNanos == 0) {
            mobMapStartNanos = System.nanoTime();
            step = "first world frame: drawing the mob shadow map";
        }
    }

    public static void mobShadowMapDrawn() {
        if (inFirstWorldFrame() && mobMapStartNanos != 0 && !mobMapShown) {
            mobMapShown = true;
            LOG.info("LiteLights: first world frame: mob shadow map drawn in {} ms", millis(System.nanoTime() - mobMapStartNanos));
            step = "first world frame: drawing the level, after the mob shadow map";
        }
    }

    /** The level is drawn; LiteLights' post chain runs next (LiteLightsRenderer.afterLevel). */
    public static void levelDrawn() {
        if (inFirstWorldFrame() && levelStartNanos != 0 && postChainStartNanos == 0) {
            postChainStartNanos = System.nanoTime();
            LOG.info("LiteLights: first world frame: level drawn in {} ms", millis(postChainStartNanos - levelStartNanos));
            step = "first world frame: LiteLights' post chain";
        }
    }

    /** The post chain's world stage is done; the hand, the HUD and presenting the frame are left. */
    public static void postChainDone() {
        if (inFirstWorldFrame() && postChainStartNanos != 0 && !postChainShown) {
            postChainShown = true;
            LOG.info("LiteLights: first world frame: post chain done in {} ms", millis(System.nanoTime() - postChainStartNanos));
            step = "first world frame: hand, HUD and showing the frame";
        }
    }

    /** Wraps one named step inside the frame set-up, so the watch can name it. */
    public static String beginStep(String name) {
        String outer = step;
        if (!finished) {
            step = name;
        }
        return outer;
    }

    public static void endStep(String outer) {
        if (!finished) {
            step = outer;
        }
    }

    // --- Things made on first use ---

    public static void sunShadowMapMade(int size, long tookNanos) {
        if (sunMapShown) {
            return;
        }
        sunMapShown = true;
        long mebibytes = (long) size * size * Float.BYTES >> 20;
        LOG.info("LiteLights: sun shadow map made: {}x{}, two depth textures of {} MiB, in {} ms", size, size, mebibytes, millis(tookNanos));
    }

    public static void postPipelinesReady(int count, long tookNanos) {
        if (postPipelinesShown) {
            return;
        }
        postPipelinesShown = true;
        LOG.info("LiteLights: post pipelines ready: {} compiled in {} ms", count, millis(tookNanos));
    }

    /** One frame's share of a sun shadow redraw was drawn. */
    public static void shadowSectionsDrawn(int sections) {
        if (redrawShown) {
            return;
        }
        if (redrawStartNanos == 0) {
            redrawStartNanos = System.nanoTime();
        }
        redrawFrames++;
        redrawSections += sections;
    }

    public static void shadowRedrawFinished() {
        if (redrawShown || redrawStartNanos == 0) {
            return;
        }
        if (redrawSections == 0) {
            // Drawn before any casters were built (the usual first redraw): wait for one that draws some.
            redrawStartNanos = 0;
            redrawFrames = 0;
            return;
        }
        redrawShown = true;
        LOG.info("LiteLights: first sun shadow redraw done: {} sections over {} frames, {} ms",
            redrawSections, redrawFrames, millis(System.nanoTime() - redrawStartNanos));
    }

    // --- The watch ---

    /** Leaving the world or quitting: frames may stop for a while for good reasons, so stop watching. */
    public static void stopWatching() {
        finished = true;
    }

    private static void startWatch() {
        Thread watch = new Thread(StartupTrace::watch, "LiteLights start-up watch");
        watch.setDaemon(true);
        watch.setPriority(Thread.MIN_PRIORITY);
        watch.start();
    }

    private static void watch() {
        StallCheck check = new StallCheck(SLOW_NANOS);
        while (!finished) {
            try {
                Thread.sleep(WATCH_INTERVAL_MILLIS);
            } catch (InterruptedException stopped) {
                return;
            }
            if (!finished && check.shouldWarn(System.nanoTime(), lastFrameNanos)) {
                reportStall();
                return;
            }
        }
    }

    private static void reportStall() {
        Thread thread = renderThread;
        RenderThreadStack where = new RenderThreadStack();
        if (thread != null) {
            where.setStackTrace(thread.getStackTrace());
        }
        stallReported = true;
        String stalledStep = step;
        LOG.warn("LiteLights: slow start-up step: {} has taken over 10 s; the render thread is here:", stalledStep, where);
        if (TerrainStallMarker.inTerrainDraw(where.getStackTrace())) {
            TerrainStallMarker.get().stalledInTerrainDraw(stalledStep);
            LOG.warn("LiteLights: the game is stuck drawing the world's blocks; the next launch will offer vanilla terrain shading for that session");
        }
    }

    /** Carries the render thread's stack into the log; not an error of its own. */
    private static final class RenderThreadStack extends Throwable {
        RenderThreadStack() {
            super("where the render thread is (not an error)", null, false, true);
        }
    }

    // --- Formatting ---

    private static final class CompileTally {
        int count;
        int withLiteLightsShaders;
        long totalNanos;
        long slowestNanos;
        @Nullable String slowestName;

        void add(RenderPipeline pipeline, long nanos, boolean liteLights) {
            count++;
            if (liteLights) {
                withLiteLightsShaders++;
            }
            totalNanos += nanos;
            if (nanos > slowestNanos) {
                slowestNanos = nanos;
                slowestName = pipeline.getLocation().toString();
            }
        }

        void clear() {
            count = 0;
            withLiteLightsShaders = 0;
            totalNanos = 0;
            slowestNanos = 0;
            slowestName = null;
        }

        String slowest() {
            return slowestName == null ? "" : " (slowest " + slowestName + ", " + millis(slowestNanos) + " ms)";
        }
    }

    static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1f s", nanos / 1.0e9);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
