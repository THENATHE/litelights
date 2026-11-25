package com.thenathe.litelights.render.pipeline;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.compat.SodiumSupport;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The shaders LiteLights replaces. Pipelines keep their own definitions (vertex formats, defines,
 * blending); only the shader source they compile is swapped for the LiteLights version, which
 * takes the same inputs and defines and adds the LiteLights shading.
 *
 * <p>Swapping the shader id at pipeline build time, rather than overriding the vanilla shader
 * files through a resource pack, means no pack order can silently drop LiteLights' shaders, and a
 * resource pack that overrides vanilla's terrain shader cannot break ours. If a replaced shader
 * fails to compile, the pipeline is compiled again with the original shader (see
 * {@link PipelineVariants#fallbackFor}).
 */
public final class ShaderRedirects {
    private static final Map<Identifier, Identifier> VANILLA = Map.of(
        Identifier.withDefaultNamespace("core/terrain"), LiteLightsClient.id("core/terrain"),
        Identifier.withDefaultNamespace("core/entity"), LiteLightsClient.id("core/entity"),
        Identifier.withDefaultNamespace("core/item"), LiteLightsClient.id("core/item"),
        Identifier.withDefaultNamespace("core/block"), LiteLightsClient.id("core/block"),
        Identifier.withDefaultNamespace("core/clouds"), LiteLightsClient.id("core/clouds")
    );
    /** Sodium's single chunk shader, used for every terrain pass. */
    private static final Identifier SODIUM_TERRAIN = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
    private static final Identifier LITE_SODIUM_TERRAIN = LiteLightsClient.id("sodium/block_layer_opaque");

    private ShaderRedirects() {
    }

    /** The replacement for a shader, or null to keep it. */
    public static @Nullable Identifier redirect(ShaderType type, Identifier shader) {
        Identifier vanilla = VANILLA.get(shader);
        if (vanilla != null) {
            return vanilla;
        }
        if (shader.equals(SODIUM_TERRAIN) && SodiumSupport.shaderOverrideEnabled()) {
            return LITE_SODIUM_TERRAIN;
        }
        return null;
    }

    public static boolean isLiteShader(Identifier shader) {
        return shader.getNamespace().equals(LiteLightsClient.MOD_ID);
    }

    /** The original shader a LiteLights replacement stands in for. */
    public static @Nullable Identifier original(Identifier liteShader) {
        if (liteShader.equals(LITE_SODIUM_TERRAIN)) {
            return SODIUM_TERRAIN;
        }
        for (Map.Entry<Identifier, Identifier> entry : VANILLA.entrySet()) {
            if (entry.getValue().equals(liteShader)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
