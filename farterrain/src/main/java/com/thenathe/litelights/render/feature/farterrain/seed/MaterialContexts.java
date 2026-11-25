package com.thenathe.litelights.render.feature.farterrain.seed;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;

/**
 * The game's surface rule context, which it makes only inside its own chunk pipeline: the
 * constructor and the two update calls are package-private, as are the badlands and iceberg
 * extensions of {@link MaterialSystem}. Reached by method handles once, so the predicted surface
 * runs the dimension's own rules, any mod's rule types included, exactly as real generation does.
 * Mixin invokers would do the same in the game, but not in unit tests, which check this against
 * real generation.
 */
final class MaterialContexts {
    private static final MethodHandle CREATE;
    private static final MethodHandle UPDATE_XZ;
    private static final MethodHandle UPDATE_Y;
    private static final MethodHandle BADLANDS;
    private static final MethodHandle ICEBERGS;

    static {
        try {
            MethodHandles.Lookup context = MethodHandles.privateLookupIn(MaterialRuleContext.class, MethodHandles.lookup());
            CREATE = context.findConstructor(MaterialRuleContext.class, MethodType.methodType(void.class, MaterialSystem.class, RandomState.class,
                DensityVolume.class, DensitySamplerSet.class, Function.class, WorldGenerationContext.class, Set.class));
            UPDATE_XZ = context.findVirtual(MaterialRuleContext.class, "updateXZ", MethodType.methodType(void.class, int.class, int.class, int.class, int.class));
            UPDATE_Y = context.findVirtual(MaterialRuleContext.class, "updateY", MethodType.methodType(void.class, int.class, int.class, int.class, int.class));
            MethodHandles.Lookup system = MethodHandles.privateLookupIn(MaterialSystem.class, MethodHandles.lookup());
            BADLANDS = system.findVirtual(MaterialSystem.class, "erodedBadlandsExtension", MethodType.methodType(void.class, BlockColumn.class,
                int.class, int.class, int.class, net.minecraft.world.level.LevelHeightAccessor.class));
            ICEBERGS = system.findVirtual(MaterialSystem.class, "frozenOceanExtension", MethodType.methodType(void.class, int.class, Biome.class,
                BlockColumn.class, BlockPos.MutableBlockPos.class, int.class, int.class, int.class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private MaterialContexts() {
    }

    static MaterialRuleContext create(MaterialSystem system, RandomState randomState, DensityVolume expectedVolume, DensitySamplerSet samplers,
                                      Function<BlockPos, Holder<Biome>> biomes, WorldGenerationContext generation) {
        try {
            return (MaterialRuleContext) CREATE.invoke(system, randomState, expectedVolume, samplers, biomes, generation, (Set<?>) null);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static void updateXZ(MaterialRuleContext context, int blockX, int blockZ, int gradientX, int gradientZ) {
        try {
            UPDATE_XZ.invoke(context, blockX, blockZ, gradientX, gradientZ);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static void updateY(MaterialRuleContext context, int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int blockY) {
        try {
            UPDATE_Y.invoke(context, stoneDepthAbove, stoneDepthBelow, waterHeight, blockY);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static void erodedBadlands(MaterialSystem system, BlockColumn column, int blockX, int blockZ, int height,
                               net.minecraft.world.level.LevelHeightAccessor heights) {
        try {
            BADLANDS.invoke(system, column, blockX, blockZ, height, heights);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static void icebergs(MaterialSystem system, int minSurfaceLevel, Biome biome, BlockColumn column, BlockPos.MutableBlockPos scratch,
                         int blockX, int blockZ, int height) {
        try {
            ICEBERGS.invoke(system, minSurfaceLevel, biome, column, scratch, blockX, blockZ, height);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(failure);
    }
}
