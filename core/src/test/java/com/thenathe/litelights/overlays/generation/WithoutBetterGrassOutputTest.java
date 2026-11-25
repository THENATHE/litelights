package com.thenathe.litelights.overlays.generation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Without LambdaBetterGrass, a build of the default hierarchy from vanilla's textures writes what
 * 0.1.7's overlays17 merge (2a0cda8) wrote, byte for byte: every tile, and every properties file
 * except {@code variety.firstLayerTints}, the line lbgfix17 added so a face of two quads gets its
 * pieces once (it changes how many quads are emitted, not what they show). The digests were taken
 * from that commit with this same test.
 */
class WithoutBetterGrassOutputTest {
    private static final String ADDED_LINE = "variety.firstLayerTints=";

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "VARIETY, d05579d273143af148c394420dc886b9472c7da829da1c6642cb016c44e642e4, "
            + "3c1845b72848fba4814ebe5d7fb4fbe2868fa1c87086bb95d2bfcc5902a367b9",
        "PLAIN, bf92ba4878cb24e9245282c23785bc018462c897ec215646c9916869052ca38d, "
            + "5d5cfa3ce289d66982a23efda1942c4a94e525566ad8c18e87132ffd40bc6729"
    })
    void matchesTheBuildBeforeLambdaBetterGrassRules(OverlayFormat format, String tilesDigest, String propertiesDigest) {
        GeneratedFiles files = VanillaSources.build(new OverlayConfig(), format, 1, 0);
        Map<String, byte[]> sorted = new TreeMap<>(files.files());
        MessageDigest tiles = sha256();
        MessageDigest properties = sha256();
        sorted.forEach((path, bytes) -> {
            MessageDigest digest = path.endsWith(".properties") ? properties : tiles;
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update(path.endsWith(".properties") ? withoutAddedLine(bytes) : bytes);
        });
        String tilesFound = HexFormat.of().formatHex(tiles.digest());
        String propertiesFound = HexFormat.of().formatHex(properties.digest());
        assertAll(() -> assertEquals(tilesDigest, tilesFound, "tiles"),
            () -> assertEquals(propertiesDigest, propertiesFound, "properties files"));
    }

    private static byte[] withoutAddedLine(byte[] properties) {
        String text = new String(properties, StandardCharsets.UTF_8);
        return text.lines()
            .filter(line -> !line.startsWith(ADDED_LINE))
            .map(line -> line + "\n")
            .collect(Collectors.joining())
            .getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
