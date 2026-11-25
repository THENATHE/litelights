package com.thenathe.litelights.render.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.thenathe.litelights.api.DimensionAspect;
import com.thenathe.litelights.api.FarTerrainSettings;
import com.thenathe.litelights.api.FarTerrainSettings.FarShadowMode;
import com.thenathe.litelights.api.ModuleFeature;
import com.thenathe.litelights.render.RenderSettings;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The API's names for core's features, switches and settings stay in step with core's own. */
class CoreApiTest {
    @Test
    void everyModuleFeatureIsACoreRenderFeature() {
        assertEquals(ModuleFeature.values().length, CoreFeatureStates.renderFeatures().size());
        CoreFeatureStates.renderFeatures().forEach((feature, core) -> assertEquals(feature.name(), core.name()));
    }

    @Test
    void everyDimensionAspectIsACoreAspect() {
        assertEquals(DimensionAspect.values().length, CoreFeatureStates.aspects().size());
    }

    @Test
    void farShadowModesMatchTheSettings() {
        assertEquals(Arrays.stream(RenderSettings.FarShadows.values()).map(Enum::name).toList(),
            Arrays.stream(FarShadowMode.values()).map(Enum::name).toList());
    }

    @Test
    void farTerrainSettingsCarryTheSnapshotsValues() {
        RenderSettings defaults = RenderSettings.DEFAULTS;
        FarTerrainSettings settings = CoreSettingsAccess.farTerrainOf(defaults);
        assertEquals(defaults.shadows().enabled(), settings.shadows());
        assertEquals(defaults.shadows().farShadows().name(), settings.farShadows().name());
        assertEquals(defaults.shadows().reach(), settings.shadowReach());
    }

    @Test
    void aSnapshotIsConvertedOnce() {
        CoreSettingsAccess access = new CoreSettingsAccess();
        FarTerrainSettings first = access.farTerrain(RenderSettings.DEFAULTS);
        assertSame(first, access.farTerrain(RenderSettings.DEFAULTS), "reading the same snapshot again allocates nothing");
    }
}
