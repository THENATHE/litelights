package com.thenathe.litelights.overlays.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.hierarchy.OverlayPlan;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/**
 * The sets a build writes for LambdaBetterGrass (lbgfix17): a source that is one of its blocks
 * gets a role per layer, its top layer is matched on sides too, the soil under it is found by its
 * texels, other sources draw over its sides in both formats, and a face of two layers gets its
 * pieces on one quad.
 */
class BetterGrassJobsTest {
    private static final String GRASS = "minecraft:grass_block";
    private static final String PODZOL = "minecraft:podzol";
    private static final String MOSS = "minecraft:moss_block";
    private static final String DIRT = "minecraft:dirt";
    private static final String SAND = "minecraft:sand";
    private static final int SIZE = 16;
    private static final int DIRT_COLOUR = 0xFF593D29;

    @Test
    void varietySetsCarryTheirRoleSoilAndFirstLayer() {
        Map<String, Properties> files = build(OverlayFormat.VARIETY);

        Properties top = files.get("grass_block_spill0");
        assertEquals("top sides", top.getProperty("faces"), "the top layer is matched on sides, beside full sides");
        assertEquals("top", top.getProperty("variety.betterGrassRole"));
        assertEquals(Set.of(DIRT, PODZOL), words(top.getProperty("variety.betterGrassSoil")),
            "dirt and podzol are the colour of the grass side's dirt in their bottom half; sand isn't");
        assertEquals(Set.of(PODZOL), words(top.getProperty("variety.betterGrassBlocks")));

        Properties side = files.get("grass_block_spill1");
        assertEquals("sides", side.getProperty("faces"));
        assertEquals("side", side.getProperty("variety.betterGrassRole"));
        Properties fringe = files.get("grass_block_spill2");
        assertEquals("fringe", fringe.getProperty("variety.betterGrassRole"));
        assertEquals(Set.of(DIRT, PODZOL), words(fringe.getProperty("variety.betterGrassSoil")));

        Properties moss = files.get("moss_block_spill0");
        assertNull(moss.getProperty("variety.betterGrassRole"), "moss isn't LambdaBetterGrass's: it draws over its sides");
        assertEquals(Set.of(GRASS), words(moss.getProperty("variety.betterGrassBlocks")));
        assertEquals(GRASS + "=*,-1", moss.getProperty("variety.firstLayerTints"),
            "grass's sides are two quads: the pieces go on the bottom one");
        assertNull(top.getProperty("variety.firstLayerTints"), "dirt, sand and podzol draw every face in one layer");
    }

    @Test
    void plainSetsSplitOnlyForLambdaBetterGrassSources() {
        Map<String, Properties> files = build(OverlayFormat.PLAIN);

        Properties grassOwn = files.get("grass_block_spill1own");
        assertNotNull(grassOwn, "grass's side band onto podzol only matches podzol's own sides");
        assertEquals("minecraft:block/podzol_side", grassOwn.getProperty("matchTiles"));
        Properties moss = files.get("moss_block_spill0");
        assertNotNull(moss);
        assertEquals("minecraft:grass_block", moss.getProperty("matchBlocks"), "moss draws over every grass side");
        assertNull(moss.getProperty("matchTiles"));
        assertFalse(files.keySet().stream().anyMatch(name -> name.startsWith("moss_block_spill0own")));
        assertEquals("top", files.get("grass_block_spill0").getProperty("faces"), "plain can't see a full side");
    }

    @Test
    void soilIsTheSourceSidesBottomHalf() {
        SourceTexture grassSide = new SourceTexture("side", SIZE, sidePixels(DIRT_COLOUR), false);
        assertTrue(OverlayGenerator.isSoil(grassSide, new SourceTexture("dirt", SIZE, filled(DIRT_COLOUR), false)));
        assertFalse(OverlayGenerator.isSoil(grassSide, new SourceTexture("sand", SIZE, filled(0xFFDBCF9F), false)));
        // As far from this dirt as vanilla's coarse dirt (21) and granite (27) are from vanilla's.
        assertTrue(OverlayGenerator.isSoil(grassSide, new SourceTexture("coarse", SIZE, filled(0xFF493221), false)));
        assertFalse(OverlayGenerator.isSoil(grassSide, new SourceTexture("granite", SIZE, filled(0xFF6A453D), false)));
        // The same dirt in a pack twice the size still counts.
        int[] largeDirt = new int[SIZE * SIZE * 4];
        Arrays.fill(largeDirt, DIRT_COLOUR);
        assertTrue(OverlayGenerator.isSoil(grassSide, new SourceTexture("dirt", SIZE * 2, largeDirt, false)));
    }

    private static Map<String, Properties> build(OverlayFormat format) {
        OverlayPlan plan = new OverlayPlan(List.of(
            new OverlayPlan.Source(MOSS, EdgeStyle.ORGANIC, Set.of(GRASS), Set.of()),
            new OverlayPlan.Source(GRASS, EdgeStyle.ORGANIC, Set.of(DIRT, SAND, PODZOL), Set.of())));
        OverlayGenerator.Input input = new OverlayGenerator.Input(new OverlayConfig(), null,
            Map.of(GRASS, Map.of("snowy", "false"), PODZOL, Map.of("snowy", "false")), "litelights", format, 16384, true);
        GeneratedFiles files = OverlayGenerator.generate(plan, new Fixtures(), input);
        Map<String, Properties> byKind = new LinkedHashMap<>();
        files.files().forEach((path, bytes) -> {
            if (path.endsWith(".properties")) {
                Properties properties = new Properties();
                try {
                    properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".properties".length());
                byKind.put(name.substring(name.indexOf('_') + 1).replace("minecraft_", ""), properties);
            }
        });
        return byKind;
    }

    private static Set<String> words(String list) {
        return list == null ? Set.of() : Set.of(list.trim().split("\\s+"));
    }

    private static int[] filled(int colour) {
        int[] pixels = new int[SIZE * SIZE];
        Arrays.fill(pixels, colour);
        return pixels;
    }

    /** Dirt with a grass-coloured top quarter, as grass's and podzol's sides are. */
    private static int[] sidePixels(int top) {
        int[] pixels = filled(DIRT_COLOUR);
        Arrays.fill(pixels, 0, SIZE * 4, top == DIRT_COLOUR ? 0xFF74B44A : top);
        return pixels;
    }

    /** Grass and podzol (both LambdaBetterGrass's), moss, and two plain receivers. */
    private static final class Fixtures implements OverlayGenerator.Sources {
        @Override
        public Map<String, FaceLayers> layers(OverlayPlan plan, OverlayGenerator.Input input, List<String> skipped,
                                              ExecutorService pool) {
            Map<String, FaceLayers> layers = new HashMap<>();
            layers.put(GRASS, new FaceLayers(List.of(new FaceLayers.Layer("minecraft:block/grass_block_top", 0)),
                List.of(new FaceLayers.Layer("minecraft:block/grass_block_side", -1),
                    new FaceLayers.Layer("minecraft:block/grass_block_side_overlay", 0))));
            layers.put(PODZOL, new FaceLayers(List.of(new FaceLayers.Layer("minecraft:block/podzol_top", -1)),
                List.of(new FaceLayers.Layer("minecraft:block/podzol_side", -1))));
            for (String block : List.of(MOSS, DIRT, SAND)) {
                FaceLayers.Layer own = new FaceLayers.Layer(block.replace("minecraft:", "minecraft:block/"), -1);
                layers.put(block, new FaceLayers(List.of(own), List.of(own)));
            }
            return layers;
        }

        @Override
        public ExistingOverlays existingOverlays() {
            return ExistingOverlays.NONE;
        }

        @Override
        public OverlayGenerator.BetterGrassSides betterGrassSides(Map<String, FaceLayers> layers, OverlayGenerator.Input input) {
            return new OverlayGenerator.BetterGrassSides(
                Map.of(GRASS, Set.of("minecraft:block/grass_block_side", "minecraft:block/grass_block_side_overlay"),
                    PODZOL, Set.of("minecraft:block/podzol_side")),
                Map.of());
        }

        @Override
        public Map<String, SourceTexture> textures(Set<String> ids, List<String> skipped, ExecutorService pool) {
            Map<String, SourceTexture> textures = new HashMap<>();
            for (String id : ids) {
                int[] pixels = switch (id) {
                    case "minecraft:block/grass_block_side" -> sidePixels(DIRT_COLOUR);
                    case "minecraft:block/podzol_side" -> sidePixels(0xFF6A4418);
                    case "minecraft:block/dirt" -> filled(DIRT_COLOUR);
                    case "minecraft:block/sand" -> filled(0xFFDBCF9F);
                    case "minecraft:block/grass_block_side_overlay" -> {
                        int[] fringe = new int[SIZE * SIZE];
                        Arrays.fill(fringe, 0, SIZE * 4, 0xFFA0A0A0);
                        yield fringe;
                    }
                    default -> filled(0xFF4F7A2F);
                };
                textures.put(id, new SourceTexture(id, SIZE, pixels, false));
            }
            return textures;
        }
    }
}
