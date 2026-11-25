package com.thenathe.litelights.render.modules;

import com.thenathe.litelights.api.CoreServices;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsModule;
import com.thenathe.litelights.api.ModuleEvents;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Core's side of the internal API (docs/ARCHITECTURE.md, "Modules"): installs core's services for
 * the modules, then starts every module listed under {@code litelights:module} in fabric.mod.json.
 */
public final class ModuleHost {
    private static final CoreFrameAccess FRAME = new CoreFrameAccess();
    private static final CoreSettingsAccess SETTINGS = new CoreSettingsAccess();

    private ModuleHost() {
    }

    /** First thing as the client starts, so the API is there before anything could ask for it. */
    public static void installApi() {
        LiteLightsApi.install(new CoreServices(FRAME, SETTINGS, new CoreFeatureStates(), new CoreRenderPasses(), new CoreClimateAccess(),
            new CoreModuleSettings(), new CoreWorldAccess(), new CoreNearTerrain()));
    }

    /**
     * Starts the modules. Called where far terrain used to register itself among core's streams,
     * so the modules' frame update runs where far terrain's always did: after every core stream's,
     * before the per-dimension switches clear their bits.
     */
    public static void startModules() {
        LiteRenderEvents.FRAME_UPDATE.register((frame, settings) ->
            ModuleEvents.FRAME_UPDATE.invoker().onFrameUpdate(FRAME, SETTINGS.farTerrain(settings)));
        for (LiteLightsModule module : FabricLoader.getInstance().getEntrypoints(LiteLightsModule.ENTRYPOINT, LiteLightsModule.class)) {
            module.initialize();
        }
    }
}
