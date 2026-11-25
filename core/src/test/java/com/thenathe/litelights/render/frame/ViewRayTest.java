package com.thenathe.litelights.render.frame;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky, the light rays and TAA's sky reprojection turn a pixel back into a direction with the
 * level's view-projection, which carries view bobbing and nausea. The sun is where the level's own
 * projection puts a point infinitely far toward it, so a pixel's direction must come out as exactly
 * that, at every step of the bob.
 */
class ViewRayTest {
    private static final float NEAR = 0.05f;
    private static final float FAR = 1024.0f;
    private static final Vector3f SUN = new Vector3f(0.976f, 0.216f, 0.0f).normalize();
    /** A walking player's bob (Player.bob reaches 0.1 at walking speed). */
    private static final float WALKING_BOB = 0.1f;

    @Test
    void directionThroughTheSunsPixelPointsAtTheSunAtEveryBobPhase() {
        for (boolean zeroToOne : new boolean[] {true, false}) {
            for (float walked = 0.0f; walked < 2.0f; walked += 0.125f) {
                Matrix4f inverse = levelViewProjection(walked, WALKING_BOB, 0.0f, 0.0f, zeroToOne).invert();
                float[] uv = sunPixel(levelViewProjection(walked, WALKING_BOB, 0.0f, 0.0f, zeroToOne));
                Vector3f direction = ViewRayMirror.viewDirection(inverse, uv[0], uv[1], zeroToOne);
                assertTrue(degreesBetween(direction, SUN) < 0.01,
                    "off by " + degreesBetween(direction, SUN) + " degrees at walk " + walked + ", zeroToOne " + zeroToOne);
            }
        }
    }

    @Test
    void nauseaAndPortalDistortionKeepTheSunInPlace() {
        for (boolean zeroToOne : new boolean[] {true, false}) {
            for (float angle = 0.0f; angle < 360.0f; angle += 45.0f) {
                Matrix4f viewProjection = levelViewProjection(0.3f, WALKING_BOB, 1.0f, angle, zeroToOne);
                float[] uv = sunPixel(viewProjection);
                Vector3f direction = ViewRayMirror.viewDirection(new Matrix4f(viewProjection).invert(), uv[0], uv[1], zeroToOne);
                assertTrue(degreesBetween(direction, SUN) < 0.01, "off by " + degreesBetween(direction, SUN) + " degrees at spin " + angle);
            }
        }
    }

    @Test
    void aPointNearTheCameraSwingsWithTheBob() {
        // The 0.1.1 sky and light rays took the direction toward a point at depth 0.5, about 0.1
        // blocks from the camera; the bob moves the eye by as much, so the sun jumped with every step.
        Matrix4f viewProjection = levelViewProjection(0.5f, WALKING_BOB, 0.0f, 0.0f, true);
        float[] uv = sunPixel(viewProjection);
        Vector3f direction = ViewRayMirror.nearPointDirection(new Matrix4f(viewProjection).invert(), uv[0], uv[1], true);
        assertTrue(degreesBetween(direction, SUN) > 5.0, "only " + degreesBetween(direction, SUN) + " degrees");
    }

    @Test
    void viewOriginIsTheBobbedEye() {
        for (boolean zeroToOne : new boolean[] {true, false}) {
            Matrix4f viewProjection = levelViewProjection(0.0f, WALKING_BOB, 0.0f, 0.0f, zeroToOne);
            Vector3f eye = ViewRayMirror.viewOrigin(new Matrix4f(viewProjection).invert());
            // The eye is the one point that projects to the middle of the view with w = 0.
            Vector4f clip = viewProjection.transform(new Vector4f(eye, 1.0f));
            assertEquals(0.0f, clip.x, 1.0e-5f);
            assertEquals(0.0f, clip.y, 1.0e-5f);
            assertEquals(0.0f, clip.w, 1.0e-5f);
            // Between steps the bob moves the view up or down by the whole bob.
            assertEquals(WALKING_BOB, eye.length(), 1.0e-4f);

            Vector3f still = ViewRayMirror.viewOrigin(levelViewProjection(0.5f, 0.0f, 0.0f, 0.0f, zeroToOne).invert());
            assertEquals(0.0f, still.length(), 1.0e-5f);
        }
    }

    @Test
    void skyEventsDrawnFromTheEyeStayWithTheStarsAtEveryBobPhase() {
        // The sky events (shaders/sky/events.vsh) put a point a few blocks out, the aurora's
        // nearest curtains, at the eye rather than at the camera position. On screen it must then
        // land where a star in the same direction does, however the view bobs.
        Vector3f near = new Vector3f(0.976f, 0.216f, 0.0f).normalize().mul(6.0f);
        double largestFromCamera = 0.0;
        for (boolean zeroToOne : new boolean[] {true, false}) {
            for (float walked = 0.0f; walked < 2.0f; walked += 0.125f) {
                Matrix4f viewProjection = levelViewProjection(walked, WALKING_BOB, 0.0f, 0.0f, zeroToOne);
                Vector3f eye = ViewRayMirror.viewOrigin(new Matrix4f(viewProjection).invert());
                float[] star = screen(viewProjection.transform(new Vector4f(near, 0.0f)));
                float[] fromEye = screen(viewProjection.transform(new Vector4f(new Vector3f(near).add(eye), 1.0f)));
                float[] fromCamera = screen(viewProjection.transform(new Vector4f(near, 1.0f)));
                assertEquals(star[0], fromEye[0], 1.0e-4f, "at walk " + walked);
                assertEquals(star[1], fromEye[1], 1.0e-4f, "at walk " + walked);
                largestFromCamera = Math.max(largestFromCamera, Math.hypot(star[0] - fromCamera[0], star[1] - fromCamera[1]));
            }
        }
        // 0.9.7 drew from the camera: the point strayed from the stars by a hundredth of the screen
        // and more, about twenty pixels on a 1080-line screen.
        assertTrue(largestFromCamera > 0.01, "only " + largestFromCamera);
    }

    /** Screen position (0..1) of a clip-space point. */
    private static float[] screen(Vector4f clip) {
        return new float[] {clip.x / clip.w * 0.5f + 0.5f, clip.y / clip.w * 0.5f + 0.5f};
    }

    /**
     * The level's projection and view rotation as GameRenderer.renderLevel and Camera build them:
     * reversed depth (near and far swapped), view bobbing, then the nausea skew, then the rotation.
     */
    private static Matrix4f levelViewProjection(float walked, float bob, float spin, float spinDegrees, boolean zeroToOne) {
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70.0), 16.0f / 9.0f, FAR, NEAR, zeroToOne);
        float phase = walked * (float) Math.PI;
        Matrix4f bobbing = new Matrix4f()
            .translate((float) Math.sin(phase) * bob * 0.5f, -Math.abs((float) Math.cos(phase) * bob), 0.0f)
            .rotateZ((float) Math.toRadians(Math.sin(phase) * bob * 3.0f))
            .rotateX((float) Math.toRadians(Math.abs(Math.cos(phase - 0.2f) * bob) * 5.0f));
        projection.mul(bobbing);
        if (spin > 0.0f) {
            float skew = 5.0f / (spin * spin + 5.0f) - spin * 0.04f;
            skew *= skew;
            Vector3f axis = new Vector3f(0.0f, (float) Math.sqrt(0.5), (float) Math.sqrt(0.5));
            float angle = (float) Math.toRadians(spinDegrees);
            projection.rotate(angle, axis).scale(1.0f / skew, 1.0f, 1.0f).rotate(-angle, axis);
        }
        // Looking east and a little up, toward the morning sun.
        Matrix4f viewRotation = new Matrix4f().rotationX((float) Math.toRadians(-10.0)).rotateY((float) Math.toRadians(90.0));
        return projection.mul(viewRotation);
    }

    /** The screen position (0..1) of a point infinitely far toward the sun. */
    private static float[] sunPixel(Matrix4f viewProjection) {
        Vector4f clip = viewProjection.transform(new Vector4f(SUN, 0.0f));
        assertTrue(clip.w > 0.0f, "the sun is behind the camera");
        float u = clip.x / clip.w * 0.5f + 0.5f;
        float v = clip.y / clip.w * 0.5f + 0.5f;
        assertTrue(u > 0.0f && u < 1.0f && v > 0.0f && v < 1.0f, "the sun is off screen");
        return new float[] {u, v};
    }

    private static double degreesBetween(Vector3f a, Vector3f b) {
        return Math.toDegrees(Math.acos(Math.min(1.0, a.dot(b) / (a.length() * b.length()))));
    }
}
