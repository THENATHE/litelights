package com.thenathe.litelights.render.feature.lightning;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.RenderSettingsAccess;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LightningBoltRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Draws vanilla's lightning bolt as a branching strike (storm099): the {@link BoltShape} grown from
 * the bolt's seed, as camera-facing ribbons through vanilla's own lightning render type, so it
 * blends, sorts and fades in the fog exactly as vanilla's bolt does and works with Improved
 * Transparency. The bolt stands where vanilla's stands and lives as long; only the drawing changes.
 *
 * <p>The main channel is a bright core with a soft glow on either side; the forks are a soft core
 * that thins and fades toward its tip. About 80 quads for a bolt. A bolt flares while vanilla
 * counts it as lit and dims in the dark moments between strikes; with Hide Lightning Flashes on it
 * stays steady, as vanilla's does.
 */
public final class BranchingBolts {
    /** Branching bolts are drawn this far (times the entity distance setting); vanilla's stop at 64 blocks. */
    public static final double REACH = 256.0;
    /** Vanilla's bolt is this tall; used when the cloud height isn't known. */
    private static final float VANILLA_HEIGHT = 128.0f;
    private static final float LOWEST = 40.0f;
    private static final float HIGHEST = 320.0f;
    /** Widths (half the ribbon, in blocks) near by; far away they grow to stay a pixel or two wide. */
    private static final float CORE_HALF_WIDTH = 0.16f;
    private static final float GLOW_HALF_WIDTH = 1.4f;
    private static final float CORE_PER_BLOCK = 0.0011f;
    private static final float GLOW_PER_BLOCK = 0.007f;
    /** A dark bolt keeps this much light, between its strikes and after the last. */
    private static final float DARK_GLOW = 0.12f;

    private static final int CACHED_SHAPES = 4;
    private static final long[] cachedSeed = new long[CACHED_SHAPES];
    private static final int[] cachedHeight = new int[CACHED_SHAPES];
    private static final BoltShape[] cachedShape = new BoltShape[CACHED_SHAPES];
    private static int nextCacheSlot;
    private static boolean readyMarked;

    private BranchingBolts() {
    }

    /**
     * Submits the bolt in place of vanilla's. Returns false to let vanilla draw it: the setting is
     * off, the feature is waiting after a failure, or this bolt failed.
     */
    public static boolean submit(LightningBoltRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!RenderSettingsAccess.current().weather().storms().lightning().branching() || !RenderFeatureStatus.mayRun(RenderFeature.LIGHTNING)) {
            return false;
        }
        try {
            float height = height(state.y);
            BoltShape shape = shapeFor(state.seed, height);
            float eyeX = (float) (camera.pos.x - state.x);
            float eyeY = (float) (camera.pos.y - state.y);
            float eyeZ = (float) (camera.pos.z - state.z);
            float middleY = eyeY - height * 0.5f;
            float distance = (float) Math.sqrt(eyeX * eyeX + middleY * middleY + eyeZ * eyeZ);
            float light = light(state);
            collector.submitCustomGeometry(poseStack, RenderTypes.lightning(),
                (pose, consumer) -> new Ribbons(pose.pose(), consumer, eyeX, eyeY, eyeZ).bolt(shape, light, distance));
            if (!readyMarked) {
                readyMarked = true;
                RenderFeatureStatus.markReady("lightning");
            }
            return true;
        } catch (RuntimeException error) {
            RenderFeatureStatus.failed(RenderFeature.LIGHTNING, error);
            LiteLightsClient.LOG.debug("Branching bolt failed", error);
            return false;
        }
    }

    /** Whether bolts are drawn out to {@link #REACH}: while LiteLights draws them. */
    public static boolean drawsFarther() {
        return RenderSettingsAccess.current().weather().storms().lightning().branching() && RenderFeatureStatus.mayRun(RenderFeature.LIGHTNING);
    }

    /** The space a bolt standing at a point fills, from the ground to the cloud base, for culling. */
    public static AABB cullingBox(double x, double y, double z) {
        float height = height(y);
        return new AABB(x - 8.0, y, z - 8.0, x + 8.0, y + height, z + 8.0);
    }

    private static float height(double strikeY) {
        float base = CloudLightning.get().cloudBaseY();
        float top = Float.isNaN(base) ? (float) strikeY + VANILLA_HEIGHT : base;
        return Math.clamp(top - (float) strikeY, LOWEST, HIGHEST);
    }

    /** How bright the bolt is now: full while lit, quickly dimming in the dark moments between strikes. */
    static float light(LightningBoltRenderState state) {
        if (!(state instanceof BoltLife life) || Minecraft.getInstance().options.hideLightningFlash().get()) {
            return 1.0f;
        }
        return light(life.litelights$life(), life.litelights$partialTick());
    }

    static float light(int life, float partialTick) {
        if (life >= 0) {
            // The first tick of a strike is the brightest.
            return life >= 1 ? 1.0f : 0.85f;
        }
        float dark = -life - 1 + partialTick;
        return DARK_GLOW + (1.0f - DARK_GLOW) * (float) Math.exp(-1.6 * dark);
    }

    private static BoltShape shapeFor(long seed, float height) {
        int roundedHeight = Math.round(height);
        for (int slot = 0; slot < CACHED_SHAPES; slot++) {
            if (cachedShape[slot] != null && cachedSeed[slot] == seed && cachedHeight[slot] == roundedHeight) {
                return cachedShape[slot];
            }
        }
        BoltShape shape = BoltShape.grow(seed, roundedHeight);
        cachedSeed[nextCacheSlot] = seed;
        cachedHeight[nextCacheSlot] = roundedHeight;
        cachedShape[nextCacheSlot] = shape;
        nextCacheSlot = (nextCacheSlot + 1) % CACHED_SHAPES;
        return shape;
    }

    /** Writes a bolt's channels as camera-facing quads. Positions are relative to the strike point. */
    private static final class Ribbons {
        private final Matrix4f pose;
        private final VertexConsumer out;
        private final float eyeX;
        private final float eyeY;
        private final float eyeZ;
        // The current segment's two ends, their sideways offsets, and the corners' colours.
        private final float[] corner = new float[12];
        private final float[] alpha = new float[4];
        private final float[] side = new float[6];

        Ribbons(Matrix4f pose, VertexConsumer out, float eyeX, float eyeY, float eyeZ) {
            this.pose = pose;
            this.out = out;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
        }

        void bolt(BoltShape shape, float light, float distance) {
            float core = Math.max(CORE_HALF_WIDTH, distance * CORE_PER_BLOCK);
            float glow = Math.max(GLOW_HALF_WIDTH, distance * GLOW_PER_BLOCK);
            for (BoltShape.Channel channel : shape.channels()) {
                if (channel.main()) {
                    // The glow first, so the core lands on top of it in the sort.
                    ribbon(channel, glow, 0.50f, 0.56f, 0.90f, 0.32f * light, true);
                    ribbon(channel, core, 0.92f, 0.94f, 1.00f, light, false);
                } else {
                    ribbon(channel, core * 2.2f, 0.80f, 0.84f, 1.00f, 0.95f * light, true);
                }
            }
        }

        /**
         * One channel as a ribbon. A hard ribbon is one quad a segment in an even colour; a soft one
         * is two, from the full colour along the middle to nothing at the edges.
         */
        private void ribbon(BoltShape.Channel channel, float halfWidth, float red, float green, float blue, float strength, boolean soft) {
            float[] points = channel.points();
            int segments = channel.segments();
            for (int segment = 0; segment < segments; segment++) {
                float startShare = segment / (float) segments;
                float endShare = (segment + 1) / (float) segments;
                float startWidth = halfWidth * lerp(channel.startWidth(), channel.endWidth(), startShare);
                float endWidth = halfWidth * lerp(channel.startWidth(), channel.endWidth(), endShare);
                float startAlpha = strength * lerp(channel.startLight(), channel.endLight(), startShare);
                float endAlpha = strength * lerp(channel.startLight(), channel.endLight(), endShare);
                sideways(points, segment, startWidth, 0);
                sideways(points, segment + 1, endWidth, 3);
                int start = segment * 3;
                int end = start + 3;
                if (soft) {
                    quad(points, start, end, -1.0f, 0.0f, startAlpha, endAlpha, red, green, blue);
                    quad(points, start, end, 0.0f, 1.0f, startAlpha, endAlpha, red, green, blue);
                } else {
                    quad(points, start, end, -1.0f, 1.0f, startAlpha, endAlpha, red, green, blue);
                }
            }
        }

        /**
         * The sideways offset at a point: across the channel and across the line of sight, so the
         * ribbon faces the camera. Neighbouring segments share it, so the ribbon has no gaps at kinks.
         */
        private void sideways(float[] points, int point, float halfWidth, int into) {
            int last = points.length / 3 - 1;
            int before = Math.max(point - 1, 0) * 3;
            int after = Math.min(point + 1, last) * 3;
            float alongX = points[after] - points[before];
            float alongY = points[after + 1] - points[before + 1];
            float alongZ = points[after + 2] - points[before + 2];
            float viewX = points[point * 3] - eyeX;
            float viewY = points[point * 3 + 1] - eyeY;
            float viewZ = points[point * 3 + 2] - eyeZ;
            float x = alongY * viewZ - alongZ * viewY;
            float y = alongZ * viewX - alongX * viewZ;
            float z = alongX * viewY - alongY * viewX;
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            float scale = length > 1.0e-5f ? halfWidth / length : 0.0f;
            side[into] = x * scale;
            side[into + 1] = y * scale;
            side[into + 2] = z * scale;
        }

        /**
         * The quad between two points of a segment, from {@code from} to {@code to} across the
         * ribbon (-1 one edge, 0 the middle, 1 the other edge). Colour is full in the middle and
         * gone at an edge, unless the quad spans the whole ribbon (a hard one). Wound toward the
         * camera, since the lightning pipeline culls back faces.
         */
        private void quad(float[] points, int start, int end, float from, float to, float startAlpha, float endAlpha,
                          float red, float green, float blue) {
            boolean hard = from < 0.0f && to > 0.0f;
            setCorner(0, points, start, 0, from, hard || from == 0.0f ? startAlpha : 0.0f);
            setCorner(1, points, end, 3, from, hard || from == 0.0f ? endAlpha : 0.0f);
            setCorner(2, points, end, 3, to, hard || to == 0.0f ? endAlpha : 0.0f);
            setCorner(3, points, start, 0, to, hard || to == 0.0f ? startAlpha : 0.0f);
            if (!facesEye()) {
                emit(3, red, green, blue);
                emit(2, red, green, blue);
                emit(1, red, green, blue);
                emit(0, red, green, blue);
            } else {
                emit(0, red, green, blue);
                emit(1, red, green, blue);
                emit(2, red, green, blue);
                emit(3, red, green, blue);
            }
        }

        private void setCorner(int index, float[] points, int point, int sideAt, float across, float cornerAlpha) {
            corner[index * 3] = points[point] + side[sideAt] * across;
            corner[index * 3 + 1] = points[point + 1] + side[sideAt + 1] * across;
            corner[index * 3 + 2] = points[point + 2] + side[sideAt + 2] * across;
            alpha[index] = cornerAlpha;
        }

        /** Whether the corners as they are go anticlockwise seen from the camera. */
        private boolean facesEye() {
            float ax = corner[3] - corner[0];
            float ay = corner[4] - corner[1];
            float az = corner[5] - corner[2];
            float bx = corner[9] - corner[0];
            float by = corner[10] - corner[1];
            float bz = corner[11] - corner[2];
            float normalX = ay * bz - az * by;
            float normalY = az * bx - ax * bz;
            float normalZ = ax * by - ay * bx;
            return normalX * (eyeX - corner[0]) + normalY * (eyeY - corner[1]) + normalZ * (eyeZ - corner[2]) > 0.0f;
        }

        private void emit(int index, float red, float green, float blue) {
            out.addVertex(pose, corner[index * 3], corner[index * 3 + 1], corner[index * 3 + 2])
                .setColor(red, green, blue, Math.clamp(alpha[index], 0.0f, 1.0f));
        }

        private static float lerp(float from, float to, float amount) {
            return from + (to - from) * amount;
        }
    }
}
