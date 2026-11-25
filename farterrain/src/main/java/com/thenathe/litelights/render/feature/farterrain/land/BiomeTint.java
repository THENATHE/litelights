package com.thenathe.litelights.render.feature.farterrain.land;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * A stand-in for the level that answers every tint question with one stored biome, so the game's
 * own block colours (grass, leaves, water, vines and any mod's) tint far terrain as they tint the
 * land near by, LiteLights' natural foliage colours included. Everything else reads as empty.
 */
final class BiomeTint implements BlockAndTintGetter {
    private final Biome biome;

    BiomeTint(Biome biome) {
        this.biome = biome;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return resolver.getColor(biome, pos.getX(), pos.getZ());
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return EMPTY.cardinalLighting();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return EMPTY.getLightEngine();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return EMPTY.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return EMPTY.getFluidState(pos);
    }

    @Override
    public int getHeight() {
        return EMPTY.getHeight();
    }

    @Override
    public int getMinY() {
        return EMPTY.getMinY();
    }
}
