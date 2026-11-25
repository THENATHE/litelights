package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.PreviewWeather;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameClock;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.frame.WeatherPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * Snowy leaves and ground (Weather details): keeps {@link SnowCover} going from the world's weather
 * and hands the terrain shaders how much snow has gathered, in the LiteFrame block's
 * {@code LiteSnowCover} (include/weather/snow_cover.glsl). Which leaves and which ground take it
 * the shaders find per column from the snow cover region the water stream keeps under its column
 * map (SnowCoverGrid): canopies open to the sky in biomes where it snows, and the bare ground
 * under them. This asks the water stream to keep that region while the row is on in a dimension
 * with weather, so it is ready when the snow begins.
 *
 * <p>The snow keeps gathering and thawing with the row off, so switching it on shows the right
 * amount. While the settings screen previews snow, the canopies show full at once, in any biome,
 * so the row can be seen from wherever the screen is opened; the world's own snow carries on
 * underneath.
 */
public final class SnowyLeaves {
    private static final SnowyLeaves INSTANCE = new SnowyLeaves();
    /** Longer frames than this (a hitch, a world load) only move the snow on this far. */
    private static final float LONGEST_STEP = 0.25f;

    private final SnowCover snow = new SnowCover();
    private @Nullable ClientLevel lastLevel;
    private volatile boolean wantsMap;
    private boolean visualPlants;
    private boolean fanTrees;
    private boolean fanOverhangs;
    /** Bits of {@code LiteSnowCover.w}, mirrored in include/weather/snow_cover.glsl. */
    static final int VISUAL_ON = 1;
    static final int VISUAL_GRASS = 2;
    static final int VISUAL_LEAF_TOPS = 4;
    static final int VISUAL_FAN_TREES = 8;
    static final int VISUAL_FAN_OVERHANGS = 16;
    /** QA only: how much faster than real time the snow gathers and thaws. */
    private float timeScaleForQa = 1.0f;

    private SnowyLeaves() {
    }

    public static SnowyLeaves get() {
        return INSTANCE;
    }

    /** Called once from the client entrypoint. */
    public void register() {
        LiteRenderEvents.FRAME_UPDATE.register(this::frameUpdate);
    }

    /** Whether the row is on where there is weather, so the water stream keeps the snow cover region up to date. */
    public boolean wantsSnowMap() {
        return wantsMap;
    }

    /** How much snow has gathered now, 0 to 1 (for QA). */
    public float cover() {
        return snow.cover();
    }

    /** QA only: gathers and thaws the snow this many times faster, so a check needn't wait minutes. */
    public void setTimeScaleForQa(float scale) {
        timeScaleForQa = scale;
    }

    /** The world was left: no snow, and nothing kept of the level. */
    public void onWorldLeft() {
        snow.reset();
        lastLevel = null;
        wantsMap = false;
    }

    /** QA only: no snow, and the next reading starts afresh as on joining a world. */
    public void resetForQa() {
        snow.reset();
    }

    private void frameUpdate(FrameState frame, RenderSettings settings) {
        try {
            update(frame, settings);
            RenderFeatureStatus.succeeded(RenderFeature.SNOWY_LEAVES);
        } catch (RuntimeException error) {
            frame.snowCover.set(0.0f);
            wantsMap = false;
            RenderFeatureStatus.failed(RenderFeature.SNOWY_LEAVES, error);
        }
    }

    private void update(FrameState frame, RenderSettings settings) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            snow.reset();
            lastLevel = level;
        }
        if (!DimensionGate.hasWeather(level)) {
            wantsMap = false;
            frame.snowCover.set(0.0f);
            return;
        }
        // The world's own weather, never the preview's: the preview only changes what is drawn. With
        // a seasons mod that shares the rain out by climate (Almanac), the world's levels, not the
        // player's share: a snowing taiga's canopies frost while the player stands in dry land.
        float seconds = FrameClock.gameSeconds(Math.min(LONGEST_STEP, FrameClock.frameSeconds()));
        snow.update(seconds * timeScaleForQa, LocalClimate.worldRain(level), LocalClimate.worldThunder(level));

        float cover = snow.cover();
        float anyBiome = 0.0f;
        if (WeatherPreview.active()) {
            boolean previewSnow = WeatherPreview.fall() == PreviewWeather.Fall.SNOW;
            cover = LiteMath.lerp(cover, previewSnow ? 1.0f : 0.0f, WeatherPreview.mix());
            anyBiome = previewSnow ? 1.0f : 0.0f;
        }

        boolean leaves = settings.weather().snowyLeaves() && RenderFeatureStatus.mayRun(RenderFeature.SNOWY_LEAVES);
        RenderSettings.VisualSnow visual = settings.weather().visualSnow();
        int visualBits = visualBits(visual, leaves);
        wantsMap = leaves || (visualBits & ~VISUAL_ON) != 0;
        visualPlants = visual.on();
        fanTrees = (visualBits & VISUAL_FAN_TREES) != 0;
        fanOverhangs = (visualBits & VISUAL_FAN_OVERHANGS) != 0;
        if (!wantsMap) {
            frame.snowCover.set(0.0f);
            return;
        }
        // Plants standing in snow are white whether or not it is snowing now: with the visual snow on,
        // only grass and ferns (its White grass in snow), otherwise every plant, as since 1.0.7.
        float plantTint = leaves && !visual.on() ? 1.0f : 0.0f;
        frame.snowCover.set(leaves ? Math.max(cover, 0.0f) : 0.0f, anyBiome, plantTint, visualBits);
    }

    /**
     * The visual snow's parts the terrain shaders draw from the snow cover region, as bits of
     * {@code LiteSnowCover.w} (include/weather/snow_cover.glsl). The snow under trees fans out from the
     * dusting Snowy leaves and ground draws, so it needs that row too.
     */
    static int visualBits(RenderSettings.VisualSnow visual, boolean snowyLeaves) {
        if (!visual.on()) {
            return 0;
        }
        int bits = VISUAL_ON;
        if (visual.grass()) {
            bits |= VISUAL_GRASS;
        }
        if (visual.leaves() == RenderSettings.SnowyLeavesLook.TOPS) {
            bits |= VISUAL_LEAF_TOPS;
        }
        if (visual.underTrees() && snowyLeaves) {
            bits |= VISUAL_FAN_TREES;
        }
        if (visual.underOverhangs()) {
            bits |= VISUAL_FAN_OVERHANGS;
        }
        return bits;
    }

    /** Whether plants stand in snow by the visual snow's rule, in the snow cover region. */
    public boolean visualPlants() {
        return visualPlants;
    }

    /** Whether the snow cover region carries the fanning under trees. */
    public boolean fanTrees() {
        return fanTrees;
    }

    /** Whether the snow cover region carries the fanning under overhangs. */
    public boolean fanOverhangs() {
        return fanOverhangs;
    }
}
