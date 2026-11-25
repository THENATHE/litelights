package com.thenathe.litelights.render.modules;

import com.thenathe.litelights.api.ModuleSettings;
import java.lang.reflect.Constructor;

/**
 * The rows the modules declare at start-up, declared here for core's tests through core's own
 * implementation of the API, exactly as the modules do in game. Core doesn't compile against the
 * modules, so the far terrain module's rows are made by name; the module is on the test runtime
 * classpath. Once per test run: an option id can be registered only once.
 */
public final class ModuleRows {
    private static final String FAR_TERRAIN_ROWS = "com.thenathe.litelights.render.feature.farterrain.land.FarLandSettings";
    private static Object farTerrain;

    private ModuleRows() {
    }

    /** The far terrain module's rows (its FarLandSettings), declared once. */
    public static synchronized Object declareFarTerrain() {
        if (farTerrain == null) {
            try {
                Constructor<?> constructor = Class.forName(FAR_TERRAIN_ROWS).getConstructor(ModuleSettings.class);
                farTerrain = constructor.newInstance(new CoreModuleSettings());
            } catch (ReflectiveOperationException missing) {
                throw new IllegalStateException("The far terrain module's rows couldn't be declared", missing);
            }
        }
        return farTerrain;
    }
}
