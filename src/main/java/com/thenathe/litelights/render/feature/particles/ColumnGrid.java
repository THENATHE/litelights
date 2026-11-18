package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.feature.climate.LocalClimate;
import java.util.Arrays;
import java.util.function.IntBinaryOperator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * A copy of the ground around the camera: for every column, the height rain and snow stop at, what
 * the top is (water or not), and per four-by-four cell the precipitation and climate. Weather
 * spawners read it on the client thread; the simulation reads it on its worker for ground contact,
 * so it never touches the level from another thread.
 *
 * <p>Filled on the client thread between two simulation steps, never while one runs. Four rows are
 * read again every tick, so at Near (64 blocks across) placed and broken blocks show within 0.8 s,
 * and the wider grids of the further Weather distances take a little longer at their far edges;
 * moving the camera shifts what is known and reads only the new edge. Columns in chunks that
 * aren't loaded yet (a server's short view distance under a wide Weather distance) are tried again
 * on the rolling refresh, not every tick. Each cell also keeps the light at its ground, so weather
 * particles don't each look up their light, and the grid counts its rain and snow cells, so the
 * spawners can skip the kind of weather nothing around the camera has.
 */
public final class ColumnGrid implements Simulation.Ground {
    private static final int CELL = 4;
    private static final int SNAP = ParticleReach.GRID_SNAP;
    /** A column not read since it came into the grid: read on the next update. */
    private static final int UNKNOWN = Integer.MIN_VALUE;
    /** A column whose chunk wasn't loaded when it was read: tried again on the rolling refresh only. */
    private static final int UNLOADED = Integer.MIN_VALUE + 1;
    private static final byte UNREAD = -1;
    private static final int ROWS_PER_TICK = 4;

    public static final byte PRECIPITATION_NONE = 0;
    public static final byte PRECIPITATION_RAIN = 1;
    public static final byte PRECIPITATION_SNOW = 2;

    private int size;
    private int half;
    private int cells;
    /** Ticks for the rolling refresh to come round to every row once. */
    private int refreshTicks;
    private int originX;
    private int originZ;
    private boolean hasOrigin;
    private int refreshRow;
    private int climateChanges = LocalClimate.changes();
    private int[] heights;
    private boolean[] water;
    private byte[] precipitation;
    private byte[] climate;
    private int[] cellLight;
    private int[] scratchHeights;
    private boolean[] scratchWater;
    private byte[] scratchPrecipitation;
    private byte[] scratchClimate;
    private int[] scratchCellLight;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public ColumnGrid() {
        resize(ParticleReach.NEAR_GRID_HALF);
    }

    /**
     * Sets how far the grid reaches each way from the camera, a multiple of 8 blocks, forgetting
     * what it knew if that changes. Client thread only, between steps.
     */
    public void resize(int halfWidth) {
        if (halfWidth == half && heights != null) {
            return;
        }
        half = halfWidth;
        size = halfWidth * 2;
        cells = size / CELL;
        refreshTicks = size / ROWS_PER_TICK;
        heights = new int[size * size];
        water = new boolean[size * size];
        precipitation = new byte[cells * cells];
        climate = new byte[cells * cells];
        cellLight = new int[cells * cells];
        scratchHeights = new int[size * size];
        scratchWater = new boolean[size * size];
        scratchPrecipitation = new byte[cells * cells];
        scratchClimate = new byte[cells * cells];
        scratchCellLight = new int[cells * cells];
        refreshRow = 0;
        forget();
    }

    /** Half the grid's width, in blocks. */
    public int halfWidth() {
        return half;
    }

    public void forget() {
        hasOrigin = false;
        Arrays.fill(heights, UNKNOWN);
        Arrays.fill(precipitation, UNREAD);
    }

    /** Recentres on the camera and refreshes stale columns. Client thread only. */
    public void update(ClientLevel level, double cameraX, double cameraZ) {
        int wantedX = Math.floorDiv((int) Math.floor(cameraX), SNAP) * SNAP - half;
        int wantedZ = Math.floorDiv((int) Math.floor(cameraZ), SNAP) * SNAP - half;
        if (!hasOrigin) {
            originX = wantedX;
            originZ = wantedZ;
            hasOrigin = true;
            Arrays.fill(heights, UNKNOWN);
            Arrays.fill(precipitation, UNREAD);
        } else if (wantedX != originX || wantedZ != originZ) {
            shift(wantedX - originX, wantedZ - originZ);
            originX = wantedX;
            originZ = wantedZ;
        }
        // A new sub-season, or a rain event reaching other climates: every cell is read again now
        // rather than over the rolling refresh, so the rain turns to snow everywhere at once.
        if (climateChanges != LocalClimate.changes()) {
            climateChanges = LocalClimate.changes();
            Arrays.fill(precipitation, UNREAD);
        }
        // Columns that came in with a shift are read at once; the rest four rows a tick.
        for (int row = 0; row < size; row++) {
            boolean rolling = row % refreshTicks == refreshRow;
            for (int column = 0; column < size; column++) {
                int index = row * size + column;
                if (rolling || heights[index] == UNKNOWN) {
                    readColumn(level, index, originX + column, originZ + row);
                }
            }
        }
        for (int cellRow = 0; cellRow < cells; cellRow++) {
            boolean rolling = cellRow % refreshTicks == refreshRow;
            for (int cellColumn = 0; cellColumn < cells; cellColumn++) {
                int cellIndex = cellRow * cells + cellColumn;
                if (rolling || precipitation[cellIndex] == UNREAD) {
                    readCell(level, cellIndex);
                }
            }
        }
        refreshRow = (refreshRow + 1) % refreshTicks;
    }

    /**
     * For tests, which have no level: centres the grid on (x, z) over flat land open to the sky at
     * {@code height}, each cell falling as {@code fallAt} says for its centre's block x and z.
     */
    public void fillFlatForTests(double x, double z, int height, IntBinaryOperator fallAt) {
        originX = Math.floorDiv((int) Math.floor(x), SNAP) * SNAP - half;
        originZ = Math.floorDiv((int) Math.floor(z), SNAP) * SNAP - half;
        hasOrigin = true;
        Arrays.fill(heights, height);
        Arrays.fill(water, false);
        Arrays.fill(cellLight, LightCoordsUtil.FULL_SKY);
        Arrays.fill(climate, (byte) BiomeClimate.TEMPERATE.ordinal());
        for (int cellIndex = 0; cellIndex < cells * cells; cellIndex++) {
            int blockX = originX + cellIndex % cells * CELL + CELL / 2;
            int blockZ = originZ + cellIndex / cells * CELL + CELL / 2;
            precipitation[cellIndex] = (byte) fallAt.applyAsInt(blockX, blockZ);
        }
    }

    private void shift(int deltaX, int deltaZ) {
        System.arraycopy(heights, 0, scratchHeights, 0, heights.length);
        System.arraycopy(water, 0, scratchWater, 0, water.length);
        Arrays.fill(heights, UNKNOWN);
        for (int row = 0; row < size; row++) {
            int oldRow = row + deltaZ;
            if (oldRow < 0 || oldRow >= size) {
                continue;
            }
            for (int column = 0; column < size; column++) {
                int oldColumn = column + deltaX;
                if (oldColumn >= 0 && oldColumn < size) {
                    heights[row * size + column] = scratchHeights[oldRow * size + oldColumn];
                    water[row * size + column] = scratchWater[oldRow * size + oldColumn];
                }
            }
        }
        // Cells move too (the origin moves in whole cells), so a wide grid only reads its new edge.
        System.arraycopy(precipitation, 0, scratchPrecipitation, 0, precipitation.length);
        System.arraycopy(climate, 0, scratchClimate, 0, climate.length);
        System.arraycopy(cellLight, 0, scratchCellLight, 0, cellLight.length);
        Arrays.fill(precipitation, UNREAD);
        int cellDeltaX = deltaX / CELL;
        int cellDeltaZ = deltaZ / CELL;
        for (int cellRow = 0; cellRow < cells; cellRow++) {
            int oldRow = cellRow + cellDeltaZ;
            if (oldRow < 0 || oldRow >= cells) {
                continue;
            }
            for (int cellColumn = 0; cellColumn < cells; cellColumn++) {
                int oldColumn = cellColumn + cellDeltaX;
                if (oldColumn >= 0 && oldColumn < cells) {
                    int to = cellRow * cells + cellColumn;
                    int from = oldRow * cells + oldColumn;
                    precipitation[to] = scratchPrecipitation[from];
                    climate[to] = scratchClimate[from];
                    cellLight[to] = scratchCellLight[from];
                }
            }
        }
    }

    private void readColumn(ClientLevel level, int index, int blockX, int blockZ) {
        if (!level.hasChunk(blockX >> 4, blockZ >> 4)) {
            heights[index] = UNLOADED;
            return;
        }
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        heights[index] = height;
        FluidState fluid = level.getFluidState(cursor.set(blockX, height - 1, blockZ));
        water[index] = fluid.is(FluidTags.WATER);
    }

    private void readCell(ClientLevel level, int cellIndex) {
        int cellX = cellIndex % cells;
        int cellZ = cellIndex / cells;
        int blockX = originX + cellX * CELL + CELL / 2;
        int blockZ = originZ + cellZ * CELL + CELL / 2;
        int height = heights[(cellZ * CELL + CELL / 2) * size + cellX * CELL + CELL / 2];
        if (!known(height) || !level.hasChunk(blockX >> 4, blockZ >> 4)) {
            precipitation[cellIndex] = PRECIPITATION_NONE;
            climate[cellIndex] = (byte) BiomeClimate.TEMPERATE.ordinal();
            return;
        }
        cursor.set(blockX, height, blockZ);
        cellLight[cellIndex] = LightCoordsUtil.getLightCoords(level, cursor);
        Holder<Biome> biome = level.getBiome(cursor);
        precipitation[cellIndex] = precipitationByte(LocalClimate.fallAt(level, biome, cursor));
        climate[cellIndex] = (byte) BiomeClimate.of(LocalClimate.seasonalBiome(level, biome, cursor)).ordinal();
    }

    private static boolean known(int height) {
        return height != UNKNOWN && height != UNLOADED;
    }

    static byte precipitationByte(Biome.Precipitation fall) {
        return switch (fall) {
            case RAIN -> PRECIPITATION_RAIN;
            case SNOW -> PRECIPITATION_SNOW;
            case NONE -> PRECIPITATION_NONE;
        };
    }

    private int columnIndex(double x, double z) {
        int column = (int) Math.floor(x) - originX;
        int row = (int) Math.floor(z) - originZ;
        if (column < 0 || row < 0 || column >= size || row >= size) {
            return -1;
        }
        return row * size + column;
    }

    private int cellIndex(double x, double z) {
        int column = (int) Math.floor(x) - originX;
        int row = (int) Math.floor(z) - originZ;
        if (column < 0 || row < 0 || column >= size || row >= size) {
            return -1;
        }
        return (row / CELL) * cells + column / CELL;
    }

    /**
     * The height rain stops at in this column (the top of the highest block that blocks motion,
     * water included), or negative infinity where nothing is known, so nothing collides there.
     */
    @Override
    public double groundHeight(double x, double z) {
        int index = columnIndex(x, z);
        if (index < 0 || !known(heights[index])) {
            return Double.NEGATIVE_INFINITY;
        }
        return heights[index];
    }

    @Override
    public boolean isWaterSurface(double x, double z) {
        int index = columnIndex(x, z);
        return index >= 0 && known(heights[index]) && water[index];
    }

    public byte precipitationAt(double x, double z) {
        int index = cellIndex(x, z);
        return index < 0 || precipitation[index] == UNREAD ? PRECIPITATION_NONE : precipitation[index];
    }

    /**
     * Whether any cell of the grid within {@code reach} blocks of (x, z), as a square, falls as
     * {@code fall}. Cells not read yet count as none, as {@link #precipitationAt} reads them.
     */
    public boolean fallsWithin(double x, double z, double reach, byte fall) {
        int firstColumn = Math.max(0, Math.floorDiv((int) Math.floor(x - reach) - originX, CELL));
        int lastColumn = Math.min(cells - 1, Math.floorDiv((int) Math.floor(x + reach) - originX, CELL));
        int firstRow = Math.max(0, Math.floorDiv((int) Math.floor(z - reach) - originZ, CELL));
        int lastRow = Math.min(cells - 1, Math.floorDiv((int) Math.floor(z + reach) - originZ, CELL));
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                if (precipitation[row * cells + column] == fall) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Vanilla's packed light just above the ground of this column's cell; full sky light where unknown. */
    public int lightAt(double x, double z) {
        int index = cellIndex(x, z);
        return index < 0 || precipitation[index] == UNREAD ? LightCoordsUtil.FULL_SKY : cellLight[index];
    }

    public BiomeClimate climateAt(double x, double z) {
        int index = cellIndex(x, z);
        return index < 0 ? BiomeClimate.TEMPERATE : BiomeClimate.byOrdinal(climate[index]);
    }
}
