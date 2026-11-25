package com.thenathe.litelights.render.feature.farterrain;

import com.thenathe.litelights.api.LiteLightsModule;

/** The far terrain module's entrypoint ({@code litelights:module} in fabric.mod.json). */
public final class FarTerrainModule implements LiteLightsModule {
    @Override
    public void initialize() {
        FarTerrain.register();
    }
}
