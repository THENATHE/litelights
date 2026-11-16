package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.overlays.VanillaAssets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the brick pattern of every vanilla 26.3 masonry texture and checks it against the
 * pattern measured by hand from the texels (DECISIONS, overlays17). Each course is written as
 * {@code height:joints}, heights and joint columns in texels at 16x, from the top course down.
 */
class BrickGridTest {
    private static final String STONE_BRICKS = "8:15 8:7";
    private static final String BRICKS = "4:3,11 4:7,15 4:3,11 4:7,15";
    private static final String NETHER_BRICKS = "4:3,11 4:7,15 4:1,5,9,13 4:3,11";
    private static final String DEEPSLATE_TILES = "5:3 4:15 4:4 3:12";

    private static final Map<String, String> MEASURED = new LinkedHashMap<>();

    static {
        MEASURED.put("bricks", BRICKS);
        MEASURED.put("stone_bricks", STONE_BRICKS);
        MEASURED.put("mossy_stone_bricks", STONE_BRICKS);
        MEASURED.put("cracked_stone_bricks", STONE_BRICKS);
        MEASURED.put("deepslate_bricks", STONE_BRICKS);
        MEASURED.put("cracked_deepslate_bricks", STONE_BRICKS);
        MEASURED.put("deepslate_tiles", DEEPSLATE_TILES);
        MEASURED.put("cracked_deepslate_tiles", DEEPSLATE_TILES);
        MEASURED.put("tuff_bricks", STONE_BRICKS);
        MEASURED.put("mud_bricks", "8:0,8 8:4,12");
        MEASURED.put("polished_blackstone_bricks", STONE_BRICKS);
        MEASURED.put("end_stone_bricks", "8:15 8:8");
        MEASURED.put("nether_bricks", NETHER_BRICKS);
        MEASURED.put("red_nether_bricks", NETHER_BRICKS);
        MEASURED.put("quartz_bricks", "8:15 8:3,11");
        MEASURED.put("resin_bricks", BRICKS);
        MEASURED.put("cinnabar_bricks", STONE_BRICKS);
        MEASURED.put("sulfur_bricks", STONE_BRICKS);
    }

    /** Plain blocks: no pattern may be read from them, so they never get cut into bricks by accident. */
    private static final List<String> PLAIN = List.of("stone", "smooth_stone", "polished_andesite", "polished_diorite",
        "polished_granite", "polished_deepslate", "polished_blackstone", "polished_tuff", "polished_cinnabar",
        "polished_sulfur", "cut_sandstone", "sandstone", "smooth_basalt", "andesite", "dark_prismarine", "purpur_block");

    @Test
    void readsEveryVanillaBrickPattern() {
        for (Map.Entry<String, String> texture : MEASURED.entrySet()) {
            BrickGrid grid = BrickGrid.detect(VanillaAssets.blockTexture(texture.getKey()).pixels(), 16);
            assertNotNull(grid, texture.getKey() + ": no pattern read");
            assertEquals(texture.getValue(), grid.describe(), texture.getKey());
        }
    }

    @Test
    void readsNoPatternFromPlainBlocks() {
        for (String texture : PLAIN) {
            BrickGrid grid = BrickGrid.detect(VanillaAssets.blockTexture(texture).pixels(), 16);
            assertNull(grid, texture + " was read as " + (grid == null ? "" : grid.describe()));
        }
    }

    /**
     * Cracks that cut the mortar, and prismarine's irregular pattern, can't be read; the nearest
     * measured pattern stands in. Cracked nether bricks lose one mortar line to a crack but still
     * read as whole bricks.
     */
    @Test
    void fallsBackToTheNearestMeasuredPattern() {
        SourceTexture cracked = VanillaAssets.blockTexture("cracked_polished_blackstone_bricks");
        assertNull(BrickGrid.detect(cracked.pixels(), 16));
        BrickGrid stand = BrickGrid.of(cracked);
        assertEquals(BrickGrid.Origin.STONE_BRICKS_PRESET, stand.origin());
        assertEquals("8:15 8:7", stand.describe());

        assertEquals("4:3,11 8:7 4:3,12", BrickGrid.of(VanillaAssets.blockTexture("cracked_nether_bricks")).describe());
        assertNotNull(BrickGrid.of(VanillaAssets.blockTexture("prismarine_bricks")));
    }

    @Test
    void thePresetsMatchTheTexturesTheyWereMeasuredFrom() {
        assertEquals("4:3,11 4:7,15 4:3,11 4:7,15", BrickGrid.preset(16, BrickGrid.Origin.BRICKS_PRESET).describe());
        assertEquals("8:15 8:7", BrickGrid.preset(16, BrickGrid.Origin.STONE_BRICKS_PRESET).describe());
        assertEquals("16:31 16:15", BrickGrid.preset(32, BrickGrid.Origin.STONE_BRICKS_PRESET).describe());
    }

    /** A pack that draws vanilla's bricks at a higher resolution gets the same pattern, scaled. */
    @Test
    void readsTheSamePatternAtHigherResolutions() {
        for (Map.Entry<String, String> texture : MEASURED.entrySet()) {
            SourceTexture small = VanillaAssets.blockTexture(texture.getKey());
            for (int scale : new int[] {2, 4, 8}) {
                BrickGrid grid = BrickGrid.detect(upscaled(small, scale), 16 * scale);
                assertNotNull(grid, texture.getKey() + " at " + 16 * scale + "x");
                assertEquals(scaled(texture.getValue(), scale), grid.describe(), texture.getKey() + " at " + 16 * scale + "x");
            }
        }
    }

    /** Every texel belongs to exactly one brick, and every brick has mortar on its right and bottom. */
    @Test
    void dividesTheTextureIntoWholeBricks() {
        for (String texture : MEASURED.keySet()) {
            BrickGrid grid = BrickGrid.of(VanillaAssets.blockTexture(texture));
            int[] owners = new int[16 * 16];
            for (BrickGrid.Brick brick : grid.bricks()) {
                for (int y = brick.top(); y <= brick.bottom(); y++) {
                    for (int x = brick.left(); x <= brick.right(); x++) {
                        owners[y * 16 + x]++;
                        assertEquals(brick, grid.brickAt(x, y));
                    }
                }
                if (!brick.continuesRight()) {
                    assertTrue(grid.isMortar(brick.right(), brick.top()), texture + ": brick " + brick + " has no joint");
                }
                if (!brick.continuesDown()) {
                    assertTrue(grid.isMortar(brick.left(), brick.bottom()), texture + ": brick " + brick + " has no bed");
                }
            }
            for (int owner : owners) {
                assertEquals(1, owner, texture);
            }
        }
    }

    private static int[] upscaled(SourceTexture texture, int scale) {
        int size = texture.size() * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = texture.pixels()[(y / scale) * texture.size() + x / scale];
            }
        }
        return pixels;
    }

    /** A 16x description at a higher resolution: heights grow, and each joint ends on its last column. */
    private static String scaled(String description, int scale) {
        StringBuilder text = new StringBuilder();
        for (String course : description.split(" ")) {
            String[] parts = course.split(":");
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(Integer.parseInt(parts[0]) * scale).append(':');
            String[] joints = parts[1].split(",");
            for (int i = 0; i < joints.length; i++) {
                text.append(i == 0 ? "" : ",").append((Integer.parseInt(joints[i]) + 1) * scale - 1);
            }
        }
        return text.toString();
    }
}
