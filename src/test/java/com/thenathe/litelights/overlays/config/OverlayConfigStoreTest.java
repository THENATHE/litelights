package com.thenathe.litelights.overlays.config;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-time import of the standalone Dynamic Overlays mod's settings. */
class OverlayConfigStoreTest {
    @TempDir
    Path configDir;

    /** Stands in for LiteLights' config: the module's object, and how often it was written. */
    private final MemoryData data = new MemoryData();

    private static final class MemoryData implements OverlayConfigStore.ModuleData {
        JsonObject saved;
        final Map<String, JsonObject> beside = new LinkedHashMap<>();
        int writes;

        @Override
        public boolean exists() {
            return saved != null && !saved.isEmpty();
        }

        @Override
        public JsonObject read() {
            return saved;
        }

        @Override
        public void write(JsonObject object) {
            saved = object.deepCopy();
            writes++;
        }

        @Override
        public JsonObject readBeside(String key) {
            return beside.get(key);
        }

        @Override
        public void writeBeside(String key, JsonObject object) {
            beside.put(key, object.deepCopy());
        }
    }

    @Test
    void importsTheStandaloneSettingsAndLeavesTheOldFileAsItWas() throws IOException {
        byte[] original = fixture("config-0.2.0.json");
        Path old = configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE);
        Files.write(old, original);
        long modified = Files.getLastModifiedTime(old).toMillis();

        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.IMPORTED, loaded.origin());
        OverlayConfig config = loaded.config();
        assertEquals("clumps", config.fallbackStyle);
        assertEquals(5, config.edgeReach);
        assertEquals(80, config.edgeRoughness);
        assertEquals("high", config.edgeVariety);
        assertFalse(config.spillDownSteps);
        assertTrue(config.plainOverlays);
        assertEquals(List.of("Soft", "Hard"), config.groups.stream().map(group -> group.name).limit(2).toList());
        assertEquals("clean", config.find("minecraft:sand").style);

        assertArrayEquals(original, Files.readAllBytes(old), "the old file must not change");
        assertEquals(modified, Files.getLastModifiedTime(old).toMillis());
        assertEquals(1, data.writes, "the import is saved as LiteLights' own");
        assertEquals("clumps", data.saved.get("fallbackStyle").getAsString());
        try (Stream<Path> files = Files.list(configDir)) {
            assertEquals(List.of("dynamic_overlays.json"), files.map(path -> path.getFileName().toString()).toList(),
                "nothing is written beside it");
        }
    }

    @Test
    void importsAFileFromTheFirstRelease() throws IOException {
        Files.write(configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE), fixture("config-0.1.0.json"));

        OverlayConfig config = OverlayConfigStore.load(data, configDir).config();

        assertEquals("dithered", config.fallbackStyle);
        assertEquals(7, config.edgeReach);
        assertTrue(config.blendWithinGroup);
        assertFalse(config.find("minecraft:gravel").enabled);
        // Its stone bricks still had the old default, receive only, and were offered as a
        // default: they move to the Bricks style (0.1.7), in the player's own group.
        assertEquals("bricks", config.find("minecraft:stone_bricks").style);
        assertTrue(config.groups.stream().anyMatch(group -> group.name.equals("Built")
            && group.blocks.stream().anyMatch(entry -> entry.id.equals("minecraft:stone_bricks"))));
        // Fields 0.1.0 didn't have take their defaults.
        assertEquals("normal", config.edgeVariety);
        assertTrue(config.spillDownSteps);
        assertEquals(OverlayConfig.CURRENT_VERSION, config.configVersion);
    }

    @Test
    void movesTheSavedDefaultSizeLimitOf020LikeDynamicOverlaysDid() throws IOException {
        String text = new String(fixture("config-0.2.0.json"), StandardCharsets.UTF_8)
            .replace("\"largestOverlaySize\": 0", "\"largestOverlaySize\": 128");
        Files.writeString(configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE), text);

        assertEquals(OverlayConfig.MAX_OVERLAY_SIZE, OverlayConfigStore.load(data, configDir).config().largestOverlaySize);
    }

    @Test
    void neverImportsOverSettingsLiteLightsAlreadyHas() throws IOException {
        Files.write(configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE), fixture("config-0.2.0.json"));
        OverlayConfig own = new OverlayConfig();
        own.edgeReach = 2;
        own.fallbackStyle = "jagged";
        OverlayConfigStore.save(own, data);

        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.LITELIGHTS, loaded.origin());
        assertEquals(2, loaded.config().edgeReach);
        assertEquals("jagged", loaded.config().fallbackStyle);
    }

    @Test
    void importsOnlyOnce() throws IOException {
        Path old = configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE);
        Files.write(old, fixture("config-0.2.0.json"));
        OverlayConfig imported = OverlayConfigStore.load(data, configDir).config();
        imported.edgeReach = 3;
        OverlayConfigStore.save(imported, data);

        OverlayConfigStore.Loaded again = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.LITELIGHTS, again.origin());
        assertEquals(3, again.config().edgeReach);
    }

    @Test
    void startsFromDefaultsWhenTheOldFileIsUnreadableAndLeavesItAlone() throws IOException {
        Path old = configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE);
        Files.writeString(old, "{ not json");

        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.DEFAULTS, loaded.origin());
        assertEquals(new OverlayConfig().generationFingerprint(), loaded.config().generationFingerprint());
        assertEquals("{ not json", Files.readString(old));
        assertEquals(0, data.writes);
        try (Stream<Path> files = Files.list(configDir)) {
            assertEquals(1, files.count(), "no copy is kept beside the standalone mod's file");
        }
    }

    @Test
    void anUnreadableSavedObjectIsKeptAsideAndDefaultsAreUsed() {
        data.saved = new JsonObject();
        data.saved.addProperty("groups", "not a list");

        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.DEFAULTS, loaded.origin());
        assertEquals(0, data.writes, "nothing is written over it until the player changes something");
        assertEquals("not a list", data.beside.get("overlays_unreadable").get("groups").getAsString(),
            "a copy is kept before anything can replace it");
        assertEquals("overlays_unreadable", loaded.keptAs(), "the page can say where the copy is");
    }

    /**
     * R5: the player's hand-edited hierarchy broke, the defaults were saved over it, and a later
     * edit broke again. The first copy stays as it was and the second goes beside it; loading the
     * same broken settings again keeps no third copy.
     */
    @Test
    void aSecondBadLoadNeverReplacesTheKeptCopy() {
        data.saved = new JsonObject();
        data.saved.addProperty("groups", "first typo");
        OverlayConfigStore.load(data, configDir);
        OverlayConfigStore.save(new OverlayConfig(), data);
        data.saved.addProperty("groups", "second typo");

        OverlayConfigStore.Loaded second = OverlayConfigStore.load(data, configDir);
        OverlayConfigStore.Loaded again = OverlayConfigStore.load(data, configDir);

        assertEquals("first typo", data.beside.get("overlays_unreadable").get("groups").getAsString(),
            "the copy kept first is never replaced");
        assertEquals("second typo", data.beside.get("overlays_unreadable_2").get("groups").getAsString());
        assertEquals("overlays_unreadable_2", second.keptAs());
        assertEquals("overlays_unreadable_2", again.keptAs(), "the same broken settings reuse their copy");
        assertEquals(2, data.beside.size());
    }

    @Test
    void readableSettingsKeepNothingAside() {
        data.saved = OverlayConfig.GSON.toJsonTree(new OverlayConfig()).getAsJsonObject();
        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);
        assertEquals(OverlayConfigStore.Origin.LITELIGHTS, loaded.origin());
        assertEquals(null, loaded.keptAs());
        assertTrue(data.beside.isEmpty());
    }

    /**
     * Settings saved by another version of the module (one with a renderer or feature since removed)
     * may hold keys this one doesn't know. They load, and they survive the next save untouched.
     */
    @Test
    void keysFromAnotherVersionLoadAndSurviveASave() {
        OverlayConfig saved = new OverlayConfig();
        saved.edgeReach = 7;
        saved.fallbackStyle = "jagged";
        data.saved = OverlayConfig.GSON.toJsonTree(saved).getAsJsonObject();
        JsonObject removedTints = new JsonObject();
        removedTints.addProperty("minecraft:grass_block", "biome_grass");
        data.saved.add("removedRendererTints", removedTints);
        data.saved.addProperty("renderer", "removed_renderer");

        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.LITELIGHTS, loaded.origin());
        assertEquals(7, loaded.config().edgeReach);
        assertEquals("jagged", loaded.config().fallbackStyle);

        loaded.config().edgeReach = 3;
        OverlayConfigStore.save(loaded.config(), data);

        assertEquals(3, data.saved.get("edgeReach").getAsInt());
        assertEquals("removed_renderer", data.saved.get("renderer").getAsString());
        assertEquals("biome_grass", data.saved.getAsJsonObject("removedRendererTints").get("minecraft:grass_block").getAsString());
    }

    @Test
    void theRemovedFaceCullingSwitchLoadsHarmlesslyAndStaysInTheFile() {
        // Development builds of 0.1.7 had a switch for the face culling fix; it is always on now.
        OverlayConfig before = new OverlayConfig();
        before.edgeReach = 6;
        JsonObject savedWithSwitch = OverlayConfig.GSON.toJsonTree(before).getAsJsonObject();
        savedWithSwitch.addProperty("sodiumFacingFix", false);
        data.saved = savedWithSwitch;

        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.LITELIGHTS, loaded.origin(), "the file still reads as LiteLights' own");
        assertEquals(6, loaded.config().edgeReach, "the rest carries over");
        assertEquals(before.generationFingerprint(), loaded.config().generationFingerprint());
        OverlayConfigStore.save(loaded.config(), data);
        assertFalse(data.saved.get("sodiumFacingFix").getAsBoolean(), "the old value is kept as an unknown key");
        assertEquals(6, data.saved.get("edgeReach").getAsInt());
    }

    @Test
    void startsFromDefaultsWithNoFileAtAll() {
        OverlayConfigStore.Loaded loaded = OverlayConfigStore.load(data, configDir);

        assertEquals(OverlayConfigStore.Origin.DEFAULTS, loaded.origin());
        assertEquals(new OverlayConfig().generationFingerprint(), loaded.config().generationFingerprint());
    }

    @Test
    void keepsUnknownBlocksAndStylesFromTheOldFile() throws IOException {
        String text = new String(fixture("config-0.2.0.json"), StandardCharsets.UTF_8)
            .replace("\"style\": \"clean\"", "\"style\": \"some_future_style\"")
            .replace("\"id\": \"minecraft:stone\"", "\"id\": \"othermod:rock\"");
        Files.writeString(configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE), text);

        OverlayConfig config = OverlayConfigStore.load(data, configDir).config();

        assertEquals("some_future_style", config.find("minecraft:sand").style);
        assertTrue(config.find("othermod:rock") != null);
        String saved = data.saved.toString();
        assertTrue(saved.contains("some_future_style") && saved.contains("othermod:rock"));
    }

    /**
     * A file from 0.1.6, where every masonry block received only. The ones the player never
     * touched move to Bricks; every choice the player made stays; the masonry added in 0.1.7 is
     * offered with its new default; and once moved, a later "receive only" is kept.
     */
    @Test
    void movesUntouchedMasonryToBricksAndKeepsThePlayersChoices() {
        OverlayConfig old = new OverlayConfig();
        old.configVersion = 4;
        List<String> addedIn017 = List.of("minecraft:cracked_deepslate_bricks", "minecraft:cracked_deepslate_tiles",
            "minecraft:cracked_polished_blackstone_bricks", "minecraft:nether_bricks", "minecraft:cracked_nether_bricks",
            "minecraft:red_nether_bricks", "minecraft:quartz_bricks", "minecraft:resin_bricks");
        old.offeredDefaults.removeAll(addedIn017);
        for (HardnessGroup group : old.groups) {
            group.blocks.removeIf(entry -> addedIn017.contains(entry.id));
            if (group.name.equals("Masonry")) {
                group.blocks.forEach(entry -> entry.style = BlockEntry.RECEIVE_ONLY);
            }
        }
        old.find("minecraft:bricks").style = "jagged";
        old.find("minecraft:mud_bricks").style = BlockEntry.USE_DEFAULT;
        old.find("minecraft:tuff_bricks").enabled = false;
        // A block the player added themselves, with "receive only" on purpose.
        old.offeredDefaults.remove("minecraft:end_stone_bricks");
        data.saved = OverlayConfig.GSON.toJsonTree(old).getAsJsonObject();

        OverlayConfig config = OverlayConfigStore.load(data, configDir).config();

        assertEquals("bricks", config.find("minecraft:stone_bricks").style);
        assertEquals("bricks", config.find("minecraft:deepslate_tiles").style);
        assertEquals("bricks", config.find("minecraft:tuff_bricks").style, "a block turned off keeps its off switch only");
        assertFalse(config.find("minecraft:tuff_bricks").enabled);
        assertEquals("jagged", config.find("minecraft:bricks").style);
        assertEquals(BlockEntry.USE_DEFAULT, config.find("minecraft:mud_bricks").style);
        assertEquals(BlockEntry.RECEIVE_ONLY, config.find("minecraft:end_stone_bricks").style);
        assertEquals(BlockEntry.RECEIVE_ONLY, config.find("minecraft:smooth_stone").style, "plain masonry receives only");
        assertEquals(BlockEntry.RECEIVE_ONLY, config.find("minecraft:polished_andesite").style);
        for (String added : addedIn017) {
            assertEquals("bricks", config.find(added).style, added);
            assertTrue(config.offeredDefaults.contains(added), added);
        }
        assertEquals(OverlayConfig.CURRENT_VERSION, config.configVersion);

        config.find("minecraft:stone_bricks").style = BlockEntry.RECEIVE_ONLY;
        OverlayConfigStore.save(config, data);
        OverlayConfig again = OverlayConfigStore.load(data, configDir).config();
        assertEquals(BlockEntry.RECEIVE_ONLY, again.find("minecraft:stone_bricks").style, "a choice made after the move stays");
    }

    /** A Dynamic Overlays 0.2.0 file had no masonry left in it: the new masonry arrives in a group of its own, as Bricks. */
    @Test
    void offersTheNewMasonryToAnOldFileWithout() throws IOException {
        Files.write(configDir.resolve(OverlayConfigStore.DYNAMIC_OVERLAYS_FILE), fixture("config-0.2.0.json"));

        OverlayConfig config = OverlayConfigStore.load(data, configDir).config();

        assertEquals("bricks", config.find("minecraft:nether_bricks").style);
        assertEquals(null, config.find("minecraft:stone_bricks"), "a default the player removed stays removed");
        assertEquals("clean", config.find("minecraft:sand").style);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream stream = OverlayConfigStoreTest.class.getResourceAsStream("/overlays/" + name)) {
            return stream.readAllBytes();
        }
    }
}
