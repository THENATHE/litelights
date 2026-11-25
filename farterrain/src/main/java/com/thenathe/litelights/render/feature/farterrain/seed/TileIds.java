package com.thenathe.litelights.render.feature.farterrain.seed;

import com.thenathe.litelights.render.feature.farterrain.land.BlockKinds;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How predicted blocks and biomes become a tile's ids and kinds: in the game, the dimension's
 * {@code BlockPalette} and {@code BlockLooks}; in tests, a table.
 */
public interface TileIds extends BlockKinds {
    int idOf(BlockState state);

    short biomeIdOf(Holder<Biome> biome);
}
