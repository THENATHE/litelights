package com.thenathe.litelights.render.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The ore mask against the game's own textures (from the Minecraft jar on the test classpath). */
class OreRocksTest {
    /** How far outside its rock a texel must be to sparkle at all (LITE_ORE_EDGE_START in ore_glint.glsl). */
    private static final float SPARKLE_START = 0.03f;

    private static final List<String> STONE_ORES = List.of("coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore",
        "lapis_ore", "diamond_ore", "emerald_ore");

    private record Texture(int[] abgr, int width) {
    }

    @Test
    void everyVanillaOreFindsItsRock() {
        OreRocks.Profile[] rocks = vanillaRocks();
        for (String ore : STONE_ORES) {
            assertEquals(OreRocks.STONE, pair(ore, rocks), ore);
            assertEquals(OreRocks.DEEPSLATE, pair("deepslate_" + ore, rocks), "deepslate_" + ore);
        }
        assertEquals(OreRocks.NETHERRACK, pair("nether_gold_ore", rocks));
        assertEquals(OreRocks.NETHERRACK, pair("nether_quartz_ore", rocks));
    }

    @Test
    void oreOnARockOfItsOwnIsLeftOut() {
        // Ancient debris is in c:ores, but it isn't drawn on netherrack: it keeps the plain glimmer.
        assertEquals(OreRocks.NONE, pair("ancient_debris_side", vanillaRocks()));
        assertEquals(MaterialIds.DEFAULT, OreRocks.material(OreRocks.NONE));
    }

    @Test
    void noRockTexelEverSparkles() {
        String[] names = {"stone", "deepslate", "netherrack"};
        OreRocks.Profile[] rocks = vanillaRocks();
        for (int rock = 0; rock < names.length; rock++) {
            Texture texture = texture(names[rock]);
            for (int i = 0; i < texture.width() * texture.width(); i++) {
                int pixel = texture.abgr()[i];
                assertTrue(outside(rocks[rock], pixel) <= 0.0f, names[rock] + " texel " + i);
            }
        }
    }

    @Test
    void texelsTheOreSharesWithItsRockNeverSparkle() {
        OreRocks.Profile[] rocks = vanillaRocks();
        Texture stone = texture("stone");
        for (String ore : STONE_ORES) {
            Texture texture = texture(ore);
            for (int i = 0; i < 256; i++) {
                if (texture.abgr()[i] == stone.abgr()[i]) {
                    assertTrue(outside(rocks[OreRocks.STONE], texture.abgr()[i]) <= 0.0f, ore + " texel " + i);
                }
            }
        }
    }

    @Test
    void theOreItselfSparkles() {
        OreRocks.Profile[] rocks = vanillaRocks();
        for (String ore : STONE_ORES) {
            if (!ore.equals("coal_ore")) {
                assertTrue(sparklingTexels(ore, rocks[OreRocks.STONE]) >= 30, ore);
                assertTrue(sparklingTexels("deepslate_" + ore, rocks[OreRocks.DEEPSLATE]) >= 30, "deepslate_" + ore);
            }
        }
        // Coal is darker than any stone texel, only just: a few of its texels, faintly.
        assertTrue(sparklingTexels("coal_ore", rocks[OreRocks.STONE]) >= 10);
        assertTrue(sparklingTexels("nether_gold_ore", rocks[OreRocks.NETHERRACK]) >= 15);
        assertTrue(sparklingTexels("nether_quartz_ore", rocks[OreRocks.NETHERRACK]) >= 30);
    }

    @Test
    void missingRockPairsWithNothing() {
        OreRocks.Profile missing = OreRocks.profile(new int[0], 0);
        Texture gold = texture("gold_ore");
        assertEquals(OreRocks.NONE, OreRocks.pair(gold.abgr(), gold.width(), new OreRocks.Profile[] {missing, missing, missing}));
        float[] uniforms = OreRocks.uniforms(new OreRocks.Profile[] {missing});
        // An empty luma range: nothing fits it.
        assertTrue(uniforms[4] > uniforms[5]);
        assertEquals(24, uniforms.length);
    }

    private static int sparklingTexels(String name, OreRocks.Profile rock) {
        Texture texture = texture(name);
        int count = 0;
        for (int i = 0; i < texture.width() * texture.width(); i++) {
            if (outside(rock, texture.abgr()[i]) > SPARKLE_START) {
                count++;
            }
        }
        return count;
    }

    private static int pair(String name, OreRocks.Profile[] rocks) {
        Texture texture = texture(name);
        return OreRocks.pair(texture.abgr(), texture.width(), rocks);
    }

    private static float outside(OreRocks.Profile rock, int abgr) {
        return rock.outside((abgr & 0xFF) / 255.0f, (abgr >>> 8 & 0xFF) / 255.0f, (abgr >>> 16 & 0xFF) / 255.0f);
    }

    private static OreRocks.Profile[] vanillaRocks() {
        OreRocks.Profile[] rocks = new OreRocks.Profile[OreRocks.COUNT];
        String[] names = {"stone", "deepslate", "netherrack"};
        for (int rock = 0; rock < OreRocks.COUNT; rock++) {
            Texture texture = texture(names[rock]);
            rocks[rock] = OreRocks.profile(texture.abgr(), texture.width());
        }
        return rocks;
    }

    /** A vanilla block texture's pixels as 0xAABBGGRR, as NativeImage gives them. */
    private static Texture texture(String name) {
        try (InputStream stream = OreRocksTest.class.getResourceAsStream("/assets/minecraft/textures/block/" + name + ".png")) {
            if (stream == null) {
                throw new IllegalArgumentException("vanilla has no texture " + name);
            }
            BufferedImage image = ImageIO.read(stream);
            int width = image.getWidth();
            // Grey PNGs (stone is one) are read as their stored values: getRGB would take them as
            // linear grey and brighten them, which the game doesn't.
            boolean grey = image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY;
            Raster raster = image.getRaster();
            int[] abgr = new int[width * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < width; x++) {
                    int argb;
                    if (grey) {
                        int value = raster.getSample(x, y, 0);
                        int alpha = raster.getNumBands() > 1 ? raster.getSample(x, y, 1) : 255;
                        argb = alpha << 24 | value << 16 | value << 8 | value;
                    } else {
                        argb = image.getRGB(x, y);
                    }
                    abgr[x + y * width] = argb & 0xFF00FF00 | (argb & 0xFF) << 16 | (argb >>> 16 & 0xFF);
                }
            }
            return new Texture(abgr, width);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
