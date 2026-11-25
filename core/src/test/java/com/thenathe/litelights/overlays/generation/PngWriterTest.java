package com.thenathe.litelights.overlays.generation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PngWriterTest {
    @Test
    void decodesBackToTheSamePixels() throws Exception {
        int width = 37;
        int height = 19;
        int[] argb = new int[width * height];
        Random random = new Random(5);
        for (int i = 0; i < argb.length; i++) {
            argb[i] = random.nextBoolean() ? 0 : 0xFF000000 | random.nextInt(0xFFFFFF);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(PngWriter.encode(argb, width, height)));
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(argb[y * width + x], image.getRGB(x, y), "texel " + x + ", " + y);
            }
        }
    }
}
