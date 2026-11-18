package com.thenathe.litelights.render.feature.particles;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettings.ParticleDensity;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntConsumer;
import net.minecraft.client.particle.SingleQuadParticle;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frame's particle quads built in slices on several threads, and over the worker's cull list,
 * come out exactly as one walk over every particle would make them: the same particles, each once,
 * in the same order, with the same vertices.
 */
class ParticleFrameTest {
    private static final double CAMERA_X = 0.5;
    private static final double CAMERA_Y = 70.0;
    private static final double CAMERA_Z = 0.5;
    private static final ParticleKind[] KINDS = {
        ParticleKind.RAIN, ParticleKind.SNOW, ParticleKind.MIST, ParticleKind.SAND, ParticleKind.SANDSTORM_GRAIN,
        ParticleKind.RAIN_RIPPLE, ParticleKind.WIND_GRIT
    };
    private static final Set<SingleQuadParticle.Layer> LAYERS = Set.of(ParticleLayers.TRANSLUCENT, ParticleLayers.SOFT,
        ParticleLayers.GLOW, ParticleLayers.PUFF, ParticleLayers.HAZE_BILLBOARD);
    private static final Simulation.Ground NO_GROUND = new Simulation.Ground() {
        @Override
        public double groundHeight(double x, double z) {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public boolean isWaterSurface(double x, double z) {
            return false;
        }
    };

    /** A stepped state (so every particle has moved since the tick before) of mixed kinds all round the camera. */
    private static ParticleState particles(int count, long seed) {
        ParticleState spawned = new ParticleState(count);
        Random random = new Random(seed);
        for (int i = 0; i < count; i++) {
            ParticleKind kind = KINDS[random.nextInt(KINDS.length)];
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = random.nextDouble() * 45.0;
            spawned.append(kind, CAMERA_X + Math.cos(angle) * distance, CAMERA_Y - 10.0 + random.nextDouble() * 30.0,
                CAMERA_Z + Math.sin(angle) * distance, (random.nextFloat() - 0.5f) * 0.6f, -random.nextFloat() * 0.8f,
                (random.nextFloat() - 0.5f) * 0.6f, 0.05f + random.nextFloat() * (kind.haze != ParticleKind.Haze.NONE ? 6.0f : 0.2f),
                200 + random.nextInt(200), 0xE0FFFFFF, 0xF000F0, random.nextInt());
        }
        ParticleState stepped = new ParticleState(count);
        Simulation.Inputs inputs = new Simulation.Inputs();
        inputs.cameraX = CAMERA_X;
        inputs.cameraY = CAMERA_Y;
        inputs.cameraZ = CAMERA_Z;
        inputs.cap = count;
        SpawnQueue queue = new SpawnQueue();
        queue.begin(ParticleDensity.FULL, new int[ParticleCategory.values().length], 0);
        Simulation.step(spawned, stepped, queue, NO_GROUND, inputs);
        return stepped;
    }

    private static Quaternionf looking(float yawDegrees, float pitchDegrees) {
        return new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(yawDegrees), -(float) Math.toRadians(pitchDegrees), 0.0f);
    }

    private static Matrix4f viewProjection(Quaternionf orientation, float fovDegrees) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fovDegrees), 16.0f / 9.0f, 0.05f, 1024.0f);
        return projection.mul(new Matrix4f().rotation(new Quaternionf(orientation).conjugate()));
    }

    private static ParticleRenderer renderer() {
        ParticleSprites sprites = new ParticleSprites();
        sprites.useStandInsForTests();
        return new ParticleRenderer(sprites);
    }

    private static void prepare(ParticleRenderer renderer, ParticleState state, double x, double y, double z, Quaternionf orientation,
                                float fov) {
        renderer.prepare(state, x, y, z, orientation, viewProjection(orientation, fov), 0.6f, false, ParticleBudget.unscaled(), 0.0f,
            RenderSettings.MistStyle.PUFFS);
    }

    /** Every vertex of every layer, slice after slice, as plain numbers. */
    private static List<Float> vertices(List<ParticleBatch> batches) {
        List<Float> out = new ArrayList<>();
        VertexConsumer recorder = new Recorder(out);
        for (SingleQuadParticle.Layer layer : LAYERS) {
            out.add(Float.NaN);
            for (ParticleBatch batch : batches) {
                batch.buildLayer(layer, recorder);
            }
        }
        return out;
    }

    private static List<ParticleBatch> slices(ParticleRenderer renderer) {
        List<ParticleBatch> batches = new ArrayList<>();
        for (int slice = 0; slice < renderer.sliceCount(); slice++) {
            batches.add(renderer.slice(slice));
        }
        return batches;
    }

    @Test
    void slicesOnHelperThreadsMakeTheSameQuadsAsOneWalk() {
        ParticleState state = particles(20_000, 1);
        ParticleRenderer renderer = renderer();
        renderer.cullListOff = true;
        prepare(renderer, state, CAMERA_X, CAMERA_Y, CAMERA_Z, looking(30.0f, 10.0f), 70.0f);
        assertTrue(renderer.sliceCount() > 1, "a frame this full is cut into slices");

        ParticleBatch whole = new ParticleBatch();
        int[] wholeDrawn = new int[state.count];
        int wholeCount = renderer.walkEveryParticle(whole, wholeDrawn);
        assertTrue(wholeCount > 1000, "the view holds plenty of particles");

        QuadHelpers helpers = new QuadHelpers(renderer::buildSlice, 2);
        try {
            renderer.recordDrawn(true);
            prepare(renderer, state, CAMERA_X, CAMERA_Y, CAMERA_Z, looking(30.0f, 10.0f), 70.0f);
            helpers.start(renderer.sliceCount());
            helpers.finish();
            renderer.finishSlices();
        } finally {
            helpers.stop();
        }
        assertEquals(wholeCount, renderer.drawnLastFrame());
        assertArrayEquals(new int[] {0, 0}, renderer.compareWithFullWalk(new ParticleBatch()), "every particle once, in order");
        assertEquals(vertices(List.of(whole)), vertices(slices(renderer)), "the same vertices in the same order");
    }

    @Test
    void theCullListNeverLeavesOutWhatAFrameInsideItsMarginsDraws() {
        Random random = new Random(5);
        int listFrames = 0;
        for (int trial = 0; trial < 60; trial++) {
            ParticleState state = particles(6000, trial);
            ParticleRenderer renderer = renderer();
            float yaw = random.nextFloat() * 360.0f;
            float pitch = (random.nextFloat() - 0.5f) * 120.0f;
            float fov = 50.0f + random.nextFloat() * 60.0f;
            prepare(renderer, state, CAMERA_X, CAMERA_Y, CAMERA_Z, looking(yaw, pitch), fov);
            CullList.Basis basis = new CullList.Basis();
            assertTrue(renderer.writeView(basis));
            basis.moveMargin = 2.0f + random.nextFloat() * 6.0f;
            float turnMargin = (float) Math.toRadians(5.0 + random.nextFloat() * 40.0);
            basis.coneAngle += turnMargin;
            basis.known = true;
            state.drawable.pick(state, basis);
            assertTrue(state.drawable.count < state.count, "the list leaves something out");

            // Frames moved and turned at random, some within the margins and some beyond.
            for (int frame = 0; frame < 6; frame++) {
                double moveScale = basis.moveMargin * random.nextFloat() * 1.3;
                Vector3f move = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f).normalize();
                float turn = (float) Math.toDegrees(turnMargin) * random.nextFloat() * 1.3f;
                renderer.recordDrawn(true);
                prepare(renderer, state, CAMERA_X + move.x * moveScale, CAMERA_Y + move.y * moveScale, CAMERA_Z + move.z * moveScale,
                    looking(yaw + turn * 0.8f, pitch + turn * 0.6f * (random.nextBoolean() ? 1 : -1)), fov);
                for (int slice = 0; slice < renderer.sliceCount(); slice++) {
                    renderer.buildSlice(slice);
                }
                renderer.finishSlices();
                if (renderer.walksList()) {
                    listFrames++;
                }
                assertArrayEquals(new int[] {0, 0}, renderer.compareWithFullWalk(new ParticleBatch()),
                    "trial " + trial + ", frame " + frame + (renderer.walksList() ? " (list)" : " (full walk)"));
            }
        }
        assertTrue(listFrames > 100, "most frames inside the margins walk the list (" + listFrames + ")");
    }

    @Test
    void aFrameBeyondTheMarginsWalksEveryParticle() {
        ParticleState state = particles(3000, 9);
        ParticleRenderer renderer = renderer();
        prepare(renderer, state, CAMERA_X, CAMERA_Y, CAMERA_Z, looking(0.0f, 0.0f), 70.0f);
        CullList.Basis basis = new CullList.Basis();
        renderer.writeView(basis);
        basis.moveMargin = 2.0f;
        basis.coneAngle += (float) Math.toRadians(10.0);
        basis.known = true;
        state.drawable.pick(state, basis);

        prepare(renderer, state, CAMERA_X + 1.0, CAMERA_Y, CAMERA_Z, looking(5.0f, 0.0f), 70.0f);
        assertTrue(renderer.walksList(), "a small move and turn stays inside");
        prepare(renderer, state, CAMERA_X + 3.0, CAMERA_Y, CAMERA_Z, looking(0.0f, 0.0f), 70.0f);
        assertFalse(renderer.walksList(), "moved too far");
        prepare(renderer, state, CAMERA_X, CAMERA_Y, CAMERA_Z, looking(15.0f, 0.0f), 70.0f);
        assertFalse(renderer.walksList(), "turned too far");
        prepare(renderer, state, CAMERA_X, CAMERA_Y, CAMERA_Z, looking(0.0f, 0.0f), 100.0f);
        assertFalse(renderer.walksList(), "a wider view (sprinting, a speed effect) than the list allows");
        float[] farther = ParticleBudget.unscaled();
        farther[ParticleCategory.WEATHER.ordinal()] = 1.4f;
        Quaternionf ahead = looking(0.0f, 0.0f);
        renderer.prepare(state, CAMERA_X, CAMERA_Y, CAMERA_Z, ahead, viewProjection(ahead, 70.0f), 0.6f, false, farther, 0.0f,
            RenderSettings.MistStyle.PUFFS);
        assertFalse(renderer.walksList(), "the Weather distance changed");
        state.clear();
        prepare(renderer, state, CAMERA_X, CAMERA_Y, CAMERA_Z, looking(0.0f, 0.0f), 70.0f);
        assertFalse(renderer.walksList(), "a cleared state's list is gone");
    }

    @Test
    void theStormFogLimitsTheListOnlyWhileItIsAsThick() {
        ParticleState state = particles(3000, 11);
        ParticleRenderer renderer = renderer();
        Quaternionf ahead = looking(0.0f, 0.0f);
        Matrix4f projection = viewProjection(ahead, 70.0f);
        float fog = 0.08f;
        renderer.prepare(state, CAMERA_X, CAMERA_Y, CAMERA_Z, ahead, projection, 0.6f, false, ParticleBudget.unscaled(), fog,
            RenderSettings.MistStyle.PUFFS);
        CullList.Basis basis = new CullList.Basis();
        renderer.writeView(basis);
        assertTrue(basis.softLimit < 60.0f, "a thick storm fog hides soft particles within the reach");
        basis.moveMargin = 2.0f;
        basis.known = true;
        state.drawable.pick(state, basis);
        renderer.prepare(state, CAMERA_X, CAMERA_Y, CAMERA_Z, ahead, projection, 0.6f, false, ParticleBudget.unscaled(), fog,
            RenderSettings.MistStyle.PUFFS);
        assertTrue(renderer.walksList());
        renderer.prepare(state, CAMERA_X, CAMERA_Y, CAMERA_Z, ahead, projection, 0.6f, false, ParticleBudget.unscaled(), fog * 0.5f,
            RenderSettings.MistStyle.PUFFS);
        assertFalse(renderer.walksList(), "thinner fog shows soft particles the list left out");
    }

    @Test
    void aPointsDistanceFromTheConeIsMeasuredToItsSideOrItsApex() {
        float cos = (float) Math.cos(Math.toRadians(45.0));
        float sin = (float) Math.sin(Math.toRadians(45.0));
        // Axis +z, half-angle 45 degrees.
        assertTrue(CullList.nearCone(0, 0, 10, 100, 0, 0, 1, cos, sin, 0.0f), "on the axis");
        assertTrue(CullList.nearCone(9, 0, 10, 181, 0, 0, 1, cos, sin, 0.0f), "inside, near the side");
        // (12, 0, 10) is (12 cos 45 - 10 sin 45) = 1.41 blocks outside the side.
        assertFalse(CullList.nearCone(12, 0, 10, 244, 0, 0, 1, cos, sin, 1.3f));
        assertTrue(CullList.nearCone(12, 0, 10, 244, 0, 0, 1, cos, sin, 1.5f));
        // Behind the apex, the distance is to the apex itself.
        assertFalse(CullList.nearCone(0, 0, -3, 9, 0, 0, 1, cos, sin, 2.9f));
        assertTrue(CullList.nearCone(0, 0, -3, 9, 0, 0, 1, cos, sin, 3.1f));
        // A cone wider than a half-space (a wide view with a large turn margin) keeps all but a narrow cone behind.
        float wideCos = (float) Math.cos(Math.toRadians(150.0));
        float wideSin = (float) Math.sin(Math.toRadians(150.0));
        assertTrue(CullList.nearCone(0, 0, 10, 100, 0, 0, 1, wideCos, wideSin, 0.0f), "on the axis of a wide cone");
        assertTrue(CullList.nearCone(10, 0, -1, 101, 0, 0, 1, wideCos, wideSin, 0.0f), "beside and a little behind");
        assertFalse(CullList.nearCone(1, 0, -10, 101, 0, 0, 1, wideCos, wideSin, 0.5f), "straight behind, outside 150 degrees");
    }

    @Test
    void helpersBuildEverySliceOfEveryFrameExactlyOnce() {
        int[] builds = new int[ParticleRenderer.MAX_SLICES];
        IntConsumer count = slice -> {
            synchronized (builds) {
                builds[slice]++;
            }
        };
        QuadHelpers helpers = new QuadHelpers(count, 2);
        Random random = new Random(3);
        try {
            for (int frame = 0; frame < 2000; frame++) {
                int slices = random.nextInt(ParticleRenderer.MAX_SLICES + 1);
                java.util.Arrays.fill(builds, 0);
                helpers.start(slices);
                if (random.nextBoolean()) {
                    Thread.onSpinWait();
                }
                helpers.finish();
                for (int slice = 0; slice < ParticleRenderer.MAX_SLICES; slice++) {
                    assertEquals(slice < slices ? 1 : 0, builds[slice], "frame " + frame + ", slice " + slice + " of " + slices);
                }
            }
        } finally {
            helpers.stop();
        }
    }

    @Test
    void aFailingSliceIsPassedOnAndTheHelpersCarryOn() {
        boolean[] fail = {true};
        QuadHelpers helpers = new QuadHelpers(slice -> {
            if (fail[0] && slice == 2) {
                throw new IllegalStateException("broken slice");
            }
        }, 2);
        try {
            helpers.start(6);
            IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, helpers::finish);
            assertEquals("broken slice", thrown.getMessage());
            fail[0] = false;
            helpers.start(6);
            helpers.finish();
        } finally {
            helpers.stop();
        }
    }

    @Test
    void stoppingEndsTheHelperThreadsAndTheyComeBackWhenNeeded() throws InterruptedException {
        QuadHelpers helpers = new QuadHelpers(slice -> { }, 2);
        long before = helperThreads();
        for (int round = 0; round < 3; round++) {
            helpers.start(4);
            helpers.finish();
            assertEquals(2, helpers.threadCount());
            helpers.start(4);
            // Stopping finishes a frame still in progress.
            helpers.stop();
            assertEquals(0, helpers.threadCount());
        }
        Thread.sleep(50);
        assertEquals(before, helperThreads(), "no helper outlives a stop");
        assertEquals(0, QuadHelpers.helpersFor(4));
        assertEquals(1, QuadHelpers.helpersFor(6));
        assertEquals(2, QuadHelpers.helpersFor(16));
    }

    private static long helperThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().startsWith("LiteLights particle quads")).count();
    }

    /** Writes every vertex's values into a list. */
    private record Recorder(List<Float> out) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            out.add(x);
            out.add(y);
            out.add(z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            out.add((float) (red << 24 | green << 16 | blue << 8 | alpha));
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            out.add((float) argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            out.add(u);
            out.add(v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            out.add((float) u);
            out.add((float) v);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
