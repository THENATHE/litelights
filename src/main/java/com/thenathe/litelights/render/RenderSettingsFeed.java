package com.thenathe.litelights.render;

import com.thenathe.litelights.config.LiteLightsConfig;
import com.thenathe.litelights.config.LiteLightsOptions.ColourGrade;
import com.thenathe.litelights.config.LiteLightsOptions.GradeTime;
import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.config.PreviewTime;

/**
 * Turns the config system's options into {@link RenderSettings} snapshots. Once per frame,
 * {@link #refresh()} compares the registry's change counter and the time-of-day preview with what
 * the current snapshot was built from, and publishes a new snapshot only when either moved, so
 * render code never reads options directly and never takes a lock.
 */
public final class RenderSettingsFeed {
    private static long builtFromChangeCount = Long.MIN_VALUE;
    private static PreviewTime.Time builtFromPreview;

    private RenderSettingsFeed() {
    }

    public static void refresh() {
        long changeCount = LiteLightsConfig.registry().changeCount();
        PreviewTime.Time preview = PreviewTime.current();
        if (changeCount == builtFromChangeCount && preview == builtFromPreview) {
            return;
        }
        builtFromChangeCount = changeCount;
        builtFromPreview = preview;
        RenderSettingsAccess.publish(build());
    }

    static RenderSettings build() {
        return new RenderSettings(
            new RenderSettings.General(
                RenderSettings.AntiAliasing.valueOf(LiteLightsOptions.ANTI_ALIASING.get().name()),
                LiteLightsOptions.MODDED_BIOME_FOG.on()),
            new RenderSettings.PixelLock(
                LiteLightsOptions.PIXEL_LOCK.on(),
                LiteLightsOptions.PIXEL_LOCK_SHADOWS.on(),
                LiteLightsOptions.PIXEL_LOCK_CAUSTICS.on(),
                LiteLightsOptions.PIXEL_LOCK_WATER_SURFACE.on(),
                LiteLightsOptions.PIXEL_LOCK_GLINT.on(),
                LiteLightsOptions.PIXEL_LOCK_SUNBEAMS.on()),
            new RenderSettings.Sky(
                RenderSettings.SkyPalette.valueOf(LiteLightsOptions.SKY_PALETTE.get().name()),
                LiteLightsOptions.FALLING_STARS.on(),
                RenderSettings.CloudStyle.valueOf(LiteLightsOptions.CLOUDS.get().name()),
                LiteLightsOptions.CLOUD_LAYERS.value()),
            new RenderSettings.Fog(
                RenderSettings.FogLook.valueOf(LiteLightsOptions.FOG_LOOK.get().name()),
                LiteLightsOptions.FOG_DENSITY.value(),
                LiteLightsOptions.MIST_BANKS.on(),
                LiteLightsOptions.FAR_TERRAIN.on(),
                LiteLightsOptions.BIOME_HAZE_TINT.value(),
                LiteLightsOptions.SUN_GLOW.value()),
            new RenderSettings.Lighting(
                LiteLightsOptions.HALO.on(),
                LiteLightsOptions.HALO_STRENGTH.value(),
                LiteLightsOptions.GLOW_HEAT.on(),
                LiteLightsOptions.SUNBEAMS.on(),
                LiteLightsOptions.SUNBEAM_STRENGTH.value(),
                LiteLightsOptions.UNDERWATER_SUNBEAMS.on(),
                LiteLightsOptions.UNDERWATER_SUNBEAM_STRENGTH.value(),
                RenderSettings.SunbeamQuality.valueOf(LiteLightsOptions.SUNBEAM_QUALITY.get().name()),
                LiteLightsOptions.GLINT_WATER.on(),
                RenderSettings.WaterSparkle.valueOf(LiteLightsOptions.WATER_SPARKLE.get().name()),
                LiteLightsOptions.GLINT_ICE_SNOW.on(),
                LiteLightsOptions.GLINT_METAL_GLASS.on(),
                LiteLightsOptions.GLINT_SAND_STONE.on(),
                LiteLightsOptions.SUBMERGED_GLINT.on(),
                LiteLightsOptions.GLINT_WATER_SOFTNESS.value(),
                LiteLightsOptions.GLINT_WATER_INTENSITY.value(),
                LiteLightsOptions.GLINT_GROUND_SOFTNESS.value(),
                LiteLightsOptions.GLINT_GROUND_INTENSITY.value(),
                LiteLightsOptions.NIGHT_TONE.on()),
            new RenderSettings.ColorGrading(
                gradeSchedule(),
                grade(GradeTime.ALL_DAY),
                grade(GradeTime.DAWN),
                grade(GradeTime.MIDDAY),
                grade(GradeTime.DUSK),
                grade(GradeTime.NIGHT),
                RenderSettings.ToneCurve.valueOf(LiteLightsOptions.TONE_CURVE.get().name())),
            new RenderSettings.Shadows(
                LiteLightsOptions.WORLD_SHADOWS.on(),
                RenderSettings.LightModel.valueOf(LiteLightsOptions.LIGHT_MODEL.get().name()),
                LiteLightsOptions.SHADOW_DARKNESS.value(),
                LiteLightsOptions.SUN_BRIGHTNESS.value(),
                LiteLightsOptions.SUNLIGHT_COLOUR.value(),
                LiteLightsOptions.SHADOW_REACH.value(),
                RenderSettings.ShadowDetail.valueOf(LiteLightsOptions.SHADOW_DETAIL.get().name()),
                LiteLightsOptions.ENTITY_SHADOWS.on(),
                LiteLightsOptions.SHADOW_GLIDE.on(),
                LiteLightsOptions.SHADOWS_IN_WATER.on(),
                RenderSettings.FarShadows.valueOf(LiteLightsOptions.FAR_SHADOWS.get().name()),
                RenderSettings.ShadowOffset.valueOf(LiteLightsOptions.SHADOW_OFFSET.get().name())),
            new RenderSettings.Water(
                RenderSettings.WaterPalette.valueOf(LiteLightsOptions.WATER_PALETTE.get().name()),
                LiteLightsOptions.WATER_MIRRORS.on(),
                LiteLightsOptions.ICE_MIRRORS.on(),
                RenderSettings.MirrorDetail.valueOf(LiteLightsOptions.MIRROR_DETAIL.get().name()),
                LiteLightsOptions.MIRROR_STRENGTH.value(),
                LiteLightsOptions.BIOME_DEPTH_COLOUR.on(),
                LiteLightsOptions.EDGE_FOAM.on(),
                LiteLightsOptions.EDGE_FOAM_BLOCKS_ONLY.on(),
                RenderSettings.FoamMotion.valueOf(LiteLightsOptions.FOAM_MOTION.get().name()),
                LiteLightsOptions.LAVA_CRUST.on(),
                LiteLightsOptions.FLOATING_LEAVES.on(),
                LiteLightsOptions.CAUSTICS.on(),
                LiteLightsOptions.UNDERWATER_BLUR.on(),
                LiteLightsOptions.LARGE_WATER_TEXTURES.on(),
                LiteLightsOptions.LARGE_WATER_SCALE.value(),
                LiteLightsOptions.LARGE_LAVA_SCALE.value(),
                RenderSettings.JumpSplash.valueOf(LiteLightsOptions.JUMP_SPLASH.get().name()),
                LiteLightsOptions.FAR_WATER.on(),
                LiteLightsOptions.FAR_CAUSTICS.on(),
                LiteLightsOptions.FAR_DEPTH_COLOUR.on(),
                LiteLightsOptions.FAR_EDGE_FOAM.on(),
                LiteLightsOptions.OCEAN_WAVES.on(),
                LiteLightsOptions.SHORE_RUN_UP.value(),
                LiteLightsOptions.WAVE_FOAM.value(),
                LiteLightsOptions.WAKES.on(),
                LiteLightsOptions.WAKE_STRENGTH.value(),
                LiteLightsOptions.WAKE_FOAM.on()),
            new RenderSettings.Vegetation(
                LiteLightsOptions.SWAY_GRASS.on(),
                LiteLightsOptions.SWAY_PLANTS.on(),
                LiteLightsOptions.SWAY_LEAVES.on()),
            new RenderSettings.Particles(
                RenderSettings.ParticleDensity.valueOf(LiteLightsOptions.PARTICLE_DENSITY.get().name()),
                RenderSettings.SnowSandSprites.valueOf(LiteLightsOptions.SNOW_SAND_SPRITES.get().name()),
                LiteLightsOptions.FIREFLY_SWARMS.on(),
                LiteLightsOptions.DUST_SPECKS.on(),
                LiteLightsOptions.POLLEN.on(),
                LiteLightsOptions.SPORES.on(),
                LiteLightsOptions.ENHANCED_FIRE.on(),
                LiteLightsOptions.ENHANCED_FIRE.on() && LiteLightsOptions.EMBER_VEINS.on(),
                LiteLightsOptions.ENHANCED_FIRE.on() && LiteLightsOptions.BURN_MARKS.on(),
                LiteLightsOptions.ENHANCED_WATER.on(),
                RenderSettings.SpawnDistance.valueOf(LiteLightsOptions.PARTICLE_DISTANCE.get().name()),
                RenderSettings.DistanceFill.valueOf(LiteLightsOptions.PARTICLE_DISTANCE_FILL.get().name())),
            new RenderSettings.Weather(
                RenderSettings.PrecipitationLook.valueOf(LiteLightsOptions.PRECIPITATION.get().name()),
                RenderSettings.WeatherDensity.valueOf(LiteLightsOptions.WEATHER_DENSITY.get().name()),
                LiteLightsOptions.RAIN_MIST.on(),
                LiteLightsOptions.DRIFTING_SAND.on(),
                LiteLightsOptions.SANDSTORMS.on(),
                LiteLightsOptions.STORM_ATMOSPHERE.on(),
                LiteLightsOptions.POLAR_LIGHTS.on(),
                LiteLightsOptions.METEOR_NIGHTS.on(),
                LiteLightsOptions.FAR_FIREWORKS.on(),
                RenderSettings.SpawnDistance.valueOf(LiteLightsOptions.WEATHER_DISTANCE.get().name()),
                RenderSettings.DistanceFill.valueOf(LiteLightsOptions.WEATHER_DISTANCE_FILL.get().name()),
                LiteLightsOptions.RAIN_DRIPS.on(),
                LiteLightsOptions.WALL_STREAKS.on(),
                new RenderSettings.Glass(
                    LiteLightsOptions.GLASS_WEATHER.on(),
                    RenderSettings.GlassDetail.valueOf(LiteLightsOptions.GLASS_DETAIL.get().name()),
                    LiteLightsOptions.GLASS_FROST.on(),
                    LiteLightsOptions.GLASS_DUST.on()),
                new RenderSettings.Storms(
                    storm(LiteLightsOptions.StormType.RAIN),
                    storm(LiteLightsOptions.StormType.THUNDERSTORM),
                    storm(LiteLightsOptions.StormType.SNOW),
                    storm(LiteLightsOptions.StormType.BLIZZARD),
                    storm(LiteLightsOptions.StormType.SANDSTORM),
                    storm(LiteLightsOptions.StormType.HABOOB)),
                RenderSettings.MistStyle.valueOf(LiteLightsOptions.MIST_STYLE.get().name())),
            new RenderSettings.Outlines(
                LiteLightsOptions.OUTLINES.on(),
                RenderSettings.GlowColour.valueOf(LiteLightsOptions.OUTLINE_COLOUR.get().name()),
                LiteLightsOptions.OUTLINE_INTENSITY.value(),
                LiteLightsOptions.OUTLINE_SIZE.value(),
                LiteLightsOptions.OUTLINE_SATURATION.value(),
                LiteLightsOptions.OUTLINE_DIFFUSION.value(),
                LiteLightsOptions.OUTLINE_WORN_ARMOUR.on(),
                LiteLightsOptions.OUTLINE_HIDE_SHIMMER.on(),
                LiteLightsOptions.OUTLINE_TRIM_COLOURS.on()),
            new RenderSettings.Dimensions(
                LiteLightsOptions.END_HAZE.on(),
                LiteLightsOptions.END_SKY_GLOW.on(),
                LiteLightsOptions.END_TINT.on(),
                LiteLightsOptions.END_MOTES.on()),
            PreviewTime.overrideDayTime().orElse(RenderSettings.NO_PREVIEW)
        );
    }

    private static RenderSettings.Storm storm(LiteLightsOptions.StormType type) {
        LiteLightsOptions.StormRows rows = LiteLightsOptions.storm(type);
        return new RenderSettings.Storm(RenderSettings.StormFog.valueOf(rows.fog().get().name()),
            rows.clouds().get() == LiteLightsOptions.StormClouds.FOLLOW_DENSITY);
    }

    private static RenderSettings.GradeSchedule gradeSchedule() {
        return switch (LiteLightsOptions.GRADE_SCHEDULE.get()) {
            case OFF -> RenderSettings.GradeSchedule.OFF;
            case ALL_DAY -> RenderSettings.GradeSchedule.ALL_DAY;
            case BY_TIME_OF_DAY -> RenderSettings.GradeSchedule.BY_TIME_OF_DAY;
        };
    }

    private static RenderSettings.Grade grade(GradeTime time) {
        ColourGrade grade = LiteLightsOptions.grade(time);
        return new RenderSettings.Grade(
            grade.exposure().value(), grade.contrast().value(), grade.shadows().value(), grade.midtones().value(),
            grade.highlights().value(), grade.saturation().value(), grade.vibrance().value(), grade.hueShift().value(),
            grade.temperature().value(), grade.tint().value(), grade.redStrength().value(), grade.greenStrength().value(),
            grade.blueStrength().value(), grade.redHue().value(), grade.greenHue().value(), grade.blueHue().value());
    }
}
