package com.thenathe.litelights.render.pipeline;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.thenathe.litelights.render.api.RenderFeature;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Which render feature a pipeline with LiteLights shaders belongs to, so a shader that fails to
 * compile is reported on that feature's settings row and nowhere else.
 *
 * <p>Pipelines that swap in a LiteLights replacement for vanilla's or Sodium's shader belong to the
 * shading (the light model and shadows) without being claimed. Pipelines with LiteLights' own
 * shaders (post passes, the shadow casters) are claimed by the feature that builds them, before
 * they are first drawn; an unclaimed one counts as post-processing.
 */
public final class PipelineOwners {
    private static final Map<RenderPipeline, RenderFeature> CLAIMS = new ConcurrentHashMap<>();

    private PipelineOwners() {
    }

    public static void claim(RenderFeature feature, RenderPipeline... pipelines) {
        for (RenderPipeline pipeline : pipelines) {
            CLAIMS.put(pipeline, feature);
        }
    }

    /** The feature that owns a pipeline, or null if it uses no LiteLights shader. */
    public static @Nullable RenderFeature ownerOf(RenderPipeline pipeline) {
        RenderFeature claimed = CLAIMS.get(pipeline);
        if (claimed != null) {
            return claimed;
        }
        boolean usesLiteShader = false;
        for (Identifier shader : pipeline.getShaders().values()) {
            if (!ShaderRedirects.isLiteShader(shader)) {
                continue;
            }
            usesLiteShader = true;
            Identifier original = ShaderRedirects.original(shader);
            if (original != null) {
                return shadingFeatureFor(original);
            }
        }
        return usesLiteShader ? RenderFeature.POST_PROCESSING : null;
    }

    private static RenderFeature shadingFeatureFor(Identifier originalShader) {
        if (originalShader.getNamespace().equals("sodium")) {
            return RenderFeature.SODIUM_TERRAIN;
        }
        return switch (originalShader.getPath()) {
            case "core/terrain" -> RenderFeature.TERRAIN_SHADING;
            // Vanilla's cloud pipelines with LiteLights' cloud shader belong to the Clouds row.
            case "core/clouds" -> RenderFeature.CLOUDS;
            default -> RenderFeature.ENTITY_SHADING;
        };
    }
}
