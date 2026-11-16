package com.thenathe.litelights.render.api;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.frame.FrameState;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Extension points for LiteLights features. See docs/ARCHITECTURE.md for when each fires and what a
 * listener may do there.
 */
public final class LiteRenderEvents {
    private LiteRenderEvents() {
    }

    /**
     * Before the frame's LiteFrame block is uploaded. Fill your feature's values into
     * {@code frame.featureValuesA..D} (each stream owns the slots ARCHITECTURE.md gives it) and set
     * your feature bits. No render pass is open: GPU uploads are allowed.
     */
    public static final Event<FrameUpdate> FRAME_UPDATE = EventFactory.createArrayBacked(FrameUpdate.class,
        listeners -> (frame, settings) -> {
            for (FrameUpdate listener : listeners) {
                try {
                    listener.onFrameUpdate(frame, settings);
                } catch (RuntimeException failure) {
                    ListenerFailures.report("FRAME_UPDATE", listener, failure);
                }
            }
        });

    /**
     * After the sky is drawn into the main target, before terrain. For drawing a replacement or
     * addition to the sky (stars, aurora, clouds). Open your own render pass on {@code target}.
     */
    public static final Event<AfterSky> AFTER_SKY = EventFactory.createArrayBacked(AfterSky.class,
        listeners -> (target, frame) -> {
            for (AfterSky listener : listeners) {
                try {
                    listener.afterSky(target, frame);
                } catch (RuntimeException failure) {
                    ListenerFailures.report("AFTER_SKY", listener, failure);
                }
            }
        });

    /**
     * After the whole level (terrain, entities, translucents, particles, weather) is in the main
     * target and before the hand and the HUD: the place for post-processing (tonemap, grade, bloom,
     * screen-space reflections, underwater passes). Open your own passes; read the main target's
     * colour and depth.
     */
    public static final Event<AfterLevel> AFTER_LEVEL = EventFactory.createArrayBacked(AfterLevel.class,
        listeners -> (target, frame) -> {
            for (AfterLevel listener : listeners) {
                try {
                    listener.afterLevel(target, frame);
                } catch (RuntimeException failure) {
                    ListenerFailures.report("AFTER_LEVEL", listener, failure);
                }
            }
        });

    @FunctionalInterface
    public interface FrameUpdate {
        void onFrameUpdate(FrameState frame, RenderSettings settings);
    }

    @FunctionalInterface
    public interface AfterSky {
        void afterSky(RenderTarget mainTarget, FrameState frame);
    }

    @FunctionalInterface
    public interface AfterLevel {
        void afterLevel(RenderTarget mainTarget, FrameState frame);
    }
}
