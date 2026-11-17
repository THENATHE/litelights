package com.thenathe.litelights.mixin.render;

import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import com.thenathe.litelights.render.feature.particles.ParticleSystem;
import com.thenathe.litelights.render.feature.vegetation.SwingingLanterns;
import com.thenathe.litelights.render.feature.water.WaterRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the shadow caster store which sections changed, and water's column map, far terrain,
 * fire's marks and veins, the swinging lanterns and the heat shimmer's sources which blocks did.
 * These are the client level's own entry points for block and section updates, so they fire
 * whichever renderer (vanilla or Sodium) is listening downstream. Light updates arrive here too;
 * the caster hash filters them out.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void litelights$blockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int updateFlags, CallbackInfo callback) {
        if (oldState != newState) {
            LiteLightsRenderer.get().onBlockChanged(pos);
            WaterRenderer.get().onBlockChanged(pos, oldState, newState);
            FarTerrain.get().onBlockChanged(pos);
            ParticleSystem.get().onBlockChanged(pos, oldState, newState);
            SwingingLanterns.get().onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
            LightPost.get().onBlockChanged(pos, oldState, newState);
        }
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void litelights$blocksDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo callback) {
        LiteLightsRenderer.get().onBlockChanged(pos);
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void litelights$sectionDirty(int sectionX, int sectionY, int sectionZ, CallbackInfo callback) {
        LiteLightsRenderer.get().onSectionsDirty(sectionX - 1, sectionY - 1, sectionZ - 1, sectionX + 1, sectionY + 1, sectionZ + 1);
    }

    @Inject(method = "setSectionRangeDirty", at = @At("HEAD"))
    private void litelights$sectionRangeDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo callback) {
        LiteLightsRenderer.get().onSectionsDirty(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
