package com.thenathe.litelights.render.feature.farterrain.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.codec.RegistryFileCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryValidator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fault behind "Couldn't start: Registry Loading" on servers with terrain mods (1.1.1): a
 * mod's world-generation data that refers to a registry of the mod's own (Lithostitched's density
 * functions read their noise from {@code lithostitched:fast_noise_config}) can only be read with
 * the mod's registries loaded beside vanilla's, and when it can't be, loading fails for every
 * registry at once. Here a stand-in mod: a "config" registry and a "user" registry whose entries
 * refer to configs, with a data pack ("brokenmod") holding one of each.
 */
class InstalledGenerationDataTest {
    private static final ResourceKey<Registry<Integer>> CONFIG = ResourceKey.createRegistryKey(Identifier.parse("litelights_test:test_config"));
    private static final ResourceKey<Registry<Holder<Integer>>> USER = ResourceKey.createRegistryKey(Identifier.parse("litelights_test:test_user"));
    private static final RegistryDataLoader.RegistryData<Integer> CONFIG_DATA = new RegistryDataLoader.RegistryData<>(CONFIG, Codec.INT,
        RegistryValidator.none());
    private static final RegistryDataLoader.RegistryData<Holder<Integer>> USER_DATA = new RegistryDataLoader.RegistryData<>(USER,
        RegistryFileCodec.create(CONFIG, Codec.INT, false), RegistryValidator.none());

    @TempDir
    static Path packs;

    @BeforeAll
    static void writePack() throws IOException {
        // Bootstraps the game and loads vanilla's data once, as the other seed tests do.
        VanillaGenerationData.get();
        // A registry of a mod's own keeps its entries under data/<namespace>/<the registry's path>/.
        Path pack = packs.resolve("brokenmod-pack");
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
        Files.createDirectories(pack.resolve("data/brokenmod/test_config"));
        Files.createDirectories(pack.resolve("data/brokenmod/test_user"));
        Files.writeString(pack.resolve("pack.mcmeta"), """
            {"pack": {"description": "LiteLights test: a mod's data referring to its own registry", "min_format": %d, "max_format": %d}}
            """.formatted(format, format));
        Files.writeString(pack.resolve("data/brokenmod/test_config/seven.json"), "7");
        Files.writeString(pack.resolve("data/brokenmod/test_user/thing.json"), "\"brokenmod:seven\"");
    }

    @Test
    void withoutTheModsOwnRegistryItsDataCantBeReadAndTheFailureNamesIt() {
        List<RegistryDataLoader.RegistryData<?>> vanillaAndUser = new ArrayList<>(RegistryDataLoader.WORLD_REGISTRIES);
        vanillaAndUser.add(USER_DATA);
        UnreadableData failure = assertThrows(UnreadableData.class, () -> InstalledGenerationData.load(repository(), vanillaAndUser, Runnable::run));
        assertEquals(List.of("brokenmod"), failure.namespaces(), failure.getMessage());
    }

    @Test
    void withTheModsRegistriesBesideVanillasItLoads() {
        List<RegistryDataLoader.RegistryData<?>> all = InstalledGenerationData.withModRegistries(RegistryDataLoader.WORLD_REGISTRIES,
            List.of(USER_DATA, CONFIG_DATA));
        InstalledGenerationData data = InstalledGenerationData.load(repository(), all, Runnable::run);
        Holder<Integer> thing = data.generationRegistries().lookupOrThrow(USER)
            .getValueOrThrow(ResourceKey.create(USER, Identifier.parse("brokenmod:thing")));
        assertEquals(7, thing.value());
        assertTrue(data.inputsFor("minecraft:overworld", 1L).isPresent(), "vanilla's overworld still comes out of the same data");
    }

    @Test
    void modRegistriesComeAfterVanillasOnceEach() {
        List<RegistryDataLoader.RegistryData<?>> vanilla = RegistryDataLoader.WORLD_REGISTRIES;
        // Fabric API's list holds vanilla's registries as well as the mods'.
        List<RegistryDataLoader.RegistryData<?>> fabric = new ArrayList<>(vanilla);
        fabric.add(1, CONFIG_DATA);
        fabric.add(USER_DATA);
        List<RegistryDataLoader.RegistryData<?>> merged = InstalledGenerationData.withModRegistries(vanilla, fabric);
        assertEquals(vanilla, merged.subList(0, vanilla.size()), "vanilla's first, in its own order");
        assertEquals(List.of(CONFIG, USER), merged.subList(vanilla.size(), merged.size()).stream().map(RegistryDataLoader.RegistryData::key).toList());
        Set<ResourceKey<?>> keys = new HashSet<>();
        merged.forEach(data -> assertTrue(keys.add(data.key()), "once: " + data.key()));
    }

    @Test
    void theGamesCrashReportLinesNameTheEntriesNamespaces() {
        // As the game words them (RegistryDataLoader's brief information), from the owner's log of 1.1.1 with Tectonic.
        String details = "-- Loading info --\nDetails:\n\tErrors: "
            + "\n\t\tminecraft:worldgen/density_function/lithostitched:region/overworld: Failed to parse lithostitched:region/overworld from pack lithostitched"
            + "\n\t\tminecraft:worldgen/density_function/lithostitched:region/the_end: Failed to parse lithostitched:region/the_end from pack lithostitched"
            + "\n\t\tminecraft:worldgen/noise_settings/tectonic:overworld: Unbound values in registry\n";
        assertEquals(List.of("lithostitched", "tectonic"), UnreadableData.from(details, new IllegalStateException()).namespaces());
        assertTrue(UnreadableData.from("nothing the game wrote", null).namespaces().isEmpty());
    }

    private static net.minecraft.server.packs.repository.PackRepository repository() {
        return ServerPacksSource.createPackRepository(packs, new DirectoryValidator(path -> false));
    }
}
