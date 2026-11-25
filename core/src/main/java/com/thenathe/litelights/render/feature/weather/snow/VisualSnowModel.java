package com.thenathe.litelights.render.feature.weather.snow;

import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A block's model with the visual snow added: wraps the models of the blocks that can take fake
 * snow (SnowHosts) as they bake, after every other mod's wrapping, and adds the snow's quads after
 * the block's own as its chunk mesh is built. With the visual snow off it hands everything to the
 * model it wraps and adds nothing, so meshes are exactly as before; the cost is one read of the
 * published state per such block per mesh.
 */
final class VisualSnowModel extends WrapperBlockStateModel {
    private final SnowHosts.Host host;

    VisualSnowModel(BlockStateModel wrapped, SnowHosts.Host host) {
        super(wrapped);
        this.host = host;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter region, BlockPos pos, BlockState state, RandomSource random,
                          Predicate<@Nullable Direction> cullTest) {
        super.emitQuads(emitter, region, pos, state, random, cullTest);
        SnowState snow = VisualSnow.state();
        if (!snow.drawsAny()) {
            return;
        }
        try {
            SnowGeometry.emit(snow, host, emitter, region, pos, state, random, cullTest);
        } catch (RuntimeException failure) {
            VisualSnow.meshFailed(failure);
        }
    }

    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter region, BlockPos pos, BlockState state, RandomSource random) {
        // The snow depends on the world around the block, so a mesh with it can't be shared.
        return VisualSnow.state().drawsAny() ? null : super.createGeometryKey(region, pos, state, random);
    }
}
