package com.thenathe.litelights.render.feature.farterrain.seed;

import com.thenathe.litelights.render.feature.farterrain.land.BlockKinds;
import com.thenathe.litelights.render.feature.farterrain.land.TileKey;
import com.thenathe.litelights.render.feature.farterrain.land.TileVoxels;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Predicts one far terrain tile from the seed: samples the game's terrain at the tile's level
 * ({@link TerrainColumns}), puts the stand-in trees on it ({@link Canopies}), and turns the
 * columns into the tile's voxels by the rule the store uses for land it has seen
 * ({@code LevelBuilder}): a voxel is solid if any block in it is, takes the block highest in it
 * (the one most open to the air above), keeps how far below its top its land really ends, and a
 * column takes the biome of its highest part.
 *
 * <p>Each level is sampled at its own stride, never built up from finer ones: level 0 is every
 * block column of its 32 by 32 blocks; level 1 every block column of its 64 by 64, two by two to a
 * voxel, exactly as the store builds a level from the one under it; from level 2 up a voxel takes
 * the four columns at its corners, one grid of 33 by 33 columns a tile, so a level-L tile costs
 * about the same whatever L is, and each level covers four times the ground of the one under it.
 *
 * <p>Light: open sky over each column's top and a little way into caves and under overhangs (the
 * light carried sideways and down from lit air, as the engine carries it), none in closed caves,
 * so the mesher leaves their faces out as it does for land it has seen. Water darkens a level a
 * block. No block light: nothing predicted gives off light but lava, whose own blocks the far
 * lights already find.
 *
 * <p>One per worker thread and set of inputs.
 */
public final class TerrainSampler {
    /** From this level up the columns stand on a grid of corners; below it every block column is sampled. */
    public static final int GRID_LEVEL = 2;

    private final TerrainColumns columns;
    private final TileIds ids;
    private final @Nullable Canopies canopies;
    private final int seaLevel;
    private final BlockState snowCover = Blocks.SNOW.defaultBlockState();
    private final Map<BlockState, Integer> idCache = new IdentityHashMap<>();
    private int[][] columnIds = new int[0][];
    private final IntArrayFIFOQueue lightQueue = new IntArrayFIFOQueue();

    public TerrainSampler(PredictionInputs inputs, int minY, int height, TileIds ids, @Nullable Canopies canopies) {
        this.columns = new TerrainColumns(inputs, minY, height);
        this.ids = ids;
        this.canopies = canopies;
        this.seaLevel = inputs.seaLevel();
    }

    /** The predicted tile for a key, every column known. */
    public TileVoxels sample(long key) {
        int level = TileKey.level(key);
        int voxel = 1 << level;
        int originX = TileKey.originX(key);
        int originZ = TileKey.originZ(key);
        if (level < GRID_LEVEL) {
            columns.sample(originX, originZ, TileVoxels.SIZE << level, 1, voxel);
        } else {
            columns.sample(originX, originZ, TileVoxels.SIZE + 1, voxel);
        }
        if (canopies != null) {
            canopies.grow(columns);
        }
        columns.freezeTops(seaLevel, snowCover);
        TileVoxels tile = new TileVoxels(level, columns.minY(), TileVoxels.heightFor(columns.height(), level));
        toIds();
        voxelize(tile);
        light(tile);
        return tile;
    }

    /** The sampled columns, for tests and readings: block rows from minY up. */
    TerrainColumns columns() {
        return columns;
    }

    TileIds ids() {
        return ids;
    }

    private void toIds() {
        int count = columns.columns.length;
        if (columnIds.length != count) {
            columnIds = new int[count][];
        }
        for (int i = 0; i < count; i++) {
            BlockState[] blocks = columns.columns[i];
            int[] row = columnIds[i];
            if (row == null || row.length != blocks.length) {
                row = new int[blocks.length];
                columnIds[i] = row;
            }
            BlockState last = null;
            int lastId = 0;
            for (int y = 0; y < blocks.length; y++) {
                BlockState state = blocks[y];
                if (state != last) {
                    last = state;
                    lastId = state.isAir() ? 0 : idCache.computeIfAbsent(state, ids::idOf);
                }
                row[y] = lastId;
            }
        }
    }

    private void voxelize(TileVoxels tile) {
        int level = tile.level;
        int voxel = 1 << level;
        int[] sources = new int[4];
        for (int vz = 0; vz < TileVoxels.SIZE; vz++) {
            for (int vx = 0; vx < TileVoxels.SIZE; vx++) {
                int count = sourcesOf(level, vx, vz, sources);
                int highest = Integer.MIN_VALUE;
                int highestSource = sources[0];
                for (int i = 0; i < count; i++) {
                    int top = columns.tops[sources[i]];
                    if (top > highest) {
                        highest = top;
                        highestSource = sources[i];
                    }
                }
                int column = TileVoxels.column(vx, vz);
                tile.biomes[column] = ids.biomeIdOf(columns.biomes[highestSource]);
                tile.setKnown(vx, vz);
                if (highest < 0) {
                    continue;
                }
                int topVoxel = Math.min(highest / voxel, tile.height - 1);
                for (int vy = topVoxel; vy >= 0; vy--) {
                    int bottom = vy * voxel;
                    int ceiling = Math.min(bottom + voxel, columns.height()) - 1;
                    int state = 0;
                    int stateRow = -1;
                    for (int i = 0; i < count; i++) {
                        int[] blocks = columnIds[sources[i]];
                        for (int row = ceiling; row > stateRow && row >= bottom; row--) {
                            int id = blocks[row];
                            if (id != 0 && ids.kind(id) != BlockKinds.AIR) {
                                state = id;
                                stateRow = row;
                                break;
                            }
                        }
                    }
                    if (state == 0) {
                        continue;
                    }
                    int index = TileVoxels.index(vx, vy, vz);
                    tile.states[index] = state;
                    if (level > 0) {
                        // How far below the voxel's top its land ends (TileVoxels.inset).
                        tile.light[index] = (byte) (voxel - (stateRow - bottom + 1));
                    }
                }
            }
        }
    }

    /** The sampled columns a voxel column is made of: one at level 0, its four blocks at level 1, its four corners above. */
    private int sourcesOf(int level, int vx, int vz, int[] sources) {
        if (level == 0) {
            sources[0] = vx + vz * TileVoxels.SIZE;
            return 1;
        }
        int size = columns.size;
        if (level == 1) {
            int x = vx * 2;
            int z = vz * 2;
            sources[0] = x + z * size;
            sources[1] = x + 1 + z * size;
            sources[2] = x + (z + 1) * size;
            sources[3] = x + 1 + (z + 1) * size;
            return 4;
        }
        sources[0] = vx + vz * size;
        sources[1] = vx + 1 + vz * size;
        sources[2] = vx + (vz + 1) * size;
        sources[3] = vx + 1 + (vz + 1) * size;
        return 4;
    }

    /**
     * Sky light: full over each column's top, a level less a block through water at level 0, and
     * carried from lit air into the air beside and below it, losing a level a block at level 0 and
     * half a voxel's blocks a step above it (coarse voxels average their cells' light, so an
     * overhang's underside stays lit at least a step in, as it does in land built from chunks).
     */
    private void light(TileVoxels tile) {
        int level = tile.level;
        int fall = level == 0 ? 1 : Math.max(1, (1 << level) / 2);
        for (int vz = 0; vz < TileVoxels.SIZE; vz++) {
            for (int vx = 0; vx < TileVoxels.SIZE; vx++) {
                boolean open = true;
                int waterDepth = 0;
                for (int vy = tile.height - 1; vy >= 0; vy--) {
                    int index = TileVoxels.index(vx, vy, vz);
                    int kind = ids.kind(tile.states[index]);
                    if (kind == BlockKinds.AIR) {
                        tile.light[index] = (byte) (open ? 0xF0 : 0);
                    } else {
                        if (kind == BlockKinds.WATER && open) {
                            waterDepth++;
                            if (level == 0) {
                                tile.light[index] = (byte) (Math.max(0, 15 - waterDepth) << 4);
                            }
                            continue;
                        }
                        open = false;
                    }
                }
            }
        }
        // Carried from lit air into dark air beside and below it.
        lightQueue.clear();
        for (int index = 0; index < tile.states.length; index++) {
            if (tile.states[index] == 0 && tile.skyLight(index) > fall) {
                lightQueue.enqueue(index);
            }
        }
        while (!lightQueue.isEmpty()) {
            int index = lightQueue.dequeueInt();
            int next = tile.skyLight(index) - fall;
            if (next <= 0) {
                continue;
            }
            int x = index % TileVoxels.SIZE;
            int z = (index / TileVoxels.SIZE) % TileVoxels.SIZE;
            int y = index / TileVoxels.COLUMNS;
            if (x > 0) {
                spread(tile, index - 1, next);
            }
            if (x < TileVoxels.SIZE - 1) {
                spread(tile, index + 1, next);
            }
            if (z > 0) {
                spread(tile, index - TileVoxels.SIZE, next);
            }
            if (z < TileVoxels.SIZE - 1) {
                spread(tile, index + TileVoxels.SIZE, next);
            }
            if (y > 0) {
                spread(tile, index - TileVoxels.COLUMNS, next);
            }
        }
    }

    private void spread(TileVoxels tile, int index, int light) {
        if (tile.states[index] == 0 && tile.skyLight(index) < light) {
            tile.light[index] = (byte) (light << 4);
            lightQueue.enqueue(index);
        }
    }
}
