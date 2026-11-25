package com.thenathe.litelights.render.feature.farterrain;

import java.nio.ByteBuffer;
import org.joml.Vector3f;

/**
 * The push constants of the far shadow passes, in the order of {@code FarTerrainValues} in
 * shaders/include/farterrain/source.glsl: four vectors, 64 bytes.
 */
final class FarValues {
    static final int BYTES = 64;

    float shadowTexelX;
    float shadowTexelY;
    float shadowFadeStart;
    float shadowFadeEnd;
    float seaSurfaceHeight;
    /** In the shadow passes, the heightfield's values (see far_shadow.fsh). */
    float heightfieldMinY;
    float heightfieldReach;
    float marchDistance;
    /**
     * In the shadow passes, the player the camera follows: the middle of their body, camera-relative,
     * and how far around it the screen-space trace ignores what it sees (0: nobody to leave out).
     */
    final Vector3f playerCentre = new Vector3f();
    float playerReach;

    /** The terrain pass reads only the shadow texel and the fade band; FarSwitches and FarPlayer are the shadow passes'. */
    void writeForTerrain(ByteBuffer buffer) {
        writeCommon(buffer);
        buffer.putFloat(16, 0.0f).putFloat(20, 0.0f).putFloat(24, 0.0f).putFloat(28, 0.0f);
        buffer.putFloat(48, 0.0f).putFloat(52, 0.0f).putFloat(56, 0.0f).putFloat(60, 0.0f);
    }

    void writeForShadows(ByteBuffer buffer) {
        writeCommon(buffer);
        buffer.putFloat(16, heightfieldMinY).putFloat(20, heightfieldReach).putFloat(24, marchDistance).putFloat(28, 0.0f);
        buffer.putFloat(48, playerCentre.x).putFloat(52, playerCentre.y).putFloat(56, playerCentre.z).putFloat(60, playerReach);
    }

    private void writeCommon(ByteBuffer buffer) {
        buffer.putFloat(0, 0.0f).putFloat(4, 0.0f).putFloat(8, shadowTexelX).putFloat(12, shadowTexelY);
        buffer.putFloat(32, shadowFadeStart).putFloat(36, shadowFadeEnd).putFloat(40, seaSurfaceHeight).putFloat(44, 0.0f);
    }
}
