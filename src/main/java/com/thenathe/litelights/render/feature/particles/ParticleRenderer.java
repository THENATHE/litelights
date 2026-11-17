package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Turns the published particle state into this frame's quads: interpolates between the last two
 * ticks, drops what is out of range or outside the view, works out fade, size and sprite frame,
 * and fills a {@link ParticleBatch}. Runs on the render thread; allocates nothing.
 */
final class ParticleRenderer {
    /** Rain streak length as a multiple of the distance a drop falls in one tick. */
    private static final float STREAK_LENGTH = 0.34f;
    /** A quad lying flat, facing up: -90 degrees about X. */
    private static final float FLAT_X = (float) -Math.sqrt(0.5);
    private static final float FLAT_W = (float) Math.sqrt(0.5);
    private static final float HALF = (float) Math.sqrt(0.5);
    /**
     * How far fire's decals lie in front of their block face. Fire's flames along a block's side
     * stand a hundredth of a texel (1/1600 of a block) out from it, so the decals sit closer than
     * that, to be drawn under the flames rather than over them. The depth buffer (32-bit float,
     * reversed) still tells them from the face itself well past their 48-block reach, and Sodium's
     * vertex grid (1/32768 of a block) keeps the flames where vanilla puts them.
     */
    static final float DECAL_LIFT = 0.0003f;
    /**
     * Soft particles take LiteLights' air in front of them (particle/soft.vsh): where the storm fog
     * alone lets less than this through, the puff is the fog's colour against fog and can't be seen.
     */
    private static final float HIDDEN_BY_FOG = 0.015f;
    private static final float HIDDEN_BY_FOG_DEPTH = (float) -Math.log(HIDDEN_BY_FOG);
    /**
     * Turns a quad (facing +z, right +x, up +y) to lie on a block face, by face in Direction's order:
     * down, up, north, south, west, east. On top faces "up" points north, as the block texture does.
     */
    private static final float[][] FACE_ROTATIONS = {
        {HALF, 0.0f, 0.0f, HALF},
        {-HALF, 0.0f, 0.0f, HALF},
        {0.0f, 1.0f, 0.0f, 0.0f},
        {0.0f, 0.0f, 0.0f, 1.0f},
        {0.0f, -HALF, 0.0f, HALF},
        {0.0f, HALF, 0.0f, HALF}
    };

    /**
     * Generous reach for the quick view test, by kind: kinds that grow (mist, haze, dust clouds,
     * smoke) reach up to about ten blocks across, everything else stays under a block, and nothing
     * moves more than about two blocks a tick.
     */
    private static final float[] QUICK_REACH = new float[ParticleKind.all().size()];

    static {
        for (ParticleKind kind : ParticleKind.all()) {
            QUICK_REACH[kind.id] = kind.growth > 1.0f ? 18.0f : 3.0f;
        }
    }

    private final ParticleBatch batch = new ParticleBatch();
    /**
     * Fire's decals, each in a batch of its own: vanilla draws the layers of one batch in no set
     * order, and the veins must lie over the soot where the two share a face.
     */
    private final ParticleBatch burnMarkBatch = new ParticleBatch();
    private final ParticleBatch emberVeinBatch = new ParticleBatch();
    private final ParticleSprites sprites;
    private int drawnLastFrame;
    /** QA only: draw soft particles however much fog hides them, to time what skipping them saves. */
    boolean fogCullOff;

    // The view-projection's rows, copied once a frame for the frustum test.
    private float m00, m10, m20, m30;
    private float m01, m11, m21, m31;
    private float m03, m13, m23, m33;

    ParticleRenderer(ParticleSprites sprites) {
        this.sprites = sprites;
    }

    ParticleBatch batch() {
        return batch;
    }

    ParticleBatch burnMarkBatch() {
        return burnMarkBatch;
    }

    ParticleBatch emberVeinBatch() {
        return emberVeinBatch;
    }

    int drawnLastFrame() {
        return drawnLastFrame;
    }

    /**
     * Fills the batch for one frame. Positions handed to the batch are camera-relative, as vanilla's
     * particle quads are.
     */
    void build(ParticleState state, double cameraX, double cameraY, double cameraZ, Quaternionf cameraRotation,
               Matrix4f viewProjection, float partialTick, boolean hdStyle, float[] rangeScales, float stormFogDensity,
               RenderSettings.MistStyle mistStyle) {
        batch.clear();
        burnMarkBatch.clear();
        emberVeinBatch.clear();
        drawnLastFrame = 0;
        copyFrustum(viewProjection);
        if (state.count == 0) {
            return;
        }
        float rotationX = cameraRotation.x;
        float rotationY = cameraRotation.y;
        float rotationZ = cameraRotation.z;
        float rotationW = cameraRotation.w;
        // The camera's up, for trimmed soft billboards (rotating 0, 1, 0 by the camera's rotation).
        float cameraUpX = 2.0f * (rotationX * rotationY - rotationW * rotationZ);
        float cameraUpY = 1.0f - 2.0f * (rotationX * rotationX + rotationZ * rotationZ);
        float cameraUpZ = 2.0f * (rotationY * rotationZ + rotationW * rotationX);
        // Past this distance the storm fog hides soft particles completely (no fog: never).
        float fogHidden = stormFogDensity > 0.0f && !fogCullOff ? HIDDEN_BY_FOG_DEPTH / stormFogDensity : Float.MAX_VALUE;
        float fogHiddenSquared = fogHidden * fogHidden;
        boolean puffs = mistStyle == RenderSettings.MistStyle.PUFFS;
        for (int i = 0; i < state.count; i++) {
            ParticleKind kind = ParticleKind.byId(state.kind[i]);
            boolean haze = kind.haze != ParticleKind.Haze.NONE;
            if (haze && mistStyle == RenderSettings.MistStyle.FIELD) {
                // The Mist field draws the haze; puffs left from before the switch aren't drawn.
                continue;
            }
            // Most weather is behind the camera or out to the side: turn it away on this tick's
            // position alone, before reading anything else of it, with room for a tick's motion and
            // the kind's largest size.
            float nowX = (float) (state.x[i] - cameraX);
            float nowY = (float) (state.y[i] - cameraY);
            float nowZ = (float) (state.z[i] - cameraZ);
            if (!inView(nowX, nowY, nowZ, QUICK_REACH[kind.id])) {
                continue;
            }
            float x = (float) (state.previousX[i] + (state.x[i] - state.previousX[i]) * partialTick - cameraX);
            float y = (float) (state.previousY[i] + (state.y[i] - state.previousY[i]) * partialTick - cameraY);
            float z = (float) (state.previousZ[i] + (state.z[i] - state.previousZ[i]) * partialTick - cameraZ);
            float distanceSquared = x * x + y * y + z * z;
            float range = ParticleReach.range(kind, rangeScales);
            if (distanceSquared > range * range || kind.layer == ParticleKind.Layer.SOFT && distanceSquared > fogHiddenSquared) {
                continue;
            }
            float lifeTicks = state.age[i] + partialTick;
            float lifeShare = Math.min(1.0f, lifeTicks / state.lifetime[i]);
            float size = state.size[i] * (1.0f + (kind.growth - 1.0f) * lifeShare);
            if (lifeTicks < kind.hangTicks) {
                // A drip swells as it forms.
                size *= 0.55f + 0.45f * lifeTicks / kind.hangTicks;
            }
            float reach = kind.shape == ParticleKind.Shape.STREAK ? size + 1.0f : size * 1.5f;
            if (!inView(x, y, z, reach)) {
                continue;
            }

            float alpha = (state.color[i] >>> 24) / 255.0f;
            if (lifeTicks < kind.fadeInTicks) {
                float fadeIn = lifeTicks / kind.fadeInTicks;
                // Haze eases in and out of nothing where it is, with no moment it visibly starts.
                alpha *= haze ? fadeIn * fadeIn * (3.0f - 2.0f * fadeIn) : fadeIn;
            }
            float remaining = 1.0f - lifeShare;
            if (remaining < kind.fadeOutShare) {
                float fadeOut = remaining / kind.fadeOutShare;
                alpha *= haze ? fadeOut * fadeOut * (3.0f - 2.0f * fadeOut) : fadeOut;
            }
            // A 3D puff thins out around the eye in its own shader, so flying through one is a soft veil.
            if (kind.nearFade > 0.0f && !(haze && puffs)) {
                // Puffs close to the camera fill the view as flat squares; they thin out instead.
                float near = ((float) Math.sqrt(distanceSquared) - kind.nearFade * 0.5f) / (kind.nearFade * 0.5f);
                if (near <= 0.0f) {
                    continue;
                }
                if (near < 1.0f) {
                    alpha *= near * near * (3.0f - 2.0f * near);
                }
            }
            float flash = 1.0f;
            if (kind.twinkle) {
                flash = twinkle(state.seed[i], lifeTicks, kind.frames == ParticleKind.Frames.BLINK);
                alpha *= kind.frames == ParticleKind.Frames.BLINK ? 0.35f + 0.65f * flash : 0.6f + 0.4f * flash;
            }
            if (alpha < 0.02f) {
                continue;
            }

            float[][] frames = sprites.frames(kind, hdStyle);
            float[] frame = switch (kind.frames) {
                case BY_SEED -> frames[Math.floorMod(state.seed[i], frames.length)];
                case BY_AGE -> frames[Math.min(frames.length - 1, (int) (lifeShare * frames.length))];
                case BLINK -> frames[flash > 0.6f && frames.length > 1 ? 1 : 0];
            };
            int rgb = state.color[i] & 0x00FFFFFF;
            if (kind.fadeToColor >= 0) {
                rgb = LiteMath.mixRgb(rgb, kind.fadeToColor, lifeShare);
            }
            int color = ((int) (alpha * 255.0f) << 24) | rgb;
            int light = kind.emissive ? LightCoordsUtil.FULL_BRIGHT : state.light[i];
            SingleQuadParticle.Layer layer = ParticleLayers.of(kind.layer);

            if (haze) {
                if (puffs) {
                    batch.add(ParticleLayers.PUFF, x, y, z, 0.0f, 0.0f, 0.0f, 0.0f, ParticleBatch.PUFF,
                        ParticleBatch.puffSize(size), 0.0f, ParticleBatch.puffCode(kind.haze, state.seed[i]), 0.0f, color, light);
                } else {
                    addHazeBillboard(x, y, z, size, frame, color, light, cameraUpX, cameraUpY, cameraUpZ);
                }
                HazeDepth.get().want();
                drawnLastFrame++;
                continue;
            }
            switch (kind.shape) {
                case BILLBOARD -> {
                    if (frame[4] < 1.0f || frame[5] < 1.0f) {
                        // Trimmed to the sprite's content: a rectangle facing the camera, drawn through the
                        // streak path with the camera's up as its axis.
                        float halfHeight = size * frame[5];
                        batch.add(layer, x, y, z, cameraUpX * halfHeight, cameraUpY * halfHeight, cameraUpZ * halfHeight,
                            size * frame[4], ParticleBatch.STREAK, frame[0], frame[1], frame[2], frame[3], color, light);
                    } else {
                        batch.add(layer, x, y, z, rotationX, rotationY, rotationZ, rotationW, size,
                            frame[0], frame[1], frame[2], frame[3], color, light);
                    }
                }
                case FLAT -> batch.add(layer, x, y, z, FLAT_X, 0.0f, 0.0f, FLAT_W, size,
                    frame[0], frame[1], frame[2], frame[3], color, light);
                case STREAK -> {
                    // "Up" points back along the motion, so the sprite's bright head leads the drop.
                    float stretch = STREAK_LENGTH;
                    if (kind.streakLength > 0.0f) {
                        float speed = (float) Math.sqrt(state.velocityX[i] * state.velocityX[i] + state.velocityY[i] * state.velocityY[i]
                            + state.velocityZ[i] * state.velocityZ[i]);
                        stretch = speed > 1.0e-5f ? kind.streakLength / speed : 0.0f;
                    }
                    float axisX = -state.velocityX[i] * stretch;
                    float axisY = -state.velocityY[i] * stretch;
                    float axisZ = -state.velocityZ[i] * stretch;
                    batch.add(layer, x, y, z, axisX, axisY, axisZ, size, ParticleBatch.STREAK,
                        frame[0], frame[1], frame[2], frame[3], color, light);
                }
            }
            drawnLastFrame++;
        }
    }

    /**
     * A classic haze billboard (Mist style: Classic billboards), trimmed to its sprite like other soft
     * billboards, but kept upright: its up is the world's up turned to face the eye, not the camera's
     * up, so it never rolls or swims as the view turns. Looking straight up or down at it, where the
     * world's up says nothing, the camera's up stands in.
     */
    private void addHazeBillboard(float x, float y, float z, float size, float[] frame, int color, int light,
                                  float cameraUpX, float cameraUpY, float cameraUpZ) {
        float distance = (float) Math.sqrt(x * x + y * y + z * z);
        float upX = cameraUpX;
        float upY = cameraUpY;
        float upZ = cameraUpZ;
        if (distance > 1.0e-4f) {
            float along = y / distance;
            float uprightX = -x / distance * along;
            float uprightY = 1.0f - along * along;
            float uprightZ = -z / distance * along;
            float length = (float) Math.sqrt(uprightX * uprightX + uprightY * uprightY + uprightZ * uprightZ);
            if (length > 0.05f) {
                upX = uprightX / length;
                upY = uprightY / length;
                upZ = uprightZ / length;
            }
        }
        float halfHeight = size * frame[5];
        batch.add(ParticleLayers.HAZE_BILLBOARD, x, y, z, upX * halfHeight, upY * halfHeight, upZ * halfHeight,
            size * frame[4], ParticleBatch.STREAK, frame[0], frame[1], frame[2], frame[3], color, light);
    }

    /**
     * Adds a square covering one block face (half a block each way from its centre), for fire's
     * decals, to that decal's own batch. Call after {@link #build} in the same frame. The centre is
     * camera-relative; a frame is u0, u1, v0, v1, and the flips mirror it so one sprite gives four
     * looks.
     */
    void addFace(SingleQuadParticle.Layer layer, float x, float y, float z, int face, float[] frame, boolean flipU, boolean flipV,
                 int color, int light) {
        if (!inView(x, y, z, 0.75f)) {
            return;
        }
        ParticleBatch target = layer == ParticleLayers.EMBER_VEIN ? emberVeinBatch : burnMarkBatch;
        float[] rotation = FACE_ROTATIONS[face];
        float u0 = flipU ? frame[1] : frame[0];
        float u1 = flipU ? frame[0] : frame[1];
        float v0 = flipV ? frame[3] : frame[2];
        float v1 = flipV ? frame[2] : frame[3];
        target.add(layer, x, y, z, rotation[0], rotation[1], rotation[2], rotation[3], 0.5f, u0, u1, v0, v1, color, light);
    }

    /**
     * A slow pulse per particle. Blinking kinds (fireflies) stay dark most of the time and flash;
     * the rest only shimmer.
     */
    private static float twinkle(int seed, float lifeTicks, boolean blink) {
        float rate = 0.06f + (seed & 15) * 0.006f;
        float wave = (float) Math.sin(lifeTicks * rate + (seed >>> 4 & 255) * 0.1f);
        if (!blink) {
            return 0.5f + 0.5f * wave;
        }
        float positive = Math.max(0.0f, wave);
        return positive * positive * positive;
    }

    private void copyFrustum(Matrix4f matrix) {
        m00 = matrix.m00();
        m10 = matrix.m10();
        m20 = matrix.m20();
        m30 = matrix.m30();
        m01 = matrix.m01();
        m11 = matrix.m11();
        m21 = matrix.m21();
        m31 = matrix.m31();
        m03 = matrix.m03();
        m13 = matrix.m13();
        m23 = matrix.m23();
        m33 = matrix.m33();
    }

    /**
     * Whether a sphere around a camera-relative point can be on screen. Tests the clip-space x and
     * y against w with a margin for the particle's size; near and far planes are left to the depth
     * test and the range check.
     */
    private boolean inView(float x, float y, float z, float reach) {
        float clipW = m03 * x + m13 * y + m23 * z + m33;
        if (clipW < -reach) {
            return false;
        }
        float clipX = m00 * x + m10 * y + m20 * z + m30;
        float clipY = m01 * x + m11 * y + m21 * z + m31;
        float margin = clipW + reach * 2.5f;
        return clipX <= margin && clipX >= -margin && clipY <= margin && clipY >= -margin;
    }
}
