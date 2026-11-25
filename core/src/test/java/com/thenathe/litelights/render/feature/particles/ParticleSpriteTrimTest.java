package com.thenathe.litelights.render.feature.particles;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Soft billboards are trimmed to their sprite's content, centred, in whole texels, and never cut into it. */
class ParticleSpriteTrimTest {
    private static final Path FOLDER = Path.of("src/main/resources/assets/litelights/textures/particle");

    @Test
    void aSpriteThatFillsItsSquareStaysWhole() {
        int[] full = new int[16];
        java.util.Arrays.fill(full, 0xFF000000);
        assertArrayEquals(new float[] {1.0f, 1.0f}, ParticleSprites.trimToContent(full, 4, 4));
        assertArrayEquals(new float[] {1.0f, 1.0f}, ParticleSprites.trimToContent(new int[16], 4, 4), "a clear sprite isn't trimmed away");
    }

    @Test
    void theTrimStaysCentredAndReachesTheFurthestTexel() {
        // 8 x 8, content from x 3 to 4 and y 1 to 5: x reaches one texel each way, y three upward.
        int[] argb = new int[64];
        for (int y = 1; y <= 5; y++) {
            for (int x = 3; x <= 4; x++) {
                argb[y * 8 + x] = 0x10FFFFFF;
            }
        }
        assertArrayEquals(new float[] {0.25f, 0.75f}, ParticleSprites.trimToContent(argb, 8, 8));
    }

    @Test
    void onlySoftBillboardsAreTrimmed() {
        assertTrue(ParticleSprites.trimmed(ParticleKind.MIST));
        assertTrue(ParticleSprites.trimmed(ParticleKind.SAND_CLOUD));
        assertTrue(ParticleSprites.trimmed(ParticleKind.SNOW));
        assertTrue(!ParticleSprites.trimmed(ParticleKind.SANDSTORM_GRAIN), "streaks keep their shape");
        assertTrue(!ParticleSprites.trimmed(ParticleKind.FIREFLY));
        assertTrue(!ParticleSprites.trimmed(ParticleKind.RAIN_RIPPLE));
    }

    @Test
    void theHazePuffsLoseAboutHalfTheirArea() throws IOException {
        for (String name : new String[] {"haze_0", "haze_1", "haze_2"}) {
            BufferedImage image = ImageIO.read(FOLDER.resolve(name + ".png").toFile());
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
            float[] trim = ParticleSprites.trimToContent(argb, width, height);
            float area = trim[0] * trim[1];
            assertTrue(area < 0.7f, name + " keeps " + area + " of its square");
            // Nothing visible falls outside the trimmed rectangle.
            int halfX = Math.round(trim[0] * width / 2);
            int halfY = Math.round(trim[1] * height / 2);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean inside = Math.abs(x + 0.5f - width / 2.0f) < halfX && Math.abs(y + 0.5f - height / 2.0f) < halfY;
                    if (!inside) {
                        assertEquals(0, argb[y * width + x] >>> 24, name + " has a visible texel at " + x + "," + y + " outside the trim");
                    }
                }
            }
        }
    }
}
