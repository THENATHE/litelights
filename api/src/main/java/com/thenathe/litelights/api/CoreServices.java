package com.thenathe.litelights.api;

/** What core gives the modules, installed once through {@link LiteLightsApi#install}. */
public record CoreServices(FrameAccess frame, SettingsAccess settings, FeatureStates features, RenderPasses passes,
                           ClimateAccess climate, ModuleSettings rows, WorldAccess world, NearTerrain nearTerrain) {
}
