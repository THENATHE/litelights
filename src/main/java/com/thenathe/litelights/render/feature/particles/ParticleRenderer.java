package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.feature.skyfog.SkyFogMath;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Turns the published particle state into this frame's quads: interpolates between the last two
 * ticks, drops what is out of range or outside the view, works out fade, size and sprite frame,
 * and fills {@link ParticleBatch}es. Allocates nothing.
 *
 * <p>The work is cut into slices, each filling a batch of its own, so the render thread and
 * {@link QuadHelpers} can build them at once: {@link #prepare} takes the frame's inputs on the
 * render thread, then any thread may build any slice ({@link #buildSlice}), each slice exactly once,
 * and the render thread submits the batches in slice order. The slices are consecutive runs of the
 * particles, so the quads come out in the same order as from one walk. Where the worker's
 * {@link CullList} covers the frame's camera, only its particles are walked.
 */
final class ParticleRenderer {
    /** Most slices a frame is cut into, and the particles walked per slice before another is worth it. */
    static final int MAX_SLICES = 8;
    private static final int PARTICLES_PER_SLICE = 2048;
    private static final int NOT_DRAWN = 0;
    private static final int DRAWN = 1;
    private static final int DRAWN_HAZE = 2;
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
    /** The view test's margin in clip space, per block of a particle's reach (see {@link #inView}). */
    private static final float CLIP_MARGIN = 2.5f;
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

    private final ParticleBatch[] slices = new ParticleBatch[MAX_SLICES];
    private final int[] sliceDrawn = new int[MAX_SLICES];
    private final boolean[] sliceHaze = new boolean[MAX_SLICES];
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
    /** QA only: never use the cull list, to compare against a walk over every particle. */
    boolean cullListOff;

    // This frame's inputs, written by prepare() on the render thread before any slice is built and
    // only read while slices are built.
    private ParticleState state;
    /** The worker's list of particles to walk, or null to walk every particle. */
    private CullList list;
    private int walkCount;
    private int sliceCount;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float rotationX;
    private float rotationY;
    private float rotationZ;
    private float rotationW;
    private float cameraUpX;
    private float cameraUpY;
    private float cameraUpZ;
    private float partialTick;
    private boolean hdStyle;
    private float[] rangeScales;
    private float fogHiddenSquared;
    private RenderSettings.MistStyle mistStyle;
    private boolean puffs;

    // The view-projection's rows, copied once a frame for the frustum test.
    private float m00, m10, m20, m30;
    private float m01, m11, m21, m31;
    private float m03, m13, m23, m33;

    // The frame's view as the cull list sees it (CullList.Basis), worked out from the same rows.
    private float viewForwardX;
    private float viewForwardY;
    private float viewForwardZ;
    private float viewAngle;
    private float viewMargin;
    private float fogHidden;

    /** QA only: the particles each slice drew, by index, for comparing the list against a full walk. */
    private int[][] recorded;
    private final int[] recordedCount = new int[MAX_SLICES];

    ParticleRenderer(ParticleSprites sprites) {
        this.sprites = sprites;
        for (int slice = 0; slice < MAX_SLICES; slice++) {
            slices[slice] = new ParticleBatch();
        }
    }

    static float quickReach(ParticleKind kind) {
        return QUICK_REACH[kind.id];
    }

    int sliceCount() {
        return sliceCount;
    }

    ParticleBatch slice(int slice) {
        return slices[slice];
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

    /** Whether this frame walks the worker's cull list rather than every particle. */
    boolean walksList() {
        return list != null;
    }

    /** Particles this frame walks: the cull list's, or every live one. */
    int walkCount() {
        return walkCount;
    }

    /** Render-thread time vanilla's particle renderer spent building these batches' vertices since last asked. */
    long takeBuildNanos() {
        long nanos = burnMarkBatch.takeBuildNanos() + emberVeinBatch.takeBuildNanos();
        for (ParticleBatch slice : slices) {
            nanos += slice.takeBuildNanos();
        }
        return nanos;
    }

    /**
     * Takes one frame's inputs and clears the batches, on the render thread, before any slice is
     * built. Positions handed to the batches are camera-relative, as vanilla's particle quads are.
     */
    void prepare(ParticleState frameState, double frameCameraX, double frameCameraY, double frameCameraZ, Quaternionf cameraRotation,
                 Matrix4f viewProjection, float framePartialTick, boolean frameHdStyle, float[] frameRangeScales, float stormFogDensity,
                 RenderSettings.MistStyle frameMistStyle) {
        for (ParticleBatch slice : slices) {
            slice.clear();
        }
        burnMarkBatch.clear();
        emberVeinBatch.clear();
        drawnLastFrame = 0;
        copyFrustum(viewProjection);
        copyView();
        state = frameState;
        cameraX = frameCameraX;
        cameraY = frameCameraY;
        cameraZ = frameCameraZ;
        rotationX = cameraRotation.x;
        rotationY = cameraRotation.y;
        rotationZ = cameraRotation.z;
        rotationW = cameraRotation.w;
        // The camera's up, for trimmed soft billboards (rotating 0, 1, 0 by the camera's rotation).
        cameraUpX = 2.0f * (rotationX * rotationY - rotationW * rotationZ);
        cameraUpY = 1.0f - 2.0f * (rotationX * rotationX + rotationZ * rotationZ);
        cameraUpZ = 2.0f * (rotationY * rotationZ + rotationW * rotationX);
        partialTick = framePartialTick;
        hdStyle = frameHdStyle;
        rangeScales = frameRangeScales;
        mistStyle = frameMistStyle;
        puffs = frameMistStyle == RenderSettings.MistStyle.PUFFS;
        // Past this distance the storm fog hides soft particles completely (no fog: never).
        fogHidden = stormFogDensity > 0.0f && !fogCullOff ? SkyFogMath.stormDistanceBeyond(HIDDEN_BY_FOG_DEPTH, stormFogDensity) : Float.POSITIVE_INFINITY;
        fogHiddenSquared = fogHidden * fogHidden;
        boolean listUsable = !cullListOff && frameState.drawable.covers(cameraX, cameraY, cameraZ, viewForwardX, viewForwardY, viewForwardZ,
            viewAngle, viewMargin, fogHidden, frameRangeScales);
        list = listUsable ? frameState.drawable : null;
        walkCount = listUsable ? frameState.drawable.count : frameState.count;
        sliceCount = walkCount == 0 ? 0 : Math.clamp((walkCount + PARTICLES_PER_SLICE - 1) / PARTICLES_PER_SLICE, 1, MAX_SLICES);
        if (recorded != null) {
            java.util.Arrays.fill(recordedCount, 0);
            for (int slice = 0; slice < MAX_SLICES; slice++) {
                if (recorded[slice].length < frameState.count) {
                    recorded[slice] = new int[frameState.capacity];
                }
            }
        }
    }

    /**
     * Builds one slice into its own batch. Any thread, once {@link #prepare} has run; every slice
     * exactly once a frame, and no two threads on the same slice.
     */
    void buildSlice(int slice) {
        ParticleBatch target = slices[slice];
        int from = (int) ((long) walkCount * slice / sliceCount);
        int to = (int) ((long) walkCount * (slice + 1) / sliceCount);
        int[] indices = list != null ? list.indices : null;
        int[] record = recorded != null ? recorded[slice] : null;
        int drawn = 0;
        boolean haze = false;
        for (int j = from; j < to; j++) {
            int i = indices != null ? indices[j] : j;
            int result = draw(i, target);
            if (result != NOT_DRAWN) {
                if (record != null) {
                    record[drawn] = i;
                }
                drawn++;
                haze |= result == DRAWN_HAZE;
            }
        }
        sliceDrawn[slice] = drawn;
        sliceHaze[slice] = haze;
        if (record != null) {
            recordedCount[slice] = drawn;
        }
    }

    /** After every slice is built, on the render thread: the frame's count, and the haze copy if any haze was drawn. */
    void finishSlices() {
        drawnLastFrame = 0;
        boolean haze = false;
        for (int slice = 0; slice < sliceCount; slice++) {
            drawnLastFrame += sliceDrawn[slice];
            haze |= sliceHaze[slice];
        }
        if (haze) {
            HazeDepth.get().want();
        }
    }

    /**
     * The last prepared frame's camera, view direction, half-diagonal angle and view margin, and the
     * storm fog's limit, for the next cull list. False before any frame.
     */
    boolean writeView(CullList.Basis basis) {
        if (state == null) {
            return false;
        }
        basis.cameraX = cameraX;
        basis.cameraY = cameraY;
        basis.cameraZ = cameraZ;
        basis.forwardX = viewForwardX;
        basis.forwardY = viewForwardY;
        basis.forwardZ = viewForwardZ;
        basis.coneAngle = viewAngle;
        basis.viewMargin = viewMargin;
        basis.softLimit = fogHidden;
        return true;
    }

    /**
     * QA only: from now on remember which particles each slice draws, or stop. Allocates the
     * records, so not for timing runs.
     */
    void recordDrawn(boolean record) {
        recorded = record ? new int[MAX_SLICES][state == null ? 0 : state.capacity] : null;
    }

    /**
     * QA only, on the render thread after the slices are built with recording on: walks every
     * particle as a frame without the cull list would, and compares. Returns how many particles the
     * full walk draws that the frame missed, and how many the frame drew that the walk doesn't (both
     * 0 when the list was right, and always 0 when the frame walked everything); -1 if nothing was
     * recorded.
     */
    int[] compareWithFullWalk(ParticleBatch scratch) {
        if (recorded == null || state == null) {
            return new int[] {-1, -1};
        }
        int[] full = new int[state.count];
        int fullCount = walkEveryParticle(scratch, full);
        scratch.clear();
        int[] framed = new int[state.count];
        int framedCount = 0;
        for (int slice = 0; slice < sliceCount; slice++) {
            System.arraycopy(recorded[slice], 0, framed, framedCount, recordedCount[slice]);
            framedCount += recordedCount[slice];
        }
        int missing = 0;
        int extra = 0;
        int a = 0;
        int b = 0;
        while (a < fullCount || b < framedCount) {
            if (b >= framedCount || a < fullCount && full[a] < framed[b]) {
                missing++;
                a++;
            } else if (a >= fullCount || framed[b] < full[a]) {
                extra++;
                b++;
            } else {
                a++;
                b++;
            }
        }
        return new int[] {missing, extra};
    }

    /**
     * Walks every live particle in one go into {@code target}, as a frame without slices or a cull
     * list would, writing the indices drawn to {@code drawn}; returns how many. For the QA audit and
     * tests.
     */
    int walkEveryParticle(ParticleBatch target, int[] drawn) {
        int count = 0;
        for (int i = 0; i < state.count; i++) {
            if (draw(i, target) != NOT_DRAWN) {
                drawn[count++] = i;
            }
        }
        return count;
    }

    /** Adds particle {@code i} to {@code target} if this frame draws it. */
    private int draw(int i, ParticleBatch target) {
        ParticleKind kind = ParticleKind.byId(state.kind[i]);
        boolean haze = kind.haze != ParticleKind.Haze.NONE;
        if (haze && mistStyle == RenderSettings.MistStyle.FIELD) {
            // The Mist field draws the haze; puffs left from before the switch aren't drawn.
            return NOT_DRAWN;
        }
        // Most weather is behind the camera or out to the side: turn it away on this tick's
        // position alone, before reading anything else of it, with room for a tick's motion and
        // the kind's largest size.
        float nowX = (float) (state.x[i] - cameraX);
        float nowY = (float) (state.y[i] - cameraY);
        float nowZ = (float) (state.z[i] - cameraZ);
        if (!inView(nowX, nowY, nowZ, QUICK_REACH[kind.id])) {
            return NOT_DRAWN;
        }
        float x = (float) (state.previousX[i] + (state.x[i] - state.previousX[i]) * partialTick - cameraX);
        float y = (float) (state.previousY[i] + (state.y[i] - state.previousY[i]) * partialTick - cameraY);
        float z = (float) (state.previousZ[i] + (state.z[i] - state.previousZ[i]) * partialTick - cameraZ);
        float distanceSquared = x * x + y * y + z * z;
        float range = ParticleReach.range(kind, rangeScales);
        // Haze lies on the land: its range is over the ground, so flying high still shows the
        // mist below (fog097's spawners, fog098); the simulation removes it on the same terms.
        float rangeSquared = haze ? x * x + z * z : distanceSquared;
        if (rangeSquared > range * range || kind.layer == ParticleKind.Layer.SOFT && distanceSquared > fogHiddenSquared) {
            return NOT_DRAWN;
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
            return NOT_DRAWN;
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
                return NOT_DRAWN;
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
            return NOT_DRAWN;
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
                target.add(ParticleLayers.PUFF, x, y, z, 0.0f, 0.0f, 0.0f, 0.0f, ParticleBatch.PUFF,
                    ParticleBatch.puffSize(size), 0.0f, ParticleBatch.puffCode(kind.haze, state.seed[i]), 0.0f, color, light);
            } else {
                addHazeBillboard(target, x, y, z, size, frame, color, light);
            }
            return DRAWN_HAZE;
        }
        switch (kind.shape) {
            case BILLBOARD -> {
                if (frame[4] < 1.0f || frame[5] < 1.0f) {
                    // Trimmed to the sprite's content: a rectangle facing the camera, drawn through the
                    // streak path with the camera's up as its axis.
                    float halfHeight = size * frame[5];
                    target.add(layer, x, y, z, cameraUpX * halfHeight, cameraUpY * halfHeight, cameraUpZ * halfHeight,
                        size * frame[4], ParticleBatch.STREAK, frame[0], frame[1], frame[2], frame[3], color, light);
                } else {
                    target.add(layer, x, y, z, rotationX, rotationY, rotationZ, rotationW, size,
                        frame[0], frame[1], frame[2], frame[3], color, light);
                }
            }
            case FLAT -> target.add(layer, x, y, z, FLAT_X, 0.0f, 0.0f, FLAT_W, size,
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
                target.add(layer, x, y, z, axisX, axisY, axisZ, size, ParticleBatch.STREAK,
                    frame[0], frame[1], frame[2], frame[3], color, light);
            }
        }
        return DRAWN;
    }

    /**
     * A classic haze billboard (Mist style: Classic billboards), trimmed to its sprite like other soft
     * billboards, but kept upright: its up is the world's up turned to face the eye, not the camera's
     * up, so it never rolls or swims as the view turns. Looking straight up or down at it, where the
     * world's up says nothing, the camera's up stands in.
     */
    private void addHazeBillboard(ParticleBatch target, float x, float y, float z, float size, float[] frame, int color, int light) {
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
        target.add(ParticleLayers.HAZE_BILLBOARD, x, y, z, upX * halfHeight, upY * halfHeight, upZ * halfHeight,
            size * frame[4], ParticleBatch.STREAK, frame[0], frame[1], frame[2], frame[3], color, light);
    }

    /**
     * Adds a square covering one block face (half a block each way from its centre), for fire's
     * decals, to that decal's own batch. The centre is
     * camera-relative; a frame is u0, u1, v0, v1, and the flips mirror it so one sprite gives four
     * looks. Render thread, after {@link #prepare}.
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
     * The view as {@link CullList} needs it, from the frustum rows: the direction clip w grows in
     * (the view's forward), the half-angle of the cone that holds the whole view pyramid, and how many
     * blocks out from that cone {@link #inView}'s clip-space margin reaches per block of reach. Clip
     * x and y each have a part across the forward (the view's width) and a small part along it (an
     * off-centre projection, such as TAA's jitter); both widen the cone a little. A projection that
     * isn't a plain perspective one around the camera gets an unbounded angle, so lists then never
     * leave out a direction.
     */
    private void copyView() {
        float w = (float) Math.sqrt(m03 * m03 + m13 * m13 + m23 * m23);
        float offsets = Math.abs(m30) + Math.abs(m31) + Math.abs(m33);
        if (!(w > 1.0e-6f) || offsets > 1.0e-4f * w) {
            viewForwardX = 0.0f;
            viewForwardY = 0.0f;
            viewForwardZ = -1.0f;
            viewAngle = Float.POSITIVE_INFINITY;
            viewMargin = Float.POSITIVE_INFINITY;
            return;
        }
        float forwardX = m03 / w;
        float forwardY = m13 / w;
        float forwardZ = m23 / w;
        float alongX = m00 * forwardX + m10 * forwardY + m20 * forwardZ;
        float alongY = m01 * forwardX + m11 * forwardY + m21 * forwardZ;
        float acrossXx = m00 - alongX * forwardX;
        float acrossXy = m10 - alongX * forwardY;
        float acrossXz = m20 - alongX * forwardZ;
        float acrossYx = m01 - alongY * forwardX;
        float acrossYy = m11 - alongY * forwardY;
        float acrossYz = m21 - alongY * forwardZ;
        float acrossX = (float) Math.sqrt(acrossXx * acrossXx + acrossXy * acrossXy + acrossXz * acrossXz);
        float acrossY = (float) Math.sqrt(acrossYx * acrossYx + acrossYy * acrossYy + acrossYz * acrossYz);
        float shift = Math.max(Math.abs(alongX), Math.abs(alongY));
        if (!(acrossX > 1.0e-6f) || !(acrossY > 1.0e-6f) || shift >= w) {
            viewForwardX = forwardX;
            viewForwardY = forwardY;
            viewForwardZ = forwardZ;
            viewAngle = Float.POSITIVE_INFINITY;
            viewMargin = Float.POSITIVE_INFINITY;
            return;
        }
        // The steepest tangent from the forward the view reaches across x and across y, and the
        // corner's from both (exact when the two directions are square to each other, as they are
        // for a camera; the skew term keeps it safe if they aren't).
        float tanX = (w + Math.abs(alongX)) / acrossX;
        float tanY = (w + Math.abs(alongY)) / acrossY;
        float skew = Math.abs(acrossXx * acrossYx + acrossXy * acrossYy + acrossXz * acrossYz) / (acrossX * acrossY);
        float cornerSquared = (tanX * tanX + tanY * tanY + 2.0f * tanX * tanY * skew) / Math.max(1.0e-6f, 1.0f - skew * skew);
        viewForwardX = forwardX;
        viewForwardY = forwardY;
        viewForwardZ = forwardZ;
        viewAngle = (float) Math.atan(Math.sqrt(cornerSquared) * 1.001f);
        viewMargin = CLIP_MARGIN / (w - shift) * 1.001f;
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
        float margin = clipW + reach * CLIP_MARGIN;
        return clipX <= margin && clipX >= -margin && clipY <= margin && clipY >= -margin;
    }
}
