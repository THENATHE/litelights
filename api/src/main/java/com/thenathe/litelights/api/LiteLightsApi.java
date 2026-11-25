package com.thenathe.litelights.api;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * LiteLights' internal API: the one way a module reaches the rest of LiteLights. Core installs its
 * implementation as the client starts, before any module starts, and a module compiles against
 * this package only, so the build refuses any reach into core's classes. Every module ships in
 * the same jar and the same release, so the API can change freely.
 */
public final class LiteLightsApi {
    private static @Nullable CoreServices core;
    private static FarTerrainState farTerrain = FarTerrainState.NONE;

    private LiteLightsApi() {
    }

    /** Called once by core, before any module starts. */
    public static void install(CoreServices services) {
        if (core != null) {
            throw new IllegalStateException("LiteLights' core services are already installed");
        }
        core = Objects.requireNonNull(services);
    }

    public static FrameAccess frame() {
        return core().frame();
    }

    public static SettingsAccess settings() {
        return core().settings();
    }

    public static FeatureStates features() {
        return core().features();
    }

    public static RenderPasses passes() {
        return core().passes();
    }

    public static ClimateAccess climate() {
        return core().climate();
    }

    /** Declaring settings rows of a module's own. */
    public static ModuleSettings rows() {
        return core().rows();
    }

    public static WorldAccess world() {
        return core().world();
    }

    public static NearTerrain nearTerrain() {
        return core().nearTerrain();
    }

    /** Far terrain's state, for the rest of LiteLights to read; {@link FarTerrainState#NONE} without the module. */
    public static FarTerrainState farTerrain() {
        return farTerrain;
    }

    /** Called once by the far terrain module as it starts. */
    public static void provideFarTerrain(FarTerrainState state) {
        farTerrain = Objects.requireNonNull(state);
    }

    private static CoreServices core() {
        CoreServices services = core;
        if (services == null) {
            throw new IllegalStateException("LiteLights' core services aren't installed yet");
        }
        return services;
    }
}
