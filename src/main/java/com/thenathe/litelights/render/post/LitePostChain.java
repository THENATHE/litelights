package com.thenathe.litelights.render.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.frame.FrameState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The post chain: every pass that runs after the level, in {@link PostSlot} order. The world stage
 * runs when the level is finished (from {@code LiteRenderEvents.AFTER_LEVEL}), the final stage after
 * the hand and screen overlays, before the HUD. Streams add their passes with {@link #register}
 * during client start-up.
 */
public final class LitePostChain {
    private static final Map<PostSlot, List<PostPass>> PASSES = new EnumMap<>(PostSlot.class);
    private static final PostContext CONTEXT = new PostContext();
    private static boolean worldStageRan;
    private static long lastErrorMillis;

    private LitePostChain() {
    }

    /** Hooks the world stage to the end of the level. Called once at client start. */
    public static void install() {
        LiteRenderEvents.AFTER_LEVEL.register(LitePostChain::runWorldStage);
    }

    /** Adds a pass to a slot. Passes in the same slot run in the order they were registered. */
    public static synchronized void register(PostSlot slot, PostPass pass) {
        PASSES.computeIfAbsent(slot, key -> new ArrayList<>()).add(pass);
    }

    /** The level is finished and nothing else is drawn yet. */
    public static void runWorldStage(RenderTarget mainTarget, FrameState frame) {
        PostTimings.startFrame();
        run(PostSlot.Stage.WORLD, mainTarget, frame);
        worldStageRan = true;
    }

    /** The hand and the screen overlays are drawn; the HUD is next. Only after a world stage. */
    public static void runFinalStage(RenderTarget mainTarget, FrameState frame) {
        if (!worldStageRan) {
            return;
        }
        worldStageRan = false;
        run(PostSlot.Stage.FINAL, mainTarget, frame);
    }

    private static void run(PostSlot.Stage stage, RenderTarget mainTarget, FrameState frame) {
        if (mainTarget.getColorTexture() == null) {
            return;
        }
        CONTEXT.begin(mainTarget, frame, RenderSettingsAccess.current(), stage);
        for (PostSlot slot : PostSlot.values()) {
            List<PostPass> passes = PASSES.get(slot);
            if (slot.stage() != stage || passes == null) {
                continue;
            }
            PostTimings.begin(slot.ordinal());
            for (PostPass pass : passes) {
                try {
                    pass.draw(CONTEXT);
                } catch (RuntimeException error) {
                    // Passes report their own failures on their settings rows; this only keeps one
                    // broken pass from stopping the rest of the chain, and the log readable.
                    long now = System.currentTimeMillis();
                    if (now - lastErrorMillis > 5_000L) {
                        lastErrorMillis = now;
                        LiteLightsClient.LOG.error("Post pass in {} failed", slot, error);
                    }
                }
            }
            PostTimings.end(slot.ordinal());
        }
    }
}
