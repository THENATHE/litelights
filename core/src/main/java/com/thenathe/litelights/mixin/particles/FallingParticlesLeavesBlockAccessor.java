package com.thenathe.litelights.mixin.particles;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingParticlesLeavesBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's leaves that drop falling leaves: how often they drop one, and dropping one their own
 * way. In wind LiteLights drops a few more of exactly the same leaves (FallingLeaves).
 */
@Mixin(FallingParticlesLeavesBlock.class)
public interface FallingParticlesLeavesBlockAccessor {
    @Accessor("leafParticleChance")
    float litelights$leafParticleChance();

    @Invoker("spawnFallingLeavesParticle")
    void litelights$spawnFallingLeavesParticle(Level level, BlockPos pos, RandomSource random);
}
