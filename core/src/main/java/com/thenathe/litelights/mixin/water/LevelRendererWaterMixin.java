package com.thenathe.litelights.mixin.water;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.thenathe.litelights.api.ModuleEvents;
import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import com.thenathe.litelights.render.feature.particles.HazeDepth;
import com.thenathe.litelights.render.feature.particles.ParticleSystem;
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
 * translucent terrain, and lets the modules (far terrain) draw just before that copy
 * (ModuleEvents.BEFORE_TRANSLUCENTS), then the Dynamic clouds, then the distant fireworks and the
 * rainbow over them (ParticleSystem's sky events), and over the copy just after it
 * (ModuleEvents.AFTER_SCENE_COPY: far water); TAA copies the
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
        ModuleEvents.BeforeTranslucents modules = ModuleEvents.BEFORE_TRANSLUCENTS.invoker();
        ModuleEvents.AfterSceneCopy overScene = ModuleEvents.AFTER_SCENE_COPY.invoker();
        boolean drawOverScene = overScene.wantsPassAfterSceneCopy();
        boolean copyScene = water.wantsSceneCopy() || (drawOverScene && overScene.wantsSceneCopy() && water.allowsSceneCopy());
        LightPost lightPost = LightPost.get();
        HazeDepth hazeDepth = HazeDepth.get();
        DynamicClouds clouds = DynamicClouds.get();
        ParticleSystem skyEvents = ParticleSystem.get();
        if (!copyScene && !drawOverScene && !modules.wantsPassBeforeTranslucents() && !lightPost.wantsPassBeforeTranslucents()
            && !hazeDepth.wanted() && !clouds.wantsPassBeforeTranslucents() && !skyEvents.wantsSkyEventsBeforeTranslucents()) {
            original.call(chunkSectionsToRender, featureFrame, renderPass);
            return;
        }
        renderPass.close();
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            // Far terrain first, so water's copy (its reflections and what shows through it) sees it shaded;
            // then the clouds, after far terrain's pass so it never takes a cloud for land; then the
            // fireworks and the rainbow, which read the depth of both.
            modules.beforeTranslucents(mainTarget);
            clouds.drawBeforeTranslucents(mainTarget);
            skyEvents.drawSkyEventsBeforeTranslucents(mainTarget);
            lightPost.beforeTranslucents(mainTarget);
            hazeDepth.take(mainTarget);
            if (copyScene) {
                water.copyScene(mainTarget);
            }
            if (drawOverScene) {
                overScene.afterSceneCopy(mainTarget);
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
        ModuleEvents.BEFORE_TRANSLUCENTS.invoker().beforeTranslucents(mainTarget);
        DynamicClouds.get().drawBeforeTranslucents(mainTarget);
        ParticleSystem.get().drawSkyEventsBeforeTranslucents(mainTarget);
        LightPost.get().beforeTranslucents(mainTarget);
        HazeDepth.get().take(mainTarget);
        WaterRenderer water = WaterRenderer.get();
        ModuleEvents.AfterSceneCopy overScene = ModuleEvents.AFTER_SCENE_COPY.invoker();
        boolean drawOverScene = overScene.wantsPassAfterSceneCopy();
        if (water.wantsSceneCopy() || (drawOverScene && overScene.wantsSceneCopy() && water.allowsSceneCopy())) {
            water.copyScene(mainTarget);
        }
        if (drawOverScene) {
            overScene.afterSceneCopy(mainTarget);
        }
    }
}
