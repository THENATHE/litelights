package com.thenathe.litelights.render.modules;

import com.thenathe.litelights.api.FarTerrainSettings;
import com.thenathe.litelights.api.FarTerrainSettings.FarShadowMode;
import com.thenathe.litelights.api.SettingsAccess;
import com.thenathe.litelights.api.VisualSnowSettings;
import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettingsAccess;
import org.jspecify.annotations.Nullable;

/**
 * Far terrain's share of each settings snapshot. Made again only when a new snapshot is published,
 * so reading it every frame allocates nothing. Render thread only.
 */
final class CoreSettingsAccess implements SettingsAccess {
    private @Nullable RenderSettings madeFrom;
    private @Nullable FarTerrainSettings farTerrain;
    private @Nullable VisualSnowSettings visualSnow;

    @Override
    public FarTerrainSettings farTerrain() {
        return farTerrain(RenderSettingsAccess.current());
    }

    @Override
    public VisualSnowSettings visualSnow() {
        // As saved, whatever the Visual snow switch: far snow is far terrain's own row (far-snow-115).
        VisualSnowSettings made = visualSnow;
        boolean leafTops = LiteLightsOptions.SNOWY_LEAVES_LOOK.get() == LiteLightsOptions.SnowyLeavesLook.TOPS;
        boolean grass = LiteLightsOptions.SNOWY_GRASS.on();
        if (made == null || made.leafTops() != leafTops || made.grass() != grass) {
            made = new VisualSnowSettings(leafTops, grass);
            visualSnow = made;
        }
        return made;
    }

    @Override
    public FarTerrainSettings farTerrainDefaults() {
        return farTerrainOf(RenderSettings.DEFAULTS);
    }

    FarTerrainSettings farTerrain(RenderSettings settings) {
        FarTerrainSettings made = farTerrain;
        if (made == null || settings != madeFrom) {
            made = farTerrainOf(settings);
            farTerrain = made;
            madeFrom = settings;
        }
        return made;
    }

    static FarTerrainSettings farTerrainOf(RenderSettings settings) {
        RenderSettings.Shadows shadows = settings.shadows();
        return new FarTerrainSettings(shadows.enabled(), FarShadowMode.valueOf(shadows.farShadows().name()), shadows.reach());
    }
}
