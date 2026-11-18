package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.config.PreviewWeather;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.feature.particles.BiomeClimate;
import com.thenathe.litelights.render.feature.skyfog.StormAtmosphere;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameClock;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.frame.WeatherPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Weather on glass (idea by duffopuffo, Weather Glass; concepts only): works out every frame what
 * the weather at the camera leaves on glass ({@link SurfaceWeather}: frost, snow and sand) and hands
 * it to the terrain shaders in the LiteFrame block (include/glass/weather.glsl). The shaders find
 * out per face whether snow and sand reach it from the rain tops the water stream keeps under its
 * column map, and where its window's edges are from the glass windows it keeps under those. Rain on
 * glass is the wall streaks' (RainDetails).
 *
 * <p>It follows its own rows only, whatever Rain and snow is set to. Off, handled by Weather Glass,
 * or in a dimension whose weather switch is off, the frame values are all 0 and the shaders draw
 * nothing (a single uniform test). The surface model keeps running regardless, since the rain drips
 * and wall streaks follow it too.
 */
public final class GlassWeather {
    private static final GlassWeather INSTANCE = new GlassWeather();
    /** Longer frames than this (a hitch, a world load) only move the weather on this far. */
    private static final float LONGEST_STEP = 0.25f;

    private final SurfaceWeather surfaces = new SurfaceWeather();
    private final SurfaceWeather.Conditions conditions = new SurfaceWeather.Conditions();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private @Nullable ClientLevel lastLevel;
    private volatile boolean wantsMaps;
    private boolean rowsMarked;
    /** QA only: how much faster than real time frost, snow, sand and drying come and go. */
    private float timeScaleForQa = 1.0f;

    private GlassWeather() {
    }

    public static GlassWeather get() {
        return INSTANCE;
    }

    /** Called once from the client entrypoint. */
    public void register() {
        LiteRenderEvents.FRAME_UPDATE.register(this::frameUpdate);
        if (WeatherCompat.glassHandledElsewhere()) {
            Feature.of("glass_weather").markHandledBy(WeatherCompat.WEATHER_GLASS);
        }
    }

    /** What the weather leaves on surfaces near the camera (the rain drips read its wetness). */
    public SurfaceWeather surfaces() {
        return surfaces;
    }

    /** The world was left: nothing on any glass, and nothing kept of the level. */
    public void onWorldLeft() {
        surfaces.reset();
        lastLevel = null;
        wantsMaps = false;
    }

    /** QA only: runs the surface weather this many times faster, so a check needn't wait minutes for snow. */
    public void setTimeScaleForQa(float scale) {
        timeScaleForQa = scale;
    }

    /**
     * Whether glass has weather on it this frame or soon will (it is cold, snowing or a sandstorm
     * blows), so the water stream keeps its rain tops and glass windows up to date. Elsewhere they
     * aren't needed, and the glass windows aren't kept at all.
     */
    public boolean wantsRainTops() {
        return wantsMaps;
    }

    private void frameUpdate(FrameState frame, RenderSettings settings) {
        try {
            update(frame, settings);
            RenderFeatureStatus.succeeded(RenderFeature.GLASS_WEATHER);
        } catch (RuntimeException error) {
            clearFrame(frame);
            wantsMaps = false;
            RenderFeatureStatus.failed(RenderFeature.GLASS_WEATHER, error);
        }
    }

    private void update(FrameState frame, RenderSettings settings) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        // Seconds of the world's time: frost, snow and sand keep up when the time races.
        float seconds = FrameClock.gameSeconds(Math.min(LONGEST_STEP, FrameClock.frameSeconds()));
        if (level != lastLevel) {
            surfaces.reset();
            lastLevel = level;
        }
        if (level == null) {
            seconds = 0.0f;
        }
        boolean weatherHere = DimensionGate.hasWeather(level);
        double cameraX = frame.cameraBlockX + frame.cameraFraction.x;
        double cameraY = frame.cameraBlockY + frame.cameraFraction.y;
        double cameraZ = frame.cameraBlockZ + frame.cameraFraction.z;
        if (weatherHere) {
            readConditions(level, cameraX, cameraY, cameraZ);
        } else {
            conditions.clear();
        }
        surfaces.update(seconds * timeScaleForQa, conditions);

        RenderSettings.Glass glass = settings.weather().glass();
        boolean drawing = weatherHere && glass.enabled() && !WeatherCompat.glassHandledElsewhere()
            && RenderFeatureStatus.mayRun(RenderFeature.GLASS_WEATHER);
        if (!drawing) {
            wantsMaps = false;
            clearFrame(frame);
            return;
        }
        float frost = glass.frost() ? surfaces.frost() : 0.0f;
        float snow = glass.frost() ? surfaces.snow() : 0.0f;
        float dust = glass.dust() ? surfaces.dust() : 0.0f;
        // Frost takes 40 seconds to grow and snow and sand longer, so the maps are ready long before
        // a band is a texel wide.
        boolean coming = glass.frost() && (conditions.cold || conditions.snow > 0.0f) || glass.dust() && conditions.sandstorm > 0.05f;
        wantsMaps = coming || frost > 0.0f || snow > 0.0f || dust > 0.0f;

        if (frost <= 0.0f && snow <= 0.0f && dust <= 0.0f) {
            // Nothing on any glass: the shaders skip it all on one test.
            clearFrame(frame);
            return;
        }
        frame.glassCold.set(frost, snow, dust, surfaces.redSand() ? 1.0f : 0.0f);
        frame.glassInfo.set(glass.detail().texelsPerBlock, 0.0f, 0.0f, 0.0f);
        if (!rowsMarked) {
            rowsMarked = true;
            RenderFeatureStatus.markReady("glass_weather");
        }
    }

    /**
     * The weather at the camera: rain or snow by the climate there (vanilla's rule, height included,
     * with a seasons mod the season's), the sandstorm as sky-fog's storm model has it, and while the settings screen
     * previews a weather, that weather.
     */
    private void readConditions(ClientLevel level, double x, double y, double z) {
        cursor.set(x, y, z);
        Holder<Biome> biome = level.getBiome(cursor);
        float rainLevel = WeatherPreview.rain(level.getRainLevel(1.0f));
        PreviewWeather.Fall previewed = WeatherPreview.previewing() ? WeatherPreview.fall() : null;
        Biome.Precipitation fall = previewed == null ? LocalClimate.fallAt(level, biome, cursor)
            : switch (previewed) {
                case RAIN -> Biome.Precipitation.RAIN;
                case SNOW -> Biome.Precipitation.SNOW;
                case SAND -> Biome.Precipitation.NONE;
            };
        // Classified by the season's biome, as the particles do: a winter plain counts as snowy land.
        BiomeClimate climate = BiomeClimate.of(LocalClimate.seasonalBiome(level, biome, cursor));
        conditions.rain = fall == Biome.Precipitation.RAIN ? rainLevel : 0.0f;
        conditions.snow = fall == Biome.Precipitation.SNOW ? rainLevel : 0.0f;
        conditions.cold = previewed == null ? LocalClimate.coldEnoughToSnow(level, biome, cursor) : previewed == PreviewWeather.Fall.SNOW;
        conditions.sandstorm = StormAtmosphere.current().sandstorm();
        conditions.sandy = climate.isSandy() || previewed == PreviewWeather.Fall.SAND;
        conditions.redSand = climate == BiomeClimate.BADLANDS;
    }

    private static void clearFrame(FrameState frame) {
        frame.glassCold.set(0.0f);
        frame.glassInfo.set(0.0f);
    }
}
