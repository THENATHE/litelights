package com.thenathe.litelights.overlays.generation;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins every mask EdgeShape draws, in every style, both kinds (full and blend), at several sizes,
 * to the output of Dynamic Overlays 0.2.3. Speed work on the generator must leave the overlays
 * pixel for pixel the same; if a change to the shapes is meant, update the digest.
 */
class EdgeShapeOutputTest {
    /**
     * Recorded from 0.1.6 (the code as moved in from Dynamic Overlays 0.2.3, plus its speed work)
     * over every mask except the diagonal-only ones 0.1.7 dropped (corner-only tiles and outer
     * corners), so it shows that dropping them changed nothing else. 0.1.6's digest over all of
     * them was a56e6d22ac38be20ddf90ad73f72f345b8c53cd62108dff6455b85df3088efe6.
     */
    private static final String DIGEST = "0c907a8e7b2fc40c6962d309f38cfd8f0515a5ac7bf19dee8e26bf1eccbda077";

    @Test
    void masksAreUnchanged() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (EdgeStyle style : EdgeShape.STYLES) {
            for (boolean blend : new boolean[] {false, true}) {
                for (int size : new int[] {8, 16, 37, 64}) {
                    EdgeShape shape = new EdgeShape(style, size, 4, 0.6, blend, 12345);
                    for (int tile = 0; tile < TileLayout.TILE_COUNT; tile++) {
                        if (TileLayout.corner(tile) == 0) {
                            digest.update(shape.cover(tile));
                        }
                    }
                    for (int direction = 0; direction < 4; direction++) {
                        for (int start = 0; start < 3; start++) {
                            for (int end = 0; end < 3; end++) {
                                for (int wave = 0; wave < 2; wave++) {
                                    digest.update(shape.band(direction, start, end, wave, 3));
                                }
                            }
                        }
                        digest.update(shape.innerCorner(direction, 1, 3));
                    }
                }
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        System.out.println("EdgeShape digest " + actual);
        assertEquals(DIGEST, actual);
    }
}
