package com.thenathe.litelights.render.api;

import org.jspecify.annotations.Nullable;

/**
 * Render features that can fail on their own and report it on their settings row. Each names the
 * settings feature (see docs/SETTINGS-API.md) whose rows show its state, if it has one.
 */
public enum RenderFeature {
    SHADOWS("shadows"),
    MOB_SHADOWS("mob_shadows"),
    MATERIALS(null),
    TERRAIN_SHADING("shadows"),
    SODIUM_TERRAIN("shadows"),
    ENTITY_SHADING("shadows"),
    POST_PROCESSING(null),
    PARTICLES("particles"),
    SKY_EVENTS("weather.polar_lights"),
    /** Weather on glass: its frame values; the drawing itself is part of the terrain shading. */
    GLASS_WEATHER("glass_weather"),
    /** Puddles: their frame values; the drawing itself is part of the terrain shading. */
    PUDDLES("weather.puddles"),
    /** Snowy leaves and ground: their frame values; the drawing itself is part of the terrain shading. */
    SNOWY_LEAVES("weather.snowy_leaves"),
    /** Visual snowlogging: its state and the snow added to chunk meshes; the shading is part of the terrain shading. */
    VISUAL_SNOW("visual_snow"),
    BLOOM("bloom"),
    LIGHT_RAYS("light_rays"),
    UNDERWATER_RAYS("underwater_rays"),
    COLOUR_GRADING("colour_grading"),
    FXAA("fxaa"),
    TAA("taa"),
    ENCHANTMENT_OUTLINES("outlines"),
    WATER("water.palette"),
    LARGE_WATER_TEXTURES("large_water_textures"),
    UNDERWATER_BLUR("water.underwater_blur"),
    WATER_SPLASHES("water.jump_splash"),
    SKY("sky"),
    ATMOSPHERIC_FOG("atmospheric_fog"),
    CLOUDS("clouds"),
    DYNAMIC_CLOUDS("dynamic_clouds"),
    /** Flashes inside the clouds and the branching bolts (storm099). */
    LIGHTNING("lightning"),
    /** Far shadows on LiteLights' own land past the shadow map (the far terrain module). */
    FAR_SHADOWS("far_shadows"),
    /** LiteLights' own far terrain, the land past the render distance (the far terrain module). */
    FAR_TERRAIN("far_terrain"),
    WAKES("water.wakes"),
    HEAT_SHIMMER("heat_shimmer");

    private final @Nullable String settingsFeature;

    RenderFeature(@Nullable String settingsFeature) {
        this.settingsFeature = settingsFeature;
    }

    /** The settings feature id whose rows show this feature's state, or null. */
    public @Nullable String settingsFeature() {
        return settingsFeature;
    }
}
