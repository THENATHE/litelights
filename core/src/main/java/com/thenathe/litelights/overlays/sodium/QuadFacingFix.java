package com.thenathe.litelights.overlays.sodium;

import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State of the temporary fix for Continuity's overlays vanishing under Sodium 0.9.2's block face
 * culling (see DECISIONS, {@code ## culling17}).
 *
 * <p>Sodium files every quad of a chunk under the side of the block it faces, and skips a whole
 * side's quads for a chunk section when the camera is behind that side. Continuity collects its
 * overlay quads in a renderer mesh and copies them into Sodium's block emitter afterwards; in
 * Sodium 0.9.2 that copy ({@code QuadViewImpl.load()}) keeps the facing of whatever quad the
 * emitter held before, usually the block's own last side. The overlays then go missing whenever
 * that other side is culled. The fix (the mixin in {@code mixin.overlayssodium}) takes the facing
 * from the copied quad's own normal, which is what Sodium computes for every other quad, so the
 * overlay lands in the right group and nothing extra is drawn.
 *
 * <p>There is no setting: wherever the fix goes in it runs, and the General page's Compatibility
 * section says whether it is active (0.1.7 had a switch on the Overlays page before release).
 *
 * <p>This class stays free of Minecraft and Sodium types: the mixin plugin reports to it while
 * mixins are still being prepared, before game classes may load.
 */
public final class QuadFacingFix {
    private static final Logger LOG = LoggerFactory.getLogger("LiteLights/Overlays");

    /** Whether the fix could go in, and if not, why. */
    public enum Status {
        /** The mixin plugin hasn't run (or decided nothing yet). */
        NOT_CHECKED,
        NO_SODIUM,
        NO_CONTINUITY,
        /** This Sodium keeps the facing inside the quad's data already (0.9.3 betas), so a copy keeps it. */
        FIXED_IN_SODIUM,
        /** Sodium's quad classes aren't the ones the fix was written against; it stays out. */
        UNKNOWN_SODIUM,
        APPLIED,
        /** It went in but hit an error while drawing and switched itself off. */
        FAILED
    }

    private static volatile Status status = Status.NOT_CHECKED;
    private static volatile boolean wanted = true;
    private static volatile boolean running;
    private static volatile boolean counting;
    private static final LongAdder LOADED = new LongAdder();
    private static final LongAdder REFILED = new LongAdder();

    private QuadFacingFix() {
    }

    /** Called by the mixin plugin once it has looked at the installed mods and Sodium's classes. */
    public static void checked(Status result, String detail) {
        status = result;
        if (result == Status.UNKNOWN_SODIUM) {
            LOG.warn("Sodium face culling fix for Continuity overlays is off: {}", detail);
        }
        update();
    }

    /** Called by the mixin plugin once the mixin is in Sodium's quad class. */
    public static void markApplied() {
        if (status != Status.FAILED) {
            status = Status.APPLIED;
            LOG.info("Sodium face culling fix for Continuity overlays is in place");
        }
        update();
    }

    /**
     * For the rendered tests only, which draw each view with the fix off and then on. Chunks built
     * before the change keep the old filing until they are rebuilt.
     */
    public static void setWantedForTesting(boolean on) {
        wanted = on;
        update();
    }

    public static Status status() {
        return status;
    }

    public static boolean applied() {
        return status == Status.APPLIED;
    }

    /** Whether quads copied out of a mesh get their own facing back, read once per copied quad. */
    public static boolean running() {
        return running;
    }

    /**
     * Stops the fix for the rest of the session and logs once. Sodium then files the overlays as
     * it would without LiteLights.
     */
    public static synchronized void failed(Throwable failure) {
        if (status == Status.FAILED) {
            return;
        }
        status = Status.FAILED;
        update();
        LOG.error("Sodium face culling fix for Continuity overlays failed and is now off", failure);
    }

    /** For the rendered tests: counts copied quads, and those that had a stale facing, from now on. */
    public static void startCountingForTesting() {
        LOADED.reset();
        REFILED.reset();
        counting = true;
    }

    public static String countsForTesting() {
        return "copied " + LOADED.sum() + ", refiled " + REFILED.sum();
    }

    /** Called by the mixin for every quad it corrects; counts only while a test asks. */
    public static void noteCopied(boolean facingWasStale) {
        if (counting) {
            LOADED.increment();
            if (facingWasStale) {
                REFILED.increment();
            }
        }
    }

    /** Back to how a fresh start finds it, for unit tests. */
    static synchronized void resetForTesting() {
        status = Status.NOT_CHECKED;
        wanted = true;
        counting = false;
        LOADED.reset();
        REFILED.reset();
        update();
    }

    /** One line for logs and tests. */
    public static String describe() {
        return status + (running ? ", running" : ", not running");
    }

    private static void update() {
        running = wanted && status == Status.APPLIED;
    }
}
