package com.thenathe.litelights.render.feature.farterrain.land;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The game's block states and biomes against a store's name tables ({@link NameTable}): a state is
 * stored by its registry name and properties, a biome by its id. A name this game doesn't know
 * (a mod removed) reads as stone, or plains for a biome. Thread-safe; the biome registry is the
 * level's, taken when the palette is made.
 */
public final class BlockPalette {
    private static final int MAX_IDS = 1 << 20;

    private final NameTable blockNames;
    private final NameTable biomeNames;
    private final Registry<Biome> biomeRegistry;
    private final Map<BlockState, Integer> stateIds = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<BlockState> states = new AtomicReferenceArray<>(MAX_IDS);
    private final Map<Holder<Biome>, Integer> biomeIds = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<Biome> biomes = new AtomicReferenceArray<>(1 << 15);
    private final Biome fallbackBiome;

    public BlockPalette(NameTable blockNames, NameTable biomeNames, ClientLevel level) {
        this.blockNames = blockNames;
        this.biomeNames = biomeNames;
        this.biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Biome plains = biomeRegistry.getValue(Biomes.PLAINS);
        this.fallbackBiome = plains != null ? plains : biomeRegistry.stream().findFirst().orElseThrow();
        stateIds.put(Blocks.AIR.defaultBlockState(), 0);
        states.set(0, Blocks.AIR.defaultBlockState());
    }

    public int idOf(BlockState state) {
        Integer id = stateIds.get(state);
        if (id != null) {
            return id;
        }
        int made = state.isAir() ? 0 : blockNames.idOf(BlockStateParser.serialize(state));
        if (made >= MAX_IDS) {
            return 0;
        }
        states.compareAndSet(made, null, state);
        stateIds.putIfAbsent(state, made);
        return made;
    }

    /** The state behind an id; stone for a name the game doesn't know. */
    public BlockState stateOf(int id) {
        if (id <= 0 || id >= MAX_IDS) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = states.get(id);
        if (state != null) {
            return state;
        }
        BlockState parsed = parse(blockNames.nameOf(id));
        states.compareAndSet(id, null, parsed);
        return states.get(id);
    }

    private static BlockState parse(@Nullable String name) {
        if (name == null) {
            return Blocks.STONE.defaultBlockState();
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState();
        } catch (CommandSyntaxException | RuntimeException unknown) {
            return Blocks.STONE.defaultBlockState();
        }
    }

    public short biomeIdOf(Holder<Biome> biome) {
        Integer id = biomeIds.get(biome);
        if (id == null) {
            String name = biome.unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:plains");
            id = Math.min(biomeNames.idOf(name), Short.MAX_VALUE);
            biomeIds.putIfAbsent(biome, id);
            biomes.compareAndSet(id, null, biome.value());
        }
        return (short) (int) id;
    }

    public Biome biomeOf(int id) {
        if (id < 0 || id >= biomes.length()) {
            return fallbackBiome;
        }
        Biome biome = biomes.get(id);
        if (biome != null) {
            return biome;
        }
        String name = biomeNames.nameOf(id);
        Identifier identifier = name == null ? null : Identifier.tryParse(name);
        Biome found = identifier == null ? null : biomeRegistry.getValue(ResourceKey.create(Registries.BIOME, identifier));
        biomes.compareAndSet(id, null, found != null ? found : fallbackBiome);
        return biomes.get(id);
    }
}
