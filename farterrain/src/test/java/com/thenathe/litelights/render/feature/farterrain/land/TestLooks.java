package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * Block kinds and looks by a fixed table, for tests: 0 air, 1 stone, 2 grass, 3 water, 4 leaves,
 * 5 dirt, 6 a bottom slab, 7 a fence post, 8 a plant, 10 a torch (9 is CoversTest's snow).
 */
final class TestLooks implements FaceLooks {
    static final int AIR = 0;
    static final int STONE = 1;
    static final int GRASS = 2;
    static final int WATER = 3;
    static final int LEAVES = 4;
    static final int DIRT = 5;
    static final int SLAB = 6;
    static final int FENCE = 7;
    static final int PLANT = 8;
    static final int TORCH = 10;

    static final FarShape SLAB_SHAPE = FarShape.ofBoxes(new int[] {0, 0, 0, 16, 8, 16});
    static final FarShape FENCE_SHAPE = FarShape.ofBoxes(new int[] {6, 0, 6, 10, 16, 10});

    @Override
    public int kind(int state) {
        return switch (state) {
            case AIR, FENCE, PLANT, TORCH -> BlockKinds.AIR;
            case WATER -> BlockKinds.WATER;
            default -> BlockKinds.SOLID;
        };
    }

    @Override
    public int appearance(int state, int face) {
        return state * 8 + face + 1;
    }

    @Override
    public int tint(int state, int face, int biome) {
        return state == GRASS && face == FarQuad.UP ? 0x40A000 + biome : 0xFFFFFF;
    }

    @Override
    public FarShape shape(int state) {
        return switch (state) {
            case AIR, WATER -> FarShape.EMPTY;
            case SLAB -> SLAB_SHAPE;
            case FENCE, TORCH -> FENCE_SHAPE;
            case PLANT -> FarShape.CROSS;
            default -> FarShape.WHOLE;
        };
    }

    @Override
    public int lightEmission(int state) {
        return state == TORCH ? 14 : 0;
    }

    @Override
    public int lightColour(int state) {
        return 0xFFB46A;
    }

    /** A level-0 tile, every column seen, stone up to {@code ground} (rows), grass on top, lit air above. */
    static TileVoxels flatLand(int height, int ground) {
        TileVoxels tile = new TileVoxels(0, -64, height);
        for (int z = 0; z < TileVoxels.SIZE; z++) {
            for (int x = 0; x < TileVoxels.SIZE; x++) {
                tile.setKnown(x, z);
                for (int y = 0; y < height; y++) {
                    int index = TileVoxels.index(x, y, z);
                    if (y < ground) {
                        tile.states[index] = STONE;
                    } else if (y == ground) {
                        tile.states[index] = GRASS;
                    } else {
                        tile.light[index] = (byte) 0xF0;
                    }
                }
            }
        }
        return tile;
    }
}
