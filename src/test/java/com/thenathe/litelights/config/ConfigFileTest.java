package com.thenathe.litelights.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ConfigFileTest {
    enum Quality { LOW, NORMAL, HIGH }

    @TempDir
    Path folder;

    /** A fresh registry with one option of each type, as a module would register them. */
    private static final class Fixture {
        final OptionRegistry registry = new OptionRegistry();
        final BoolOption toggle = registry.register(BoolOption.builder("test.toggle", true).at("page", "section").build());
        final EnumOption<Quality> quality = registry.register(
                EnumOption.builder("test.quality", Quality.class, Quality.NORMAL).at("page", "section").build());
        final IntOption distance = registry.register(IntOption.builder("test.distance", 256, 64, 512, 16).at("page", "section").build());
        final FloatOption strength = registry.register(FloatOption.builder("test.strength", 1.0F, 0.0F, 2.0F, 0.05F).at("page", "section").build());
    }

    private Path file() {
        return folder.resolve("litelights.json");
    }

    private ConfigFile load(OptionRegistry registry) {
        ConfigFile file = new ConfigFile(file(), registry, ConfigMigrations.standard());
        file.load();
        return file;
    }

    private JsonObject written() throws IOException {
        return JsonParser.parseString(Files.readString(file())).getAsJsonObject();
    }

    @Test
    void valuesSurviveASaveAndLoad() {
        Fixture first = new Fixture();
        ConfigFile file = load(first.registry);
        first.toggle.set(false);
        first.quality.set(Quality.HIGH);
        first.distance.set(128);
        first.strength.set(0.35F);
        file.save();

        Fixture second = new Fixture();
        load(second.registry);
        assertFalse(second.toggle.on());
        assertEquals(Quality.HIGH, second.quality.get());
        assertEquals(128, second.distance.value());
        assertEquals(0.35F, second.strength.value(), 1.0E-6);
    }

    @Test
    void missingFileAndMissingKeysGiveDefaults() throws IOException {
        Fixture fresh = new Fixture();
        load(fresh.registry);
        assertEquals(0, fresh.registry.changedCount());

        Files.writeString(file(), "{\"schema\": 1, \"options\": {\"test.distance\": 96}}");
        Fixture partial = new Fixture();
        load(partial.registry);
        assertEquals(96, partial.distance.value());
        assertTrue(partial.toggle.on());
        assertEquals(Quality.NORMAL, partial.quality.get());
    }

    @Test
    void unknownKeysAndModuleDataAreKept() throws IOException {
        Files.writeString(file(), """
                {
                  "schema": 1,
                  "options": {"test.toggle": false, "future.option": "ultra", "overlays.reach": 3, "shadows.forest_prepass": true},
                  "modules": {"overlays": {"groups": [1, 2, 3]}},
                  "somethingNewer": {"kept": true}
                }""");
        Fixture fixture = new Fixture();
        ConfigFile file = load(fixture.registry);
        fixture.distance.set(320);
        file.save();

        JsonObject saved = written();
        JsonObject options = saved.getAsJsonObject("options");
        assertEquals("ultra", options.get("future.option").getAsString());
        assertEquals(3, options.get("overlays.reach").getAsInt());
        // A removed option's saved value (Forest prepass, gone since the integration pass) stays readable.
        assertTrue(options.get("shadows.forest_prepass").getAsBoolean());
        assertFalse(options.get("test.toggle").getAsBoolean());
        assertEquals(320, options.get("test.distance").getAsInt());
        assertEquals(3, saved.getAsJsonObject("modules").getAsJsonObject("overlays").getAsJsonArray("groups").size());
        assertTrue(saved.getAsJsonObject("somethingNewer").get("kept").getAsBoolean());
    }

    @Test
    void anOptionRegisteredLaterStillGetsItsSavedValue() throws IOException {
        Files.writeString(file(), "{\"options\": {\"module.late\": 7}}");
        OptionRegistry registry = new OptionRegistry();
        load(registry);
        IntOption late = registry.register(IntOption.builder("module.late", 1, 0, 10, 1).at("page", "section").build());
        assertEquals(7, late.value());
    }

    @Test
    void unreadableValuesFallBackToDefaultButStayInTheFile() throws IOException {
        Files.writeString(file(), "{\"options\": {\"test.toggle\": \"yes\", \"test.quality\": \"ultra\", \"test.distance\": 128}}");
        Fixture fixture = new Fixture();
        ConfigFile file = load(fixture.registry);
        assertTrue(fixture.toggle.on());
        assertEquals(Quality.NORMAL, fixture.quality.get());
        assertEquals(128, fixture.distance.value());
        file.save();

        JsonObject unreadable = written().getAsJsonObject("unreadable");
        assertEquals("yes", unreadable.get("test.toggle").getAsString());
        assertEquals("ultra", unreadable.get("test.quality").getAsString());

        // Once the player picks a value on purpose, the old unreadable one goes.
        fixture.quality.set(Quality.LOW);
        file.save();
        unreadable = written().getAsJsonObject("unreadable");
        assertFalse(unreadable.has("test.quality"));
        assertTrue(unreadable.has("test.toggle"));
    }

    @Test
    void storedValuesAreClampedAndSnappedIntoRange() throws IOException {
        Files.writeString(file(), "{\"options\": {\"test.distance\": 9999, \"test.strength\": -4.0}}");
        Fixture high = new Fixture();
        load(high.registry);
        assertEquals(512, high.distance.value());
        assertEquals(0.0F, high.strength.value());

        Files.writeString(file(), "{\"options\": {\"test.distance\": 100, \"test.strength\": 0.333}}");
        Fixture between = new Fixture();
        load(between.registry);
        assertEquals(96, between.distance.value());
        assertEquals(0.35F, between.strength.value(), 1.0E-6);
    }

    @Test
    void settersClampToo() {
        Fixture fixture = new Fixture();
        fixture.distance.set(1);
        assertEquals(64, fixture.distance.value());
        fixture.strength.set(Float.NaN);
        assertEquals(1.0F, fixture.strength.value());
        fixture.strength.set(0.1F + 0.2F);
        assertEquals(0.3F, fixture.strength.value());
    }

    @Test
    void olderSchemasAreMigratedAndBackedUp() throws IOException {
        Files.writeString(file(), "{\"schema\": 1, \"options\": {\"old.distance\": 200}}");
        List<String> ran = new ArrayList<>();
        ConfigMigrations chain = new ConfigMigrations(3, List.of(
                new ConfigMigrations.Step(1, "rename old.distance", root -> {
                    JsonObject options = root.getAsJsonObject("options");
                    options.add("test.distance", options.remove("old.distance"));
                    ran.add("1");
                }),
                new ConfigMigrations.Step(2, "nothing to move", root -> ran.add("2"))));
        Fixture fixture = new Fixture();
        ConfigFile file = new ConfigFile(file(), fixture.registry, chain);
        file.load();
        assertEquals(List.of("1", "2"), ran);
        assertEquals(208, fixture.distance.value(), "200 snaps to the 16-block step from 64");
        assertTrue(Files.exists(folder.resolve("litelights.json.schema1.bak")));

        file.save();
        assertEquals(3, written().get("schema").getAsInt());
        assertFalse(written().getAsJsonObject("options").has("old.distance"));
    }

    @Test
    void theJumpSplashSwitchBecomesAChoiceAndKeepsOff() {
        for (boolean on : new boolean[] {true, false}) {
            JsonObject root = com.google.gson.JsonParser.parseString(
                "{\"schema\": 1, \"options\": {\"water.jump_splash\": " + on + ", \"water.edge_foam\": false}}").getAsJsonObject();
            assertEquals(ConfigMigrations.CURRENT_SCHEMA, ConfigMigrations.standard().migrate(root, 1));
            JsonObject options = root.getAsJsonObject("options");
            assertEquals(on ? "advanced" : "off", options.get("water.jump_splash").getAsString());
            assertFalse(options.get("water.edge_foam").getAsBoolean(), "everything else is left as it was");
        }
        JsonObject chosen = com.google.gson.JsonParser.parseString("{\"options\": {\"water.jump_splash\": \"basic\"}}").getAsJsonObject();
        ConfigMigrations.jumpSplashChoices(chosen);
        assertEquals("basic", chosen.getAsJsonObject("options").get("water.jump_splash").getAsString());
    }

    @Test
    void aBrokenFileIsMovedAsideNotLost() throws IOException {
        Files.writeString(file(), "{ this is not json");
        Fixture fixture = new Fixture();
        ConfigFile file = load(fixture.registry);
        assertEquals(0, fixture.registry.changedCount());
        try (var listing = Files.list(folder)) {
            assertTrue(listing.anyMatch(path -> path.getFileName().toString().startsWith("litelights.json.broken-")
                && readQuietly(path).equals("{ this is not json")));
        }
        assertNull(file.notSavingReason(), "with the broken file safely aside, saving goes on as usual");
        fixture.distance.set(128);
        file.save();
        assertEquals(128, written().getAsJsonObject("options").get("test.distance").getAsInt());
    }

    /**
     * R2: a file that can't be read (locked by a virus scanner or a sync tool on Windows; here, no
     * read permission) still holds the player's settings. The session runs on the defaults and
     * never saves, so the file is never replaced, and nothing is moved aside.
     */
    @Test
    void anUnreadableFileIsNeverReplacedByTheDefaults() throws IOException {
        String saved = "{\"schema\": 1, \"options\": {\"test.distance\": 96}}";
        Files.writeString(file(), saved);
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("---------"));
        Fixture fixture = new Fixture();
        ConfigFile file;
        try {
            assumeFalse(Files.isReadable(file()), "running with permissions that read anything (root)");
            file = load(fixture.registry);
        } finally {
            Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-------"));
        }
        assertEquals(256, fixture.distance.value(), "the defaults are used");
        assertNotNull(file.notSavingReason());

        fixture.distance.set(320);
        file.save();
        file.save();
        assertEquals(saved, Files.readString(file()), "the player's file is untouched, even once it can be read again");
        try (var listing = Files.list(folder)) {
            assertEquals(1, listing.count(), "nothing was moved aside or written beside it");
        }
    }

    /** R2: a broken file that can't be renamed aside (its folder is read-only) is never written over either. */
    @Test
    void aBrokenFileThatCantBeMovedAsideIsNeverReplaced() throws IOException {
        Files.writeString(file(), "{ this is not json");
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
        Fixture fixture = new Fixture();
        ConfigFile file;
        try {
            assumeFalse(Files.isWritable(folder), "running with permissions that write anything (root)");
            file = load(fixture.registry);
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        }
        assertNotNull(file.notSavingReason());

        fixture.toggle.set(false);
        file.save();
        assertEquals("{ this is not json", Files.readString(file()), "the folder is writable again, and still nothing replaced it");
        try (var listing = Files.list(folder)) {
            assertEquals(1, listing.count());
        }
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            return "";
        }
    }

    @Test
    void listenersRunOnlyForRealChanges() {
        Fixture fixture = new Fixture();
        AtomicInteger optionCalls = new AtomicInteger();
        List<String> seen = new ArrayList<>();
        fixture.distance.addListener((oldValue, newValue) -> optionCalls.incrementAndGet());
        fixture.registry.addListener(option -> seen.add(option.id()));
        long before = fixture.registry.changeCount();

        fixture.distance.set(256);
        fixture.distance.set(300);
        fixture.distance.set(304);
        fixture.toggle.set(false);

        assertEquals(1, optionCalls.get(), "256 is the current value and 300 snaps to 304");
        assertEquals(List.of("test.distance", "test.toggle"), seen);
        assertEquals(before + 2, fixture.registry.changeCount());
        assertTrue(fixture.registry.isDirty());
        assertEquals(2, fixture.registry.changedCount());
        fixture.registry.resetAll();
        assertEquals(0, fixture.registry.changedCount());
    }
}
