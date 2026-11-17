package com.thenathe.litelights.render.feature.lightpost;

import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.config.GlintScale;
import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameClock;
import com.thenathe.litelights.render.frame.FrameFlags;
import com.thenathe.litelights.render.frame.FrameState;
import com.thenathe.litelights.render.frame.WeatherPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/**
 * Light-post's part of each frame, worked out once before the LiteFrame block is uploaded: the
 * surface-hook bits and {@code featureValuesC}, and what the post passes need to decide whether to
 * run (camera under water, the sun, the dimension's switches).
 *
 * <p>featureValuesC: x the water's glint softness and intensity ({@link #waterGlintTuning}), y the
 * glint switches with the ground's softness and intensity above them ({@link #glintSwitches}), z
 * night tone strength, w light-ray landing strength (1 at each ray setting's default).
 */
final class LightPostFrame implements LiteRenderEvents.FrameUpdate {
    /**
     * How quickly sunbeams follow the thunder: the game's thunder level, eased with this time
     * constant, so the beams dim over roughly 25 seconds as a storm builds and come back as slowly
     * after it clears, never popping when the weather switches.
     */
    private static final float THUNDER_EASE_SECONDS = 8.0f;
    /** While the settings screen previews a weather the beams follow it at once, so the preview shows the storm's look. */
    private static final float PREVIEW_THUNDER_EASE_SECONDS = 0.3f;

    private final BlockPos.MutableBlockPos cameraBlock = new BlockPos.MutableBlockPos();
    private boolean surfaceRowsReady;

    private boolean underwater;
    private boolean sunMap;
    private boolean overworldSky;
    private int cameraSkyLight;
    private float sunPower;
    private double dayTime;
    private float thunderClear = 1.0f;
    private boolean thunderEased;

    @Override
    public void onFrameUpdate(FrameState frame, RenderSettings settings) {
        RenderSettings.Lighting lighting = settings.lighting();
        underwater = (frame.featureFlags & FrameFlags.FEATURE_CAMERA_UNDERWATER) != 0;
        sunMap = (frame.featureFlags & FrameFlags.FEATURE_SUN_MAP) != 0;
        overworldSky = frame.dimensionKind == FrameFlags.DIMENSION_OVERWORLD;
        float aboveHorizon = LiteMath.smoothstep(0.0f, 0.12f, frame.sunDirection.w);
        sunPower = frame.sunColor.w * aboveHorizon * (1.0f - 0.85f * frame.weather.x);
        easeThunder(frame.weather.y);
        dayTime = ColourGradeMath.dayTimeFromSunTurns(frame.time.x);
        ClientLevel level = Minecraft.getInstance().level;
        cameraBlock.set(frame.cameraBlockX, frame.cameraBlockY, frame.cameraBlockZ);
        cameraSkyLight = level == null ? 15 : level.getBrightness(LightLayer.SKY, cameraBlock);

        int flags = 0;
        float landing = 0.0f;
        if (DimensionGate.allows(Aspect.LIGHT)) {
            if (lighting.nightColorTone()) {
                flags |= FrameFlags.FEATURE_NIGHT_TONE;
            }
            if (lighting.landGlint()) {
                flags |= FrameFlags.FEATURE_TERRAIN_GLINT;
            }
            if (lighting.submergedGlint()) {
                flags |= FrameFlags.FEATURE_UNDERWATER_GLINT;
            }
            landing = rayLandingStrength(lighting);
            if (landing > 0.0f) {
                flags |= FrameFlags.FEATURE_RAY_LANDING;
            }
        } else {
            frame.featureFlags &= ~FrameFlags.FEATURE_ENHANCED_LIGHT;
        }
        if (bloomWanted(settings)) {
            flags |= FrameFlags.FEATURE_BLOOM_MASK;
        }
        frame.featureFlags |= flags;
        frame.featureValuesC.set(waterGlintTuning(lighting), glintSwitches(lighting), 1.0f, landing);

        if (!surfaceRowsReady) {
            // The surface effects draw from the first world frame on (the shaders compiled, or the
            // Shadows row already reports why not).
            surfaceRowsReady = true;
            RenderFeatureStatus.markReady("lighting.glow_heat", "lighting.night_tone", "glint");
        }
    }

    /**
     * The glint switches as the shader reads them (LITE_GLINT_* in light_post/surface_light.glsl):
     * bit 0 water, 1 ice and snow, 2 metal and glass, 3 sand and stone, from bit 4 the water
     * sparkle's motion (0 still, 1 gentle, 2 lively) and bit 6 ores and crystals. From bit 8 the
     * ground's softness and intensity ({@link GlintScale#pack}). A whole number below 2^24, so
     * exact as a float.
     */
    static float glintSwitches(RenderSettings.Lighting lighting) {
        int switches = (lighting.glintWater() ? 1 : 0) | (lighting.glintIceSnow() ? 2 : 0)
            | (lighting.glintMetalGlass() ? 4 : 0) | (lighting.glintSandStone() ? 8 : 0) | (lighting.glintOres() ? 64 : 0);
        int ground = GlintScale.pack(GlintScale.groundSoftness(lighting.glintGroundSoftness()),
            GlintScale.groundIntensity(lighting.glintGroundIntensity()));
        return switches | lighting.waterSparkle().ordinal() << 4 | ground << 8;
    }

    /** The water's glint softness and intensity in one exact float ({@link GlintScale#pack}); glint under water uses them too. */
    static float waterGlintTuning(RenderSettings.Lighting lighting) {
        return GlintScale.pack(GlintScale.waterSoftness(lighting.glintWaterSoftness()),
            GlintScale.waterIntensity(lighting.glintWaterIntensity()));
    }

    private float rayLandingStrength(RenderSettings.Lighting lighting) {
        if (!overworldSky || sunPower <= 0.0f) {
            return 0.0f;
        }
        if (underwater) {
            return lighting.underwaterSunbeams() && RenderFeatureStatus.mayRun(RenderFeature.UNDERWATER_RAYS)
                ? lighting.underwaterSunbeamStrength() / 1.5f : 0.0f;
        }
        return lighting.sunbeams() && sunMap && RenderFeatureStatus.mayRun(RenderFeature.LIGHT_RAYS)
            ? lighting.sunbeamStrength() / 2.0f : 0.0f;
    }

    boolean bloomWanted(RenderSettings settings) {
        return settings.lighting().halo() && settings.lighting().haloStrength() > 0.0f && DimensionGate.allows(Aspect.BLOOM)
            && RenderFeatureStatus.mayRun(RenderFeature.BLOOM);
    }

    boolean lightAllowed() {
        return DimensionGate.allows(Aspect.LIGHT);
    }

    boolean outlinesAllowed() {
        return DimensionGate.allows(Aspect.OUTLINES);
    }

    boolean underwater() {
        return underwater;
    }

    /** The cached sun shadow map is drawn and bound this frame. */
    boolean sunMap() {
        return sunMap;
    }

    boolean overworldSky() {
        return overworldSky;
    }

    /** Sky light at the camera, 0 to 15: 0 deep in a cave, where no sunbeam can be seen. */
    int cameraSkyLight() {
        return cameraSkyLight;
    }

    private void easeThunder(float thunderLevel) {
        float target = 1.0f - LiteMath.smoothstep(0.0f, 1.0f, thunderLevel);
        if (!thunderEased) {
            thunderClear = target;
            thunderEased = true;
        } else {
            float seconds = FrameClock.gameSeconds(Math.min(FrameClock.frameSeconds(), 0.25f));
            float ease = WeatherPreview.settling() ? PREVIEW_THUNDER_EASE_SECONDS : THUNDER_EASE_SECONDS;
            thunderClear += (target - thunderClear) * (1.0f - (float) Math.exp(-seconds / ease));
        }
    }

    /** 1 in clear weather, easing to 0 in thunder (storms, blizzards, sandstorms): sunbeams fade with it. */
    float thunderClear() {
        return thunderClear;
    }

    /** 0..1: daylight, the sun above the horizon and not hidden by rain. */
    float sunPower() {
        return sunPower;
    }

    /** The day time the sun stands at (the settings screen's preview included), 0 to 23999. */
    double dayTime() {
        return dayTime;
    }
}
