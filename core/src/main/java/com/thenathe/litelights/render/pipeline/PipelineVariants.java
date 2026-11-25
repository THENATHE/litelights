package com.thenathe.litelights.render.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.RenderFeature;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Pipelines derived from other pipelines:
 *
 * <ul>
 *   <li><b>Shadow-caster variants</b> of entity, item and block-model pipelines, for the mob shadow
 *   map. Same shaders, vertex formats and bindings, compiled with {@code LITELIGHTS_SHADOW_CASTER},
 *   no colour target, standard depth and no culling.</li>
 *   <li><b>Fallbacks</b>: the same pipeline with its original shaders, used when a LiteLights
 *   replacement shader fails to compile, so a shader bug costs the shading and not the game.</li>
 * </ul>
 *
 * Every pipeline built anywhere is recorded by location here, because a render pass only sees the
 * compiled form, and the compiled form only knows its location. Locations aren't unique (vanilla's
 * {@code ITEM_CUTOUT} and {@code ITEM_CUTOUT_GLINT} share one, with different defines), so a location
 * can hold several pipelines, and {@link #sourceOf} tells them apart by their compiled form.
 */
public final class PipelineVariants {
    private static final Map<String, List<RenderPipeline>> BY_LOCATION = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, Optional<RenderPipeline>> CASTER_VARIANTS = new ConcurrentHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> FALLBACKS = new ConcurrentHashMap<>();
    /** Set while this class builds a pipeline, so the build hook leaves its shaders alone. */
    private static final ThreadLocal<Boolean> BUILDING_DERIVED = ThreadLocal.withInitial(() -> false);

    /** Standard depth: the shadow maps clear to 1 and keep the nearest caster. */
    private static final DepthStencilState CASTER_DEPTH = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true);

    private PipelineVariants() {
    }

    public static boolean isBuildingDerived() {
        return BUILDING_DERIVED.get();
    }

    public static void register(RenderPipeline pipeline) {
        BY_LOCATION.compute(pipeline.getLocation().toString(), (location, known) -> withRegistered(known, pipeline));
    }

    /**
     * The pipelines known at a location with one more added. A pipeline built again with the same
     * definition (a mod rebuilding its pipelines on reload) replaces the old one, so the list only
     * grows for pipelines that really differ.
     */
    static List<RenderPipeline> withRegistered(@Nullable List<RenderPipeline> known, RenderPipeline added) {
        List<RenderPipeline> pipelines = new ArrayList<>();
        if (known != null) {
            for (RenderPipeline pipeline : known) {
                if (!sameDefinition(pipeline, added)) {
                    pipelines.add(pipeline);
                }
            }
        }
        pipelines.add(added);
        return List.copyOf(pipelines);
    }

    private static boolean sameDefinition(RenderPipeline first, RenderPipeline second) {
        return first.getShaders().equals(second.getShaders())
            && first.getShaderDefines().equals(second.getShaderDefines())
            && first.getVertexFormatBindings().equals(second.getVertexFormatBindings());
    }

    /** The pipeline a compiled pipeline was built from, or null if it isn't known. */
    public static @Nullable RenderPipeline sourceOf(FrontendRenderPipeline compiled) {
        List<RenderPipeline> candidates = BY_LOCATION.get(compiled.name());
        if (candidates == null) {
            return null;
        }
        return pickSource(candidates, candidate -> RenderSystem.getCompiledPipelineNullable(candidate) == compiled);
    }

    /**
     * Picks a compiled pipeline's source among the pipelines sharing its location. The lone pipeline
     * at a location needs no check; otherwise each is asked whether it compiled to this pipeline.
     */
    static @Nullable RenderPipeline pickSource(List<RenderPipeline> candidates, Predicate<RenderPipeline> compiledToThis) {
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        for (RenderPipeline candidate : candidates) {
            if (compiledToThis.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The depth-only variant of a pipeline drawn with one of LiteLights' entity, item or block
     * shaders, or null for anything else (text, beams, particles), which casts no mob shadow.
     */
    public static @Nullable RenderPipeline casterVariant(RenderPipeline pipeline) {
        return CASTER_VARIANTS.computeIfAbsent(pipeline, PipelineVariants::createCasterVariant).orElse(null);
    }

    private static Optional<RenderPipeline> createCasterVariant(RenderPipeline source) {
        Identifier vertex = source.getShaders().get(ShaderType.VERTEX);
        if (vertex == null || !castsMobShadow(vertex)) {
            return Optional.empty();
        }
        ShaderDefines.Builder defines = ShaderDefines.builder();
        ShaderDefines original = source.getShaderDefines();
        original.values().forEach((key, value) -> {
            if (!key.startsWith("OIT")) {
                defines.define(key, value);
            }
        });
        for (String flag : original.flags()) {
            if (!flag.startsWith("OIT")) {
                defines.define(flag);
            }
        }
        defines.define("LITELIGHTS_SHADOW_CASTER");
        Identifier location = LiteLightsClient.id("shadow_caster/" + source.getLocation().getNamespace() + "/" + source.getLocation().getPath());
        RenderPipeline variant = build(source, source.getShaders(), defines.build(), new ColorTargetState[0], CASTER_DEPTH, false, location);
        PipelineOwners.claim(RenderFeature.MOB_SHADOWS, variant);
        return Optional.of(variant);
    }

    private static boolean castsMobShadow(Identifier vertexShader) {
        if (!ShaderRedirects.isLiteShader(vertexShader)) {
            return false;
        }
        String path = vertexShader.getPath();
        return path.equals("core/entity") || path.equals("core/item") || path.equals("core/block");
    }

    /**
     * The pipeline with LiteLights' replacement shaders swapped back for the originals, or null if it
     * uses none (LiteLights' own pipelines have no original to fall back to).
     */
    public static @Nullable RenderPipeline fallbackFor(RenderPipeline pipeline) {
        boolean replacesAShader = pipeline.getShaders().values().stream().anyMatch(shader -> ShaderRedirects.original(shader) != null);
        if (!replacesAShader) {
            return null;
        }
        return FALLBACKS.computeIfAbsent(pipeline, PipelineVariants::createFallback);
    }

    private static RenderPipeline createFallback(RenderPipeline source) {
        Map<ShaderType, Identifier> shaders = new EnumMap<>(ShaderType.class);
        source.getShaders().forEach((type, shader) -> {
            Identifier original = ShaderRedirects.isLiteShader(shader) ? ShaderRedirects.original(shader) : null;
            shaders.put(type, original != null ? original : shader);
        });
        List<@Nullable ColorTargetState> targets = source.getColorTargetStates();
        Identifier location = LiteLightsClient.id("fallback/" + source.getLocation().getNamespace() + "/" + source.getLocation().getPath());
        return build(source, shaders, source.getShaderDefines(), targets.toArray(new ColorTargetState[0]),
            source.getDepthStencilState(), source.isCull(), location);
    }

    private static RenderPipeline build(
        RenderPipeline source,
        Map<ShaderType, Identifier> shaders,
        ShaderDefines defines,
        @Nullable ColorTargetState[] colorTargets,
        @Nullable DepthStencilState depth,
        boolean cull,
        Identifier location
    ) {
        List<@Nullable VertexFormat> formats = source.getVertexFormatBindings();
        RenderPipeline.Snippet snippet = new RenderPipeline.Snippet(
            Map.copyOf(shaders),
            Optional.of(defines),
            Optional.of(List.<BindGroupLayout>copyOf(source.getBindGroupLayouts())),
            colorTargets,
            colorTargets.length,
            Optional.ofNullable(depth),
            Optional.of(source.getPolygonMode()),
            Optional.of(cull),
            formats.toArray(new VertexFormat[0]),
            Optional.of(source.getPrimitiveTopology()),
            source.pushConstantSize()
        );
        BUILDING_DERIVED.set(true);
        try {
            return RenderPipeline.builder(snippet).withLocation(location).build();
        } finally {
            BUILDING_DERIVED.set(false);
        }
    }
}
