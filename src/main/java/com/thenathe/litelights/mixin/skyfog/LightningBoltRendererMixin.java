package com.thenathe.litelights.mixin.skyfog;

import com.mojang.blaze3d.vertex.PoseStack;
import com.thenathe.litelights.render.feature.lightning.BoltLife;
import com.thenathe.litelights.render.feature.lightning.BranchingBolts;
import com.thenathe.litelights.render.feature.lightning.CloudLightning;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.client.renderer.entity.state.LightningBoltRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's lightning bolts (storm099): each one seen lights the clouds above it
 * ({@link CloudLightning}), and is drawn as a branching strike ({@link BranchingBolts}) unless that
 * is off, when vanilla draws it as always.
 */
@Mixin(LightningBoltRenderer.class)
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class LightningBoltRendererMixin extends EntityRenderer {
    private LightningBoltRendererMixin(EntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * The whole height of the strike for culling, not the bolt's tiny box on the ground: a bolt
     * whose foot is behind a hill still towers over it, but culling by the foot's chunk section
     * (as Sodium does) hid all of it.
     */
    @Override
    protected AABB getBoundingBoxForCulling(Entity entity, float partialTick) {
        if (!(entity instanceof LightningBolt) || !BranchingBolts.drawsFarther()) {
            return super.getBoundingBoxForCulling(entity, partialTick);
        }
        return BranchingBolts.cullingBox(entity.getX(), entity.getY(), entity.getZ());
    }
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LightningBolt;Lnet/minecraft/client/renderer/entity/state/LightningBoltRenderState;F)V",
        at = @At("TAIL"))
    private void litelights$extractLife(LightningBolt bolt, LightningBoltRenderState state, float partialTick, CallbackInfo info) {
        int life = ((LightningBoltAccessor) bolt).litelights$life();
        ((BoltLife) state).litelights$setLife(life, partialTick);
        CloudLightning.get().boltSeen(bolt.getId(), bolt.getX(), bolt.getZ(), life >= 0);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LightningBoltRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"), cancellable = true)
    private void litelights$submitBranching(LightningBoltRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                            CameraRenderState camera, CallbackInfo info) {
        if (BranchingBolts.submit(state, poseStack, collector, camera)) {
            info.cancel();
        }
    }
}
