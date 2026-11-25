package com.thenathe.litelights.api;

/**
 * The render features a module reports on (docs/ARCHITECTURE.md, "Feature states and errors").
 * Each is core's RenderFeature of the same name, which names the settings row that shows its state.
 */
public enum ModuleFeature {
    /** Far shadows on LiteLights' own land past the shadow map. */
    FAR_SHADOWS,
    /** LiteLights' own far terrain: the land drawn past the render distance. */
    FAR_TERRAIN
}
