package com.thenathe.litelights.render.feature.weather;

import java.util.function.IntBinaryOperator;

/**
 * The rules of {@code shaders/include/glass/weather.glsl} that decide where weather lands on glass,
 * in Java, so the tests can check them: which block of air a face looks into, which side of the
 * glass is outdoors and how much frost, snow and sand it gets, whether snow can pile against it, where the
 * outside of the glass is when seen from inside, and how deep the bands along a window's edges
 * reach. Positions are world positions (the shader works relative to the camera's block, which gives
 * the same blocks). Keep the two in step.
 */
final class GlassShaderMirror {
    static final int NO_TOP = Integer.MIN_VALUE;

    /** Rain tops by column, as the shader reads them from the rain top grid. */
    private final IntBinaryOperator rainTops;
    /** The height of the water or lava surface by column (the column map's), or NO_TOP where there is none. */
    private final IntBinaryOperator fluidSurfaces;

    GlassShaderMirror(IntBinaryOperator rainTops) {
        this(rainTops, (x, z) -> NO_TOP);
    }

    GlassShaderMirror(IntBinaryOperator rainTops, IntBinaryOperator fluidSurfaces) {
        this.rainTops = rainTops;
        this.fluidSurfaces = fluidSurfaces;
    }

    /** lite_glass_open: rain falls through this block of air. */
    boolean open(int x, int y, int z) {
        int top = rainTops.applyAsInt(x, z);
        return top != NO_TOP && y >= top;
    }

    /** The block of air a face at {@code position} looks into along {@code outward} (lite_glass_face's front). */
    static int[] front(double[] position, int[] outward) {
        return blockAt(position, outward, 1);
    }

    /** The block of glass a face at {@code position} belongs to (lite_glass_face's glass). */
    static int[] glass(double[] position, int[] outward) {
        return blockAt(position, outward, 0);
    }

    private static int[] blockAt(double[] position, int[] outward, int stepsOut) {
        int[] block = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            double inside = position[axis] - outward[axis] / 256.0;
            block[axis] = (int) Math.floor(inside + outward[axis] * stepsOut);
        }
        return block;
    }

    /** lite_glass_open_distance's reach: how many blocks out a side is still looked at. */
    static final int SIDE_REACH = 3;

    /** lite_glass_open_distance: blocks out from {@code front} to the first open air, or SIDE_REACH + 1. */
    int openDistance(int[] front, int[] outward) {
        for (int step = 0; step <= SIDE_REACH; step++) {
            if (open(front[0] + outward[0] * step, front[1] + outward[1] * step, front[2] + outward[2] * step)) {
                return step;
            }
        }
        return SIDE_REACH + 1;
    }

    /**
     * lite_glass_exposure: how much frost, snow and sand reach the face at {@code position}: 1 open to the
     * sky, 0.5, 0.35 or 0.25 when the open sky is one, two or three blocks out (and no nearer on the
     * glass's other side), 0 indoors; faces looking down get none, tops only when open.
     */
    float exposure(double[] position, int[] outward) {
        int[] front = front(position, outward);
        if (outward[1] < 0) {
            return 0.0f;
        }
        if (outward[1] > 0) {
            return open(front[0], front[1], front[2]) ? 1.0f : 0.0f;
        }
        int near = openDistance(front, outward);
        if (near == 0) {
            return 1.0f;
        }
        if (near > SIDE_REACH) {
            return 0.0f;
        }
        int[] glass = glass(position, outward);
        int[] inward = {-outward[0], -outward[1], -outward[2]};
        int[] otherFront = {glass[0] + inward[0], glass[1] + inward[1], glass[2] + inward[2]};
        int far = openDistance(otherFront, inward);
        if (far < near) {
            return 0.0f;
        }
        return near == 1 ? 0.5f : near == 2 ? 0.35f : 0.25f;
    }

    /** lite_glass_under_fluid: the block of air in front lies under a water or lava surface. */
    boolean underFluid(int[] front) {
        int surface = fluidSurfaces.applyAsInt(front[0], front[2]);
        return surface != NO_TOP && front[1] + 0.5 < surface;
    }

    /** lite_glass_frost's share: the face's exposure, as for snow and sand, but none with water or lava against it. */
    float frost(double[] position, int[] outward) {
        return underFluid(front(position, outward)) ? 0.0f : exposure(position, outward);
    }

    /** lite_glass_on_ledge: something rain can't pass is right under the air in front. */
    boolean onLedge(int[] front) {
        return rainTops.applyAsInt(front[0], front[2]) == front[1];
    }

    /**
     * The snow and sand the camera sees on a face: its own when the face is outdoors, otherwise
     * (seen from inside) the far face's, which lies a block away for full blocks and an eighth of a
     * block for panes. {@code view} is the unit direction from the eye to the point.
     */
    float seenExposure(double[] position, int[] towardCamera, double[] view) {
        return seen(position, towardCamera, view, this::exposure);
    }

    /** The frost the camera sees on a face, the same way: its own outdoors, the far face's from inside. */
    float seenFrost(double[] position, int[] towardCamera, double[] view) {
        return seen(position, towardCamera, view, this::frost);
    }

    private interface FaceShare {
        float of(double[] position, int[] outward);
    }

    private static float seen(double[] position, int[] towardCamera, double[] view, FaceShare share) {
        float near = share.of(position, towardCamera);
        if (near > 0.0f) {
            return near;
        }
        int axis = towardCamera[0] != 0 ? 0 : towardCamera[1] != 0 ? 1 : 2;
        double along = position[axis];
        double thickness = Math.abs(along - Math.rint(along)) < 0.01 ? 1.0 : 0.125;
        double facing = -(view[0] * towardCamera[0] + view[1] * towardCamera[1] + view[2] * towardCamera[2]);
        if (facing <= 0.05) {
            return 0.0f;
        }
        double[] far = new double[3];
        for (int i = 0; i < 3; i++) {
            far[i] = position[i] + view[i] * (thickness / facing);
        }
        int[] away = {-towardCamera[0], -towardCamera[1], -towardCamera[2]};
        return share.of(far, away) * 0.9f;
    }

    /** lite_glass_band_width: a full band's width in blocks on a window whose smaller side is {@code size}. */
    static double bandWidth(double size) {
        return Math.min(0.6, 0.05 + 0.07 * size);
    }

    /**
     * lite_glass_edge_depth: how deep a point lies in its window from the nearest edge, with the
     * corners rounded. runs are the window blocks beyond this one toward u low, u high, v low, v high;
     * inBlock the point's place in its block along u and v.
     */
    static double edgeDepth(double[] runs, double[] inBlock, double band, double bottom) {
        double acrossU = Math.min(runs[0] + inBlock[0], runs[1] + 1.0 - inBlock[0]);
        double acrossV = Math.min((runs[2] + inBlock[1]) / bottom, runs[3] + 1.0 - inBlock[1]);
        double rounding = band * 2.5;
        double blend = Math.max(rounding - Math.abs(acrossU - acrossV), 0.0) / rounding;
        return Math.min(acrossU, acrossV) - blend * blend * rounding * 0.25;
    }

    /** lite_glass_snow_bottom: how many times deeper snow lies along a window's bottom than its sides. */
    static double snowBottom(double band, double height, boolean ledge) {
        double deepest = Math.min(1.0, 0.2 * height);
        return Math.max(1.0, Math.min(ledge ? 3.0 : 2.0, deepest / Math.max(band, 0.001)));
    }

    /** The ragged edge's range in lite_glass_band: the band reaches 0.65 to 1.35 times its width. */
    static final double LEAST_RAGGED = 0.65;
    static final double MOST_RAGGED = 1.35;

    /** The shares of snow's band width that frost and sand draw (lite_glass_frost, lite_glass_dust). */
    static final double FROST_BAND = 0.78;
    static final double SAND_BAND = 0.68;

    /**
     * How far past a band's ragged inner edge, in blocks, frost's feathers or sand's loose grains can
     * reach at most: feathers a third and a bit of the band, up to 2.5 texels; grains a texel and a half.
     */
    static double frostFeatherReach(double band, double texels) {
        return Math.min(2.5, band * texels * 0.35) / texels;
    }

    static double looseSandReach(double texels) {
        return 1.5 / texels;
    }
}
