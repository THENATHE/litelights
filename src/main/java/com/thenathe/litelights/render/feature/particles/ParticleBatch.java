package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/**
 * LiteLights' particles for one frame, handed to vanilla's particle feature renderer through the
 * submit API, so they are sorted and blended with vanilla's translucent geometry (Improved
 * Transparency included) and drawn in one batch per layer.
 *
 * <p>Two changes from vanilla's quads, both in {@link #renderRotatedQuad}: corners are worked out
 * without allocating, and a negative scale marks a streak, a quad stretched along a direction and
 * turned to face the camera around it (rain). For a streak the rotation slots carry the half-length
 * axis (x, y, z) and the half width (w) instead of a quaternion.
 */
final class ParticleBatch extends QuadParticleRenderState {
    static final float STREAK = -1.0f;

    /** Render-thread time vanilla's particle renderer spends building this batch's vertices, summed. */
    private long buildNanos;

    @Override
    public void buildLayer(SingleQuadParticle.Layer layer, VertexConsumer bufferBuilder) {
        long started = System.nanoTime();
        super.buildLayer(layer, bufferBuilder);
        buildNanos += System.nanoTime() - started;
    }

    long takeBuildNanos() {
        long nanos = buildNanos;
        buildNanos = 0;
        return nanos;
    }

    @Override
    protected void renderRotatedQuad(VertexConsumer builder, float x, float y, float z, float xRot, float yRot, float zRot,
                                     float wRot, float scale, float u0, float u1, float v0, float v1, int color, int lightCoords) {
        float rightX;
        float rightY;
        float rightZ;
        float upX;
        float upY;
        float upZ;
        if (scale < 0.0f) {
            // Up along the streak; right across it, perpendicular to the line of sight (the camera is at
            // 0,0,0). Right is (position x up), so right x up points back at the camera and the quad
            // winds the same way as a billboard: particle pipelines cull back faces.
            upX = xRot;
            upY = yRot;
            upZ = zRot;
            float sideX = y * upZ - z * upY;
            float sideY = z * upX - x * upZ;
            float sideZ = x * upY - y * upX;
            float length = (float) Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
            if (length < 1.0e-6f) {
                return;
            }
            float width = wRot / length;
            rightX = sideX * width;
            rightY = sideY * width;
            rightZ = sideZ * width;
        } else {
            // Rotate (scale, 0, 0) and (0, scale, 0) by the quaternion: v + 2w(q x v) + 2 q x (q x v).
            rightX = scale * (1.0f - 2.0f * (yRot * yRot + zRot * zRot));
            rightY = scale * (2.0f * (xRot * yRot + wRot * zRot));
            rightZ = scale * (2.0f * (xRot * zRot - wRot * yRot));
            upX = scale * (2.0f * (xRot * yRot - wRot * zRot));
            upY = scale * (1.0f - 2.0f * (xRot * xRot + zRot * zRot));
            upZ = scale * (2.0f * (yRot * zRot + wRot * xRot));
        }
        // Same corner order and texture mapping as vanilla's particle quads.
        builder.addVertex(x + rightX - upX, y + rightY - upY, z + rightZ - upZ).setUv(u1, v1).setColor(color).setLight(lightCoords);
        builder.addVertex(x + rightX + upX, y + rightY + upY, z + rightZ + upZ).setUv(u1, v0).setColor(color).setLight(lightCoords);
        builder.addVertex(x - rightX + upX, y - rightY + upY, z - rightZ + upZ).setUv(u0, v0).setColor(color).setLight(lightCoords);
        builder.addVertex(x - rightX - upX, y - rightY - upY, z - rightZ - upZ).setUv(u0, v1).setColor(color).setLight(lightCoords);
    }
}
