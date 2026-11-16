package com.thenathe.litelights.render.feature.vegetation;

import com.mojang.blaze3d.systems.RenderSystem;
import com.thenathe.litelights.config.DimensionOptions.Aspect;
import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.compat.SodiumSupport;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameState;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Vegetation: grass, plants and leaves swaying in the wind (in the terrain vertex shaders, see
 * hooks/vegetation.glsl) and natural foliage colours (see {@link NaturalFoliageColors}).
 *
 * <p>Fills the vegetation half of {@code featureValuesD} each frame: {@code z} the switches that
 * are on ({@link #SWAY_GRASS} and so on, 0 for none, which makes the shader skip everything) and
 * {@code w} the amplitude scale.
 */
public final class VegetationModule {
    public static final int SWAY_GRASS = 1;
    public static final int SWAY_PLANTS = 2;
    public static final int SWAY_LEAVES = 4;

    private static final Feature SWAY = Feature.of("sway");

    private static volatile float amplitudeScale = 1.0f;
    private static @Nullable String swayProblemShown;
    private static boolean swayStateShown;

    private VegetationModule() {
    }

    public static void initialize() {
        LiteRenderEvents.FRAME_UPDATE.register(VegetationModule::onFrameUpdate);
        NaturalFoliageColors.initialize();
        MirroredQuads.register();
    }

    /** For QA pictures: exaggerates the sway so feet and seams are easy to judge. 1 is normal. */
    public static void setAmplitudeScale(float scale) {
        amplitudeScale = scale;
    }

    public static int switches(RenderSettings.Vegetation vegetation) {
        return (vegetation.swayingGrass() ? SWAY_GRASS : 0)
            | (vegetation.swayingPlants() ? SWAY_PLANTS : 0)
            | (vegetation.swayingLeaves() ? SWAY_LEAVES : 0);
    }

    private static void onFrameUpdate(FrameState frame, RenderSettings settings) {
        String problem = terrainShaderProblem();
        showSwayState(problem);
        // Sway counts as one of a modded dimension's effects, with its fog and ambient particles.
        boolean swayHere = problem == null && DimensionGate.allows(Aspect.EFFECTS);
        frame.featureValuesD.z = swayHere ? switches(settings.vegetation()) : 0;
        frame.featureValuesD.w = amplitudeScale;
    }

    /** Sway runs in LiteLights' terrain shaders; if they couldn't be used, neither can it. */
    private static @Nullable String terrainShaderProblem() {
        String problem = RenderFeatureStatus.problem(RenderFeature.TERRAIN_SHADING);
        if (problem == null) {
            problem = RenderFeatureStatus.problem(RenderFeature.SODIUM_TERRAIN);
        }
        if (problem == null && !SodiumSupport.isLoaded() && !RenderSystem.getDevice().getDeviceInfo().features().shaderDrawParameters()) {
            // Vanilla's terrain shader needs the draw's base vertex to find a vertex's corner
            // (see terrain.vsh); Sodium's vertices carry it.
            problem = "the graphics driver has no draw parameters, which sway needs without Sodium";
        }
        return problem;
    }

    private static void showSwayState(@Nullable String problem) {
        if (swayStateShown && Objects.equals(problem, swayProblemShown)) {
            return;
        }
        swayStateShown = true;
        swayProblemShown = problem;
        if (problem == null) {
            SWAY.markReady();
        } else {
            SWAY.markUnavailable("Sway can't run: " + problem);
        }
    }
}
