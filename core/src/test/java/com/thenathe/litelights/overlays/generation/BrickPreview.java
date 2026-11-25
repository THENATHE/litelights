package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.overlays.VanillaAssets;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Draws flat walls of vanilla masonry with Bricks overlays laid out as the variety method would
 * pick them, for looking at the brick logic without starting the game. Runs only when
 * {@code -Plitelights.overlays.preview.bricks=<folder>} is given to Gradle; writes
 * {@code bricks-preview.png} there.
 */
class BrickPreview {
    private static final int SCALE = 4;
    private static final Map<Character, String> TEXTURES = Map.of(
        'S', "stone_bricks", 'B', "bricks", 'D', "deepslate_bricks", 'T', "deepslate_tiles",
        'P', "polished_blackstone_bricks", 'm', "smooth_stone", 'r', "stone", 'a', "polished_andesite");
    private static final String BRICK_SOURCES = "SBDTP";
    private static final String[] WALL = {
        "SSSmmmmmmPPP",
        "SSSmmmmmmPPP",
        "SSSmmmmmmPPP",
        "SSSrrrrrrPPP",
        "SSSrrrrrrPPP",
        "BBBBSSSSSSTT",
        "BBBBSSSSSSTT",
        "DDDDDDTTTTTT",
        "DDDDDDTTTTTT",
    };

    @Test
    void draw() throws IOException {
        String folder = System.getProperty("litelights.overlays.preview.bricks", "");
        Assumptions.assumeFalse(folder.isBlank());
        VarietyLayout layout = EdgeVariety.NORMAL.layout();
        int rows = WALL.length;
        int columns = WALL[0].length();
        BufferedImage image = new BufferedImage(columns * 16 * SCALE, rows * 16 * SCALE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                char block = WALL[y].charAt(x);
                paint(image, x, y, texture(block).pixels());
                drawOverlays(image, layout, x, y, block);
            }
        }
        ImageIO.write(image, "png", new File(folder, "bricks-preview.png"));
    }

    private static void drawOverlays(BufferedImage image, VarietyLayout layout, int x, int y, char block) {
        int[][] steps = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}};
        int classes = layout.classes();
        int[] cornerClass = new int[4];
        int[][] corners = {{2 * x, 2 * y + 2}, {2 * x + 2, 2 * y + 2}, {2 * x + 2, 2 * y}, {2 * x, 2 * y}};
        for (int corner = 0; corner < 4; corner++) {
            cornerClass[corner] = VarietyHash.cornerClass(corners[corner][0], corners[corner][1], 0, classes);
        }
        OverlayShape[] shapes = new OverlayShape[4];
        int[][] sources = new int[4][];
        char[] from = new char[4];
        for (int direction = 0; direction < 4; direction++) {
            char neighbour = at(x + steps[direction][0], y + steps[direction][1]);
            if (neighbour == ' ' || neighbour == block) {
                continue;
            }
            boolean brickSource = BRICK_SOURCES.indexOf(neighbour) >= 0;
            boolean brickReceiver = BRICK_SOURCES.indexOf(block) >= 0;
            SourceTexture source = texture(neighbour);
            // Bricks spill onto plain masonry and rock, and blend into other bricks; rock spills onto plain masonry.
            if (brickSource && !brickReceiver) {
                shapes[direction] = new BrickShape(BrickGrid.of(source), 4, 0.6, false, neighbour);
            } else if (brickSource) {
                shapes[direction] = new BrickShape(BrickGrid.of(source), 4, 0.6, true, neighbour);
            } else if (neighbour == 'r' && "ma".indexOf(block) >= 0) {
                shapes[direction] = new EdgeShape(EdgeStyle.ORGANIC, 16, 4, 0.6, false, neighbour);
            } else {
                continue;
            }
            sources[direction] = source.pixels();
            from[direction] = neighbour;
            int wave = VarietyHash.wave(2 * x + 1 + steps[direction][0], 2 * y + 1 + steps[direction][1], 0, layout.waves());
            byte[] mask = shapes[direction].band(direction, cornerClass[EdgeShape.EDGE_START[direction]],
                cornerClass[EdgeShape.EDGE_END[direction]], wave, classes);
            paint(image, x, y, TileComposer.compose(sources[direction], 16, mask, TileComposer.Mapping.FACE));
        }
        for (int corner = 0; corner < 4; corner++) {
            OverlayShape shape = shapes[corner];
            int next = (corner + 1) % 4;
            if (shape != null && !shape.isBlend() && shapes[next] != null && from[corner] == from[next]) {
                byte[] mask = shape.innerCorner(corner, cornerClass[corner], classes);
                paint(image, x, y, TileComposer.compose(sources[corner], 16, mask, TileComposer.Mapping.FACE));
            }
        }
    }

    private static SourceTexture texture(char block) {
        return VanillaAssets.blockTexture(TEXTURES.get(block));
    }

    private static char at(int x, int y) {
        if (y < 0 || y >= WALL.length || x < 0 || x >= WALL[y].length()) {
            return ' ';
        }
        return WALL[y].charAt(x);
    }

    private static void paint(BufferedImage image, int blockX, int blockY, int[] pixels) {
        if (pixels == null) {
            return;
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int pixel = pixels[y * 16 + x];
                if ((pixel >>> 24) == 0) {
                    continue;
                }
                for (int dy = 0; dy < SCALE; dy++) {
                    for (int dx = 0; dx < SCALE; dx++) {
                        image.setRGB((blockX * 16 + x) * SCALE + dx, (blockY * 16 + y) * SCALE + dy, pixel);
                    }
                }
            }
        }
    }
}
