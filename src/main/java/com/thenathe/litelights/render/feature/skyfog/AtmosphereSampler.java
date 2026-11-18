package com.thenathe.litelights.render.feature.skyfog;

import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Once per client tick, looks at the land around the camera: a Gaussian-weighted mix of the biome
 * atmospheres within about 40 blocks, the height of the ground (for ground mist) and how much sky
 * the camera can see (for caves). The renderer eases toward these each frame.
 *
 * <p>The kernel is vanilla's 1-4-6-4-1 shape on a coarser 12-block grid, interpolated by the
 * camera's position inside the grid cell, so the result changes smoothly as the camera moves.
 */
final class AtmosphereSampler {
    private static final int GRID = 6;
    private static final float SPACING = 12.0f;
    private static final float[] KERNEL = {0.0f, 1.0f, 4.0f, 6.0f, 4.0f, 1.0f, 0.0f};
    /** Ground mist sits on the lower part of the land around: this share of samples is lower. */
    private static final float GROUND_PERCENTILE = 0.3f;

    private final AtmosphereBlend target = new AtmosphereBlend();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final int[] groundSamples = new int[GRID * GRID];
    private float groundHeight;
    private float skyExposure = 1.0f;
    private boolean sampled;

    AtmosphereBlend target() {
        return target;
    }

    /** The height of the land the mist settles on, in world blocks. */
    float groundHeight() {
        return groundHeight;
    }

    /** 0 deep in a cave, 1 under open sky. Always 1 in dimensions without sky light. */
    float skyExposure() {
        return skyExposure;
    }

    boolean hasSampled() {
        return sampled;
    }

    void reset() {
        sampled = false;
    }

    void sample(ClientLevel level, double x, double y, double z, BiomeAtmospheres table) {
        DimensionType dimension = level.dimensionType();
        int seaLevel = level.getSeaLevel();
        target.clearForSampling();

        double gridX = x / SPACING - 0.5;
        double gridZ = z / SPACING - 0.5;
        int cellX = Mth.floor(gridX);
        int cellZ = Mth.floor(gridZ);
        float withinX = (float) (gridX - cellX);
        float withinZ = (float) (gridZ - cellZ);
        int sampleY = Mth.floor(y);
        int groundCount = 0;

        for (int row = 0; row < GRID; row++) {
            float weightZ = LiteMath.lerp(KERNEL[row + 1], KERNEL[row], withinZ);
            int blockZ = Mth.floor((cellZ - 2 + row + 0.5) * SPACING);
            for (int column = 0; column < GRID; column++) {
                float weightX = LiteMath.lerp(KERNEL[column + 1], KERNEL[column], withinX);
                int blockX = Mth.floor((cellX - 2 + column + 0.5) * SPACING);
                float weight = weightX * weightZ;
                if (weight > 0.0f) {
                    Holder<Biome> biome = level.getBiomeManager().getNoiseBiomeAtPosition(blockX, sampleY, blockZ);
                    Biome.Precipitation fall = LocalClimate.fallAt(level, biome, cursor.set(blockX, sampleY, blockZ));
                    boolean dry = LocalClimate.dryHeat(level, biome, cursor);
                    target.add(weight, table.of(biome, dimension), fall == Biome.Precipitation.SNOW, fall != Biome.Precipitation.NONE, dry);
                }
                if (dimension.hasSkyLight()) {
                    int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
                    if (height > level.getMinY()) {
                        groundSamples[groundCount++] = height;
                    }
                }
            }
        }
        target.finishSampling();

        if (groundCount > 0) {
            Arrays.sort(groundSamples, 0, groundCount);
            groundHeight = groundSamples[Math.min(groundCount - 1, (int) (groundCount * GROUND_PERCENTILE))];
        } else {
            groundHeight = seaLevel;
        }
        if (dimension.hasSkyLight()) {
            skyExposure = level.getBrightness(LightLayer.SKY, cursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))) / 15.0f;
        } else {
            skyExposure = 1.0f;
        }
        sampled = true;
    }
}
