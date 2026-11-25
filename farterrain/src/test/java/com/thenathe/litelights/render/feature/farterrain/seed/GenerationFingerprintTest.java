package com.thenathe.litelights.render.feature.farterrain.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The fingerprint two sides compare: equal for equal data, and naming what differs when not. */
class GenerationFingerprintTest {
    private static InstalledGenerationData installed;

    @BeforeAll
    static void load() {
        installed = VanillaGenerationData.get();
    }

    @Test
    void theSameDataGivesTheSameFingerprint() {
        GenerationFingerprint one = GenerationFingerprint.of(installed.lookups(), installed.packs());
        GenerationFingerprint two = GenerationFingerprint.of(installed.lookups(), installed.packs());
        assertTrue(one.complete(), "every vanilla world-generation registry encodes: " + unknownIn(one));
        assertEquals(one.hash(), two.hash());
        assertTrue(one.differences(two).isEmpty());
        assertTrue(one.entries().keySet().stream().anyMatch(key -> key.startsWith("registry:minecraft:worldgen/noise_settings:minecraft")));
        assertTrue(one.entries().keySet().stream().anyMatch(key -> key.startsWith("registry:minecraft:worldgen/biome:minecraft")));
    }

    @Test
    void differencesNameTheModPackOrNamespace() {
        GenerationFingerprint mine = GenerationFingerprint.of(installed.lookups(), installed.packs());
        Map<String, String> server = new TreeMap<>(mine.entries());
        server.put("registry:minecraft:worldgen/noise_settings:minecraft", "changed by a data pack");
        server.put("mod:terralith", "2.5.8");
        server.put("pack:file/amplified-ish.zip", "on");
        List<GenerationFingerprint.Difference> found = mine.differences(GenerationFingerprint.of(server));
        assertTrue(found.contains(new GenerationFingerprint.Difference(GenerationFingerprint.Difference.Kind.MOD, "terralith", null, "2.5.8")), found.toString());
        assertTrue(found.stream().anyMatch(difference -> difference.kind() == GenerationFingerprint.Difference.Kind.PACK
            && difference.name().equals("file/amplified-ish.zip")), found.toString());
        assertTrue(found.stream().anyMatch(difference -> difference.kind() == GenerationFingerprint.Difference.Kind.DATA
            && difference.name().equals("minecraft")), found.toString());
        assertTrue(found.get(0).describe().contains("terralith") || found.stream().anyMatch(d -> d.describe().contains("only on the server")));
        assertNotEquals(mine.hash(), GenerationFingerprint.of(server).hash());
    }

    @Test
    void unknownNeverMatches() {
        Map<String, String> entries = Map.of("format", String.valueOf(GenerationFingerprint.FORMAT),
            "registry:minecraft:worldgen/biome:minecraft", GenerationFingerprint.UNKNOWN);
        GenerationFingerprint here = GenerationFingerprint.of(entries);
        GenerationFingerprint there = GenerationFingerprint.of(entries);
        assertEquals(1, here.differences(there).size());
        assertTrue(!here.complete());
    }

    @Test
    void theSeedIsNotInIt() {
        PredictionInputs one = installed.inputsFor("minecraft:overworld", 1L).orElseThrow();
        PredictionInputs other = installed.inputsFor("minecraft:overworld", 2L).orElseThrow();
        assertEquals(GenerationFingerprint.of(one.registries(), installed.packs()).hash(),
            GenerationFingerprint.of(other.registries(), installed.packs()).hash());
        assertTrue(!one.toString().contains("1"), "the inputs never print their seed: " + one);
    }

    private static List<String> unknownIn(GenerationFingerprint fingerprint) {
        return fingerprint.entries().entrySet().stream().filter(entry -> entry.getValue().equals(GenerationFingerprint.UNKNOWN))
            .map(Map.Entry::getKey).toList();
    }
}
