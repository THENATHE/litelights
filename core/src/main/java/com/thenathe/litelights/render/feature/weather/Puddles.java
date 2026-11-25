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
import com.thenathe.litelights.render.frame.ShownWeather;
import com.thenathe.litelights.render.frame.WeatherPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Puddles in the rain (Weather details): keeps {@link PuddleWetness} going from the weather at the
 * camera and hands the terrain shaders how far the puddles have grown, how hard it rains on them
 * and the biome's water tint, in the LiteFrame block's {@code LitePuddles}
 * (include/weather/puddles.glsl). The shaders find out per face whether rain reaches it from the
 * rain tops the water stream keeps under its column map, so this asks the water stream to keep
 * that map while there are puddles or rain to make them.
 *
 * <p>The wetness is the camera's: rain where it rains at the camera (with a seasons mod, as the
 * season has it), nothing where it snows or never rains. It keeps running with the row off, so switching it on shows the right puddles.
 * While the settings screen previews rain, the puddles show at once as a long rain would leave
 * them; the world's own wetness carries on underneath.
 */
public final class Puddles {
    private static final Puddles INSTANCE = new Puddles();
    /** Longer frames than this (a hitch, a world load) only move the puddles on this far. */
    private static final float LONGEST_STEP = 0.25f;
    /** Vanilla's water colour, for a biome whose colour can't be read. */
    private static final int VANILLA_WATER = 0x3F76E4;

    private final PuddleWetness wetness = new PuddleWetness();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private @Nullable ClientLevel lastLevel;
    private volatile boolean wantsMaps;
    /** QA only: how much faster than real time the puddles fill and dry. */
    private float timeScaleForQa = 1.0f;

    private Puddles() {
    }

    public static Puddles get() {
        return INSTANCE;
    }

    /** Called once from the client entrypoint. */
    public void register() {
        LiteRenderEvents.FRAME_UPDATE.register(this::frameUpdate);
    }

    /** Whether puddles are drawn or soon will be, so the water stream keeps its rain tops up to date. */
    public boolean wantsRainTops() {
        return wantsMaps;
    }

    /** The puddles' growth now, 0 to 1.3 (for QA). */
    public float cover() {
        return wetness.cover();
    }

    /** QA only: fills and dries the puddles this many times faster, so a check needn't wait minutes. */
    public void setTimeScaleForQa(float scale) {
        timeScaleForQa = scale;
    }

    /** The world was left: dry ground, and nothing kept of the level. */
    public void onWorldLeft() {
        wetness.reset();
        lastLevel = null;
        wantsMaps = false;
    }

    /** QA only: dry ground, and the next reading starts afresh as on joining a world. */
    public void resetForQa() {
        wetness.reset();
    }

    private void frameUpdate(FrameState frame, RenderSettings settings) {
        try {
            update(frame, settings);
            RenderFeatureStatus.succeeded(RenderFeature.PUDDLES);
        } catch (RuntimeException error) {
            frame.puddles.set(0.0f);
            wantsMaps = false;
            RenderFeatureStatus.failed(RenderFeature.PUDDLES, error);
        }
    }

    private void update(FrameState frame, RenderSettings settings) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            wetness.reset();
            lastLevel = level;
        }
        if (!DimensionGate.hasWeather(level)) {
            wantsMaps = false;
            frame.puddles.set(0.0f);
            return;
        }
        cursor.set(frame.cameraBlockX, frame.cameraBlockY, frame.cameraBlockZ);
        Holder<Biome> biome = level.getBiome(cursor);
        PuddleWetness.Fall fall = switch (LocalClimate.fallAt(level, biome, cursor)) {
            case RAIN -> PuddleWetness.Fall.RAIN;
            case SNOW -> PuddleWetness.Fall.SNOW;
            case NONE -> PuddleWetness.Fall.NONE;
        };
        // The world's own weather, never the preview's: the preview only changes what is drawn.
        float rain = ShownWeather.rain(level, 1.0f);
        float thunder = ShownWeather.thunder(level, 1.0f);
        float seconds = FrameClock.gameSeconds(Math.min(LONGEST_STEP, FrameClock.frameSeconds()));
        wetness.update(seconds * timeScaleForQa, fall, rain, thunder);

        float cover = wetness.cover();
        float falling = fall == PuddleWetness.Fall.RAIN ? rain : 0.0f;
        if (WeatherPreview.active()) {
            boolean previewRain = WeatherPreview.fall() == PreviewWeather.Fall.RAIN;
            float previewThunder = WeatherPreview.thunder(thunder);
            cover = LiteMath.lerp(cover, previewRain ? PuddleWetness.fullCover(previewThunder) : 0.0f, WeatherPreview.mix());
            falling = LiteMath.lerp(falling, previewRain ? WeatherPreview.rain(rain) : 0.0f, WeatherPreview.mix());
        }

        boolean drawing = settings.weather().puddles() && RenderFeatureStatus.mayRun(RenderFeature.PUDDLES);
        wantsMaps = drawing && (cover > 0.0f || falling > 0.0f);
        if (!drawing || cover <= 0.0f) {
            frame.puddles.set(0.0f);
            return;
        }
        int tint = biome.value().getWaterColor() & 0xFFFFFF;
        frame.puddles.set(cover, falling, tint == 0 ? VANILLA_WATER : tint, 0.0f);
    }
}
