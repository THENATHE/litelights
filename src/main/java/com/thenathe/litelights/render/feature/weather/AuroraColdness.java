package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.render.feature.climate.LocalClimate;
import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBiomeTags;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * How cold the land around the camera is, which decides how strongly the aurora shows. Reading only
 * the biome under the camera made the aurora vanish the moment you stepped over a biome boundary,
 * so the coldness is averaged over a grid of the land around the camera, which changes it bit by bit
 * over the 32 blocks before a boundary and the 32 after, and then eased over a few
 * seconds, so a teleport or a fast flight over the boundary fades too.
 *
 * <p>Spruce and pine forests too mild for snow (the dappled forest, taigas, and modded biomes in the
 * Conventional coniferous tag) count as at least {@link #SPRUCE_FOREST} cold, so the aurora shows
 * over them too, dimmer than over snowy land.
 */
public final class AuroraColdness {
    /** Grid points on each side of the camera, and the blocks between them. */
    static final int REACH = 2;
    static final int SPACING = 16;
    /** The share of the remaining change made each tick: two thirds of a step in about 1.6 s. */
    static final float EASE = 0.03f;
    /** The least coldness a spruce or pine forest counts as: a taiga's, half of snowy land's. */
    static final float SPRUCE_FOREST = 0.5f;

    /** The coldness of the land at a block column. */
    public interface Land {
        float coldnessAt(int x, int z);
    }

    private final Map<Biome, Float> byBiome = new IdentityHashMap<>();
    private float eased = -1.0f;
    private int climateChanges;

    /** 1 over a snowy biome (base temperature 0.15 or below), 0 from 0.35 up, between in between. */
    public static float coldness(float temperature) {
        return Math.clamp((0.35f - temperature) / 0.2f, 0.0f, 1.0f);
    }

    /** As {@link #coldness(float)}, raised to {@link #SPRUCE_FOREST} in a spruce or pine forest. */
    public static float coldness(float temperature, boolean spruceForest) {
        float cold = coldness(temperature);
        return spruceForest ? Math.max(cold, SPRUCE_FOREST) : cold;
    }

    /** The coldness averaged over the grid around the column (x, z). */
    public static float around(int x, int z, Land land) {
        float sum = 0.0f;
        for (int stepX = -REACH; stepX <= REACH; stepX++) {
            for (int stepZ = -REACH; stepZ <= REACH; stepZ++) {
                sum += land.coldnessAt(x + stepX * SPACING, z + stepZ * SPACING);
            }
        }
        int side = 2 * REACH + 1;
        return sum / (side * side);
    }

    /**
     * The coldness of the land at a position, worked out once per biome while in this world. With
     * Almanac it is the season's: its sub-season's copy of the biome has the season's temperature.
     */
    public float of(ClientLevel level, BlockPos pos) {
        if (climateChanges != LocalClimate.changes()) {
            climateChanges = LocalClimate.changes();
            byBiome.clear();
        }
        Holder<Biome> biome = level.getBiome(pos);
        Holder<Biome> seasonal = LocalClimate.seasonalBiome(level, biome, pos);
        return byBiome.computeIfAbsent(seasonal.value(), value -> coldness(value.getBaseTemperature(), spruceForest(biome)));
    }

    /** One tick's step toward {@code target}; the first tick after a reset takes it at once. */
    public float ease(float target) {
        eased = eased < 0.0f ? target : eased + (target - eased) * EASE;
        return eased;
    }

    /** A new world or dimension: the next coldness is taken as it is, not eased into. */
    public void reset() {
        eased = -1.0f;
        byBiome.clear();
    }

    /** By id for vanilla's (a seasonal copy's original's), by tag for other mods' (copies carry their original's tags). */
    private static boolean spruceForest(Holder<Biome> biome) {
        ResourceKey<Biome> id = LocalClimate.idOf(biome);
        return Biomes.DAPPLED_FOREST.equals(id) || Biomes.TAIGA.equals(id)
            || Biomes.OLD_GROWTH_PINE_TAIGA.equals(id) || Biomes.OLD_GROWTH_SPRUCE_TAIGA.equals(id)
            || biome.is(ConventionalBiomeTags.IS_CONIFEROUS_TREE);
    }
}
