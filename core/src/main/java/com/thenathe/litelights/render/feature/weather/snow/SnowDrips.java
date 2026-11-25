package com.thenathe.litelights.render.feature.weather.snow;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * The snowy overlay: snow weeping a little way down a face from the snow lying along its top edge,
 * as a ragged band with short drips (idea by Snownee, Snow! Real Magic; nothing of it is used). Each
 * sixteenth along the edge hangs down by its own length, fixed per world position so it never
 * flickers between mesh builds, and runs of equal length are drawn as one quad of the snow's own
 * texture, so a resource pack's snow weeps in its own colours. Deeper snow weeps a little further.
 */
final class SnowDrips {
    /** Drip lengths in sixteenths below the band, by a hash's share: mostly short, a few long. */
    private static final int[] LENGTHS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5};
    /** The band every sixteenth hangs down at least, in sixteenths. */
    private static final int BAND = 1;

    private SnowDrips() {
    }

    /**
     * Drips on the face {@code side} at {@code plane} (sixteenths across its axis, in the block's own
     * space), from {@code from} to {@code to} along it, hanging from {@code top} and never below
     * {@code floor} (all in sixteenths; below the block's space is negative), under snow
     * {@code snowDepth} sixteenths deep. {@code x, y, z} is the block the snow belongs to, for the
     * world-fixed lengths.
     */
    static void along(QuadEmitter emitter, TextureAtlasSprite sprite, Direction side, int plane, int from, int to, int top,
                      int floor, int snowDepth, int x, int y, int z, int light) {
        int deeper = snowDepth >= 4 ? 1 : 0;
        int start = from;
        int startLength = length(side, plane, start, top, floor, deeper, x, y, z);
        for (int along = from + 1; along <= to; along++) {
            int next = along < to ? length(side, plane, along, top, floor, deeper, x, y, z) : -1;
            if (next == startLength) {
                continue;
            }
            if (startLength > 0) {
                float sixteenth = 1.0f / SnowSurfaces.GRID;
                SnowGeometry.sideQuad(emitter, sprite, side, plane * sixteenth, start * sixteenth, along * sixteenth,
                    (top - startLength) * sixteenth, top * sixteenth, 0.0f, startLength * sixteenth, light, true);
            }
            start = along;
            startLength = next;
        }
    }

    /** How far one sixteenth of the edge weeps, in sixteenths, held above the floor. */
    static int length(Direction side, int plane, int along, int top, int floor, int deeper, int x, int y, int z) {
        int worldAlong = (side.getAxis() == Direction.Axis.Z ? x : z) * SnowSurfaces.GRID + along;
        int worldAcross = (side.getAxis() == Direction.Axis.Z ? z : x) * SnowSurfaces.GRID + plane;
        long hash = SnowLayerRule.mix(worldAlong * 0x9E3779B97F4A7C15L ^ worldAcross * 0xC2B2AE3D27D4EB4FL
            ^ (y * SnowSurfaces.GRID + top) * 0x165667B19E3779F9L ^ side.ordinal());
        int length = BAND + LENGTHS[(int) Long.remainderUnsigned(hash, LENGTHS.length)] + deeper;
        return Math.max(0, Math.min(length, top - floor));
    }
}
