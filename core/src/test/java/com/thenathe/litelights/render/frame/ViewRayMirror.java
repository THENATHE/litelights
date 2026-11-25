package com.thenathe.litelights.render.frame;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The view-ray helpers of {@code shaders/include/post_common.glsl} in Java, so the tests can check
 * them against the level's own projection. Keep the two in step.
 */
final class ViewRayMirror {
    private ViewRayMirror() {
    }

    /** {@code lite_position_from_depth}: camera-relative position of a pixel from its reversed depth. */
    static Vector3f positionFromDepth(Matrix4fc inverseViewProjection, float u, float v, float depth, boolean zeroToOne) {
        float ndcZ = zeroToOne ? depth : depth * 2.0f - 1.0f;
        Vector4f position = inverseViewProjection.transform(new Vector4f(u * 2.0f - 1.0f, v * 2.0f - 1.0f, ndcZ, 1.0f));
        return new Vector3f(position.x, position.y, position.z).div(position.w);
    }

    /** {@code lite_view_direction}: between two points on the pixel's line of sight. */
    static Vector3f viewDirection(Matrix4fc inverseViewProjection, float u, float v, boolean zeroToOne) {
        Vector3f near = positionFromDepth(inverseViewProjection, u, v, 1.0f, zeroToOne);
        Vector3f far = positionFromDepth(inverseViewProjection, u, v, 0.001f, zeroToOne);
        return far.sub(near).normalize();
    }

    /** {@code lite_view_origin}: the eye, from the inverse matrix's third column. */
    static Vector3f viewOrigin(Matrix4fc inverseViewProjection) {
        Vector4f eye = inverseViewProjection.getColumn(2, new Vector4f());
        return Math.abs(eye.w) > 1.0e-6f ? new Vector3f(eye.x, eye.y, eye.z).div(eye.w) : new Vector3f();
    }

    /** What the shaders did before 0.1.3: the direction toward one point at depth 0.5, close to the camera. */
    static Vector3f nearPointDirection(Matrix4fc inverseViewProjection, float u, float v, boolean zeroToOne) {
        return positionFromDepth(inverseViewProjection, u, v, 0.5f, zeroToOne).normalize();
    }
}
