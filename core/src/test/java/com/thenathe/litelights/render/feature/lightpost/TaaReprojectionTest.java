package com.thenathe.litelights.render.feature.lightpost;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TAA's reprojection (taa.fsh, through TaaMirror), against the level's own matrices: reversed depth,
 * view bobbing, and last frame's view-projection rebased onto this frame's camera as
 * LiteLightsRenderer builds it.
 */
class TaaReprojectionTest {
    private static final float NEAR = 0.05f;
    private static final float FAR = 768.0f;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    @Test
    void aStillCameraFindsEveryPixelWhereItWas() {
        for (boolean zeroToOne : new boolean[] {true, false}) {
            Matrix4f viewProjection = viewProjection(30.0f, 10.0f, 0.05f, zeroToOne);
            Matrix4f inverse = new Matrix4f(viewProjection).invert();
            for (float u = 0.05f; u < 1.0f; u += 0.15f) {
                for (float v = 0.05f; v < 1.0f; v += 0.15f) {
                    for (float distance : new float[] {0.5f, 4.0f, 60.0f, 500.0f}) {
                        float depth = depthAt(viewProjection, inverse, u, v, distance, zeroToOne);
                        Vector3f previous = TaaMirror.previousOf(viewProjection, inverse, u, v, depth, zeroToOne);
                        assertEquals(u, previous.x, 2.0e-4f);
                        assertEquals(v, previous.y, 2.0e-4f);
                    }
                    Vector3f sky = TaaMirror.previousOf(viewProjection, inverse, u, v, 0.0f, zeroToOne);
                    assertEquals(u, sky.x, 1.0e-4f);
                    assertEquals(v, sky.y, 1.0e-4f);
                    assertEquals(TaaMirror.SKY, sky.z);
                }
            }
        }
    }

    @Test
    void viewDepthIsTheDistanceAlongTheViewWithBobbingToo() {
        for (boolean zeroToOne : new boolean[] {true, false}) {
            Matrix4f viewProjection = viewProjection(-70.0f, -20.0f, 0.1f, zeroToOne);
            Matrix4f inverse = new Matrix4f(viewProjection).invert();
            for (float distance : new float[] {0.3f, 2.0f, 37.0f, 300.0f}) {
                float depth = depthAt(viewProjection, inverse, 0.3f, 0.7f, distance, zeroToOne);
                Vector3f position = TaaMirror.positionFromDepth(inverse, 0.3f, 0.7f, depth, zeroToOne);
                float clipW = viewProjection.transform(new Vector4f(position, 1.0f)).w;
                assertEquals(clipW, TaaMirror.viewDepth(inverse, depth, zeroToOne), clipW * 1.0e-3f);
            }
            assertEquals(TaaMirror.SKY, TaaMirror.viewDepth(inverse, 0.0f, zeroToOne));
        }
    }

    @Test
    void turningMovesNearAndFarPixelsAlikeSoATranslucentSurfaceNeverParts() {
        // Turning only: what is behind water reprojects exactly as the water's surface does.
        Matrix4f before = viewProjection(10.0f, 25.0f, 0.0f, true);
        Matrix4f after = viewProjection(11.5f, 25.0f, 0.0f, true);
        Matrix4f inverse = new Matrix4f(after).invert();
        float surface = depthAt(after, inverse, 0.4f, 0.45f, 6.0f, true);
        float bottom = depthAt(after, inverse, 0.4f, 0.45f, 11.0f, true);
        Vector3f fromSurface = TaaMirror.previousOf(before, inverse, 0.4f, 0.45f, surface, true);
        Vector3f fromBottom = TaaMirror.previousOf(before, inverse, 0.4f, 0.45f, bottom, true);
        Vector3f fromSky = TaaMirror.previousOf(before, inverse, 0.4f, 0.45f, 0.0f, true);
        assertTrue(pixelsApart(fromSurface, fromBottom) < 0.01f);
        assertTrue(pixelsApart(fromSurface, fromSky) < 0.01f);
        // 1.5 degrees of turn is many pixels of motion.
        assertTrue(Math.abs(0.4f - fromSurface.x) * WIDTH > 15.0f, "moved " + (0.4f - fromSurface.x) * WIDTH);
    }

    @Test
    void movingSidewaysPartsTheSurfaceFromWhatIsBehindIt() {
        // The camera steps 0.12 blocks to the side: the lake's bottom 11 blocks away moves less
        // across the screen than its surface 6 blocks away; reprojecting the bottom with the
        // surface's depth (what TAA did in 0.1.4) misses it by about a pixel and a half.
        Matrix4f after = viewProjection(0.0f, 30.0f, 0.0f, true);
        Matrix4f before = previousRebased(viewProjection(0.0f, 30.0f, 0.0f, true), new Vector3f(-0.12f, 0.0f, 0.0f));
        Matrix4f inverse = new Matrix4f(after).invert();
        float surface = depthAt(after, inverse, 0.5f, 0.4f, 6.0f, true);
        float bottom = depthAt(after, inverse, 0.5f, 0.4f, 11.0f, true);
        Vector3f fromSurface = TaaMirror.previousOf(before, inverse, 0.5f, 0.4f, surface, true);
        Vector3f fromBottom = TaaMirror.previousOf(before, inverse, 0.5f, 0.4f, bottom, true);
        float parting = pixelsApart(fromSurface, fromBottom);
        assertTrue(parting > 0.75f, "parting " + parting);

        // The bottom's own reprojection lands where the bottom really was last frame.
        Vector3f bottomPoint = TaaMirror.positionFromDepth(inverse, 0.5f, 0.4f, bottom, true);
        Vector4f truth = before.transform(new Vector4f(bottomPoint, 1.0f));
        assertEquals(truth.x / truth.w * 0.5f + 0.5f, fromBottom.x, 1.0e-5f);
        assertEquals(truth.w, fromBottom.z, truth.w * 1.0e-4f);
    }

    @Test
    void aMobThatWalkedAwayLeavesNoSurfaceToKeepAndOneThatStaysIsKept() {
        float tolerance = 0.03f;
        // Last frame a mob 5 blocks away filled the spot; now the wall 9 blocks away shows there.
        float[] mob = filled(5.0f);
        assertEquals(0.0f, TaaMirror.sameSurface(mob, 9.0f, tolerance));
        // ... and where the mob is now, last frame showed the wall.
        assertEquals(0.0f, TaaMirror.sameSurface(filled(9.0f), 5.0f, tolerance));
        // A still surface, and one at a silhouette (the 3x3 spans both sides), are kept.
        assertEquals(1.0f, TaaMirror.sameSurface(filled(9.0f), 9.05f, tolerance));
        float[] edge = filled(9.0f);
        edge[0] = 5.0f;
        edge[1] = 5.0f;
        assertEquals(1.0f, TaaMirror.sameSurface(edge, 5.0f, tolerance));
        assertEquals(1.0f, TaaMirror.sameSurface(edge, 9.0f, tolerance));
        // A slope seen at a grazing angle: depths across the 3x3 differ, the expected one lies within.
        assertEquals(1.0f, TaaMirror.sameSurface(new float[] {20, 21, 22, 20.5f, 21.5f, 22.5f, 21, 22, 23}, 22.2f, tolerance));
    }

    @Test
    void theSkyIsOnlyKeptWhereSkyWas() {
        float[] sky = filled(TaaMirror.SKY);
        assertEquals(1.0f, TaaMirror.sameSurface(sky, TaaMirror.SKY, 0.03f));
        // A mob moved off the skyline: last frame's spot was all mob.
        assertEquals(0.0f, TaaMirror.sameSurface(filled(12.0f), TaaMirror.SKY, 0.03f));
        // A mob moved onto it: last frame's spot was all sky.
        assertEquals(0.0f, TaaMirror.sameSurface(sky, 12.0f, 0.03f));
        float[] skyline = filled(TaaMirror.SKY);
        skyline[4] = 300.0f;
        assertEquals(1.0f, TaaMirror.sameSurface(skyline, TaaMirror.SKY, 0.03f));
        assertEquals(1.0f, TaaMirror.sameSurface(skyline, 500.0f, 0.03f));
    }

    // --- helpers ---

    /** The level's view-projection: reversed depth, a walking bob, then the view rotation. */
    private static Matrix4f viewProjection(float yawDegrees, float pitchDegrees, float bob, boolean zeroToOne) {
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70.0), (float) WIDTH / HEIGHT, FAR, NEAR, zeroToOne);
        projection.translate(bob * 0.5f, -bob, 0.0f).rotateZ((float) Math.toRadians(bob * 3.0f));
        Matrix4f rotation = new Matrix4f().rotationX((float) Math.toRadians(pitchDegrees)).rotateY((float) Math.toRadians(yawDegrees + 180.0f));
        return projection.mul(rotation);
    }

    /** Last frame's view-projection rebased onto this camera, as LiteLightsRenderer.fillMatrices does. */
    private static Matrix4f previousRebased(Matrix4f previous, Vector3f cameraMoved) {
        return new Matrix4f(previous).translate(cameraMoved.x, cameraMoved.y, cameraMoved.z);
    }

    /** The reversed device depth of the point this far along the pixel's line of sight. */
    private static float depthAt(Matrix4f viewProjection, Matrix4f inverse, float u, float v, float distance, boolean zeroToOne) {
        Vector3f near = TaaMirror.positionFromDepth(inverse, u, v, 1.0f, zeroToOne);
        Vector3f far = TaaMirror.positionFromDepth(inverse, u, v, 0.001f, zeroToOne);
        Vector3f point = new Vector3f(far).sub(near).normalize().mul(distance).add(near);
        Vector4f clip = viewProjection.transform(new Vector4f(point, 1.0f));
        float ndcZ = clip.z / clip.w;
        return zeroToOne ? ndcZ : ndcZ * 0.5f + 0.5f;
    }

    private static float pixelsApart(Vector3f a, Vector3f b) {
        return (float) Math.hypot((a.x - b.x) * WIDTH, (a.y - b.y) * HEIGHT);
    }

    private static float[] filled(float depth) {
        float[] depths = new float[9];
        java.util.Arrays.fill(depths, depth);
        return depths;
    }
}
