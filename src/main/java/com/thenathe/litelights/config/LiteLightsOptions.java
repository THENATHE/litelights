package com.thenathe.litelights.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Every built-in option, with the defaults from the plan (section 5). Each field is registered
 * with the shared config when this class loads.
 *
 * <p>Read values with {@code get()}, or {@code on()} / {@code value()} for primitives. Every
 * feature is ready from the start; owners report problems
 * and other mods taking over on {@code Feature.of(id)}. The feature ids are listed in
 * docs/SETTINGS-API.md.
 */
public final class LiteLightsOptions {
    private LiteLightsOptions() {
    }

    // Choices

    public enum AntiAliasing { OFF, FXAA, TAA }

    public enum SkyPalette { VANILLA_COLOURS, COMPUTED }

    public enum Clouds { OFF, FLAT, FANCY }

    public enum FogLook { VANILLA, HAZE, OFF }

    public enum GradeSchedule { OFF, ALL_DAY, BY_TIME_OF_DAY }

    /** Relit: the sun also relights the world. Shadows only: vanilla's light with shadows added. */
    public enum LightModel { RELIT, SHADOWS_ONLY }

    /** Sun shadow map sizes, from 1024 to 4096 texels a side. */
    public enum ShadowDetail { FASTEST, FAST, SHARP, SHARPEST, ULTRA }

    /** How far a surface's shadow lookup steps off the surface: 0.5, 1, 1.5 or 2 shadow map texels. */
    public enum ShadowOffset { CONTACT, DETAILED, BALANCED, CLEAN }

    public enum FarShadows { OFF, SCREEN_SPACE, ADVANCED }

    /** How finely sunbeams are drawn: quarter, half or full resolution, and full with twice the slices. */
    public enum SunbeamQuality { LOW, MEDIUM, HIGH, ULTRA }

    /** Natural is shown as Natural; Enhanced keeps the water texture's pixel look with Natural's shading. */
    public enum WaterPalette { VANILLA_COLOURS, ENHANCED, NATURAL }

    public enum JumpSplash { OFF, BASIC, ADVANCED }

    /** Whether foam on flowing water stays where it forms (Stationary) or travels downstream with the current (Drifting). */
    public enum FoamMotion { STATIONARY, DRIFTING }

    /** The water the ocean waves run up the beach: clear water, foam up and back, or foam up and clear water back. */
    public enum ShoreFilm { WATER, FOAM, FOAM_IN_WATER_OUT }

    /** How water's sparkles move: Still only with the viewpoint, Gentle and Lively also with the waves. */
    public enum WaterSparkle { STILL, GENTLE, LIVELY }

    public enum MirrorDetail { COARSE, BALANCED, FINE }

    public enum ParticleDensity { FULL, SPARSE, OFF }

    public enum SnowSandSprites { VANILLA, DETAILED }

    /** Shown as Vanilla, Vanilla+ (vanilla's rain and snow with a LiteLights layer) and Enhanced (LiteLights' own). */
    public enum PrecipitationLook { VANILLA, VANILLA_PLUS, LITELIGHTS }

    public enum WeatherDensity { LIGHT, MEDIUM, HEAVY, DENSE }

    /** Shown as 3D puffs, Mist field and Classic billboards: how mist, dust clouds and haze puffs are drawn. */
    public enum MistStyle { PUFFS, FIELD, BILLBOARDS }

    /** The six storm types, each with its own fog and cloud particle rows. Haboob is a sandstorm with thunder. */
    public enum StormType { RAIN, THUNDERSTORM, SNOW, BLIZZARD, SANDSTORM, HABOOB }

    /** How thick a storm's fog gets. Normal is the fog as it was before these rows existed. */
    public enum StormFog { LIGHT, NORMAL, DENSE, VERY_DENSE }

    /** Whether a storm brings its large cloud particles (mist puffs, dust clouds), as thick as the Weather density says. */
    public enum StormClouds { OFF, FOLLOW_DENSITY }

    /** The pixel grid weather on glass is drawn on: 16 (vanilla's) or 32 to a block. */
    public enum GlassDetail { TEXELS_16, TEXELS_32 }

    /** How far around you particles reach: Near is the classic distance. */
    public enum SpawnDistance { NEAR, MEDIUM, FAR, VERY_FAR }

    /** Beyond Near: the same count spread wider, or Near's density over the whole area. */
    public enum DistanceFill { SPREAD, FILL }

    /** The glow colour of enchantment outlines; one fixed colour, not one per item. */
    public enum GlowColour { ENCHANT, BLUE, CYAN, GREEN, GOLD, RED, WHITE }

    /** The sets of grade sliders: one for the whole day, or one per time of day. */
    public enum GradeTime { ALL_DAY, DAWN, MIDDAY, DUSK, NIGHT }

    // General

    // The master switch stays on General; each effect's own lock is on that effect's page (below).
    public static final BoolOption PIXEL_LOCK = BoolOption.builder("general.pixel_lock", true)
            .at(Pages.GENERAL, "image").feature("pixel_lock").register();

    public static final EnumOption<AntiAliasing> ANTI_ALIASING = EnumOption.builder("general.anti_aliasing", AntiAliasing.class, AntiAliasing.OFF)
            .at(Pages.GENERAL, "image").feature("fxaa")
            .choiceCost(AntiAliasing.FXAA, Cost.LOW).choiceCost(AntiAliasing.TAA, Cost.MEDIUM)
            .choiceFeature(AntiAliasing.TAA, "taa")
            .register();

    public static final BoolOption MODDED_BIOME_FOG = BoolOption.builder("general.modded_biome_fog", true)
            .at(Pages.GENERAL, "world_colours").register();
    public static final BoolOption NATURAL_FOLIAGE = BoolOption.builder("general.natural_foliage", true)
            .at(Pages.GENERAL, "world_colours").register();

    // Read by each resource reload, not per frame (render.pipeline.ShaderLoopCaps).
    public static final BoolOption SHADER_LOOP_CAPS = BoolOption.builder("general.shader_loop_caps", true)
            .at(Pages.GENERAL, "compatibility").tag("litelights.option.general.shader_loop_caps.tag").register();

    // Mac troubleshooting: parts of the terrain and block-model shaders, compiled in or out by each
    // resource reload (render.pipeline.TerrainParts). Every part is in by default.
    public static final BoolOption VANILLA_TERRAIN_SHADING = BoolOption.builder("general.mac.vanilla_terrain_shading", false)
            .at(Pages.GENERAL, "compatibility/mac").register();
    public static final BoolOption TERRAIN_WATER = terrainPart("water");
    public static final BoolOption TERRAIN_CAUSTICS = terrainPart("caustics");
    public static final BoolOption TERRAIN_GLASS = terrainPart("glass");
    public static final BoolOption TERRAIN_SHADOWS = terrainPart("shadows");
    public static final BoolOption TERRAIN_LIGHT = terrainPart("light");
    public static final BoolOption TERRAIN_FOG = terrainPart("fog");
    public static final BoolOption TERRAIN_SWAY = terrainPart("sway");

    // Sky

    public static final EnumOption<SkyPalette> SKY_PALETTE = EnumOption.builder("sky.palette", SkyPalette.class, SkyPalette.COMPUTED)
            .at(Pages.SKY, "sky").cost(Cost.LOW).feature("sky").register();
    public static final BoolOption FALLING_STARS = BoolOption.builder("sky.falling_stars", true)
            .at(Pages.SKY, "sky").register();
    public static final EnumOption<Clouds> CLOUDS = EnumOption.builder("sky.clouds", Clouds.class, Clouds.FANCY)
            .at(Pages.SKY, "clouds").feature("clouds").choiceCost(Clouds.OFF, Cost.FREE).choiceCost(Clouds.FLAT, Cost.LOW)
            .cost(Cost.MEDIUM).register();
    public static final IntOption CLOUD_LAYERS = IntOption.builder("sky.cloud_layers", 2, 1, 2, 1)
            .at(Pages.SKY, "clouds").feature("clouds").cost(Cost.LOW)
            .enabledWhen(() -> LiteLightsOptions.CLOUDS.get() != Clouds.OFF).register();

    // Fog

    public static final EnumOption<FogLook> FOG_LOOK = EnumOption.builder("fog.look", FogLook.class, FogLook.HAZE)
            .at(Pages.FOG, "fog").feature("atmospheric_fog").cost(Cost.LOW)
            .choiceCost(FogLook.VANILLA, Cost.FREE).choiceCost(FogLook.OFF, Cost.FREE).register();
    public static final FloatOption FOG_DENSITY = FloatOption.builder("fog.density", 1.0F, 0.0F, 2.0F, 0.05F)
            .at(Pages.FOG, "fog").feature("atmospheric_fog")
            .enabledWhen(() -> LiteLightsOptions.FOG_LOOK.get() == FogLook.HAZE).register();
    public static final BoolOption MIST_BANKS = BoolOption.builder("fog.mist_banks", true)
            .at(Pages.FOG, "mist").cost(Cost.MEDIUM).register();
    public static final BoolOption FAR_TERRAIN = BoolOption.builder("fog.far_terrain", true)
            .at(Pages.FOG, "far_terrain").feature("far_terrain").cost(Cost.LOW).register();

    // Lighting

    public static final BoolOption HALO = BoolOption.builder("lighting.halo", true)
            .at(Pages.LIGHTING, "bloom").cost(Cost.MEDIUM).feature("bloom").register();
    public static final FloatOption HALO_STRENGTH = FloatOption.builder("lighting.halo_strength", 1.0F, 0.0F, 2.0F, 0.05F)
            .at(Pages.LIGHTING, "bloom").feature("bloom").needs(HALO).register();
    public static final BoolOption GLOW_HEAT = BoolOption.builder("lighting.glow_heat", true)
            .at(Pages.LIGHTING, "light").cost(Cost.LOW).register();
    public static final BoolOption NIGHT_TONE = BoolOption.builder("lighting.night_tone", true)
            .at(Pages.LIGHTING, "light").register();
    public static final BoolOption HEAT_SHIMMER = BoolOption.builder("lighting.heat_shimmer", true)
            .at(Pages.LIGHTING, "light").cost(Cost.LOW).feature("heat_shimmer").register();
    public static final BoolOption SUNBEAMS = BoolOption.builder("lighting.sunbeams", true)
            .at(Pages.LIGHTING, "light_rays").cost(Cost.MEDIUM).feature("light_rays").register();
    public static final FloatOption SUNBEAM_STRENGTH = FloatOption.builder("lighting.sunbeam_strength", 2.0F, 0.0F, 4.0F, 0.1F)
            .at(Pages.LIGHTING, "light_rays").feature("light_rays").needs(SUNBEAMS).register();
    public static final BoolOption UNDERWATER_SUNBEAMS = BoolOption.builder("lighting.underwater_sunbeams", true)
            .at(Pages.LIGHTING, "light_rays").cost(Cost.MEDIUM).feature("underwater_rays").register();
    public static final FloatOption UNDERWATER_SUNBEAM_STRENGTH = FloatOption.builder("lighting.underwater_sunbeam_strength", 1.5F, 0.0F, 4.0F, 0.1F)
            .at(Pages.LIGHTING, "light_rays").feature("underwater_rays").needs(UNDERWATER_SUNBEAMS).register();
    public static final EnumOption<SunbeamQuality> SUNBEAM_QUALITY = EnumOption.builder("lighting.sunbeam_quality",
                    SunbeamQuality.class, SunbeamQuality.MEDIUM)
            .at(Pages.LIGHTING, "light_rays").feature("light_rays").cost(Cost.LOW)
            .choiceCost(SunbeamQuality.HIGH, Cost.MEDIUM).choiceCost(SunbeamQuality.ULTRA, Cost.HIGH)
            .enabledWhen(() -> LiteLightsOptions.SUNBEAMS.on() || LiteLightsOptions.UNDERWATER_SUNBEAMS.on()).register();
    public static final BoolOption GLINT_WATER = BoolOption.builder("lighting.glint_water", true)
            .at(Pages.LIGHTING, "glint").cost(Cost.LOW).feature("glint").register();
    public static final EnumOption<WaterSparkle> WATER_SPARKLE = EnumOption.builder("lighting.water_sparkle",
                    WaterSparkle.class, WaterSparkle.GENTLE)
            .at(Pages.LIGHTING, "glint").feature("glint").needs(GLINT_WATER).register();
    // The glint sliders (0.9.7, followb097) replace Glint softness: 50% is each one's default, and
    // GlintScale says what the rest of the range means. Glint under water follows the water's.
    public static final FloatOption GLINT_WATER_SOFTNESS = glintSlider("lighting.glint_water_softness", LiteLightsOptions::waterGlint);
    public static final FloatOption GLINT_WATER_INTENSITY = glintSlider("lighting.glint_water_intensity", LiteLightsOptions::waterGlint);
    public static final BoolOption GLINT_ICE_SNOW = BoolOption.builder("lighting.glint_ice_snow", true)
            .at(Pages.LIGHTING, "glint").cost(Cost.LOW).feature("glint").register();
    public static final BoolOption GLINT_METAL_GLASS = BoolOption.builder("lighting.glint_metal_glass", true)
            .at(Pages.LIGHTING, "glint").cost(Cost.LOW).feature("glint").register();
    public static final BoolOption GLINT_SAND_STONE = BoolOption.builder("lighting.glint_sand_stone", false)
            .at(Pages.LIGHTING, "glint").cost(Cost.LOW).feature("glint").register();
    // Ores and crystals had their glimmer under sand and stone until 0.9.9 (shimmer099).
    public static final BoolOption GLINT_ORES = BoolOption.builder("lighting.glint_ores", true)
            .at(Pages.LIGHTING, "glint").cost(Cost.LOW).feature("glint").register();
    public static final FloatOption GLINT_GROUND_SOFTNESS = glintSlider("lighting.glint_ground_softness", LiteLightsOptions::groundGlint);
    public static final FloatOption GLINT_GROUND_INTENSITY = glintSlider("lighting.glint_ground_intensity", LiteLightsOptions::groundGlint);
    public static final BoolOption SUBMERGED_GLINT = BoolOption.builder("lighting.submerged_glint", true)
            .at(Pages.LIGHTING, "glint").cost(Cost.LOW).feature("glint").register();

    // Colour grading

    public static final EnumOption<GradeSchedule> GRADE_SCHEDULE = EnumOption.builder("colour_grade.schedule", GradeSchedule.class, GradeSchedule.BY_TIME_OF_DAY)
            .at(Pages.COLOUR_GRADING, "grading").feature("colour_grading").register();

    /** Which time of day the sliders edit. Screen state, not saved; the screen changes it. */
    private static volatile GradeTime editedGradeTime = GradeTime.DAWN;

    private static final Map<GradeTime, ColourGrade> GRADES = registerGrades();

    // Shadows

    public static final BoolOption WORLD_SHADOWS = BoolOption.builder("shadows.world_shadows", true)
            .at(Pages.SHADOWS, "shadows").cost(Cost.HIGH).feature("shadows").register();
    public static final EnumOption<LightModel> LIGHT_MODEL = EnumOption.builder("shadows.light_model", LightModel.class, LightModel.RELIT)
            .at(Pages.SHADOWS, "shadows").feature("shadows").needs(WORLD_SHADOWS).choiceCost(LightModel.RELIT, Cost.MEDIUM)
            .cost(Cost.LOW).register();
    public static final FloatOption SHADOW_DARKNESS = FloatOption.builder("shadows.darkness", 1.0F, 0.0F, 1.0F, 0.05F)
            .at(Pages.SHADOWS, "shadows").feature("shadows").needs(WORLD_SHADOWS).register();
    public static final FloatOption SUN_BRIGHTNESS = FloatOption.builder("shadows.sun_brightness", 0.0F, 0.0F, 1.0F, 0.05F)
            .at(Pages.SHADOWS, "shadows").feature("shadows").needs(WORLD_SHADOWS).register();
    public static final FloatOption SUNLIGHT_COLOUR = FloatOption.builder("shadows.sunlight_colour", 1.0F, 0.0F, 2.0F, 0.05F)
            .at(Pages.SHADOWS, "shadows").feature("shadows").needs(WORLD_SHADOWS).register();
    public static final IntOption SHADOW_REACH = IntOption.builder("shadows.reach", 192, 64, 512, 16)
            .at(Pages.SHADOWS, "quality").feature("shadows").needs(WORLD_SHADOWS).cost(Cost.HIGH).format(ValueFormat.BLOCKS).register();
    public static final EnumOption<ShadowDetail> SHADOW_DETAIL = EnumOption.builder("shadows.detail",
                    ShadowDetail.class, ShadowDetail.SHARPEST)
            .at(Pages.SHADOWS, "quality").feature("shadows").needs(WORLD_SHADOWS).cost(Cost.MEDIUM)
            .choiceCost(ShadowDetail.FASTEST, Cost.LOW).choiceCost(ShadowDetail.SHARPEST, Cost.HIGH)
            .choiceCost(ShadowDetail.ULTRA, Cost.HIGH).register();
    public static final EnumOption<ShadowOffset> SHADOW_OFFSET = EnumOption.builder("shadows.offset",
                    ShadowOffset.class, ShadowOffset.BALANCED)
            .at(Pages.SHADOWS, "quality").feature("shadows").needs(WORLD_SHADOWS).cost(Cost.FREE)
            .tag("litelights.tag.experimental").register();
    public static final BoolOption SHADOW_GLIDE = BoolOption.builder("shadows.glide", true)
            .at(Pages.SHADOWS, "quality").feature("shadows").needs(WORLD_SHADOWS).cost(Cost.LOW).register();
    public static final BoolOption ENTITY_SHADOWS = BoolOption.builder("shadows.entities", true)
            .at(Pages.SHADOWS, "more").feature("mob_shadows").needs(WORLD_SHADOWS).cost(Cost.MEDIUM).register();
    public static final BoolOption SHADOWS_IN_WATER = BoolOption.builder("shadows.in_water", true)
            .at(Pages.SHADOWS, "more").feature("underwater_shadows").needs(WORLD_SHADOWS).cost(Cost.LOW).register();
    public static final EnumOption<FarShadows> FAR_SHADOWS = EnumOption.builder("shadows.far", FarShadows.class, FarShadows.ADVANCED)
            .at(Pages.SHADOWS, "far_terrain").feature("far_shadows").needs(WORLD_SHADOWS).cost(Cost.LOW)
            .choiceCost(FarShadows.OFF, Cost.FREE).choiceCost(FarShadows.ADVANCED, Cost.HIGH).register();

    // Water

    public static final EnumOption<WaterPalette> WATER_PALETTE = EnumOption.builder("water.palette", WaterPalette.class, WaterPalette.ENHANCED)
            .at(Pages.WATER, "colour").register();
    public static final BoolOption BIOME_DEPTH_COLOUR = BoolOption.builder("water.biome_depth_colour", true)
            .at(Pages.WATER, "colour").cost(Cost.LOW).register();
    public static final BoolOption WATER_MIRRORS = BoolOption.builder("water.mirrors", true)
            .at(Pages.WATER, "reflections").cost(Cost.HIGH).feature("reflections").register();
    public static final BoolOption ICE_MIRRORS = BoolOption.builder("water.ice_mirrors", true)
            .at(Pages.WATER, "reflections").cost(Cost.MEDIUM).feature("reflections").register();
    public static final EnumOption<MirrorDetail> MIRROR_DETAIL = EnumOption.builder("water.mirror_detail",
                    MirrorDetail.class, MirrorDetail.BALANCED)
            .at(Pages.WATER, "reflections").feature("reflections").cost(Cost.MEDIUM)
            .choiceCost(MirrorDetail.COARSE, Cost.LOW).choiceCost(MirrorDetail.FINE, Cost.HIGH)
            .enabledWhen(LiteLightsOptions::anyMirrors).register();
    public static final FloatOption MIRROR_STRENGTH = FloatOption.builder("water.mirror_strength", 1.0F, 0.0F, 1.0F, 0.05F)
            .at(Pages.WATER, "reflections").feature("reflections").enabledWhen(LiteLightsOptions::anyMirrors).register();
    public static final BoolOption EDGE_FOAM = BoolOption.builder("water.edge_foam", true)
            .at(Pages.WATER, "surface").cost(Cost.LOW).register();
    public static final BoolOption EDGE_FOAM_BLOCKS_ONLY = BoolOption.builder("water.edge_foam_blocks_only", true)
            .at(Pages.WATER, "surface").needs(EDGE_FOAM).register();
    public static final EnumOption<FoamMotion> FOAM_MOTION = EnumOption.builder("water.foam_motion", FoamMotion.class, FoamMotion.DRIFTING)
            .at(Pages.WATER, "surface").cost(Cost.FREE).choiceCost(FoamMotion.DRIFTING, Cost.LOW)
            .enabledWhen(LiteLightsOptions::anyFoam).register();
    public static final BoolOption OCEAN_WAVES = BoolOption.builder("water.ocean_waves", true)
            .at(Pages.WATER, "surface").cost(Cost.LOW).register();
    // How far the swash runs up the beach in fair weather, and how thick the surf's foam lines are.
    // At 1 each draws the waves exactly as before the rows existed (0.9.7, waves097).
    public static final FloatOption SHORE_RUN_UP = FloatOption.builder("water.shore_run_up", 1.0F, 1.0F, 2.0F, 0.25F)
            .at(Pages.WATER, "surface").needs(OCEAN_WAVES).format(ValueFormat.BLOCKS).register();
    // What the run-up looks like (swash099); Water is the clear sheet it always was.
    public static final EnumOption<ShoreFilm> SHORE_FILM = EnumOption.builder("water.shore_film", ShoreFilm.class, ShoreFilm.WATER)
            .at(Pages.WATER, "surface").needs(OCEAN_WAVES).register();
    public static final FloatOption WAVE_FOAM = FloatOption.builder("water.wave_foam", 1.0F, 0.25F, 2.0F, 0.05F)
            .at(Pages.WATER, "surface").needs(OCEAN_WAVES).format(ValueFormat.PERCENT).register();
    public static final BoolOption CAUSTICS = BoolOption.builder("water.caustics", true)
            .at(Pages.WATER, "surface").cost(Cost.LOW).register();
    public static final BoolOption FLOATING_LEAVES = BoolOption.builder("water.floating_leaves", true)
            .at(Pages.WATER, "surface").cost(Cost.LOW).register();
    public static final BoolOption LAVA_CRUST = BoolOption.builder("water.lava_crust", true)
            .at(Pages.WATER, "surface").cost(Cost.LOW).register();
    public static final BoolOption UNDERWATER_BLUR = BoolOption.builder("water.underwater_blur", false)
            .at(Pages.WATER, "surface").cost(Cost.MEDIUM).register();
    public static final BoolOption LARGE_WATER_TEXTURES = BoolOption.builder("water.large_textures", true)
            .at(Pages.WATER, "large_textures").feature("large_water_textures").credit().register();
    public static final IntOption LARGE_WATER_SCALE = IntOption.builder("water.large_water_scale", 4, 2, 8, 1)
            .at(Pages.WATER, "large_textures").feature("large_water_textures").needs(LARGE_WATER_TEXTURES)
            .format(ValueFormat.BLOCK_SQUARE).register();
    public static final IntOption LARGE_LAVA_SCALE = IntOption.builder("water.large_lava_scale", 4, 2, 8, 1)
            .at(Pages.WATER, "large_textures").feature("large_water_textures").needs(LARGE_WATER_TEXTURES)
            .format(ValueFormat.BLOCK_SQUARE).register();
    public static final BoolOption FAR_WATER = BoolOption.builder("water.far_terrain", true)
            .at(Pages.WATER, "far_terrain").feature("far_water").cost(Cost.MEDIUM).register();
    // Three independent extras on far water, off by default while they are Experimental. They share
    // one feature: their code is one shader variant, so a failure there shows on all three rows.
    public static final BoolOption FAR_CAUSTICS = farWaterExtra("water.far_caustics", Cost.LOW);
    public static final BoolOption FAR_DEPTH_COLOUR = farWaterExtra("water.far_depth_colour", Cost.LOW);
    public static final BoolOption FAR_EDGE_FOAM = farWaterExtra("water.far_edge_foam", Cost.MEDIUM);
    public static final EnumOption<JumpSplash> JUMP_SPLASH = EnumOption.builder("water.jump_splash", JumpSplash.class, JumpSplash.ADVANCED)
            .at(Pages.WATER, "splashes").cost(Cost.LOW).choiceCost(JumpSplash.OFF, Cost.FREE).credit().register();
    // Wakes behind boats and swimmers; the three rows share one feature, which Wakes (the mod) takes over.
    public static final BoolOption WAKES = BoolOption.builder("water.wakes", true)
            .at(Pages.WATER, "wakes").cost(Cost.LOW).feature("water.wakes").credit().register();
    public static final FloatOption WAKE_STRENGTH = FloatOption.builder("water.wake_strength", 1.0F, 0.25F, 2.0F, 0.05F)
            .at(Pages.WATER, "wakes").feature("water.wakes").needs(WAKES).format(ValueFormat.PERCENT).register();
    public static final BoolOption WAKE_FOAM = BoolOption.builder("water.wake_foam", true)
            .at(Pages.WATER, "wakes").feature("water.wakes").needs(WAKES).register();

    // Vegetation

    public static final BoolOption SWAY_GRASS = BoolOption.builder("vegetation.sway_grass", true)
            .at(Pages.VEGETATION, "sway").cost(Cost.LOW).feature("sway").register();
    public static final BoolOption SWAY_PLANTS = BoolOption.builder("vegetation.sway_plants", true)
            .at(Pages.VEGETATION, "sway").cost(Cost.LOW).feature("sway").register();
    public static final BoolOption SWAY_LEAVES = BoolOption.builder("vegetation.sway_leaves", true)
            .at(Pages.VEGETATION, "sway").cost(Cost.MEDIUM).feature("sway").register();
    // Its own feature: lanterns need no draw parameters, so they swing where the plants can't.
    public static final BoolOption SWAY_LANTERNS = BoolOption.builder("vegetation.sway_lanterns", true)
            .at(Pages.VEGETATION, "sway").cost(Cost.LOW).feature("vegetation.sway_lanterns").credit().register();
    public static final BoolOption GROUND_LEAVES = BoolOption.builder("vegetation.ground_leaves", true)
            .at(Pages.VEGETATION, "forest_floor").cost(Cost.LOW).register();

    // Particles

    public static final EnumOption<ParticleDensity> PARTICLE_DENSITY = EnumOption.builder("particles.density",
                    ParticleDensity.class, ParticleDensity.FULL)
            .at(Pages.PARTICLES, "particles").feature("particles").cost(Cost.MEDIUM)
            .choiceCost(ParticleDensity.SPARSE, Cost.LOW).choiceCost(ParticleDensity.OFF, Cost.FREE).register();
    public static final EnumOption<SnowSandSprites> SNOW_SAND_SPRITES = EnumOption.builder("particles.snow_sand_sprites",
                    SnowSandSprites.class, SnowSandSprites.VANILLA)
            .at(Pages.PARTICLES, "particles").choiceCost(SnowSandSprites.DETAILED, Cost.LOW).register();
    public static final BoolOption FIREFLY_SWARMS = ambient("firefly_swarms");
    public static final BoolOption DUST_SPECKS = ambient("dust");
    public static final BoolOption POLLEN = ambient("pollen");
    public static final BoolOption SPORES = ambient("spores");
    // Its own feature rather than the ambient rows': with Falling Leaves (the mod) installed only this row steps aside.
    public static final BoolOption FALLING_LEAVES = BoolOption.builder("particles.falling_leaves", true)
            .at(Pages.PARTICLES, "ambient").cost(Cost.LOW).feature("particles.falling_leaves").credit()
            .enabledWhen(LiteLightsOptions::particlesOn).register();
    public static final BoolOption ENHANCED_FIRE = BoolOption.builder("particles.enhanced_fire", true)
            .at(Pages.PARTICLES, "enhanced").cost(Cost.LOW).enabledWhen(LiteLightsOptions::particlesOn).register();
    public static final BoolOption EMBER_VEINS = BoolOption.builder("particles.ember_veins", true)
            .at(Pages.PARTICLES, "enhanced").cost(Cost.LOW).needs(ENHANCED_FIRE).enabledWhen(LiteLightsOptions::particlesOn).register();
    public static final BoolOption BURN_MARKS = BoolOption.builder("particles.burn_marks", true)
            .at(Pages.PARTICLES, "enhanced").cost(Cost.LOW).needs(ENHANCED_FIRE).enabledWhen(LiteLightsOptions::particlesOn).register();
    public static final BoolOption ENHANCED_WATER = BoolOption.builder("particles.enhanced_water", true)
            .at(Pages.PARTICLES, "enhanced").cost(Cost.LOW).enabledWhen(LiteLightsOptions::particlesOn).register();

    // Weather and events. The page reads Rain and snow, Storms (each storm type under its own
    // sub-heading), Weather details, Weather on glass and Sky events; rows keep the order they
    // are registered in, so each section's rows are registered together.

    public static final EnumOption<PrecipitationLook> PRECIPITATION = EnumOption.builder("weather.precipitation",
                    PrecipitationLook.class, PrecipitationLook.LITELIGHTS)
            .at(Pages.WEATHER, "rain_snow").cost(Cost.MEDIUM)
            .choiceCost(PrecipitationLook.VANILLA, Cost.FREE).choiceCost(PrecipitationLook.VANILLA_PLUS, Cost.LOW).register();
    public static final EnumOption<WeatherDensity> WEATHER_DENSITY = EnumOption.builder("weather.density",
                    WeatherDensity.class, WeatherDensity.MEDIUM)
            .at(Pages.WEATHER, "rain_snow").cost(Cost.MEDIUM).choiceCost(WeatherDensity.LIGHT, Cost.LOW)
            .choiceCost(WeatherDensity.HEAVY, Cost.HIGH).choiceCost(WeatherDensity.DENSE, Cost.HIGH).register();
    public static final EnumOption<SpawnDistance> WEATHER_DISTANCE = distance("weather.distance", Pages.WEATHER, "rain_snow");
    public static final EnumOption<DistanceFill> WEATHER_DISTANCE_FILL = distanceFill("weather.distance_fill", Pages.WEATHER,
            "rain_snow", WEATHER_DISTANCE);
    public static final BoolOption RAIN_MIST = BoolOption.builder("weather.rain_mist", true)
            .at(Pages.WEATHER, "rain_snow").cost(Cost.LOW).register();
    // New in 0.9.7 (fog097); it covers the Nether's ash haze and the End's mist too, which have no
    // rows of their own here, so it sits with the mist row rather than under Storms.
    public static final EnumOption<MistStyle> MIST_STYLE = EnumOption.builder("weather.mist_style", MistStyle.class, MistStyle.FIELD)
            .at(Pages.WEATHER, "rain_snow").cost(Cost.LOW).choiceCost(MistStyle.PUFFS, Cost.MEDIUM)
            .choiceCost(MistStyle.BILLBOARDS, Cost.LOW)
            .enabledWhen(LiteLightsOptions::particlesOn).register();
    public static final BoolOption STORM_ATMOSPHERE = BoolOption.builder("weather.storm_atmosphere", true)
            .at(Pages.WEATHER, "storms").cost(Cost.MEDIUM).credit().register();
    // New in 0.9.9 (storm099). Both are drawn by LiteLights alone, so they need neither particles nor Storm atmosphere.
    public static final BoolOption CLOUD_LIGHTNING = BoolOption.builder("weather.cloud_lightning", true)
            .at(Pages.WEATHER, "storms").feature("lightning").cost(Cost.LOW).credit().register();
    public static final BoolOption BRANCHING_LIGHTNING = BoolOption.builder("weather.branching_lightning", true)
            .at(Pages.WEATHER, "storms").feature("lightning").cost(Cost.LOW).credit().register();
    private static final Map<StormType, StormRows> STORMS = registerStorms();
    public static final BoolOption RAIN_DRIPS = BoolOption.builder("weather.rain_drips", true)
            .at(Pages.WEATHER, "details").cost(Cost.LOW).credit()
            .enabledWhen(LiteLightsOptions::particlesOn).register();
    public static final BoolOption WALL_STREAKS = BoolOption.builder("weather.wall_streaks", true)
            .at(Pages.WEATHER, "details").cost(Cost.LOW).credit()
            .enabledWhen(LiteLightsOptions::particlesOn).register();
    // New in 0.9.9 (rain099): a shader effect on the land, so it needs neither particles nor Rain and snow.
    public static final BoolOption PUDDLES = BoolOption.builder("weather.puddles", true)
            .at(Pages.WEATHER, "details").cost(Cost.LOW).register();
    // New in 0.9.9 (snowleaves099): beside the puddles, the other weather that settles on the land.
    public static final BoolOption SNOWY_LEAVES = BoolOption.builder("weather.snowy_leaves", true)
            .at(Pages.WEATHER, "details").cost(Cost.LOW).credit().register();
    public static final BoolOption DRIFTING_SAND = BoolOption.builder("weather.drifting_sand", true)
            .at(Pages.WEATHER, "details").cost(Cost.LOW).register();
    public static final BoolOption SANDSTORMS = BoolOption.builder("weather.sandstorms", true)
            .at(Pages.WEATHER, "details").cost(Cost.MEDIUM).register();
    // Weather on glass shares one feature, so if Weather Glass is installed every row says it handles them.
    // It follows its own rows only, whatever Rain and snow is set to (decide16). Breath on glass
    // (weather.glass_breath) was removed in 0.1.6 and Rain on glass (weather.glass_style) in 0.1.7,
    // when rain on glass became the wall streaks; a saved value stays in the file, unread.
    public static final BoolOption GLASS_WEATHER = BoolOption.builder("weather.glass", true)
            .at(Pages.WEATHER, "glass").feature("glass_weather").cost(Cost.LOW).credit().register();
    public static final EnumOption<GlassDetail> GLASS_DETAIL = EnumOption.builder("weather.glass_detail", GlassDetail.class,
                    GlassDetail.TEXELS_16)
            .at(Pages.WEATHER, "glass").feature("glass_weather").needs(GLASS_WEATHER).register();
    public static final BoolOption GLASS_FROST = BoolOption.builder("weather.glass_frost", true)
            .at(Pages.WEATHER, "glass").feature("glass_weather").needs(GLASS_WEATHER).register();
    public static final BoolOption GLASS_DUST = BoolOption.builder("weather.glass_dust", true)
            .at(Pages.WEATHER, "glass").feature("glass_weather").needs(GLASS_WEATHER).register();
    public static final BoolOption POLAR_LIGHTS = BoolOption.builder("weather.polar_lights", true)
            .at(Pages.WEATHER, "events").cost(Cost.LOW).register();
    public static final BoolOption METEOR_NIGHTS = BoolOption.builder("weather.meteor_nights", true)
            .at(Pages.WEATHER, "events").register();
    public static final BoolOption RAINBOWS = BoolOption.builder("weather.rainbows", true)
            .at(Pages.WEATHER, "events").credit().register();
    public static final BoolOption FAR_FIREWORKS = BoolOption.builder("weather.far_fireworks", true)
            .at(Pages.WEATHER, "events").register();

    // Enchantment outlines

    public static final BoolOption OUTLINES = BoolOption.builder("outlines.enabled", true)
            .at(Pages.OUTLINES, "outlines").cost(Cost.LOW).feature("outlines").register();
    public static final EnumOption<GlowColour> OUTLINE_COLOUR = EnumOption.builder("outlines.colour", GlowColour.class, GlowColour.ENCHANT)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).register();
    public static final FloatOption OUTLINE_INTENSITY = FloatOption.builder("outlines.intensity", 1.0F, 0.0F, 2.0F, 0.05F)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).register();
    public static final IntOption OUTLINE_SIZE = IntOption.builder("outlines.size", 10, 1, 20, 1)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).register();
    public static final FloatOption OUTLINE_SATURATION = FloatOption.builder("outlines.saturation", 0.8F, 0.0F, 1.0F, 0.05F)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).register();
    /** 0 is a crisp solid line, 0.5 the original soft glow, 1 twice as soft as that. */
    public static final FloatOption OUTLINE_DIFFUSION = FloatOption.builder("outlines.diffusion", 0.5F, 0.0F, 1.0F, 0.05F)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).register();
    public static final BoolOption OUTLINE_WORN_ARMOUR = BoolOption.builder("outlines.worn_armour", true)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).cost(Cost.LOW).register();
    /** Hides vanilla's shimmer on the items and armour that get an outline; the outline stays. */
    public static final BoolOption OUTLINE_HIDE_SHIMMER = BoolOption.builder("outlines.hide_shimmer", true)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).register();
    /** Trimmed armour glows in its trim material's colour instead of the glow colour. */
    public static final BoolOption OUTLINE_TRIM_COLOURS = BoolOption.builder("outlines.trim_colours", true)
            .at(Pages.OUTLINES, "outlines").feature("outlines").needs(OUTLINES).register();

    // Dimensions: the End (modded dimensions are in DimensionOptions)

    public static final BoolOption END_HAZE = end("haze", Cost.LOW);
    public static final BoolOption END_SKY_GLOW = end("sky_glow", Cost.LOW);
    public static final BoolOption END_TINT = end("tint", Cost.FREE);
    public static final BoolOption END_MOTES = end("motes", Cost.LOW);

    // Pixel lock, one switch per effect on its own page. Registered after every page's other
    // options so each sits at the end of its section; their ids keep the general. prefix they were
    // saved under.

    public static final BoolOption PIXEL_LOCK_SHADOWS = pixelLock("shadows", Pages.SHADOWS, "quality");
    public static final BoolOption PIXEL_LOCK_CAUSTICS = pixelLock("caustics", Pages.WATER, "surface");
    public static final BoolOption PIXEL_LOCK_WATER_SURFACE = pixelLock("water_surface", Pages.WATER, "surface");
    public static final BoolOption PIXEL_LOCK_GLINT = pixelLock("glint", Pages.LIGHTING, "glint");
    public static final BoolOption PIXEL_LOCK_SUNBEAMS = pixelLock("sunbeams", Pages.LIGHTING, "light_rays");

    // Colour controls added in 0.1.5, each at the end of its section.

    /**
     * The curve the final picture goes through. Vanilla-friendly leaves everything below the
     * brightest tones exactly as it is; the others are LiteLights' takes on well-known film curves.
     */
    public enum ToneCurve { VANILLA_FRIENDLY, ACES, AGX, HABLE }

    public static final EnumOption<ToneCurve> TONE_CURVE = EnumOption.builder("colour_grade.tone_curve", ToneCurve.class,
                    ToneCurve.VANILLA_FRIENDLY)
            .at(Pages.COLOUR_GRADING, "grading").feature("colour_grading").cost(Cost.LOW)
            .choiceCost(ToneCurve.VANILLA_FRIENDLY, Cost.FREE).register();
    /** How much of each biome's own haze colour shows: 0 is the plain temperate haze everywhere, 1 the biomes' colours. */
    public static final FloatOption BIOME_HAZE_TINT = FloatOption.builder("fog.biome_haze_tint", 1.0F, 0.0F, 1.0F, 0.05F)
            .at(Pages.FOG, "fog").feature("atmospheric_fog")
            .enabledWhen(() -> LiteLightsOptions.FOG_LOOK.get() == FogLook.HAZE).register();
    /** The warm glow toward a low sun and the haze's warming around sunrise and sunset; 1 is the built-in look. */
    public static final FloatOption SUN_GLOW = FloatOption.builder("fog.sun_glow", 1.0F, 0.0F, 2.0F, 0.05F)
            .at(Pages.FOG, "fog").feature("atmospheric_fog")
            .enabledWhen(() -> LiteLightsOptions.FOG_LOOK.get() == FogLook.HAZE).register();

    // Particle distance. Registered last, so its rows come at the end of the Particles section.

    public static final EnumOption<SpawnDistance> PARTICLE_DISTANCE = distance("particles.distance", Pages.PARTICLES, "particles");
    public static final EnumOption<DistanceFill> PARTICLE_DISTANCE_FILL = distanceFill("particles.distance_fill", Pages.PARTICLES,
            "particles", PARTICLE_DISTANCE);

    /** Loads the class, which registers every option. */
    public static void init() {
        DimensionOptions.init();
    }

    // Storm access

    /** A storm type's two rows: how thick its fog is and whether it brings its cloud particles. */
    public record StormRows(EnumOption<StormFog> fog, EnumOption<StormClouds> clouds) {
    }

    public static StormRows storm(StormType type) {
        return STORMS.get(type);
    }

    // Colour grading access

    /** The grade sliders for one time of day. */
    public static ColourGrade grade(GradeTime time) {
        return GRADES.get(time);
    }

    public static GradeTime editedGradeTime() {
        return editedGradeTime;
    }

    public static void editGradeTime(GradeTime time) {
        editedGradeTime = time == GradeTime.ALL_DAY ? GradeTime.DAWN : time;
    }

    /**
     * One time of day's grade. Percent sliders are fractions around 0 (or 1 for the channel
     * strengths); hues are degrees; exposure is in stops.
     */
    public record ColourGrade(GradeTime time, FloatOption exposure, FloatOption contrast, FloatOption shadows,
                              FloatOption midtones, FloatOption highlights, FloatOption saturation, FloatOption vibrance,
                              FloatOption hueShift, FloatOption temperature, FloatOption tint,
                              FloatOption redStrength, FloatOption greenStrength, FloatOption blueStrength,
                              FloatOption redHue, FloatOption greenHue, FloatOption blueHue) {

        public List<FloatOption> sliders() {
            return List.of(exposure, contrast, shadows, midtones, highlights, saturation, vibrance, hueShift, temperature,
                    tint, redStrength, greenStrength, blueStrength, redHue, greenHue, blueHue);
        }
    }

    private static Map<GradeTime, ColourGrade> registerGrades() {
        Map<GradeTime, ColourGrade> grades = new EnumMap<>(GradeTime.class);
        for (GradeTime time : GradeTime.values()) {
            List<FloatOption> sliders = new ArrayList<>();
            sliders.add(gradeSlider(time, "exposure", 0.0F, -2.0F, 2.0F, 0.05F, ValueFormat.STOPS));
            for (String name : new String[] {"contrast", "shadows", "midtones", "highlights", "saturation", "vibrance"}) {
                sliders.add(gradeSlider(time, name, 0.0F, -1.0F, 1.0F, 0.01F, ValueFormat.SIGNED_PERCENT));
            }
            sliders.add(gradeSlider(time, "hue_shift", 0.0F, -180.0F, 180.0F, 1.0F, ValueFormat.DEGREES));
            sliders.add(gradeSlider(time, "temperature", 0.0F, -1.0F, 1.0F, 0.01F, ValueFormat.SIGNED_PERCENT));
            sliders.add(gradeSlider(time, "tint", 0.0F, -1.0F, 1.0F, 0.01F, ValueFormat.SIGNED_PERCENT));
            for (String channel : new String[] {"red", "green", "blue"}) {
                sliders.add(gradeSlider(time, channel + "_strength", 1.0F, 0.0F, 2.0F, 0.01F, ValueFormat.PERCENT));
            }
            for (String channel : new String[] {"red", "green", "blue"}) {
                sliders.add(gradeSlider(time, channel + "_hue", 0.0F, -60.0F, 60.0F, 1.0F, ValueFormat.DEGREES));
            }
            FloatOption[] s = sliders.toArray(FloatOption[]::new);
            grades.put(time, new ColourGrade(time, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9],
                    s[10], s[11], s[12], s[13], s[14], s[15]));
        }
        return Collections.unmodifiableMap(grades);
    }

    /**
     * The sliders of every time of day share labels. Only the set being edited is shown: the
     * all-day set in "all day" mode, the chosen time of day in "by time of day" mode. neutral is
     * the value that leaves the picture alone; the slider starts at {@link #gradeDefault}.
     */
    private static FloatOption gradeSlider(GradeTime time, String name, float neutral, float min, float max,
                                           float step, ValueFormat format) {
        String timeName = EnumOption.storedName(time);
        String labelKey = "litelights.option.colour_grade." + name;
        return FloatOption.builder("colour_grade." + timeName + "." + name, gradeDefault(time, name, neutral), min, max, step)
                .at(Pages.COLOUR_GRADING, "grade").feature("colour_grading").format(format)
                .text(labelKey, labelKey + ".description")
                .enabledWhen(() -> LiteLightsOptions.GRADE_SCHEDULE.get() != GradeSchedule.OFF)
                .shownWhen(() -> shownGradeTime() == time)
                .register();
    }

    /**
     * The default grade: a light orange-and-blue look at dawn and dusk (warmer golds, a little more
     * colour in dull tones and richer blues in the sky and shade), midday nearly untouched and a cool
     * night that leaves torches and lava warm. The all-day set starts neutral. Every slider can
     * still be set back to neutral by hand; docs/DECISIONS.md (batch) has the reasoning.
     */
    private static float gradeDefault(GradeTime time, String name, float neutral) {
        return switch (time) {
            case DAWN -> switch (name) {
                case "saturation" -> 0.07F;
                case "vibrance" -> 0.16F;
                case "temperature" -> 0.10F;
                case "blue_strength" -> 1.05F;
                default -> neutral;
            };
            case MIDDAY -> name.equals("vibrance") ? 0.05F : neutral;
            case DUSK -> switch (name) {
                case "saturation" -> 0.08F;
                case "vibrance" -> 0.18F;
                case "temperature" -> 0.14F;
                case "blue_strength" -> 1.05F;
                default -> neutral;
            };
            case NIGHT -> switch (name) {
                case "vibrance" -> 0.06F;
                case "temperature" -> -0.06F;
                default -> neutral;
            };
            case ALL_DAY -> neutral;
        };
    }

    /** The set of sliders the page shows right now. */
    public static GradeTime shownGradeTime() {
        GradeSchedule schedule = GRADE_SCHEDULE.get();
        return schedule == GradeSchedule.BY_TIME_OF_DAY ? editedGradeTime : GradeTime.ALL_DAY;
    }

    // Helpers

    private static BoolOption pixelLock(String part, String page, String section) {
        return BoolOption.builder("general.pixel_lock." + part, true)
                .at(page, section).feature("pixel_lock").needs(PIXEL_LOCK).register();
    }

    private static BoolOption farWaterExtra(String id, Cost cost) {
        return BoolOption.builder(id, false)
                .at(Pages.WATER, "far_terrain").feature("far_water_details").cost(cost).tag("litelights.tag.experimental").register();
    }

    private static BoolOption ambient(String name) {
        return BoolOption.builder("particles." + name, true)
                .at(Pages.PARTICLES, "ambient").cost(Cost.LOW).feature("ambient_particles")
                .enabledWhen(LiteLightsOptions::particlesOn).register();
    }

    /** A Mac troubleshooting part, on by default; it means nothing while vanilla terrain shading is on. */
    private static BoolOption terrainPart(String name) {
        return BoolOption.builder("general.mac.terrain_" + name, true)
                .at(Pages.GENERAL, "compatibility/mac")
                .enabledWhen(() -> !LiteLightsOptions.VANILLA_TERRAIN_SHADING.on()).register();
    }

    private static BoolOption end(String name, Cost cost) {
        return BoolOption.builder("dimensions.end." + name, true)
                .at(Pages.DIMENSIONS, "end").cost(cost).feature("end_" + name).register();
    }

    private static FloatOption glintSlider(String id, BooleanSupplier enabled) {
        return FloatOption.builder(id, 0.5F, 0.0F, 1.0F, 0.01F)
                .at(Pages.LIGHTING, "glint").feature("glint").enabledWhen(enabled).register();
    }

    /** Whether the water's glint sliders do anything: glint on water, or under it. */
    public static boolean waterGlint() {
        return GLINT_WATER.on() || SUBMERGED_GLINT.on();
    }

    /** Whether the ground's glint sliders do anything: glint on any of the other materials. */
    public static boolean groundGlint() {
        return GLINT_ICE_SNOW.on() || GLINT_METAL_GLASS.on() || GLINT_SAND_STONE.on() || GLINT_ORES.on();
    }

    /** Whether any glint is on: above water by material, or under water. */
    public static boolean anyGlint() {
        return GLINT_WATER.on() || GLINT_ICE_SNOW.on() || GLINT_METAL_GLASS.on() || GLINT_SAND_STONE.on() || GLINT_ORES.on()
            || SUBMERGED_GLINT.on();
    }

    /**
     * Each storm type's rows, under a sub-heading of its own in the Storms section. They share
     * their labels ("Fog density", "Cloud particles"); the sub-heading names the storm. Rain mist
     * is the cloud of rain, thunderstorms, snow and blizzards, so those rows need Mist in rain and
     * snow; sandstorms' rows need Sandstorms.
     */
    private static Map<StormType, StormRows> registerStorms() {
        Map<StormType, StormRows> storms = new EnumMap<>(StormType.class);
        for (StormType type : StormType.values()) {
            String name = EnumOption.storedName(type);
            String id = "weather.storms." + name;
            String section = "storms/" + name;
            boolean sandy = type == StormType.SANDSTORM || type == StormType.HABOOB;
            BooleanSupplier stormShows = sandy ? () -> SANDSTORMS.on() : () -> true;
            // Rain, snow and sandstorms start with light fog, their thunder variants with the usual
            // fog (user, 2026-11-16).
            boolean thundery = type == StormType.THUNDERSTORM || type == StormType.BLIZZARD || type == StormType.HABOOB;

            EnumOption<StormFog> fog = EnumOption.builder(id + ".fog", StormFog.class, thundery ? StormFog.NORMAL : StormFog.LIGHT)
                    .at(Pages.WEATHER, section).text("litelights.option.weather.storm_fog", "litelights.option." + id + ".fog.description")
                    .enabledWhen(stormShows).register();
            EnumOption.Builder<StormClouds> clouds = EnumOption.builder(id + ".clouds", StormClouds.class, StormClouds.FOLLOW_DENSITY)
                    .at(Pages.WEATHER, section).text("litelights.option.weather.storm_clouds", "litelights.option." + id + ".clouds.description")
                    .cost(Cost.LOW).choiceCost(StormClouds.OFF, Cost.FREE)
                    .enabledWhen(() -> particlesOn() && stormShows.getAsBoolean());
            if (!sandy) {
                clouds.needs(RAIN_MIST);
            }
            storms.put(type, new StormRows(fog, clouds.register()));
        }
        return Collections.unmodifiableMap(storms);
    }

    private static EnumOption<SpawnDistance> distance(String id, String page, String section) {
        return EnumOption.builder(id, SpawnDistance.class, SpawnDistance.NEAR)
                .at(page, section).cost(Cost.FREE).choiceCost(SpawnDistance.MEDIUM, Cost.LOW)
                .choiceCost(SpawnDistance.FAR, Cost.MEDIUM).choiceCost(SpawnDistance.VERY_FAR, Cost.MEDIUM)
                .enabledWhen(LiteLightsOptions::particlesOn).register();
    }

    /** Only matters past Near, where Fill draws more and costs more. */
    private static EnumOption<DistanceFill> distanceFill(String id, String page, String section, EnumOption<SpawnDistance> distance) {
        return EnumOption.builder(id, DistanceFill.class, DistanceFill.SPREAD)
                .at(page, section).cost(Cost.FREE).choiceCost(DistanceFill.FILL, Cost.HIGH)
                .enabledWhen(() -> particlesOn() && distance.get() != SpawnDistance.NEAR).register();
    }

    private static boolean anyMirrors() {
        return WATER_MIRRORS.on() || ICE_MIRRORS.on();
    }

    /** Foam motion moves the edge foam and the foam where falling water lands, so it matters while either is on. */
    private static boolean anyFoam() {
        return EDGE_FOAM.on() || ENHANCED_WATER.on();
    }

    private static boolean particlesOn() {
        return PARTICLE_DENSITY.get() != ParticleDensity.OFF;
    }
}
