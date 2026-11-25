package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.overlays.VanillaAssets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Bricks style draws whole bricks, and neighbouring blocks agree about the bricks they share. */
class BrickShapeTest {
    private static final List<String> TEXTURES = List.of("stone_bricks", "bricks", "deepslate_tiles", "nether_bricks",
        "mud_bricks", "quartz_bricks");
    private static final int CLASSES = 3;
    private static final int WAVES = 2;

    /** A brick is drawn whole or not at all, mortar and all, and a brick the border cuts is always finished. */
    @Test
    void ownBricksAreWholeAndCutBricksAreFinished() {
        for (String name : TEXTURES) {
            BrickGrid grid = grid(name);
            for (int reach : new int[] {1, 4, 8}) {
                BrickShape shape = new BrickShape(grid, reach, 0.6, false, name.hashCode());
                forEveryBand(shape, (mask, direction) -> {
                    for (BrickGrid.Brick brick : grid.bricks()) {
                        boolean any = false;
                        boolean all = true;
                        for (int y = brick.top(); y <= brick.bottom(); y++) {
                            for (int x = brick.left(); x <= brick.right(); x++) {
                                if (!grid.isMortar(x, y)) {
                                    boolean covered = mask[y * 16 + x] != EdgeShape.EMPTY;
                                    any |= covered;
                                    all &= covered;
                                }
                            }
                        }
                        assertTrue(!any || all, name + ": brick " + brick + " drawn in part, direction " + direction);
                        if (cutBy(brick, direction)) {
                            assertTrue(all, name + ": brick " + brick + " cut by edge " + direction + " isn't finished");
                        }
                    }
                });
            }
        }
    }

    /** Every brick a source draws over its neighbour shows the mortar all round it. */
    @Test
    void ownBricksCarryTheirMortarOutline() {
        for (String name : TEXTURES) {
            BrickGrid grid = grid(name);
            BrickShape shape = new BrickShape(grid, 4, 0.6, false, 7);
            forEveryBand(shape, (mask, direction) -> {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        if (!grid.isMortar(x, y) || mask[y * 16 + x] != EdgeShape.EMPTY) {
                            continue;
                        }
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int nx = x + dx;
                                int ny = y + dy;
                                if (nx >= 0 && ny >= 0 && nx < 16 && ny < 16 && !grid.isMortar(nx, ny)) {
                                    assertEquals(EdgeShape.EMPTY, mask[ny * 16 + nx],
                                        name + ": mortar at " + x + "," + y + " left out beside a drawn brick");
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * Along a top edge, a brick spanning the corner between two blocks is drawn by both or by
     * neither: the left block decides by its end corner, the right one by its start corner, and
     * that is the same corner.
     */
    @Test
    void blocksAgreeAboutABrickSpanningTheirCorner() {
        for (String name : TEXTURES) {
            BrickGrid grid = grid(name);
            BrickShape shape = new BrickShape(grid, 8, 1.0, false, 3);
            for (int direction : new int[] {EdgeShape.UP, EdgeShape.DOWN}) {
                for (int shared = 0; shared < CLASSES; shared++) {
                    for (int other = 0; other < CLASSES; other++) {
                        for (int wave = 0; wave < WAVES; wave++) {
                            byte[] left = shape.band(direction, other, shared, wave, CLASSES);
                            byte[] right = shape.band(direction, shared, other, 1 - wave, CLASSES);
                            for (BrickGrid.Brick brick : grid.bricks()) {
                                BrickGrid.Brick rest = grid.brickAt(0, brick.top());
                                // A part that is only mortar (mud bricks' joint on the border) has no brick to compare.
                                if (brick.continuesRight() && hasBody(rest, grid)) {
                                    assertEquals(covered(left, brick, grid), covered(right, rest, grid),
                                        name + ": the blocks disagree about " + brick);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Down a side edge, a course that runs on into the block above reaches as far in both. Stone
     * bricks moved down by four texels have a course split by the border.
     */
    @Test
    void blocksAgreeAboutACourseSpanningTheirCorner() {
        int[] pixels = VanillaAssets.blockTexture("stone_bricks").pixels();
        int[] moved = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            moved[(i + 4 * 16) % pixels.length] = pixels[i];
        }
        BrickGrid grid = BrickGrid.detect(moved, 16);
        List<BrickGrid.Brick> top = grid.rows().getFirst();
        List<BrickGrid.Brick> bottom = grid.rows().getLast();
        assertTrue(top.getFirst().continuesUp() && bottom.getFirst().continuesDown(), "a course runs across the border");
        BrickShape shape = new BrickShape(grid, 8, 1.0, false, 5);
        for (int shared = 0; shared < CLASSES; shared++) {
            for (int other = 0; other < CLASSES; other++) {
                byte[] below = shape.band(EdgeShape.LEFT, shared, other, 0, CLASSES);
                byte[] above = shape.band(EdgeShape.LEFT, other, shared, 1, CLASSES);
                assertEquals(reachOf(below, top, grid), reachOf(above, bottom, grid));
            }
        }
    }

    /**
     * Two brick blocks merging into each other share the edge out: where one's bricks reach
     * across, the other's don't, so the seam never shows both swapped.
     */
    @Test
    void blendedHalvesNeverReachAcrossTheSameStretch() {
        BrickGrid stone = grid("stone_bricks");
        BrickGrid bricks = grid("bricks");
        BrickShape stoneHalf = new BrickShape(stone, 4, 0.6, true, 1);
        BrickShape bricksHalf = new BrickShape(bricks, 4, 0.6, true, 2);
        int stretches = 0;
        for (int start = 0; start < CLASSES; start++) {
            for (int end = 0; end < CLASSES; end++) {
                for (int wave = 0; wave < WAVES; wave++) {
                    // Stone bricks to the west of bricks: its half lies on the bricks' face, and the other way round.
                    byte[] onBricks = stoneHalf.band(EdgeShape.LEFT, start, end, wave, CLASSES);
                    byte[] onStone = bricksHalf.band(EdgeShape.RIGHT, start, end, wave, CLASSES);
                    for (int y = 0; y < 16; y++) {
                        boolean stoneReaches = bodyInRow(onBricks, stone, y);
                        boolean bricksReach = bodyInRow(onStone, bricks, y);
                        assertFalse(stoneReaches && bricksReach, "both halves reach across in row " + y);
                        stretches += stoneReaches || bricksReach ? 1 : 0;
                    }
                }
            }
        }
        assertTrue(stretches > 0, "neither half ever reached across");
    }

    /** Plain tiles: the corner-only ones are empty, and every tile with a left edge has the left band. */
    @Test
    void plainTilesAgreeOnSharedEdges() {
        BrickShape shape = new BrickShape(grid("stone_bricks"), 4, 0.6, false, 9);
        for (int tile : new int[] {0, 2, 14, 16}) {
            for (byte texel : shape.cover(tile)) {
                assertEquals(EdgeShape.EMPTY, texel);
            }
        }
        byte[] leftOnly = shape.cover(9);
        for (int tile : new int[] {4, 5, 6, 8, 11, 13}) {
            byte[] other = shape.cover(tile);
            for (int i = 0; i < leftOnly.length; i++) {
                if (leftOnly[i] != EdgeShape.EMPTY) {
                    assertTrue(other[i] != EdgeShape.EMPTY, "tile " + tile + " drops a left-band texel at " + i);
                }
            }
        }
    }

    /** Passed instead of a direction for an inner corner piece. */
    private static final int INNER_CORNER = -1;

    private interface BandCheck {
        void check(byte[] mask, int direction);
    }

    private static void forEveryBand(BrickShape shape, BandCheck check) {
        for (int direction = 0; direction < 4; direction++) {
            for (int start = 0; start < CLASSES; start++) {
                for (int end = 0; end < CLASSES; end++) {
                    for (int wave = 0; wave < WAVES; wave++) {
                        check.check(shape.band(direction, start, end, wave, CLASSES), direction);
                    }
                }
            }
        }
        for (int corner = 0; corner < 4; corner++) {
            check.check(shape.innerCorner(corner, 0, CLASSES), INNER_CORNER);
        }
    }

    private static boolean cutBy(BrickGrid.Brick brick, int direction) {
        return switch (direction) {
            case EdgeShape.LEFT -> brick.continuesLeft();
            case EdgeShape.RIGHT -> brick.continuesRight();
            case EdgeShape.UP -> brick.continuesUp();
            case EdgeShape.DOWN -> brick.continuesDown();
            default -> false;
        };
    }

    private static boolean covered(byte[] mask, BrickGrid.Brick brick, BrickGrid grid) {
        for (int y = brick.top(); y <= brick.bottom(); y++) {
            for (int x = brick.left(); x <= brick.right(); x++) {
                if (!grid.isMortar(x, y) && mask[y * 16 + x] != EdgeShape.EMPTY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBody(BrickGrid.Brick brick, BrickGrid grid) {
        for (int y = brick.top(); y <= brick.bottom(); y++) {
            for (int x = brick.left(); x <= brick.right(); x++) {
                if (!grid.isMortar(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int reachOf(byte[] mask, List<BrickGrid.Brick> row, BrickGrid grid) {
        int count = 0;
        for (BrickGrid.Brick brick : row) {
            count += covered(mask, brick, grid) ? 1 : 0;
        }
        return count;
    }

    private static boolean bodyInRow(byte[] mask, BrickGrid grid, int y) {
        for (int x = 0; x < 16; x++) {
            if (!grid.isMortar(x, y) && mask[y * 16 + x] != EdgeShape.EMPTY) {
                return true;
            }
        }
        return false;
    }

    private static BrickGrid grid(String name) {
        return BrickGrid.of(VanillaAssets.blockTexture(name));
    }
}
