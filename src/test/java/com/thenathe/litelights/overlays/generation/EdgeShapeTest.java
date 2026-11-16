package com.thenathe.litelights.overlays.generation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeShapeTest {
    private static final int[] SIZES = {8, 16, 32};

    /**
     * Two tiles that both have a left edge must agree on it: the left band of tile 9 (left only)
     * is inside every tile with a left edge. Otherwise a column of blocks would show a seam where
     * Continuity switches tiles.
     */
    @Test
    void tilesSharingAnEdgeAgreeOnIt() {
        int[] withLeft = {4, 5, 6, 8, 11, 13};
        for (EdgeStyle style : EdgeShape.STYLES) {
            for (int size : SIZES) {
                EdgeShape shape = new EdgeShape(style, size, 4, 0.6, false, 42);
                byte[] leftOnly = shape.cover(9);
                for (int tile : withLeft) {
                    byte[] other = shape.cover(tile);
                    for (int i = 0; i < leftOnly.length; i++) {
                        if (leftOnly[i] != EdgeShape.EMPTY) {
                            assertTrue(other[i] != EdgeShape.EMPTY,
                                style + " " + size + "x: tile " + tile + " drops a left-band texel at " + i);
                        }
                    }
                }
            }
        }
    }

    /** A plain edge tile covers some texels next to its edge and none far from it. */
    @Test
    void bandsStayNearTheirEdge() {
        for (EdgeStyle style : EdgeShape.STYLES) {
            for (int size : SIZES) {
                byte[] left = new EdgeShape(style, size, 4, 0.6, false, 7).cover(9);
                int nearEdge = 0;
                for (int y = 0; y < size; y++) {
                    if (left[y * size] != EdgeShape.EMPTY) {
                        nearEdge++;
                    }
                    assertEquals(EdgeShape.EMPTY, left[y * size + size - 1], style + " " + size + "x reaches the far side");
                }
                assertTrue(nearEdge > size / 2, style + " " + size + "x leaves its own edge bare");
            }
        }
    }

    /**
     * Blended seams interlock: along a shared edge, where one block's half reaches across, the
     * other's mostly doesn't. Allow a few texels of overlap for the dithered finger tips.
     */
    @Test
    void blendHalvesInterlock() {
        for (int size : SIZES) {
            EdgeShape shape = new EdgeShape(EdgeStyle.ORGANIC, size, 4, 0.6, true, 0);
            byte[] fromLeft = shape.cover(9);
            byte[] fromRight = shape.cover(7);
            int both = 0;
            int either = 0;
            for (int y = 0; y < size; y++) {
                boolean left = fromLeft[y * size] != EdgeShape.EMPTY;
                boolean right = fromRight[y * size + size - 1] != EdgeShape.EMPTY;
                both += left && right ? 1 : 0;
                either += left || right ? 1 : 0;
            }
            assertTrue(both <= size / 4, size + "x: halves overlap on " + both + " rows");
            assertTrue(either >= size / 3, size + "x: the seam barely blends");
        }
    }

    /** Diagonal-only corners were dropped in 0.1.7: Continuity's corner-only tiles are always empty. */
    @Test
    void cornerOnlyTilesAreEmpty() {
        for (EdgeStyle style : EdgeShape.STYLES) {
            for (boolean blend : new boolean[] {false, true}) {
                EdgeShape shape = new EdgeShape(style, 16, 4, 0.6, blend, 0);
                for (int tile : new int[] {0, 2, 14, 16}) {
                    for (byte texel : shape.cover(tile)) {
                        assertEquals(EdgeShape.EMPTY, texel, style + " tile " + tile);
                    }
                }
            }
        }
    }

    @Test
    void pngRoundTrips() throws Exception {
        int size = 16;
        int[] pixels = new int[size * size];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i % 3 == 0 ? 0 : 0xFF000000 | (i * 9973);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(PngWriter.encode(pixels, size, size)));
        for (int i = 0; i < pixels.length; i++) {
            int expected = (pixels[i] >>> 24) == 0 ? 0 : pixels[i];
            int actual = image.getRGB(i % size, i / size);
            assertEquals(expected, (actual >>> 24) == 0 ? 0 : actual, "pixel " + i);
        }
    }

    /**
     * Two blocks side by side along a border use different variety pieces, but where they meet
     * (the right end of one top band and the left end of the next) the bands must be equally deep,
     * or the border would step at every block. Both ends share the corner's class. Roughness is
     * zero here: rough edges add per-texel grain and loose specks on purpose, everywhere. Dithered
     * is left out because its edge is an ordered dither, which has no single depth per column.
     */
    @Test
    void neighbouringVarietyBandsMeetAtTheSameDepth() {
        VarietyLayout layout = EdgeVariety.HIGH.layout();
        for (EdgeStyle style : EdgeShape.STYLES) {
            if (style == EdgeStyle.DITHERED) {
                continue;
            }
            for (int size : new int[] {8, 16, 32, 64, 128}) {
                EdgeShape shape = new EdgeShape(style, size, 4, 0.0, false, 42);
                for (int shared = 0; shared < layout.classes(); shared++) {
                    for (int wave = 0; wave < layout.waves(); wave++) {
                        byte[] west = shape.band(EdgeShape.UP, 0, shared, wave, layout.classes());
                        byte[] east = shape.band(EdgeShape.UP, shared, layout.classes() - 1, (wave + 1) % layout.waves(),
                            layout.classes());
                        int westDepth = runFromTop(west, size, size - 1);
                        int eastDepth = runFromTop(east, size, 0);
                        double allowed = Math.max(1.5, size / 16.0 * 1.5);
                        assertTrue(Math.abs(westDepth - eastDepth) <= allowed, style + " " + size + "x class " + shared
                            + ": the border steps from " + westDepth + " to " + eastDepth + " texels");
                    }
                }
            }
        }
    }

    /** With blending, the two halves of one seam use the same wave mirrored, so they still interlock. */
    @Test
    void varietyBlendHalvesInterlock() {
        VarietyLayout layout = EdgeVariety.NORMAL.layout();
        for (int size : SIZES) {
            EdgeShape shape = new EdgeShape(EdgeStyle.ORGANIC, size, 4, 0.6, true, 0);
            for (int wave = 0; wave < layout.waves(); wave++) {
                byte[] fromLeft = shape.band(EdgeShape.LEFT, 0, 1, wave, layout.classes());
                byte[] fromRight = shape.band(EdgeShape.RIGHT, 0, 1, wave, layout.classes());
                int both = 0;
                for (int y = 0; y < size; y++) {
                    boolean left = fromLeft[y * size] != EdgeShape.EMPTY;
                    boolean right = fromRight[y * size + size - 1] != EdgeShape.EMPTY;
                    both += left && right ? 1 : 0;
                }
                assertTrue(both <= size / 4, size + "x wave " + wave + ": halves overlap on " + both + " rows");
            }
        }
    }

    /** Every piece number belongs to exactly one piece, and each piece knows which edges it hangs from. */
    @Test
    void varietyLayoutNumbersEveryPieceOnce() {
        for (EdgeVariety variety : EdgeVariety.values()) {
            VarietyLayout layout = variety.layout();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            int classes = layout.classes();
            for (int direction = 0; direction < 4; direction++) {
                for (int start = 0; start < classes; start++) {
                    for (int end = 0; end < classes; end++) {
                        for (int wave = 0; wave < layout.waves(); wave++) {
                            int band = layout.band(direction, start, end, wave);
                            assertTrue(seen.add(band));
                            assertEquals(direction, layout.anchors(band)[0]);
                        }
                    }
                }
                int step = layout.step(direction);
                assertTrue(seen.add(step));
                assertTrue(layout.isStep(step));
                assertEquals(direction, layout.anchors(step)[0]);
            }
            for (int corner = 0; corner < 4; corner++) {
                for (int cornerClass = 0; cornerClass < classes; cornerClass++) {
                    int inner = layout.innerCorner(corner, cornerClass);
                    assertTrue(seen.add(inner));
                    assertEquals(corner, layout.anchors(inner)[0]);
                    assertEquals((corner + 1) % 4, layout.anchors(inner)[1]);
                }
            }
            assertEquals(layout.pieceCount(), seen.size());
        }
    }

    /** Corner classes and waves come from position alone and stay in range. */
    @Test
    void varietyHashStaysInRange() {
        for (int x = -40; x < 40; x += 3) {
            for (int z = -40; z < 40; z += 5) {
                int cornerClass = VarietyHash.cornerClass(x, 241, z, 3);
                int wave = VarietyHash.wave(x, 241, z, 2);
                assertTrue(cornerClass >= 0 && cornerClass < 3 && wave >= 0 && wave < 2);
                assertEquals(cornerClass, VarietyHash.cornerClass(x, 241, z, 3));
            }
        }
    }

    /** How many texels down from the top edge a column is covered without a gap. */
    private static int runFromTop(byte[] mask, int size, int column) {
        int run = 0;
        while (run < size && mask[run * size + column] != EdgeShape.EMPTY) {
            run++;
        }
        return run;
    }

    /**
     * Every Clumps and Clean edge has a body: the texel row along its edge is covered all the way,
     * for every variety piece and the plain tiles, at every resolution and roughness.
     */
    @Test
    void bandsHaveAnUnbrokenBody() {
        for (EdgeStyle style : new EdgeStyle[] {EdgeStyle.CLUMPS, EdgeStyle.CLEAN}) {
            forEveryBand(style, (label, mask, size, direction) -> {
                for (int along = 0; along < size; along++) {
                    int index = texelAt(size, direction, along, 0);
                    assertTrue(mask[index] != EdgeShape.EMPTY, label + ": the band breaks at texel " + along);
                }
            });
        }
    }

    /** No Clumps clump floats: every covered texel connects (8-way) to the band along the edge. */
    @Test
    void clumpsGrowOutOfTheBand() {
        forEveryBand(EdgeStyle.CLUMPS, (label, mask, size, direction) -> {
            boolean[] reached = new boolean[mask.length];
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            for (int along = 0; along < size; along++) {
                int index = texelAt(size, direction, along, 0);
                if (mask[index] != EdgeShape.EMPTY) {
                    reached[index] = true;
                    queue.add(index);
                }
            }
            while (!queue.isEmpty()) {
                int index = queue.poll();
                int x = index % size;
                int y = index / size;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= size || ny >= size) {
                            continue;
                        }
                        int next = ny * size + nx;
                        if (!reached[next] && mask[next] != EdgeShape.EMPTY) {
                            reached[next] = true;
                            queue.add(next);
                        }
                    }
                }
            }
            for (int index = 0; index < mask.length; index++) {
                assertTrue(mask[index] == EdgeShape.EMPTY || reached[index],
                    label + ": a clump floats at " + (index % size) + "," + (index / size));
            }
        });
    }

    /** Fine detail only starts at 32x: 16x tiles come out exactly as they would without it. */
    @Test
    void sixteenXIsUntouchedByFineDetail() {
        for (EdgeStyle style : new EdgeStyle[] {EdgeStyle.ORGANIC, EdgeStyle.DITHERED}) {
            EdgeShape shape = new EdgeShape(style, 16, 4, 0.6, false, 9);
            for (int tile = 0; tile < TileLayout.TILE_COUNT; tile++) {
                assertTrue(java.util.Arrays.equals(shape.cover(tile), new EdgeShape(style, 16, 4, 0.6, false, 9).cover(tile)));
            }
        }
    }

    private interface BandCheck {
        void check(String label, byte[] mask, int size, int direction);
    }

    /** Runs a check over every band of every variety level and every plain single-edge tile. */
    private static void forEveryBand(EdgeStyle style, BandCheck check) {
        int[] plainTiles = {9, 1, 7, 15};
        for (int size : new int[] {8, 16, 32, 64, 128}) {
            for (double roughness : new double[] {0.0, 0.6, 1.0}) {
                for (int reach : new int[] {1, 4, 8}) {
                    EdgeShape shape = new EdgeShape(style, size, reach, roughness, false, 1234);
                    String base = style + " " + size + "x roughness " + roughness + " reach " + reach;
                    for (int direction = 0; direction < 4; direction++) {
                        check.check(base + " plain tile " + plainTiles[direction], shape.cover(plainTiles[direction]), size, direction);
                    }
                    for (EdgeVariety variety : EdgeVariety.values()) {
                        VarietyLayout layout = variety.layout();
                        for (int direction = 0; direction < 4; direction++) {
                            for (int start = 0; start < layout.classes(); start++) {
                                for (int end = 0; end < layout.classes(); end++) {
                                    for (int wave = 0; wave < layout.waves(); wave++) {
                                        check.check(base + " " + variety + " band " + direction + start + end + wave,
                                            shape.band(direction, start, end, wave, layout.classes()), size, direction);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The texel {@code inward} texels in from the given edge, {@code along} texels along it. */
    private static int texelAt(int size, int direction, int along, int inward) {
        return switch (direction) {
            case EdgeShape.LEFT -> along * size + inward;
            case EdgeShape.DOWN -> (size - 1 - inward) * size + along;
            case EdgeShape.RIGHT -> along * size + (size - 1 - inward);
            default -> inward * size + along;
        };
    }
}
