package com.thenathe.litelights.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.thenathe.litelights.render.compat.SodiumSupport;
import com.thenathe.litelights.render.feature.water.WaterBindings;
import com.thenathe.litelights.render.pipeline.LiteBindings;
import com.thenathe.litelights.render.pipeline.PipelineVariants;
import com.thenathe.litelights.render.pipeline.ShaderRedirects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every render pipeline, vanilla's, Sodium's or another mod's, is built here. Adds LiteLights'
 * bind groups (the shared one and water's) so their uniforms are available to any shader, swaps in LiteLights' shaders where it
 * has one, and records the pipeline so derived variants can be made from its compiled form.
 */
@Mixin(RenderPipeline.Builder.class)
abstract class RenderPipelineBuilderMixin {
    @Shadow
    @Final
    private Map<ShaderType, Identifier> shaders;

    @Shadow
    private Optional<Set<BindGroupLayout>> bindGroupLayouts;

    @Shadow
    @Final
    private ColorTargetState[] colorTargetStates;

    @Shadow
    public abstract RenderPipeline.Builder withBindGroupLayout(BindGroupLayout bindGroupLayout);

    @Shadow
    public abstract RenderPipeline.Builder withShaderDefine(String key);

    @Inject(method = "build", at = @At("HEAD"))
    private void litelights$addBindingsAndShaders(CallbackInfoReturnable<RenderPipeline> callback) {
        withBindGroupLayout(LiteBindings.LAYOUT);
        withBindGroupLayout(WaterBindings.LAYOUT);
        if (PipelineVariants.isBuildingDerived()) {
            return;
        }
        boolean sodiumTerrain = false;
        boolean redirected = false;
        for (Map.Entry<ShaderType, Identifier> entry : shaders.entrySet()) {
            Identifier replacement = ShaderRedirects.redirect(entry.getKey(), entry.getValue());
            if (replacement == null) {
                continue;
            }
            if (isSodiumTerrain(entry.getValue())) {
                if (!sodiumPipelineLooksRight()) {
                    continue;
                }
                sodiumTerrain = true;
            }
            entry.setValue(replacement);
            redirected = true;
        }
        if (sodiumTerrain && SodiumSupport.hasCameraOffsetInGlobals()) {
            withShaderDefine("LITELIGHTS_SODIUM_CAMERA_OFFSET");
        }
        if (redirected && !blends()) {
            // Its alpha is never blended, so near_mark.glsl may use all of it for the near mark.
            withShaderDefine("LITELIGHTS_OPAQUE_OUTPUT");
        }
    }

    /** Whether the main colour target blends; an unset target is drawn with the default, which doesn't. */
    private boolean blends() {
        ColorTargetState target = colorTargetStates[0];
        return target != null && target.blendFunction().isPresent();
    }

    @ModifyReturnValue(method = "build", at = @At("RETURN"))
    private RenderPipeline litelights$register(RenderPipeline pipeline) {
        PipelineVariants.register(pipeline);
        return pipeline;
    }

    private static boolean isSodiumTerrain(Identifier shader) {
        return shader.getNamespace().equals("sodium");
    }

    /** LiteLights' Sodium shader reads the block atlas and Sodium's globals by these names. */
    private boolean sodiumPipelineLooksRight() {
        boolean hasGlobals = false;
        boolean hasAtlas = false;
        for (BindGroupLayout layout : bindGroupLayouts.orElse(Set.of())) {
            for (BindGroupLayout.UniformDescription uniform : layout.uniforms()) {
                hasGlobals |= uniform.name().equals("u_Globals");
                hasAtlas |= uniform.name().equals("u_BlockTex");
            }
        }
        if (!hasGlobals || !hasAtlas) {
            SodiumSupport.reportPipelineMismatch("missing u_Globals or u_BlockTex");
            return false;
        }
        return true;
    }
}
