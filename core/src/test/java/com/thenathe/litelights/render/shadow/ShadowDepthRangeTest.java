package com.thenathe.litelights.render.shadow;

import com.thenathe.litelights.render.frame.CelestialLight;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sunsquare101: flying a little over the clouds in the morning, the land straight down-sun of the
 * camera lies deeper along the light than the creature map reaches. The shader must hold such a
 * point at the far plane, or the empty map's whole square reads as shadow on the land.
 */
class ShadowDepthRangeTest {
    private static final Path SHADOW_GLSL = Path.of("src/main/resources/assets/litelights/shaders/include/shadow.glsl");
    private static final double GROUND_Y = 63.0;

    private static Vector3f sunAt(long dayTime) {
        float angle = CelestialLight.sunAngleForDayTime(dayTime);
        return new Vector3f((float) -Math.sin(angle), (float) Math.cos(angle), 0.0f);
    }

    /** The creature map's clip-space position of the ground straight down-sun of a camera at that height. */
    private static Vector4f groundDownSun(long dayTime, double cameraY) {
        Vector3f sun = sunAt(dayTime);
        Vector3d camera = new Vector3d(-2400.5, cameraY, 1800.5);
        LightSpace space = new LightSpace();
        space.set(sun, camera, MobShadowMap.RADIUS, 1024, MobShadowMap.DEPTH_RADIUS, true);
        double along = (cameraY - GROUND_Y) / sun.y;
        Vector4f relative = new Vector4f((float) (-sun.x * along), (float) (-sun.y * along), (float) (-sun.z * along), 1.0f);
        return space.cameraRelative(camera.x, camera.y, camera.z, new Matrix4f()).transform(relative);
    }

    /** lite_shadow_lookup's test for one texel: 1 lit, 0 shadowed. */
    private static float lit(float receiverDepth, float texelDepth) {
        return Math.min(receiverDepth, 1.0f) <= texelDepth ? 1.0f : 0.0f;
    }

    @Test
    void theReportedViewPutsTheLandBelowPastTheCreatureMap() {
        // Time 3000, a few blocks over the default clouds (the lower layer at 192, the upper at 222).
        Vector4f overTheClouds = groundDownSun(3_000L, 228.0);
        assertTrue(Math.abs(overTheClouds.x) < 1.0f && Math.abs(overTheClouds.y) < 1.0f, "inside the map's square");
        assertTrue(overTheClouds.z > 1.0f, "past the far plane: " + overTheClouds.z);
        // Under the clouds the same land is within reach, which is why the height mattered.
        assertTrue(groundDownSun(3_000L, 180.0).z < 1.0f);
    }

    @Test
    void landPastTheFarPlaneIsLitWhereTheMapIsEmptyAndShadowedUnderACaster() {
        float landDepth = groundDownSun(3_000L, 228.0).z;
        assertEquals(1.0f, lit(landDepth, 1.0f), "the cleared depth");
        assertEquals(0.0f, lit(landDepth, 0.6f), "a creature in the map, between the land and the sun");
    }

    @Test
    void theShaderHoldsTheReceiverAtTheFarPlane() throws IOException {
        String shader = Files.readString(SHADOW_GLSL);
        int lookup = shader.indexOf("float lite_shadow_lookup(");
        int end = shader.indexOf("\n}", lookup);
        assertTrue(lookup >= 0 && end > lookup);
        assertTrue(shader.substring(lookup, end).contains("float depth = min(lite_shadow_depth_from_ndc(ndc.z), 1.0);"),
            "lite_shadow_lookup must clamp the receiver's depth to the far plane, as lit() here does");
    }
}
