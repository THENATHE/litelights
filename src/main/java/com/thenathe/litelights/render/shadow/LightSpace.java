package com.thenathe.litelights.render.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * An orthographic view from the light, centred on an anchor point in the world. Matrices map
 * positions relative to the anchor; the anchor itself is kept in doubles so maps stay exact far
 * from the world origin.
 *
 * <p>The anchor is snapped to the map's texel grid in light space, so two maps drawn with the same
 * light direction and resolution put their texels in the same places: moving the map with the
 * camera never makes shadow edges crawl.
 */
public final class LightSpace {
    private static final Vector3dc WORLD_Z = new Vector3d(0, 0, 1);
    private static final Vector3dc WORLD_X = new Vector3d(1, 0, 0);

    // Reused by set(), which runs every frame for the mob map.
    private final Vector3d scratchLight = new Vector3d();
    private final Matrix4f scratchView = new Matrix4f();
    private final Vector3d right = new Vector3d();
    private final Vector3d up = new Vector3d();
    private final Vector3d anchor = new Vector3d();
    private final Vector3f lightDirection = new Vector3f(0, 1, 0);
    private final Matrix4f viewProjection = new Matrix4f();

    /**
     * @param light unit vector toward the light
     * @param center the point the map should be centred near (usually the camera)
     * @param radius half the width of the covered square, in blocks
     * @param mapSize texels along one side
     * @param depthRadius how far casters may be in front of or behind the anchor along the light
     * @param zeroToOneDepth whether the device's clip space depth runs 0..1 (Vulkan) or -1..1
     */
    public void set(Vector3f light, Vector3d center, double radius, int mapSize, float depthRadius, boolean zeroToOneDepth) {
        lightDirection.set(light);
        Vector3d lightDouble = scratchLight.set(light.x, light.y, light.z).normalize();
        // The sun moves in the X-Y plane, so world +Z is never parallel to the light; it is a stable
        // "up" for the light's view.
        Vector3dc worldUp = Math.abs(lightDouble.z) < 0.99 ? WORLD_Z : WORLD_X;
        lightDouble.cross(worldUp, right).normalize();
        right.cross(lightDouble, up).normalize();

        double texel = radius * 2.0 / mapSize;
        double alongRight = center.dot(right);
        double alongUp = center.dot(up);
        double snappedRight = Math.floor(alongRight / texel) * texel;
        double snappedUp = Math.floor(alongUp / texel) * texel;
        anchor.set(center)
            .fma(snappedRight - alongRight, right)
            .fma(snappedUp - alongUp, up);

        Matrix4f view = scratchView.setLookAt(
            light.x, light.y, light.z,
            0, 0, 0,
            (float) up.x, (float) up.y, (float) up.z);
        viewProjection.setOrtho((float) -radius, (float) radius, (float) -radius, (float) radius, -depthRadius, depthRadius, zeroToOneDepth)
            .mul(view);
    }

    /** Maps positions relative to the anchor to the map's clip space. */
    public Matrix4f viewProjection() {
        return viewProjection;
    }

    public Vector3d anchor() {
        return anchor;
    }

    public Vector3f lightDirection() {
        return lightDirection;
    }

    /**
     * The matrix for positions relative to the camera: shifts them by camera minus anchor (worked
     * out in doubles), then applies the map's view-projection.
     */
    public Matrix4f cameraRelative(double cameraX, double cameraY, double cameraZ, Matrix4f out) {
        return out.set(viewProjection).translate(
            (float) (cameraX - anchor.x),
            (float) (cameraY - anchor.y),
            (float) (cameraZ - anchor.z));
    }

    /** Where a world point lands across the map, in blocks from its centre (x right, y up). */
    public double lightRight(double x, double y, double z) {
        return (x - anchor.x) * right.x + (y - anchor.y) * right.y + (z - anchor.z) * right.z;
    }

    public double lightUp(double x, double y, double z) {
        return (x - anchor.x) * up.x + (y - anchor.y) * up.y + (z - anchor.z) * up.z;
    }

    public void copyFrom(LightSpace other) {
        right.set(other.right);
        up.set(other.up);
        anchor.set(other.anchor);
        lightDirection.set(other.lightDirection);
        viewProjection.set(other.viewProjection);
    }
}
