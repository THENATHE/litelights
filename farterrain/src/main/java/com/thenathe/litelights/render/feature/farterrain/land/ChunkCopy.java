package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.jspecify.annotations.Nullable;

/**
 * A copy of one client chunk's blocks, biomes and light, taken on the client thread so a worker can
 * turn it into far terrain without touching the live level (the shadow casters copy sections the
 * same way). Only copies of the palettes and light arrays: about a fifth of a millisecond.
 */
public final class ChunkCopy {
    public final int chunkX;
    public final int chunkZ;
    public final int minY;
    public final int height;
    public final boolean skyLight;
    private final int minSectionY;
    private final @Nullable PalettedContainer<BlockState>[] states;
    private final @Nullable PalettedContainer<Holder<Biome>>[] biomes;
    /** Sky light per section, one more above the top; null where the engine keeps none. */
    private final @Nullable DataLayer[] sky;
    private final @Nullable DataLayer[] block;

    @SuppressWarnings("unchecked")
    private ChunkCopy(ClientLevel level, LevelChunk chunk) {
        this.chunkX = chunk.getPos().x();
        this.chunkZ = chunk.getPos().z();
        this.minY = level.getMinY();
        this.height = level.getHeight();
        this.skyLight = level.dimensionType().hasSkyLight();
        this.minSectionY = level.getMinSectionY();
        LevelChunkSection[] sections = chunk.getSections();
        this.states = new PalettedContainer[sections.length];
        this.biomes = new PalettedContainer[sections.length];
        this.sky = new DataLayer[sections.length + 1];
        this.block = new DataLayer[sections.length];
        LayerLightEventListener skyEngine = level.getLightEngine().getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockEngine = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()) {
                states[i] = section.getStates().copy();
            }
            if (section != null) {
                biomes[i] = section.getBiomes().copy();
            }
            SectionPos pos = SectionPos.of(chunkX, minSectionY + i, chunkZ);
            block[i] = copy(blockEngine.getDataLayerData(pos));
            if (skyLight) {
                sky[i] = copy(skyEngine.getDataLayerData(pos));
            }
        }
        if (skyLight) {
            sky[sections.length] = copy(skyEngine.getDataLayerData(SectionPos.of(chunkX, minSectionY + sections.length, chunkZ)));
        }
    }

    public static ChunkCopy take(ClientLevel level, LevelChunk chunk) {
        return new ChunkCopy(level, chunk);
    }

    private static @Nullable DataLayer copy(@Nullable DataLayer layer) {
        return layer == null ? null : layer.copy();
    }

    /**
     * Writes this chunk into its quarter of a level-0 tile: every column replaced, seen, with its
     * biome and light. Thin covers (snow layers, carpets) take the place of the block under them.
     * Returns whether anything in the tile changed: a chunk seen again as it was (rejoining,
     * walking back) leaves its tile as it is, and nothing is stored, built or meshed again for it.
     * {@code before} is scratch room for the comparison, reused from call to call.
     */
    public boolean writeInto(TileVoxels tile, BlockPalette palette, BlockLooks looks, Before before) {
        int offsetX = (chunkX & 1) * 16;
        int offsetZ = (chunkZ & 1) * 16;
        Map<BlockState, Integer> ids = new IdentityHashMap<>();
        int[] skyColumn = new int[16 * 16];
        int rows = Math.min(tile.height, height);
        before.take(tile, offsetX, offsetZ);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int tileX = offsetX + x;
                int tileZ = offsetZ + z;
                tile.clearColumn(tileX, tileZ);
                tile.setKnown(tileX, tileZ);
                skyColumn[x + z * 16] = 15;
            }
        }
        // Sky light above the chunk's top section: the layer above, or full daylight.
        if (skyLight && sky[states.length] != null) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    skyColumn[x + z * 16] = sky[states.length].get(x, 0, z);
                }
            }
        }
        for (int section = states.length - 1; section >= 0; section--) {
            PalettedContainer<BlockState> sectionStates = states[section];
            DataLayer sectionSky = skyLight ? sky[section] : null;
            DataLayer sectionBlock = block[section];
            for (int localY = 15; localY >= 0; localY--) {
                int row = section * 16 + localY;
                if (row >= rows) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int index = TileVoxels.index(offsetX + x, row, offsetZ + z);
                        if (sectionStates != null) {
                            BlockState state = sectionStates.get(x, localY, z);
                            if (!state.isAir()) {
                                Integer id = ids.get(state);
                                if (id == null) {
                                    id = palette.idOf(state);
                                    ids.put(state, id);
                                }
                                tile.states[index] = id;
                            }
                        }
                        int skyValue;
                        if (!skyLight) {
                            skyValue = 0;
                        } else if (sectionSky != null) {
                            skyValue = sectionSky.get(x, localY, z);
                        } else {
                            // No layer kept here: the engine reads the nearest layer above, at its bottom.
                            skyValue = skyColumn[x + z * 16];
                        }
                        int blockValue = sectionBlock == null ? 0 : sectionBlock.get(x, localY, z);
                        tile.light[index] = (byte) (skyValue << 4 | blockValue);
                    }
                }
            }
            if (sectionSky != null) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        skyColumn[x + z * 16] = sectionSky.get(x, 0, z);
                    }
                }
            }
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                finishColumn(tile, offsetX + x, offsetZ + z, x, z, rows, palette, looks);
            }
        }
        return !before.sameAs(tile);
    }

    /**
     * One chunk's quarter of a tile as it was, to tell whether writing the chunk changed it. Kept by
     * the store worker and reused for every chunk, so taking it costs no garbage.
     */
    static final class Before {
        private int offsetX;
        private int offsetZ;
        private int rows;
        private int[] states = new int[0];
        private byte[] light = new byte[0];
        private final short[] biomes = new short[16 * 16];
        private final boolean[] known = new boolean[16 * 16];

        void take(TileVoxels tile, int offsetX, int offsetZ) {
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.rows = tile.height;
            if (states.length < 256 * rows) {
                states = new int[256 * rows];
                light = new byte[256 * rows];
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int column = x + z * 16;
                    for (int y = 0; y < rows; y++) {
                        int index = TileVoxels.index(offsetX + x, y, offsetZ + z);
                        states[column + y * 256] = tile.states[index];
                        light[column + y * 256] = tile.light[index];
                    }
                    biomes[column] = tile.biomes[TileVoxels.column(offsetX + x, offsetZ + z)];
                    known[column] = tile.known(offsetX + x, offsetZ + z);
                }
            }
        }

        boolean sameAs(TileVoxels tile) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int column = x + z * 16;
                    if (known[column] != tile.known(offsetX + x, offsetZ + z)
                        || biomes[column] != tile.biomes[TileVoxels.column(offsetX + x, offsetZ + z)]) {
                        return false;
                    }
                    for (int y = 0; y < rows; y++) {
                        int index = TileVoxels.index(offsetX + x, y, offsetZ + z);
                        if (states[column + y * 256] != tile.states[index] || light[column + y * 256] != tile.light[index]) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    /** Folds covers down and takes the biome at the column's top. */
    private void finishColumn(TileVoxels tile, int tileX, int tileZ, int x, int z, int rows, BlockPalette palette, BlockLooks looks) {
        int topRow = Covers.fold(tile, tileX, tileZ, rows, looks::cover, looks);
        if (skyLight) {
            // Everything over the column's top is open sky, whatever the light engine had worked out
            // when the copy was taken: the outermost chunks a player sees often arrive before their
            // light does, and a surface on unlit air would be left out as a closed cave.
            for (int row = topRow + 1; row < rows; row++) {
                int index = TileVoxels.index(tileX, row, tileZ);
                tile.light[index] = (byte) (0xF0 | (tile.light[index] & 15));
            }
        }
        int biomeRow = Math.max(topRow, 0);
        int section = biomeRow >> 4;
        PalettedContainer<Holder<Biome>> sectionBiomes = section < biomes.length ? biomes[section] : null;
        if (sectionBiomes != null) {
            Holder<Biome> biome = sectionBiomes.get(x >> 2, (biomeRow & 15) >> 2, z >> 2);
            tile.biomes[TileVoxels.column(tileX, tileZ)] = palette.biomeIdOf(biome);
        }
    }
}
