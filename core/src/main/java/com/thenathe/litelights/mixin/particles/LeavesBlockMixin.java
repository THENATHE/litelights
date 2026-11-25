package com.thenathe.litelights.mixin.particles;

import com.thenathe.litelights.render.feature.particles.FallingLeaves;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every leaf block's animation tick, which vanilla runs for random blocks around the player: in wind,
 * and for leaves that drop none of their own, FallingLeaves may add a falling leaf.
 */
@Mixin(LeavesBlock.class)
abstract class LeavesBlockMixin {
    @Inject(method = "animateTick", at = @At("TAIL"))
    private void litelights$windShedsLeaves(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo callback) {
        FallingLeaves.afterLeavesTick((LeavesBlock) (Object) this, state, level, pos);
    }
}
