package com.thenathe.litelights.overlays.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.hierarchy.OverlayPlan;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every overlay from a tinted source takes the source's tint, and none shows a colour baked into a
 * texture. The source here is built like a grass block: a tinted top, and two layers on each side,
 * a dirt side whose fringe is painted a fixed green under a tinted grey fringe layer.
 */
class OverlayTintTest {
    private static final String GRASS = "minecraft:grass_block";
    private static final String TOP = "minecraft:block/grass_block_top";
    private static final String SIDE = "minecraft:block/grass_block_side";
    private static final String FRINGE = "minecraft:block/grass_block_side_overlay";
    private static final int SIZE = 16;
    /** The side's fringe rows, painted in the default green the fringe layer hides. */
    private static final int FRINGE_ROWS = 4;
    private static final int BAKED_GREEN = 0xFF74B44A;
    private static final int DIRT = 0xFF593D29;
    private static final int GREY = 0xFFA0A0A0;
    private static final Pattern LAYER = Pattern.compile("_(?:spill|blend)(\\d+)[a-z]*\\.properties$");

    /** Layer numbers as the generator counts them: the top's layers first, then the side's. */
    private static final Map<Integer, Boolean> TINTED = Map.of(0, true, 1, false, 2, true);

    @ParameterizedTest(name = "{0}, LambdaBetterGrass {1}")
    @CsvSource({"VARIETY, false", "VARIETY, true", "PLAIN, false", "PLAIN, true"})
    void tintedLayersCarryTheirTintAndNothingBakedShows(OverlayFormat format, boolean betterGrass) throws IOException {
        OverlayConfig config = new OverlayConfig();
        OverlayPlan plan = new OverlayPlan(List.of(new OverlayPlan.Source(GRASS, EdgeStyle.ORGANIC,
            Set.of("minecraft:dirt", "minecraft:stone", "minecraft:sand"), Set.of())));
        OverlayGenerator.Input input = new OverlayGenerator.Input(config, null, Map.of(GRASS, Map.of("snowy", "false")),
            "litelights", format, 16384, betterGrass);

        GeneratedFiles files = OverlayGenerator.generate(plan, new GrassSources(betterGrass), input);

        Map<Integer, Integer> filesPerLayer = new HashMap<>();
        for (Map.Entry<String, byte[]> file : files.files().entrySet()) {
            if (!file.getKey().endsWith(".properties")) {
                continue;
            }
            Matcher layer = LAYER.matcher(file.getKey());
            assertTrue(layer.find(), "unexpected file name " + file.getKey());
            int index = Integer.parseInt(layer.group(1));
            filesPerLayer.merge(index, 1, Integer::sum);
            String text = new String(file.getValue(), StandardCharsets.UTF_8);
            if (TINTED.get(index)) {
                assertTrue(text.contains("\ntintIndex=0\n"), file.getKey() + " is from a tinted layer but has no tintIndex");
                assertTrue(text.contains("\ntintBlock=" + GRASS + "\n"), file.getKey() + " is from a tinted layer but has no tintBlock");
            } else {
                assertFalse(text.contains("tintIndex="), file.getKey() + " is from an untinted layer but has a tintIndex");
            }
        }
        for (int layer : TINTED.keySet()) {
            assertTrue(filesPerLayer.getOrDefault(layer, 0) > 0, "no overlay was written for layer " + layer);
        }

        int fringePieces = 0;
        for (Map.Entry<String, byte[]> file : files.files().entrySet()) {
            if (!file.getKey().endsWith(".png")) {
                continue;
            }
            BufferedImage piece = ImageIO.read(new ByteArrayInputStream(file.getValue()));
            boolean grey = false;
            for (int y = 0; y < piece.getHeight(); y++) {
                for (int x = 0; x < piece.getWidth(); x++) {
                    int texel = piece.getRGB(x, y);
                    assertTrue(texel != BAKED_GREEN, file.getKey() + " shows the side's baked green at " + x + "," + y);
                    grey |= texel == GREY;
                }
            }
            if (grey) {
                fringePieces++;
            }
        }
        assertTrue(fringePieces > 0, "the tinted fringe was drawn into no piece");
    }

    @Test
    void aLayerIsClearedOnlyWhereTheLayerAboveIsSolid() {
        SourceTexture side = new SourceTexture(SIDE, SIZE, sidePixels(), false);
        SourceTexture fringe = new SourceTexture(FRINGE, SIZE, fringePixels(), false);
        SourceTexture visible = side.withoutTexelsUnder(fringe);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int expected = y < FRINGE_ROWS ? 0 : DIRT;
                assertEquals(expected, visible.pixels()[y * SIZE + x], "texel " + x + "," + y);
            }
        }
        // A fringe layer at twice the size covers the same rows.
        SourceTexture largeFringe = new SourceTexture(FRINGE, SIZE * 2, scaledUp(fringePixels()), false);
        assertEquals(0, side.withoutTexelsUnder(largeFringe).pixels()[(FRINGE_ROWS - 1) * SIZE]);
        assertEquals(DIRT, side.withoutTexelsUnder(largeFringe).pixels()[FRINGE_ROWS * SIZE]);
    }

    private static int[] sidePixels() {
        int[] pixels = new int[SIZE * SIZE];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i / SIZE < FRINGE_ROWS ? BAKED_GREEN : DIRT;
        }
        return pixels;
    }

    private static int[] fringePixels() {
        int[] pixels = new int[SIZE * SIZE];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i / SIZE < FRINGE_ROWS ? GREY : 0;
        }
        return pixels;
    }

    private static int[] scaledUp(int[] pixels) {
        int[] large = new int[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE * 2; y++) {
            for (int x = 0; x < SIZE * 2; x++) {
                large[y * SIZE * 2 + x] = pixels[(y / 2) * SIZE + x / 2];
            }
        }
        return large;
    }

    private static int[] filled(int colour) {
        int[] pixels = new int[SIZE * SIZE];
        Arrays.fill(pixels, colour);
        return pixels;
    }

    /** A grass block and three plain receivers, without any resource packs. */
    private record GrassSources(boolean betterGrass) implements OverlayGenerator.Sources {
        @Override
        public Map<String, FaceLayers> layers(OverlayPlan plan, OverlayGenerator.Input input, List<String> skipped,
                                              ExecutorService pool) {
            Map<String, FaceLayers> layers = new HashMap<>();
            layers.put(GRASS, new FaceLayers(List.of(new FaceLayers.Layer(TOP, 0)),
                List.of(new FaceLayers.Layer(SIDE, -1), new FaceLayers.Layer(FRINGE, 0))));
            for (String receiver : List.of("dirt", "stone", "sand")) {
                FaceLayers.Layer own = new FaceLayers.Layer("minecraft:block/" + receiver, -1);
                layers.put("minecraft:" + receiver, new FaceLayers(List.of(own), List.of(own)));
            }
            return layers;
        }

        @Override
        public ExistingOverlays existingOverlays() {
            return ExistingOverlays.NONE;
        }

        @Override
        public OverlayGenerator.BetterGrassSides betterGrassSides(Map<String, FaceLayers> layers, OverlayGenerator.Input input) {
            if (!betterGrass) {
                return OverlayGenerator.BetterGrassSides.NONE;
            }
            // Pretend dirt is one of LambdaBetterGrass's blocks, so its sides take the per-face path.
            return new OverlayGenerator.BetterGrassSides(Map.of("minecraft:dirt", Set.of("minecraft:block/dirt")), Map.of());
        }

        @Override
        public Map<String, SourceTexture> textures(Set<String> ids, List<String> skipped, ExecutorService pool) {
            Map<String, SourceTexture> textures = new HashMap<>();
            textures.put(TOP, new SourceTexture(TOP, SIZE, filled(GREY), false));
            textures.put(SIDE, new SourceTexture(SIDE, SIZE, sidePixels(), false));
            textures.put(FRINGE, new SourceTexture(FRINGE, SIZE, fringePixels(), false));
            textures.keySet().retainAll(ids);
            return textures;
        }
    }
}
