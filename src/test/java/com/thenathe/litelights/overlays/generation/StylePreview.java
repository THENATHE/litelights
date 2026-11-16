package com.thenathe.litelights.overlays.generation;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Draws a top-down patch of grass and sand with each style, as plain overlay tiles and as variety
 * pieces, so shapes can be judged without starting the game. Development only: it runs when
 * {@code -Plitelights.overlays.preview.textures=<vanilla textures/block folder>} is given to Gradle and writes to
 * {@code build/previews}.
 */
class StylePreview {
    private static int scale = 4;
    private static final int GRASS_TINT = 0xFF91BD59;
    private static final String[] GROUND = {
        "gggggggggggggggggg",
        "gggggggggggggggggg",
        "gggggggggggggggggg",
        "ssssssssssssssssss",
        "ssssssssssssssssss",
        "ssssgsssssssggssss",
        "sssssssssssggggsss",
        "ssgggssssssssggsss",
        "ssgggsssssssssssss",
        "ssssssssssssssssss"
    };
    private static final int[][] STEPS = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}};

    @Test
    void drawPreviews() throws Exception {
        String folder = System.getProperty("litelights.overlays.preview.textures");
        assumeTrue(folder != null && !folder.isBlank(), "previews are only drawn on request");
        Path out = Path.of("build/previews");
        Files.createDirectories(out);
        // 16x as the textures come, and 128x by enlarging them, to judge the fine detail big packs get.
        for (int size : new int[] {16, 128}) {
            scale = size == 16 ? 4 : 1;
            int[] grass = enlarged(tinted(read(new File(folder, "grass_block_top.png")), GRASS_TINT), 16, size);
            int[] sand = enlarged(read(new File(folder, "sand.png")), 16, size);
            for (EdgeStyle style : EdgeShape.STYLES) {
                EdgeShape shape = new EdgeShape(style, size, 4, 0.6, false, "minecraft:grass_block".hashCode());
                String name = style.id() + (size == 16 ? "" : "-" + size + "x");
                ImageIO.write(render(shape, grass, sand, size, null), "png", out.resolve(name + "-plain.png").toFile());
                ImageIO.write(render(shape, grass, sand, size, EdgeVariety.NORMAL.layout()), "png",
                    out.resolve(name + "-normal.png").toFile());
            }
        }
    }

    private static BufferedImage render(EdgeShape shape, int[] grass, int[] sand, int size, VarietyLayout layout) {
        int rows = GROUND.length;
        int columns = GROUND[0].length();
        BufferedImage image = new BufferedImage(columns * size * scale, rows * size * scale, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < rows; z++) {
            for (int x = 0; x < columns; x++) {
                boolean isGrass = at(x, z) == 'g';
                paint(image, x, z, size, isGrass ? grass : sand);
                if (isGrass) {
                    continue;
                }
                boolean[] applied = new boolean[4];
                for (int d = 0; d < 4; d++) {
                    applied[d] = at(x + STEPS[d][0], z + STEPS[d][1]) == 'g';
                }
                if (layout == null) {
                    for (int tile : plainTiles(applied)) {
                        paint(image, x, z, size, TileComposer.compose(grass, size, shape.cover(tile), TileComposer.Mapping.FACE));
                    }
                } else {
                    drawVariety(image, shape, grass, size, layout, x, z, applied);
                }
            }
        }
        return image;
    }

    private static void drawVariety(BufferedImage image, EdgeShape shape, int[] grass, int size, VarietyLayout layout,
                                    int x, int z, boolean[] applied) {
        int classes = layout.classes();
        int[] cornerClass = new int[4];
        int[][] cornerPoints = {{2 * x, 2 * z + 2}, {2 * x + 2, 2 * z + 2}, {2 * x + 2, 2 * z}, {2 * x, 2 * z}};
        for (int c = 0; c < 4; c++) {
            cornerClass[c] = VarietyHash.cornerClass(cornerPoints[c][0], 242, cornerPoints[c][1], classes);
        }
        int[][] edgeMidpoints = {{2 * x, 2 * z + 1}, {2 * x + 1, 2 * z + 2}, {2 * x + 2, 2 * z + 1}, {2 * x + 1, 2 * z}};
        for (int d = 0; d < 4; d++) {
            if (applied[d]) {
                int wave = VarietyHash.wave(edgeMidpoints[d][0], 242, edgeMidpoints[d][1], layout.waves());
                byte[] mask = shape.band(d, cornerClass[EdgeShape.EDGE_START[d]], cornerClass[EdgeShape.EDGE_END[d]], wave, classes);
                paint(image, x, z, size, TileComposer.compose(grass, size, mask, TileComposer.Mapping.FACE));
            }
        }
        for (int c = 0; c < 4; c++) {
            if (applied[c] && applied[(c + 1) % 4]) {
                byte[] mask = shape.innerCorner(c, cornerClass[c], classes);
                paint(image, x, z, size, TileComposer.compose(grass, size, mask, TileComposer.Mapping.FACE));
            }
        }
    }

    /**
     * The edge tiles Continuity's overlay method would pick, following its StandardOverlayQuadProcessor.
     * Its corner-only tiles are left out: the generator leaves them empty.
     */
    private static int[] plainTiles(boolean[] applied) {
        int bits = (applied[0] ? 1 : 0) | (applied[1] ? 2 : 0) | (applied[2] ? 4 : 0) | (applied[3] ? 8 : 0);
        int[] edgeTile = {
            -1, 9, 1, 4, 7, -1, 3, 5, 15, 11, -1, 6, 10, 13, 12, 8
        };
        java.util.List<Integer> tiles = new java.util.ArrayList<>();
        if (bits == 5) {
            tiles.add(9);
            tiles.add(7);
        } else if (bits == 10) {
            tiles.add(1);
            tiles.add(15);
        } else if (bits != 0) {
            tiles.add(edgeTile[bits]);
        }
        return tiles.stream().mapToInt(Integer::intValue).toArray();
    }

    private static char at(int x, int z) {
        if (z < 0 || z >= GROUND.length || x < 0 || x >= GROUND[0].length()) {
            return 's';
        }
        return GROUND[z].charAt(x);
    }

    private static void paint(BufferedImage image, int blockX, int blockZ, int size, int[] pixels) {
        if (pixels == null) {
            return;
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int pixel = pixels[y * size + x];
                if ((pixel >>> 24) == 0) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        image.setRGB((blockX * size + x) * scale + dx, (blockZ * size + y) * scale + dy, pixel);
                    }
                }
            }
        }
    }

    private static int[] read(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        int size = image.getWidth();
        int[] pixels = new int[size * size];
        image.getRGB(0, 0, size, size, pixels, 0, size);
        return pixels;
    }

    private static int[] enlarged(int[] pixels, int from, int to) {
        int factor = to / from;
        int[] out = new int[to * to];
        for (int y = 0; y < to; y++) {
            for (int x = 0; x < to; x++) {
                out[y * to + x] = pixels[(y / factor) * from + x / factor];
            }
        }
        return out;
    }

    private static int[] tinted(int[] pixels, int tint) {
        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = ((p >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
            int g = ((p >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
            int b = (p & 0xFF) * (tint & 0xFF) / 255;
            out[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        return out;
    }
}
