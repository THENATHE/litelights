package com.thenathe.litelights.mixin.particles;

import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Fire's own flammability table: whether fire can catch on a block. Ember veins follow it, so they
 * run through exactly the blocks the game would let burn, modded ones included.
 */
@Mixin(FireBlock.class)
public interface FireBlockInvoker {
    @Invoker("canBurn")
    boolean litelights$canBurn(BlockState state);
}
