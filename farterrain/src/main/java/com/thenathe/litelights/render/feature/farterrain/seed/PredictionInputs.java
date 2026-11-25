package com.thenathe.litelights.render.feature.farterrain.seed;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Everything land is predicted from for one world and dimension: the seed, the dimension's own
 * noise generator (its settings, router and biome source) and the random state made from both. In
 * single player these are the integrated server's own objects; on a server they are built from the
 * client's own data ({@link InstalledGenerationData}), which may not be the server's.
 *
 * <p>The seed is never written to the log: {@link #toString} leaves it out.
 *
 * @param fromWorld true when made from the integrated server's own generator (single player or the
 *                  LAN host), false when made from the client's installed data for a server
 */
public record PredictionInputs(long seed, String dimension, NoiseBasedChunkGenerator generator, RandomState randomState,
                               HolderLookup.Provider registries, boolean fromWorld) {
    public Holder<NoiseGeneratorSettings> settingsHolder() {
        return generator.generatorSettings();
    }

    public NoiseGeneratorSettings settings() {
        return generator.generatorSettings().value();
    }

    public BiomeSource biomeSource() {
        return generator.getBiomeSource();
    }

    public int seaLevel() {
        return settings().seaLevel();
    }

    @Override
    public String toString() {
        return "PredictionInputs[" + dimension + ", " + settingsHolder().unwrapKey().map(Object::toString).orElse("inline settings")
            + (fromWorld ? ", the world's own" : ", from this client's data") + "]";
    }
}
