package com.thenathe.litelights.render.feature.farterrain;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The push constants of the far-terrain passes, in the order of {@code FarTerrainValues} in
 * shaders/include/farterrain/source.glsl: a matrix and five vectors, 128 bytes.
 */
final class FarValues {
    static final int BYTES = 128;

    /** Distant Horizons: its clip space to camera-relative positions. Identity for the main target. */
    final Matrix4f inverseProjection = new Matrix4f();
    boolean reversedDepth = true;
    boolean zeroToOneDepth = true;
    float shadowTexelX;
    float shadowTexelY;
    /** The pixels include another mod's far terrain (Voxy's marked pixels, or Distant Horizons' own texture). */
    boolean farTerrainPresent;
    boolean relight;
    boolean farShadows;
    boolean farWater;
    float shadowFadeStart;
    float shadowFadeEnd;
    float seaSurfaceHeight;
    boolean fog;
    /** Far water was found from the far mod's translucent pass this frame (FarWaterCapture). */
    boolean waterCaptured;
    boolean farCaustics;
    boolean farDepthColour;
    boolean farEdgeFoam;
    /** In the shadow passes, the heightfield's values replace the switches (see far_shadow.fsh). */
    float heightfieldMinY;
    float heightfieldReach;
    float marchDistance;
    /**
     * In the shadow passes, the player the camera follows: the middle of their body, camera-relative,
     * and how far around it the screen-space trace ignores what it sees (0: nobody to leave out).
     */
    final Vector3f playerCentre = new Vector3f();
    float playerReach;

    void writeForTerrain(ByteBuffer buffer) {
        writeCommon(buffer);
        buffer.putFloat(80, flag(farTerrainPresent)).putFloat(84, flag(relight)).putFloat(88, flag(farShadows)).putFloat(92, flag(farWater));
        writeRange(buffer);
        buffer.putFloat(112, flag(waterCaptured)).putFloat(116, flag(farCaustics)).putFloat(120, flag(farDepthColour)).putFloat(124, flag(farEdgeFoam));
    }

    /** Whether any Experimental far water extra is on: they live in their own shader variant. */
    boolean anyWaterDetails() {
        return farCaustics || farDepthColour || farEdgeFoam;
    }

    void writeForShadows(ByteBuffer buffer) {
        writeCommon(buffer);
        buffer.putFloat(80, heightfieldMinY).putFloat(84, heightfieldReach).putFloat(88, marchDistance).putFloat(92, 0.0f);
        writeRange(buffer);
        buffer.putFloat(112, playerCentre.x).putFloat(116, playerCentre.y).putFloat(120, playerCentre.z).putFloat(124, playerReach);
    }

    private void writeCommon(ByteBuffer buffer) {
        inverseProjection.get(0, buffer);
        buffer.putFloat(64, flag(reversedDepth)).putFloat(68, flag(zeroToOneDepth)).putFloat(72, shadowTexelX).putFloat(76, shadowTexelY);
    }

    private void writeRange(ByteBuffer buffer) {
        buffer.putFloat(96, shadowFadeStart).putFloat(100, shadowFadeEnd).putFloat(104, seaSurfaceHeight).putFloat(108, flag(fog));
    }

    private static float flag(boolean on) {
        return on ? 1.0f : 0.0f;
    }
}
