package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings.ParticleDensity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Times one particle step over a blizzard's and a thunderstorm's worth of particles at the top
 * settings (about 290,000 snowflakes or raindrops spread over Very far's reach), on one core, the
 * way the worker runs it, and the cull list the worker picks after it (looking level, a 70 degree
 * view, the margins of a camera standing still). Runs only with -Plitelights.particles.probe=true; the numbers go to
 * build/particle-step-probe.txt. Nothing dies or lands during a round, so every step walks the
 * same number of particles; each round starts again from the same particles.
 */
class SimulationStepProbe {
    private static final int PARTICLES = 290_000;
    /** Steps per round: few enough that rain, which falls nearly three blocks a tick, stays in reach. */
    private static final int STEPS = 40;
    private static final int WARM_UP_ROUNDS = 10;
    private static final int ROUNDS = 9;

    /** No ground anywhere: nothing settles, splashes or dies during the probe. */
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

    @Test
    void probe() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("litelights.particles.probe"));
        List<String> lines = new ArrayList<>();
        for (ParticleKind kind : new ParticleKind[] {ParticleKind.SNOW, ParticleKind.RAIN}) {
            Simulation.Inputs inputs = topSettings(PARTICLES);
            SpawnQueue queue = new SpawnQueue();
            queue.begin(ParticleDensity.FULL, new int[ParticleCategory.values().length], 0);
            for (int round = 0; round < WARM_UP_ROUNDS; round++) {
                timeRound(kind, queue, inputs);
            }
            double[] steps = new double[ROUNDS];
            double[] picks = new double[ROUNDS];
            int listed = 0;
            for (int round = 0; round < ROUNDS; round++) {
                double[] times = timeRound(kind, queue, inputs);
                steps[round] = times[0];
                picks[round] = times[1];
                listed = (int) times[2];
            }
            Arrays.sort(steps);
            Arrays.sort(picks);
            lines.add(String.format("%s: %d particles, step %.2f ms, cull list %.2f ms with %d listed (medians of %d rounds;"
                + " step fastest %.2f, slowest %.2f)", kind.name, PARTICLES, steps[ROUNDS / 2], picks[ROUNDS / 2], listed, ROUNDS,
                steps[0], steps[ROUNDS - 1]));
        }
        Files.write(Path.of("build/particle-step-probe.txt"), lines);
    }

    /** Milliseconds a step and a cull list, over one round from a fresh state, and the particles listed. */
    private static double[] timeRound(ParticleKind kind, SpawnQueue queue, Simulation.Inputs inputs) {
        ParticleState a = filled(kind);
        ParticleState b = new ParticleState(PARTICLES);
        CullList.Basis basis = standingStill();
        long stepNanos = 0;
        long pickNanos = 0;
        for (int i = 0; i < STEPS; i++) {
            ParticleState to = (i & 1) == 0 ? b : a;
            long started = System.nanoTime();
            Simulation.step((i & 1) == 0 ? a : b, to, queue, NO_GROUND, inputs);
            long stepped = System.nanoTime();
            to.drawable.pick(to, basis);
            pickNanos += System.nanoTime() - stepped;
            stepNanos += stepped - started;
        }
        if (a.count != PARTICLES) {
            throw new IllegalStateException(kind.name + ": particles were lost during the round (" + a.count + ")");
        }
        return new double[] {stepNanos / 1.0e6 / STEPS, pickNanos / 1.0e6 / STEPS, a.drawable.count};
    }

    /** A 70 degree view at 16:9 looking level along +x, the margins ParticleSystem gives a camera standing still. */
    private static CullList.Basis standingStill() {
        CullList.Basis basis = new CullList.Basis();
        basis.known = true;
        basis.cameraY = 80.0;
        basis.forwardX = 1.0f;
        double tanY = Math.tan(Math.toRadians(35.0));
        basis.coneAngle = (float) (Math.atan(Math.hypot(tanY, tanY * 16.0 / 9.0)) + Math.toRadians(12.0));
        basis.moveMargin = 2.0f;
        basis.viewMargin = 2.5f;
        Arrays.fill(basis.rangeScales, 2.4f);
        return basis;
    }

    private static ParticleState filled(ParticleKind kind) {
        ParticleState state = new ParticleState(PARTICLES);
        Random random = new Random(7);
        for (int i = 0; i < PARTICLES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * 80.0;
            state.append(kind, Math.cos(angle) * distance, 70.0 + random.nextDouble() * 30.0, Math.sin(angle) * distance,
                0.01f, -0.05f, 0.01f, 0.1f, Integer.MAX_VALUE / 2, 0xF0FFFFFF, 0, random.nextInt());
        }
        return state;
    }

    private static Simulation.Inputs topSettings(int cap) {
        Simulation.Inputs inputs = new Simulation.Inputs();
        inputs.cameraX = 0.0;
        inputs.cameraY = 80.0;
        inputs.cameraZ = 0.0;
        inputs.windX = 0.02f;
        inputs.windZ = 0.01f;
        inputs.cap = cap;
        // Very far: the weather's kinds reach 2.4 times as far.
        Arrays.fill(inputs.rangeScales, 2.4f);
        return inputs;
    }
}
