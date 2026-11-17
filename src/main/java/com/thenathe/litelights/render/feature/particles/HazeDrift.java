package com.thenathe.litelights.render.feature.particles;

import org.joml.Vector4f;

/**
 * How far the haze's own noise has travelled with the wind (fog097): the 3D puffs, the classic haze
 * billboards and the Mist field sample their noise at world position minus this, so the pattern
 * rides the wind through the air instead of sticking to each puff or to the screen.
 *
 * <p>It moves once per client tick by that tick's wind, and slowly upward so the pattern also
 * changes shape, so it stops when the game is paused and keeps time with the particles themselves.
 * Frames in between are interpolated with the partial tick. Wrapped every 2048 blocks, as the
 * shaders wrap world positions (include/particle/haze.glsl), so it keeps a float's precision and the
 * pattern never jumps.
 */
final class HazeDrift {
    static final double WRAP = 2048.0;
    /** Blocks a tick the pattern rises, so it slowly changes shape even in still air. */
    static final double RISE = 0.006;

    private double x;
    private double y;
    private double z;
    private double previousX;
    private double previousY;
    private double previousZ;

    /** One tick: the wind's travel in blocks along x and z. */
    void tick(float windX, float windZ) {
        previousX = x;
        previousY = y;
        previousZ = z;
        x += windX;
        y += RISE;
        z += windZ;
        // Wrap the pair together, so the step between them is never a wrap.
        if (Math.abs(x) >= WRAP) {
            double shift = Math.copySign(WRAP, x);
            x -= shift;
            previousX -= shift;
        }
        if (y >= WRAP) {
            y -= WRAP;
            previousY -= WRAP;
        }
        if (Math.abs(z) >= WRAP) {
            double shift = Math.copySign(WRAP, z);
            z -= shift;
            previousZ -= shift;
        }
    }

    /** The drift at a moment between the last two ticks, into xyz (w is left alone). */
    void write(Vector4f out, float partialTick) {
        out.x = (float) (previousX + (x - previousX) * partialTick);
        out.y = (float) (previousY + (y - previousY) * partialTick);
        out.z = (float) (previousZ + (z - previousZ) * partialTick);
    }

    double x() {
        return x;
    }

    double z() {
        return z;
    }
}
