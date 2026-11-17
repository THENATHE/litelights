package com.thenathe.litelights.mixin.skyfog;

import com.thenathe.litelights.render.feature.lightning.BranchingBolts;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla draws a bolt only within 64 blocks (times the entity distance setting); a branching
 * bolt is drawn as far as the server sends bolts, so distant strikes show over the land.
 */
@Mixin(LightningBolt.class)
abstract class LightningBoltDistanceMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void litelights$fartherBolts(double distanceSquared, CallbackInfoReturnable<Boolean> info) {
        if (BranchingBolts.drawsFarther()) {
            double reach = BranchingBolts.REACH * Entity.getViewScale();
            info.setReturnValue(distanceSquared < reach * reach);
        }
    }
}
