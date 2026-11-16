package com.thenathe.litelights.render.feature.water.wake;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The offline prototype for the wakes (wakes18): steps a {@link WakeField} along a boat's, a
 * swimmer's and a falling item's paths with no game running, and draws the water from straight
 * above at 16 texels a block the way the water shader does ({@link WakeLook}), so the scale,
 * damping and foam can be judged from pictures. Runs only when
 * {@code -Plitelights.wakes.preview=<folder>} is given to Gradle; writes one PNG per frame,
 * {@code <scenario>-<tick>.png}, there.
 */
class WakePreview {
    private static final int VIEW_BLOCKS = 48;
    private static final int TEXELS_PER_BLOCK = 16;
    private static final int ORIGIN = -32;

    @Test
    void draw() throws IOException {
        String folder = System.getProperty("litelights.wakes.preview", "");
        Assumptions.assumeFalse(folder.isBlank());
        File out = new File(folder);
        out.mkdirs();
        straightBoat(out);
        turningBoat(out);
        swimmer(out);
        splash(out);
        crossingBoats(out);
    }

    /** A boat at full speed (0.4 blocks a tick) across the view from west to east. */
    private static void straightBoat(File out) throws IOException {
        WakeField field = openWater();
        WakeSourceList sources = new WakeSourceList();
        double x = -22.0;
        for (int tick = 0; tick <= 100; tick++) {
            sources.clear();
            double next = x + 0.4;
            WakeShapes.boat(sources, x, 0.0, next, 0.0, 1.0, 0.0, 0.4, 1.0);
            field.step(sources);
            x = next;
            if (tick % 4 == 0) {
                write(out, "straight", tick, field, new double[] {x, 0.0, 1.0, 0.0});
            }
        }
    }

    /** A boat turning in a circle eight blocks across at 0.3 blocks a tick. */
    private static void turningBoat(File out) throws IOException {
        WakeField field = openWater();
        WakeSourceList sources = new WakeSourceList();
        double radius = 8.0;
        double angle = 0.0;
        double step = 0.3 / radius;
        for (int tick = 0; tick <= 200; tick++) {
            sources.clear();
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double nextAngle = angle + step;
            double nextX = Math.cos(nextAngle) * radius;
            double nextZ = Math.sin(nextAngle) * radius;
            WakeShapes.boat(sources, x, z, nextX, nextZ, -Math.sin(nextAngle), Math.cos(nextAngle), 0.3, 1.0);
            field.step(sources);
            angle = nextAngle;
            if (tick % 4 == 0) {
                write(out, "turning", tick, field, new double[] {nextX, nextZ, -Math.sin(nextAngle), Math.cos(nextAngle)});
            }
        }
    }

    /** A player swimming at 0.2 blocks a tick. */
    private static void swimmer(File out) throws IOException {
        WakeField field = openWater();
        WakeSourceList sources = new WakeSourceList();
        double z = -16.0;
        for (int tick = 0; tick <= 120; tick++) {
            sources.clear();
            double next = z + 0.2;
            WakeShapes.swimmer(sources, 0.0, z, 0.0, next, 0.0, 1.0, 0.2, 0.6, 1.0);
            field.step(sources);
            z = next;
            if (tick % 4 == 0) {
                write(out, "swimmer", tick, field, null);
            }
        }
    }

    /** An item falling three blocks into the water, then the ripples. */
    private static void splash(File out) throws IOException {
        WakeField field = openWater();
        WakeSourceList sources = new WakeSourceList();
        for (int tick = 0; tick <= 80; tick++) {
            sources.clear();
            if (tick == 0) {
                WakeShapes.entry(sources, 0.0, 0.0, 0.6, 0.25, 1.0);
            }
            field.step(sources);
            if (tick % 2 == 0) {
                write(out, "splash", tick, field, null);
            }
        }
    }

    /** Two boats crossing at right angles, so their wakes meet. */
    private static void crossingBoats(File out) throws IOException {
        WakeField field = openWater();
        WakeSourceList sources = new WakeSourceList();
        for (int tick = 0; tick <= 110; tick++) {
            sources.clear();
            double a = -22.0 + tick * 0.4;
            double b = -22.0 + tick * 0.35;
            WakeShapes.boat(sources, a - 0.4, -4.0, a, -4.0, 1.0, 0.0, 0.4, 1.0);
            WakeShapes.boat(sources, 4.0, b - 0.35, 4.0, b, 0.0, 1.0, 0.35, 1.0);
            field.step(sources);
            if (tick % 4 == 0) {
                write(out, "crossing", tick, field, null);
            }
        }
    }

    private static WakeField openWater() {
        WakeField field = new WakeField();
        field.moveWindow(ORIGIN, ORIGIN);
        field.fillWater(true);
        return field;
    }

    private static void write(File out, String scenario, int tick, WakeField field, double[] boat) throws IOException {
        int size = VIEW_BLOCKS * TEXELS_PER_BLOCK;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double from = -VIEW_BLOCKS / 2.0;
        for (int tz = 0; tz < size; tz++) {
            for (int tx = 0; tx < size; tx++) {
                double worldX = from + (tx + 0.5) / TEXELS_PER_BLOCK;
                double worldZ = from + (tz + 0.5) / TEXELS_PER_BLOCK;
                image.setRGB(tx, tz, WakeLook.colour(field, worldX, worldZ, TEXELS_PER_BLOCK));
            }
        }
        if (boat != null) {
            drawBoat(image, boat, from);
        }
        ImageIO.write(image, "png", new File(out, String.format(Locale.ROOT, "%s-%03d.png", scenario, tick)));
    }

    /** The boat as a plain brown shape over the water, so the wake can be read against it. */
    private static void drawBoat(BufferedImage image, double[] boat, double from) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x8A6236));
        double centreX = (boat[0] - from) * TEXELS_PER_BLOCK;
        double centreZ = (boat[1] - from) * TEXELS_PER_BLOCK;
        graphics.translate(centreX, centreZ);
        graphics.rotate(Math.atan2(boat[3], boat[2]));
        graphics.fillRoundRect((int) (-1.1 * TEXELS_PER_BLOCK), (int) (-0.65 * TEXELS_PER_BLOCK),
            (int) (2.2 * TEXELS_PER_BLOCK), (int) (1.3 * TEXELS_PER_BLOCK), 12, 12);
        graphics.dispose();
    }
}
