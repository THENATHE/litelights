package com.thenathe.litelights.render.frame;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.config.DimensionOptions;
import com.thenathe.litelights.config.LiteLightsConfig;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The per-dimension switches for modded dimensions, applied in one place for every stream.
 *
 * <p>Two halves:
 * <ul>
 *   <li>Shaders: after every other {@link LiteRenderEvents#FRAME_UPDATE} listener has run, the
 *   feature bits of each switch that is off ({@code FrameFlags.ASPECT_*_BITS}) are cleared, so no
 *   shader draws that effect in this dimension, whatever set the bit.</li>
 *   <li>Java: {@link #allows(Aspect)} lets a stream skip its own work (shadow map redraws, post
 *   passes, particles) so a switched-off effect costs nothing.</li>
 * </ul>
 * Vanilla dimensions and dimensions never seen before are always allowed
 * ({@link DimensionOptions#isOn}).
 */
public final class DimensionGate {
    /** Runs after the default phase of FRAME_UPDATE, so it sees every stream's bits. */
    public static final Identifier GATE_PHASE = LiteLightsClient.id("dimension_gate");

    private static final Aspect[] ASPECTS = Aspect.values();
    private static final boolean[] ALLOWED = new boolean[ASPECTS.length];
    private static @Nullable ClientLevel checkedLevel;
    private static @Nullable String dimensionId;
    private static long checkedChangeCount = -1L;
    private static int gatedBits;

    private DimensionGate() {
    }

    /** Hooks the gate into the frame. Called once from the renderer's start-up. */
    public static void register() {
        LiteRenderEvents.FRAME_UPDATE.addPhaseOrdering(Event.DEFAULT_PHASE, GATE_PHASE);
        LiteRenderEvents.FRAME_UPDATE.register(GATE_PHASE, (frame, settings) -> {
            refresh();
            frame.featureFlags &= ~gatedBits;
        });
    }

    /**
     * Whether an effect may run in the current dimension. Cheap: the answers are worked out again
     * only when the level or a setting changes. Call from the render thread.
     */
    public static boolean allows(Aspect aspect) {
        refresh();
        return ALLOWED[aspect.ordinal()];
    }

    /** Forgets the level checked last, so the gate holds no reference to a world the player left. */
    public static void onWorldLeft() {
        checkedLevel = null;
        dimensionId = null;
        checkedChangeCount = -1L;
    }

    /** The bits {@code FrameFlags} assigns to a switch. */
    public static int bitsOf(Aspect aspect) {
        return switch (aspect) {
            case SKY -> FrameFlags.ASPECT_SKY_BITS;
            case SHADOWS -> FrameFlags.ASPECT_SHADOWS_BITS;
            case REFLECTIONS -> FrameFlags.ASPECT_REFLECTIONS_BITS;
            case LIGHT -> FrameFlags.ASPECT_LIGHT_BITS;
            case BLOOM -> FrameFlags.ASPECT_BLOOM_BITS;
            case OUTLINES -> FrameFlags.ASPECT_OUTLINES_BITS;
            case EFFECTS -> FrameFlags.ASPECT_EFFECTS_BITS;
            case WEATHER -> FrameFlags.ASPECT_WEATHER_BITS;
        };
    }

    private static void refresh() {
        ClientLevel level = Minecraft.getInstance().level;
        long changeCount = LiteLightsConfig.registry().changeCount();
        if (level == checkedLevel && changeCount == checkedChangeCount) {
            return;
        }
        checkedLevel = level;
        checkedChangeCount = changeCount;
        dimensionId = level == null ? null : level.dimension().identifier().toString();
        String id = dimensionId;
        for (Aspect aspect : ASPECTS) {
            ALLOWED[aspect.ordinal()] = id == null || DimensionOptions.isOn(id, aspect);
        }
        gatedBits = gatedBits(aspect -> ALLOWED[aspect.ordinal()]);
    }

    /** The feature bits to clear when only the aspects {@code allowed} accepts may run. */
    static int gatedBits(Predicate<Aspect> allowed) {
        int bits = 0;
        for (Aspect aspect : ASPECTS) {
            if (!allowed.test(aspect)) {
                bits |= bitsOf(aspect);
            }
        }
        return bits;
    }
}
