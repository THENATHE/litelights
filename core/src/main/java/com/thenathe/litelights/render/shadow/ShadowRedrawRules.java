package com.thenathe.litelights.render.shadow;

import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Decides when the cached sun shadow map has to be drawn again (PLAN section 3). Only three
 * things make it stale: the light has turned by a small step, the camera has left the area the map
 * covers, or the caster geometry inside that area changed. Light-only chunk rebuilds never reach
 * this class, because the caster store compares geometry hashes first.
 */
public final class ShadowRedrawRules {
    /** The sun is redrawn after turning this far (degrees). */
    public static final double SUN_STEP_DEGREES = 0.5;
    private static final double SUN_STEP_COSINE = Math.cos(Math.toRadians(SUN_STEP_DEGREES));

    public enum Reason { NONE, FIRST_DRAW, LIGHT_MOVED, CAMERA_MOVED, CASTERS_CHANGED }

    private ShadowRedrawRules() {
    }

    /**
     * How far around the camera the map reaches beyond the shadow distance, so the camera can move
     * a while before the map has to follow it.
     */
    public static double margin(int shadowDistance) {
        return Math.max(32.0, shadowDistance / 4.0);
    }

    /** Half the width of the square the map covers, in blocks. */
    public static double coverageRadius(int shadowDistance) {
        return shadowDistance + margin(shadowDistance);
    }

    /** The camera may drift this far from the map's centre before the map is redrawn around it. */
    public static double recenterDistance(int shadowDistance) {
        return margin(shadowDistance) * 0.5;
    }

    /** Changes this close to the camera (the player building) redraw almost at once. */
    public static final double NEAR_CHANGE_BLOCKS = 48.0;
    private static final long NEAR_CHANGE_DELAY_NANOS = 100_000_000L;
    /** Farther changes, mostly chunks streaming in, are gathered into at most one redraw every two seconds. */
    private static final long FAR_CHANGE_DELAY_NANOS = 2_000_000_000L;

    /**
     * Whether pending caster changes should redraw the map now.
     *
     * @param nearestChange distance from the camera to the nearest changed section in the map, or
     *                      infinity if nothing changed
     * @param sinceLastRedraw nanoseconds since the map was last redrawn for any reason
     */
    public static boolean casterChangeIsDue(double nearestChange, long sinceLastRedraw) {
        if (Double.isInfinite(nearestChange)) {
            return false;
        }
        long delay = nearestChange <= NEAR_CHANGE_BLOCKS ? NEAR_CHANGE_DELAY_NANOS : FAR_CHANGE_DELAY_NANOS;
        return sinceLastRedraw >= delay;
    }

    public static boolean lightMoved(Vector3fc drawn, Vector3fc current) {
        double dot = (double) drawn.x() * current.x() + (double) drawn.y() * current.y() + (double) drawn.z() * current.z();
        return dot < SUN_STEP_COSINE;
    }

    public static boolean cameraLeftArea(double anchorX, double anchorZ, double cameraX, double cameraZ, int shadowDistance) {
        double dx = cameraX - anchorX;
        double dz = cameraZ - anchorZ;
        double limit = recenterDistance(shadowDistance);
        return dx * dx + dz * dz > limit * limit;
    }

    /**
     * @param drawn the light direction the current map was drawn with, or null if nothing is drawn
     * @param light the light direction now
     */
    public static Reason decide(
        @Nullable Vector3fc drawn,
        Vector3fc light,
        double anchorX,
        double anchorZ,
        double cameraX,
        double cameraZ,
        int shadowDistance,
        boolean castersChanged
    ) {
        if (drawn == null) {
            return Reason.FIRST_DRAW;
        }
        if (cameraLeftArea(anchorX, anchorZ, cameraX, cameraZ, shadowDistance)) {
            return Reason.CAMERA_MOVED;
        }
        if (lightMoved(drawn, light)) {
            return Reason.LIGHT_MOVED;
        }
        if (castersChanged) {
            return Reason.CASTERS_CHANGED;
        }
        return Reason.NONE;
    }
}
