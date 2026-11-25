package com.thenathe.litelights.render.feature.farterrain.seed;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.validation.DirectoryValidator;

/**
 * World generation as this client's own data says it, for a server whose generator the client is
 * never sent. It is loaded the way a new world's server loads it, so what the game would load is
 * what is used: vanilla's built-in packs, every mod's data pack (Fabric adds them to any server
 * pack repository, with their resource conditions and {@code pack.mcmeta} overlays), and loose
 * data packs in {@code <game>/litelights/datapacks/}, which take priority over both. The packs a
 * new world would have on are selected ({@link MinecraftServer#configurePackRepository}), then the
 * world-generation and dimension registries are read through {@link RegistryDataLoader}, with the
 * static registries' tags from the same packs, without touching the game's own registries.
 *
 * <p>A bare scan of each jar's {@code data/} folder would miss nested built-in packs and version
 * overlays; going through the game's own pack loading is what the earlier companion project
 * learned to do.
 */
public final class InstalledGenerationData {
    private final RegistryAccess.Frozen generationRegistries;
    private final RegistryAccess.Frozen dimensions;
    private final HolderLookup.Provider lookups;
    private final List<String> packs;

    private InstalledGenerationData(RegistryAccess.Frozen generationRegistries, RegistryAccess.Frozen dimensions, HolderLookup.Provider lookups,
                                    List<String> packs) {
        this.generationRegistries = generationRegistries;
        this.dimensions = dimensions;
        this.lookups = lookups;
        this.packs = packs;
    }

    /** The folder of loose data packs a player puts a server's world-generation packs in. */
    public static Path datapackFolder(Path gameFolder) {
        return gameFolder.resolve("litelights").resolve("datapacks");
    }

    /** Loads from vanilla's packs, every mod's and the loose folder's. Slow (a second or more): never on the client thread. */
    public static InstalledGenerationData load(Path datapackFolder, Executor executor) {
        // Symbolic links in the loose folder are refused, as a server refuses links it wasn't told to allow.
        PackRepository repository = ServerPacksSource.createPackRepository(datapackFolder, new DirectoryValidator(path -> false));
        return load(repository, executor);
    }

    /** Loads from the packs a repository offers, selected as for a new world. */
    public static InstalledGenerationData load(PackRepository repository, Executor executor) {
        MinecraftServer.configurePackRepository(repository, WorldDataConfiguration.DEFAULT, true, false);
        List<String> selected = List.copyOf(repository.getSelectedIds());
        List<PackResources> opened = repository.openAllSelected();
        try (CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, opened)) {
            LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
            List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
            RegistryAccess.Frozen worldContext = layers.getAccessForLoading(RegistryLayer.WORLD);
            List<HolderLookup.RegistryLookup<?>> worldLookups = TagLoader.buildUpdatedLookups(worldContext, staticTags);
            RegistryAccess.Frozen generationRegistries = RegistryDataLoader.load(resources, worldLookups, RegistryDataLoader.WORLD_REGISTRIES, executor).join();
            List<HolderLookup.RegistryLookup<?>> dimensionLookups = Stream.concat(worldLookups.stream(), generationRegistries.listRegistries()).toList();
            RegistryAccess.Frozen dimensions = RegistryDataLoader.load(resources, dimensionLookups, RegistryDataLoader.DIMENSION_REGISTRIES, executor).join();
            HolderLookup.Provider lookups = HolderLookup.Provider.create(Stream.concat(dimensionLookups.stream(), dimensions.listRegistries()));
            return new InstalledGenerationData(generationRegistries, dimensions, lookups, selected);
        }
    }

    /** The data packs selected, in order (vanilla's, the mods', the loose folder's). */
    public List<String> packs() {
        return packs;
    }

    public RegistryAccess.Frozen generationRegistries() {
        return generationRegistries;
    }

    /** Every registry the codecs may need: the static ones with the packs' tags, world generation, dimensions. */
    public HolderLookup.Provider lookups() {
        return lookups;
    }

    /**
     * The dimension's level stem: a data pack's own if one defines it, else the default world
     * preset's (a server's {@code level-type} can't be known on the client, so "normal" is assumed).
     */
    public Optional<LevelStem> stem(String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) {
            return Optional.empty();
        }
        ResourceKey<LevelStem> key = ResourceKey.create(Registries.LEVEL_STEM, id);
        Optional<LevelStem> fromPacks = dimensions.lookup(Registries.LEVEL_STEM).flatMap(stems -> stems.getOptional(key));
        if (fromPacks.isPresent()) {
            return fromPacks;
        }
        Optional<WorldPreset> preset = generationRegistries.lookup(Registries.WORLD_PRESET).flatMap(presets -> presets.getOptional(WorldPresets.NORMAL));
        return preset.flatMap(found -> found.createWorldDimensions().get(key));
    }

    /**
     * The inputs for a dimension and seed, or empty when the dimension isn't made by the game's
     * noise generator here (a flat world, an unknown dimension, a mod's own generator type).
     */
    public Optional<PredictionInputs> inputsFor(String dimension, long seed) {
        return stem(dimension).flatMap(stem -> stem.generator() instanceof NoiseBasedChunkGenerator noise ? Optional.of(noise) : Optional.empty())
            .map(noise -> {
                RandomState randomState = RandomState.create(lookups.lookupOrThrow(Registries.NOISE), seed, noise.generatorSettings().value());
                return new PredictionInputs(seed, dimension, noise, randomState, lookups, false);
            });
    }
}
