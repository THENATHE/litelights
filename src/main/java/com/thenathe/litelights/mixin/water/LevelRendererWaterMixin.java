package com.thenathe.litelights.mixin.water;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import com.thenathe.litelights.render.feature.particles.HazeDepth;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import com.thenathe.litelights.render.feature.water.WaterRenderer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies the opaque scene for the water shaders after the solid world is drawn and before
 * translucent terrain, and shades far terrain just before that copy (FarTerrain); TAA copies the
 * opaque depth at the same point (LightPost), and the haze its scene distance (HazeDepth). With
 * Improved Transparency the solid pass has already closed when the OIT passes start, so the copy
 * happens there. Without it, vanilla draws solid and translucent terrain
 * in one pass; the wrapper closes that pass at the boundary (the caller's close then does nothing,
 * as closing a pass twice is allowed), copies, and runs the translucent half in a new pass on the
 * same target. When no copy is wanted the method runs untouched.
 *
 * <p>Works the same with Sodium, which draws its terrain through these same methods.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererWaterMixin {
    @WrapMethod(method = "executeClassicTransparency")
    private void litelights$copySceneBeforeTranslucents(ChunkSectionsToRender chunkSectionsToRender,
                                                         FeatureRenderDispatcher.PreparedFrame featureFrame,
                                                         RenderPass renderPass, Operation<Void> original) {
        WaterRenderer water = WaterRenderer.get();
        FarTerrain farTerrain = FarTerrain.get();
        boolean copyScene = water.wantsSceneCopy();
        LightPost lightPost = LightPost.get();
        HazeDepth hazeDepth = HazeDepth.get();
        if (!copyScene && !farTerrain.wantsPassBeforeTranslucents() && !lightPost.wantsPassBeforeTranslucents() && !hazeDepth.wanted()) {
            original.call(chunkSectionsToRender, featureFrame, renderPass);
            return;
        }
        renderPass.close();
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            // Far terrain first, so water's copy (its reflections and what shows through it) sees it shaded.
            farTerrain.beforeTranslucents(mainTarget);
            lightPost.beforeTranslucents(mainTarget);
            hazeDepth.take(mainTarget);
            if (copyScene) {
                water.copyScene(mainTarget);
            }
        } finally {
            // Vanilla's pass is closed by now, so the translucent half runs in a new one whatever
            // happened above; each of those calls catches its own errors, this is the backstop.
            try (RenderPass translucentPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Main (translucent, LiteLights)", mainTarget.getColorTextureView(), Optional.empty(),
                mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(translucentPass);
                original.call(chunkSectionsToRender, featureFrame, translucentPass);
            }
        }
    }

    @Inject(method = "executeOit", at = @At("HEAD"))
    private void litelights$copySceneBeforeOit(ChunkSectionsToRender chunkSectionsToRender,
                                               FeatureRenderDispatcher.PreparedFrame featureFrame, CallbackInfo callback) {
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        FarTerrain.get().beforeTranslucents(mainTarget);
        LightPost.get().beforeTranslucents(mainTarget);
        HazeDepth.get().take(mainTarget);
        WaterRenderer water = WaterRenderer.get();
        if (water.wantsSceneCopy()) {
            water.copyScene(mainTarget);
        }
    }
}
