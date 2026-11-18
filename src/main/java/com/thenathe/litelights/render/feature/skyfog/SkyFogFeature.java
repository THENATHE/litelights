package com.thenathe.litelights.render.feature.skyfog;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.config.PreviewEvent;
import com.thenathe.litelights.mixin.particles.BiomeManagerAccessor;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.lightning.CloudLightning;
import com.thenathe.litelights.render.feature.weather.SkyEventSchedule;
import com.thenathe.litelights.render.feature.weather.WeatherCompat;
import com.thenathe.litelights.render.frame.CelestialLight;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameClock;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.frame.WeatherPreview;
import com.thenathe.litelights.render.pipeline.PipelineOwners;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Sky and fog: works out the air for each frame (haze, ground mist, storm fog, sky colours) from
 * the biomes around the camera, the time of day and the weather, writes it into the LiteFrame
 * block, and draws the LiteLights sky after vanilla's sky pass.
 *
 * <p>Everything here runs on the render thread except the biome sampling, which runs on the
 * client tick (also the render thread, between frames). Nothing is allocated per frame.
 */
public final class SkyFogFeature {
    private static final SkyFogFeature INSTANCE = new SkyFogFeature();

    /** Haze height falloff per block in dimensions with a sky: it halves about every 38 blocks. */
    private static final float HAZE_FALLOFF = 1.0f / 55.0f;
    /** The render-distance fade starts at this share of the render distance. */
    private static final float EDGE_FADE_START = 0.75f;
    /** How fast the air eases toward the land around the camera, per second. */
    private static final float LAND_EASE_RATE = 0.8f;
    /** The render-distance fade's end while far terrain is drawn: out of reach. */
    private static final float FAR_TERRAIN_EDGE = 1.0e9f;
    /**
     * Rain and thunderstorms have no storm fog of their own, only a thicker haze. Each step of
     * their Fog density past Normal adds this much even fog per block at full rain, in the rain's
     * own grey, so Very dense closes in to about 60 blocks wherever the camera is, not only low down.
     */
    private static final float RAIN_FOG_PER_STEP = 0.006f;

    private final BiomeAtmospheres table = new BiomeAtmospheres();
    private final AtmosphereSampler sampler = new AtmosphereSampler();
    private final AtmosphereBlend land = new AtmosphereBlend();
    private final StormAtmosphere storm = StormAtmosphere.current();
    private final SkyPass skyPass = new SkyPass();

    private @Nullable ClientLevel level;
    private boolean landKnown;
    private float exposure = 1.0f;
    private float groundHeight;

    // This frame's decisions, read by the sky and cloud hooks.
    private boolean liteSky;
    private boolean realisticSky;
    /** Whether the sky shader draws a meteor shower this frame: a shower night, or its preview. */
    private boolean meteorShower;
    /** The night last asked about, so the schedule is worked out once a night. */
    private SkyEventSchedule.@Nullable Night showerNight;
    private long showerSeed;
    private boolean skyPassWanted;
    private boolean fogReadyMarked;
    private boolean skyReadyMarked;

    // This frame's light, worked out first in fill and read by every part after it.
    private boolean skyless;
    private float rain;
    private float thunder;
    private float sunSine;
    private float day;
    private float twilight;
    /**
     * The part of twilight after the sun has set (or before it rises): 0 while it is up, rising to
     * the whole of the twilight once it is a few degrees under the horizon. The Computed sky turns
     * it into an afterglow and a blue hour instead of a dimming orange (fog098).
     */
    private float afterglow;
    /** Whether the sky colours are Computed this frame, whose sunsets have the afterglow. */
    private boolean computedSky;
    private float moonlight;
    // The land's haze colour after the Biome haze tint setting, worked out first in fill.
    private float hazeRed;
    private float hazeGreen;
    private float hazeBlue;
    // How much the rain and snow thicken the haze and ground mist this frame: the Fog density of
    // the storm types falling here, blended by how much of the wet land is snowy and by thunder.
    private float wetFog = 1.0f;

    private SkyFogFeature() {
    }

    public static SkyFogFeature get() {
        return INSTANCE;
    }

    /** Hooks the feature into LiteLights' render events. Called once from the client entrypoint. */
    public static void register() {
        PipelineOwners.claim(RenderFeature.SKY, SkyPass.PIPELINE);
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onClientTick);
        LiteRenderEvents.FRAME_UPDATE.register(INSTANCE::onFrameUpdate);
        LiteRenderEvents.AFTER_SKY.register(INSTANCE::afterSky);
        CloudLightning.register();
    }

    /** True while LiteLights draws the sun, moon and stars, so vanilla's are skipped this frame. */
    public boolean drawsCelestials() {
        return liteSky;
    }

    /** True while the Realistic sky replaces vanilla's sky disc and sunrise glow this frame. */
    public boolean replacesSkyColours() {
        return liteSky && realisticSky;
    }

    /** The player left the world: forget it, so nothing keeps the old level alive. */
    public void onWorldLeft() {
        level = null;
        table.clear();
        sampler.reset();
        landKnown = false;
        storm.clear();
    }

    private void onClientTick(Minecraft minecraft) {
        ClientLevel currentLevel = minecraft.level;
        if (currentLevel == null || minecraft.gameRenderer == null) {
            return;
        }
        if (currentLevel != level) {
            level = currentLevel;
            table.clear();
            sampler.reset();
            landKnown = false;
            storm.clear();
        }
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        try {
            sampler.sample(currentLevel, camera.x, camera.y, camera.z, table);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.ATMOSPHERIC_FOG, error);
        }
    }

    private void onFrameUpdate(FrameState frame, RenderSettings settings) {
        // Stands still while the game is paused, so the fog's easing waits with everything else.
        float seconds = Math.min(FrameClock.frameSeconds(), 0.25f);
        liteSky = false;
        realisticSky = false;
        skyPassWanted = false;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel currentLevel = minecraft.level;
        if (currentLevel == null || currentLevel != level || !sampler.hasSampled()) {
            storm.clear();
            return;
        }
        table.setModdedColours(settings.general().moddedBiomeFogColors());
        try {
            fill(frame, settings, minecraft, currentLevel, seconds);
            RenderFeatureStatus.succeeded(RenderFeature.ATMOSPHERIC_FOG);
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.ATMOSPHERIC_FOG, error);
            frame.featureFlags &= ~(FrameFlags.ASPECT_EFFECTS_BITS | FrameFlags.ASPECT_SKY_BITS | FrameFlags.FEATURE_STORM_FOG);
            liteSky = false;
        }
    }

    private void fill(FrameState frame, RenderSettings settings, Minecraft minecraft, ClientLevel currentLevel, float seconds) {
        LevelRenderState levelState = minecraft.gameRenderer.gameRenderState().levelRenderState;
        SkyRenderState skyState = levelState.skyRenderState;
        CameraRenderState camera = levelState.cameraRenderState;
        DimensionType dimension = currentLevel.dimensionType();
        boolean end = dimension.skybox() == DimensionType.Skybox.END;
        boolean overworldSky = dimension.skybox() == DimensionType.Skybox.OVERWORLD && dimension.hasSkyLight();

        easeTowardLand(seconds);
        computedSky = settings.sky().palette() == RenderSettings.SkyPalette.COMPUTED;
        readLight(frame, dimension, skyState);
        tintHaze(settings.fog().biomeHazeTint());
        wetFog = LiteMath.lerp(settings.weather().storms().rainFog(thunder), settings.weather().storms().snowFog(thunder), snowyShareOfWetLand());

        RenderSettings.Fog fogSettings = settings.fog();
        boolean liteFog = liteFogAllowed(fogSettings, camera, minecraft);
        boolean haze = liteFog && fogSettings.look() == RenderSettings.FogLook.HAZE
            && (!end || settings.dimensions().endHaze());
        boolean weatherHere = DimensionGate.hasWeather(currentLevel);
        boolean rainMist = settings.weather().rainMist() && weatherHere;

        float mist = fillHaze(frame, camera, currentLevel, fogSettings, haze, rainMist);
        float stormDensity = fillStorm(frame, settings, weatherHere, seconds);
        float stormCover = liteFog && haze ? Math.min(stormDensity * 22.0f, 0.92f) : 0.0f;
        fillSky(frame, settings, skyState, currentLevel, overworldSky, stormCover);
        boolean endTint = end && settings.dimensions().endColorTint() && DimensionGate.allows(Aspect.EFFECTS);
        float aura = end && settings.dimensions().endSkyGlow() ? 0.8f : 0.0f;
        fillEndTint(frame, endTint, aura);

        frame.featureFlags |= featureFlags(skyState, liteFog, haze, mist, endTint, stormDensity, settings);
        skyPassWanted = liteSky || (end && (aura > 0.0f || haze));

        if (!fogReadyMarked && liteFog) {
            fogReadyMarked = true;
            markFogRowsReady();
        }
    }

    /** Eases toward the land around the camera, so borders and caves blend in over a second or so. */
    private void easeTowardLand(float seconds) {
        float ease = landKnown ? SkyFogMath.approachFactor(LAND_EASE_RATE, seconds) : 1.0f;
        land.approach(sampler.target(), ease);
        exposure = LiteMath.lerp(exposure, sampler.skyExposure(), landKnown ? SkyFogMath.approachFactor(0.6f, seconds) : 1.0f);
        groundHeight = LiteMath.lerp(groundHeight, sampler.groundHeight(), landKnown ? SkyFogMath.approachFactor(0.5f, seconds) : 1.0f);
        landKnown = true;
    }

    /** Works out this frame's light: the weather, how far into day or night it is, and the moon. */
    private void readLight(FrameState frame, DimensionType dimension, SkyRenderState skyState) {
        skyless = !dimension.hasSkyLight();
        rain = frame.weather.x;
        thunder = frame.weather.y;
        sunSine = frame.sunDirection.w;
        day = skyless ? 1.0f : SkyFogMath.daylight(sunSine);
        twilight = skyless ? 0.0f : SkyFogMath.twilight(sunSine) * LiteMath.smoothstep(-0.25f, -0.05f, sunSine);
        afterglow = twilight * SkyFogMath.sunGone(sunSine);
        moonlight = skyless ? 0.0f : LiteMath.smoothstep(-0.05f, 0.3f, frame.moonDirection.w) * moonFullness(moonPhase(skyState));
    }

    /** True when LiteLights' fog may replace vanilla's this frame. */
    private static boolean liteFogAllowed(RenderSettings.Fog fogSettings, CameraRenderState camera, Minecraft minecraft) {
        FogType medium = camera.fogType;
        boolean vanillaMedium = medium == FogType.WATER || medium == FogType.LAVA || medium == FogType.POWDER_SNOW;
        boolean blinded = camera.entityRenderState.doesMobEffectBlockSky;
        boolean bossFog = minecraft.gui.hud.getBossOverlay().shouldCreateWorldFog();
        return fogSettings.look() != RenderSettings.FogLook.VANILLA && !vanillaMedium && !blinded && !bossFog
            && DimensionGate.allows(Aspect.EFFECTS) && RenderFeatureStatus.mayRun(RenderFeature.ATMOSPHERIC_FOG);
    }

    /** The haze, ground mist, render-distance fade and sun glow. Returns the ground mist's strength. */
    private float fillHaze(FrameState frame, CameraRenderState camera, ClientLevel currentLevel, RenderSettings.Fog fogSettings,
                           boolean haze, boolean rainMist) {
        lightHaze(frame, camera, fogSettings.sunGlow());
        frame.hazeColor.w = haze ? hazeDensity(fogSettings.density(), rainMist) : 0.0f;
        float mist = haze && fogSettings.mistBanks() && !skyless ? mistBanks(rainMist) : 0.0f;
        float cameraY = (float) camera.pos.y;
        float seaLevel = currentLevel.getSeaLevel();
        frame.hazeShape.set(skyless ? 0.0f : HAZE_FALLOFF, skyless ? 0.0f : cameraY - seaLevel, mist, cameraY - groundHeight);

        // The render-distance fade ends where vanilla's does for this frame, so a mod that moves it
        // (Voxy and other far-terrain mods push it out) moves LiteLights' with it. It starts earlier
        // than vanilla's (the last quarter instead of the last tenth) and eases in, so the edge of the
        // loaded land melts into the horizon instead of showing as a line.
        // While Voxy's or Distant Horizons' terrain carries on past the render distance and gets
        // LiteLights' haze, nearby land must not fade out in front of it either.
        float edgeEnd = FarTerrain.get().farTerrainDrawn() ? FAR_TERRAIN_EDGE : camera.fogData.renderDistanceEnd;
        float edgeStart = Math.min(camera.fogData.renderDistanceStart, edgeEnd * EDGE_FADE_START);
        frame.fogRange.set(edgeStart, edgeEnd, exposure, 0.02f + 0.05f * day);

        // The sun's glow in the haze: wide and warm low in the sky, faint at noon.
        float glow = skyless ? 0.0f : day * (0.10f + 1.05f * twilight) * (1.0f - 0.85f * rain) * fogSettings.sunGlow();
        if (computedSky && !skyless) {
            sunsetGlow(frame, fogSettings.sunGlow());
        } else {
            frame.sunGlow.set(glow, glow * LiteMath.lerp(0.90f, 0.48f, twilight), glow * LiteMath.lerp(0.72f, 0.16f, twilight),
                LiteMath.lerp(6.0f, 3.0f, twilight));
        }
        return mist;
    }

    /**
     * The Computed sky's sun glow (fog098): a step more saturated and warmer than the Vanilla
     * colours' around a low sun, and once the sun has gone, an afterglow in deep reds and magentas
     * along the horizon that fades with the twilight instead of with the daylight, so the end of a
     * sunset doesn't dim into brown. Wider, too, so it lies along the horizon. Dawn mirrors it.
     */
    private void sunsetGlow(FrameState frame, float sunGlow) {
        float strength = Math.max(day, 0.5f * afterglow) * (0.10f + 1.05f * twilight) * (1.0f - 0.85f * rain) * sunGlow
            * (1.0f + 0.15f * twilight);
        float green = LiteMath.lerp(LiteMath.lerp(0.90f, 0.42f, twilight), 0.20f, afterglow);
        float blue = LiteMath.lerp(LiteMath.lerp(0.72f, 0.12f, twilight), 0.32f, afterglow);
        float width = LiteMath.lerp(LiteMath.lerp(6.0f, 3.0f, twilight), 2.2f, afterglow);
        frame.sunGlow.set(strength, strength * green, strength * blue, width);
    }

    /**
     * Storms: worked out whatever the fog style, so the weather particles can follow them.
     * Returns the storm fog's density.
     */
    private float fillStorm(FrameState frame, RenderSettings settings, boolean weatherHere, float seconds) {
        boolean stormsHere = !WeatherCompat.stormAtmosphereHandledElsewhere() && weatherHere;
        // The sand blows with the world's rain, which a seasons mod may share out by climate.
        float sandRain = level == null ? rain : WeatherPreview.rain(LocalClimate.worldRain(level));
        float sandThunder = level == null ? thunder : WeatherPreview.thunder(LocalClimate.worldThunder(level));
        storm.update(land, rain, thunder, sandRain, sandThunder, stormsHere && settings.weather().stormAtmosphere(),
            stormsHere && settings.weather().sandstorms(), exposure, FrameClock.gameSeconds(seconds));
        float stormPart = storm.density(settings.weather().storms(), thunder);
        float rainPart = weatherHere ? rainFog(settings.weather().storms()) : 0.0f;
        float density = stormPart;
        if (rainPart > 0.0f) {
            density += rainPart;
        }
        float stormDensity = density * settings.fog().density() * weatherThickness(settings);
        fillStormColour(frame, rainPart > 0.0f ? rainPart / density : 0.0f);
        frame.stormFog.w = stormDensity;
        return stormDensity;
    }

    /**
     * The even wet fog a Dense or Very dense rain or thunderstorm adds over rainy (not snowy) land,
     * open to the sky; none at Normal or Light.
     */
    private float rainFog(RenderSettings.Storms storms) {
        float steps = storms.rainFog(thunder) - 1.0f;
        if (steps <= 0.0f) {
            return 0.0f;
        }
        float rainyLand = Math.max(0.0f, WeatherPreview.wetLand(land.wet) - WeatherPreview.snowyLand(land.snowy));
        return RAIN_FOG_PER_STEP * steps * rain * rainyLand * LiteMath.smoothstep(0.2f, 0.8f, exposure);
    }

    /** How much of the land where rain or snow falls gets snow, 0 to 1. */
    private float snowyShareOfWetLand() {
        float wet = WeatherPreview.wetLand(land.wet);
        return wet <= 1.0e-4f ? 0.0f : LiteMath.clamp01(WeatherPreview.snowyLand(land.snowy) / wet);
    }

    /** Whether LiteLights draws the sky this frame, its colour overhead, and the star and moon data. */
    private void fillSky(FrameState frame, RenderSettings settings, SkyRenderState skyState, ClientLevel currentLevel,
                         boolean overworldSky, float stormCover) {
        liteSky = overworldSky && DimensionGate.allows(Aspect.SKY) && RenderFeatureStatus.mayRun(RenderFeature.SKY);
        realisticSky = settings.sky().palette() == RenderSettings.SkyPalette.COMPUTED;
        fillZenith(frame, skyState, settings);
        if (liteSky && !realisticSky && (settings.hasPreview() || WeatherPreview.active()) && skyState.skyColor != null) {
            // Previewing with vanilla's colours: vanilla's sky fog would still be the real time's
            // and weather's, so LiteLights draws the whole sky, overhead in the previewed vanilla
            // sky colour (VanillaSkyPreview).
            realisticSky = true;
            frame.skyZenith.set(skyState.skyColor.x(), skyState.skyColor.y(), skyState.skyColor.z(), frame.skyZenith.w);
        }
        float starAngle = settings.hasPreview() ? CelestialLight.sunAngleForDayTime(settings.previewDayTime()) : skyState.starAngle;
        // The same noon-to-noon nights as the meteor showers and fireworks, so a night's shooting
        // stars don't change at midnight. Kept small, so the float carries it exactly.
        long night = SkyEventSchedule.nightIndex(currentLevel.getOverworldClockTime());
        frame.skyInfo.set(moonPhase(skyState), (float) Math.floorMod(night, 100_000L), starAngle, stormCover);
        PreviewEvent.Event preview = PreviewEvent.current();
        boolean showers = settings.weather().meteorNights();
        if (preview == null) {
            meteorShower = showers && showerTonight(currentLevel, night);
        } else {
            // Like the other sky events, a real shower is left out while any event is previewed.
            meteorShower = showers && preview == PreviewEvent.Event.METEOR_SHOWER;
        }
        if (preview == PreviewEvent.Event.FALLING_STARS || meteorShower && preview != null) {
            // The settings screen's falling-star and meteor-shower previews: streaks every few
            // seconds, starting a little above where the player looks.
            frame.time.z = Math.clamp(PreviewEvent.elevation() + 0.3f, 0.45f, 1.1f);
            frame.time.w = PreviewEvent.bearing();
        }
    }

    /** Whether tonight is one of the world's meteor shower nights (SkyEventSchedule, from the world's hashed seed). */
    private boolean showerTonight(ClientLevel currentLevel, long night) {
        long seed = ((BiomeManagerAccessor) currentLevel.getBiomeManager()).litelights$biomeZoomSeed();
        if (showerNight == null || showerNight.index() != night || showerSeed != seed) {
            showerNight = SkyEventSchedule.night(seed, night);
            showerSeed = seed;
        }
        return showerNight.meteorShower();
    }

    private static void fillEndTint(FrameState frame, boolean endTint, float aura) {
        if (endTint) {
            frame.endTint.set(1.0f, 0.90f, 1.07f, aura);
        } else {
            frame.endTint.set(1.0f, 1.0f, 1.0f, aura);
        }
    }

    private int featureFlags(SkyRenderState skyState, boolean liteFog, boolean haze, float mist, boolean endTint, float stormDensity,
                             RenderSettings settings) {
        int flags = 0;
        flags |= liteFog ? FrameFlags.FEATURE_LITE_FOG : 0;
        flags |= haze ? FrameFlags.FEATURE_HAZE : 0;
        flags |= mist > 0.0f ? FrameFlags.FEATURE_GROUND_MIST : 0;
        flags |= skyless ? FrameFlags.FEATURE_SKYLESS : 0;
        flags |= endTint ? FrameFlags.FEATURE_END_TINT : 0;
        flags |= haze && stormDensity > 0.0005f ? FrameFlags.FEATURE_STORM_FOG : 0;
        if (liteSky) {
            flags |= FrameFlags.FEATURE_LITE_SKY;
            flags |= realisticSky ? FrameFlags.FEATURE_REALISTIC_SKY : 0;
            flags |= settings.sky().fallingStars() ? FrameFlags.FEATURE_SHOOTING_STARS : 0;
            flags |= meteorShower ? FrameFlags.FEATURE_METEOR_SHOWER : 0;
        }
        flags |= skyState.shouldRenderDarkDisc ? FrameFlags.FEATURE_DARK_DISC : 0;
        flags |= LiteClouds.get().activeThisFrame() ? FrameFlags.FEATURE_LITE_CLOUDS : 0;
        return flags;
    }

    /** Takes the fog-side rows out of "Coming soon" once LiteLights' fog has really drawn. */
    private void markFogRowsReady() {
        RenderFeatureStatus.markReady("atmospheric_fog", "fog.mist_banks", "general.modded_biome_fog", "weather.rain_mist",
            "end_haze", "end_tint", "modded_dimensions");
        if (!WeatherCompat.stormAtmosphereHandledElsewhere()) {
            RenderFeatureStatus.markReady("weather.storm_atmosphere");
        }
    }

    /**
     * The land's haze colour, moved toward the plain temperate haze as the Biome haze tint setting
     * goes down (at 0 every biome has the same neutral haze). Dimensions without a sky keep their
     * own colour: it is what fills the space past the render distance there.
     */
    private void tintHaze(float tint) {
        BiomeAtmosphere neutral = BiomeAtmospheres.TEMPERATE;
        float amount = skyless ? 1.0f : tint;
        hazeRed = LiteMath.lerp(neutral.hazeRed(), land.hazeRed, amount);
        hazeGreen = LiteMath.lerp(neutral.hazeGreen(), land.hazeGreen, amount);
        hazeBlue = LiteMath.lerp(neutral.hazeBlue(), land.hazeBlue, amount);
    }

    /**
     * The haze's colour: the land's, lit for the time of day and washed out by rain. sunGlow scales
     * the warming around sunrise and sunset (1 as built, 0 none).
     */
    private void lightHaze(FrameState frame, CameraRenderState camera, float sunGlow) {
        float red = hazeRed;
        float green = hazeGreen;
        float blue = hazeBlue;
        if (skyless) {
            // Lean a third of the way toward vanilla's fog colour, which fills the space past the
            // render distance there, so the haze runs smoothly into it.
            red = LiteMath.lerp(red, camera.fogData.color.x, 0.35f);
            green = LiteMath.lerp(green, camera.fogData.color.y, 0.35f);
            blue = LiteMath.lerp(blue, camera.fogData.color.z, 0.35f);
        } else {
            // Night haze is a deep blue, a little lighter with a bright moon up. Around sunrise and
            // sunset it warms about two thirds as much as it used to: the haze is already darkening
            // toward night there, and a strong orange on a dim colour reads as brown over the land.
            // More of the orange now comes from the sun's glow (sunGlow), which lights the haze
            // toward the sun.
            red = LiteMath.lerp(0.040f + 0.030f * moonlight, red, day);
            green = LiteMath.lerp(0.050f + 0.036f * moonlight, green, day);
            blue = LiteMath.lerp(0.085f + 0.050f * moonlight, blue, day);
            float warming = twilight * sunGlow;
            if (computedSky) {
                // The Computed sky (fog098): a little warmer while the sun is up, but the warmth turns
                // rose rather than orange as the light fades, and once the sun is gone the haze cools to
                // the blue hour's clear violet blue: orange on a dimming colour reads as brown.
                float sunUp = warming * (1.0f - SkyFogMath.sunGone(sunSine));
                float dim = 1.0f - LiteMath.smoothstep(0.12f, 0.45f, day);
                red *= 1.0f + 0.36f * sunUp;
                green *= Math.max(0.0f, 1.0f - LiteMath.lerp(0.05f, 0.12f, dim) * sunUp);
                blue *= Math.max(0.0f, 1.0f - LiteMath.lerp(0.30f, 0.0f, dim) * sunUp);
                float blueHour = afterglow * sunGlow;
                red *= 1.0f - 0.22f * blueHour;
                green *= 1.0f - 0.10f * blueHour;
                blue *= 1.0f + 0.45f * blueHour;
            } else {
                red *= 1.0f + 0.30f * warming;
                green *= Math.max(0.0f, 1.0f - 0.05f * warming);
                blue *= Math.max(0.0f, 1.0f - 0.26f * warming);
            }
            float grey = SkyFogMath.luminance(red, green, blue);
            float wash = 0.6f * rain;
            float darken = (1.0f - 0.30f * rain) * (1.0f - 0.25f * thunder);
            red = LiteMath.lerp(red, grey, wash) * darken;
            green = LiteMath.lerp(green, grey, wash) * darken;
            blue = LiteMath.lerp(blue, grey * 1.04f, wash) * darken;
        }
        frame.hazeColor.set(red, green, blue, frame.hazeColor.w);
    }

    /** The haze's density per block: the land's, thicker in rain, much thinner inside caves. */
    private float hazeDensity(float strength, boolean rainMist) {
        float density = land.density * strength;
        if (!skyless) {
            float rainBoost = (rainMist ? 2.2f * rain + 1.0f * thunder : 0.6f * rain) * wetFog;
            density *= (1.0f + rainBoost * WeatherPreview.wetLand(land.wet)) * LiteMath.lerp(0.3f, 1.0f, exposure);
        }
        return density;
    }

    /**
     * The weather density thickens snow and sand fog a little (swamp fog stays as it is). Snow
     * follows it only with LiteLights' own rain and snow, as the snowflakes do; sand always.
     */
    private float weatherThickness(RenderSettings settings) {
        float sand = storm.sandstorm();
        float snow = storm.blizzard() + storm.snowFog();
        float total = sand + snow + storm.swampFog();
        if (total <= 1.0e-4f) {
            return 1.0f;
        }
        float extra = settings.weather().density().thickness - 1.0f;
        boolean ownSnow = settings.weather().precipitation() == RenderSettings.PrecipitationLook.LITELIGHTS;
        return 1.0f + extra * (sand + (ownSnow ? snow : 0.0f)) / total;
    }

    /** Ground mist: thickest at night and around dawn and dusk, and in the rain. */
    private float mistBanks(boolean rainMist) {
        float timeOfDay = Math.min(0.45f + 0.55f * (1.0f - day) + 0.5f * twilight, 1.2f);
        float mist = land.mist * timeOfDay;
        if (rainMist) {
            mist += 0.6f * rain * WeatherPreview.wetLand(land.wet) * wetFog;
        }
        return mist * LiteMath.smoothstep(0.3f, 0.9f, exposure);
    }

    /**
     * The storm fog's colour. rainShare is the part of its density that is a Dense rain's wet fog,
     * which takes the rain-washed haze's grey (darker in thunder, already lit for the time of day).
     */
    private void fillStormColour(FrameState frame, float rainShare) {
        fillStormColour(frame);
        if (rainShare > 0.0f) {
            float red = LiteMath.lerp(frame.stormFog.x, frame.hazeColor.x, rainShare);
            float green = LiteMath.lerp(frame.stormFog.y, frame.hazeColor.y, rainShare);
            float blue = LiteMath.lerp(frame.stormFog.z, frame.hazeColor.z, rainShare);
            frame.stormFog.set(red, green, blue, 0.0f);
        }
    }

    private void fillStormColour(FrameState frame) {
        float sand = storm.sandstorm();
        float blizzard = storm.blizzard();
        float swamp = storm.swampFog();
        float snow = storm.snowFog();
        float total = sand + blizzard + swamp + snow;
        if (total <= 1.0e-4f) {
            frame.stormFog.set(hazeRed, hazeGreen, hazeBlue, 0.0f);
            return;
        }
        // Swamp fog is the land's own haze, paler and greyer.
        float swampRed = LiteMath.lerp(hazeRed, 0.62f, 0.35f);
        float swampGreen = LiteMath.lerp(hazeGreen, 0.66f, 0.35f);
        float swampBlue = LiteMath.lerp(hazeBlue, 0.62f, 0.35f);
        float red = (sand * 0.80f + blizzard * 0.86f + swamp * swampRed + snow * 0.88f) / total;
        float green = (sand * 0.65f + blizzard * 0.90f + swamp * swampGreen + snow * 0.91f) / total;
        float blue = (sand * 0.45f + blizzard * 0.97f + swamp * swampBlue + snow * 0.96f) / total;
        float light = LiteMath.lerp(0.07f, 1.0f, day) * (1.0f - 0.3f * thunder);
        frame.stormFog.set(red * light * (1.0f + 0.15f * twilight), green * light, blue * light * (1.0f - 0.15f * twilight), 0.0f);
    }

    private void fillZenith(FrameState frame, SkyRenderState skyState, RenderSettings settings) {
        float red = LiteMath.lerp(0.010f + 0.010f * moonlight, 0.25f * land.skyRed, day);
        float green = LiteMath.lerp(0.014f + 0.014f * moonlight, 0.46f * land.skyGreen, day);
        float blue = LiteMath.lerp(0.034f + 0.025f * moonlight, 0.86f * land.skyBlue, day);
        if (computedSky) {
            // A clearer, deeper twilight blue overhead, so the afterglow fades up into blue (fog098).
            red = LiteMath.lerp(red, 0.12f, 0.45f * twilight);
            green = LiteMath.lerp(green, 0.18f, 0.45f * twilight);
            blue = LiteMath.lerp(blue, 0.46f, 0.45f * twilight);
        } else {
            red = LiteMath.lerp(red, 0.17f, 0.35f * twilight);
            green = LiteMath.lerp(green, 0.22f, 0.35f * twilight);
            blue = LiteMath.lerp(blue, 0.42f, 0.35f * twilight);
        }
        float overcast = Math.max(day, 0.05f);
        red = LiteMath.lerp(red, 0.42f * overcast, 0.75f * rain);
        green = LiteMath.lerp(green, 0.45f * overcast, 0.75f * rain);
        blue = LiteMath.lerp(blue, 0.50f * overcast, 0.75f * rain);

        // Vanilla's star brightness tops out at half; LiteLights' stars use the full range, and
        // only come out once the sky behind them has really darkened.
        float stars = settings.hasPreview() ? 1.0f : LiteMath.clamp01(skyState.starBrightness * 2.0f);
        stars *= LiteMath.smoothstep(0.0f, -0.22f, sunSine);
        frame.skyZenith.set(red, green, blue, stars);
    }

    private static int moonPhase(SkyRenderState skyState) {
        return skyState.moonPhase == null ? 0 : skyState.moonPhase.index();
    }

    /** How much light the moon gives by phase: full 1, quarters 0.5, new 0. */
    static float moonFullness(int phase) {
        return switch (phase & 7) {
            case 0 -> 1.0f;
            case 1, 7 -> 0.8f;
            case 2, 6 -> 0.5f;
            case 3, 5 -> 0.25f;
            default -> 0.0f;
        };
    }

    private void afterSky(RenderTarget target, FrameState frame) {
        if (!skyPassWanted || !RenderFeatureStatus.mayRun(RenderFeature.SKY)) {
            return;
        }
        try {
            skyPass.draw(target);
            RenderFeatureStatus.succeeded(RenderFeature.SKY);
            if (!skyReadyMarked) {
                skyReadyMarked = true;
                RenderFeatureStatus.markReady("sky", "sky.falling_stars", "weather.meteor_nights", "end_sky_glow");
            }
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.SKY, error);
            LiteLightsClient.LOG.debug("Sky pass failed", error);
        }
    }

    /** Diagnostics for QA: the eased air around the camera. */
    public AtmosphereBlend land() {
        return land;
    }

    public float exposure() {
        return exposure;
    }
}
