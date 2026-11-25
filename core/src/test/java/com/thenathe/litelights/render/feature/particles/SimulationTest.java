package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings.ParticleDensity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationTest {
    /** Flat ground at y = 64, water east of x = 0. */
    private static final Simulation.Ground FLAT = new Simulation.Ground() {
        @Override
        public double groundHeight(double x, double z) {
            return 64.0;
        }

        @Override
        public boolean isWaterSurface(double x, double z) {
            return x > 0;
        }
    };

    private static Simulation.Inputs inputs(int cap) {
        Simulation.Inputs inputs = new Simulation.Inputs();
        inputs.cameraX = 0;
        inputs.cameraY = 70;
        inputs.cameraZ = 0;
        inputs.cap = cap;
        return inputs;
    }

    private static SpawnQueue emptyQueue() {
        SpawnQueue queue = new SpawnQueue();
        queue.begin(ParticleDensity.FULL, new int[ParticleCategory.values().length], 0);
        return queue;
    }

    @Test
    void particlesAgeAndDie() {
        ParticleState from = new ParticleState(16);
        ParticleState to = new ParticleState(16);
        from.append(ParticleKind.DUST_SPECK, 0, 70, 0, 0, 0, 0, 0.1f, 2, -1, 0, 1);
        Simulation.step(from, to, emptyQueue(), FLAT, inputs(16));
        assertEquals(1, to.count);
        assertEquals(1, to.age[0]);
        Simulation.step(to, from, emptyQueue(), FLAT, inputs(16));
        assertEquals(0, from.count, "a particle is gone when its age reaches its lifetime");
    }

    @Test
    void positionsKeepTheLastTickForInterpolation() {
        ParticleState from = new ParticleState(16);
        ParticleState to = new ParticleState(16);
        from.append(ParticleKind.RAIN, 1, 80, 1, 0, -1.0f, 0, 0.03f, 60, -1, 0, 1);
        Simulation.step(from, to, emptyQueue(), FLAT, inputs(16));
        assertEquals(80.0, to.previousY[0]);
        assertTrue(to.y[0] < 79.0);
    }

    @Test
    void rainStopsAtTheGround() {
        ParticleState a = new ParticleState(64);
        ParticleState b = new ParticleState(64);
        for (int seed = 0; seed < 32; seed++) {
            a.append(ParticleKind.RAIN, -1 - seed * 0.1, 64.5, 0, 0, -1.0f, 0, 0.03f, 60, -1, 0, seed);
        }
        Simulation.step(a, b, emptyQueue(), FLAT, inputs(64));
        for (int i = 0; i < b.count; i++) {
            assertTrue(b.kind[i] == ParticleKind.RAIN_SPLASH.id, "only splashes are left after the drops land");
        }
        assertTrue(b.count > 0 && b.count < 32, "some drops, not all, leave a splash");
    }

    @Test
    void rainOnWaterLeavesRipples() {
        ParticleState a = new ParticleState(64);
        ParticleState b = new ParticleState(64);
        for (int seed = 0; seed < 32; seed++) {
            a.append(ParticleKind.RAIN, 2 + seed * 0.1, 64.5, 0, 0, -1.0f, 0, 0.03f, 60, -1, 0, seed);
        }
        Simulation.step(a, b, emptyQueue(), FLAT, inputs(64));
        assertTrue(b.count > 0);
        for (int i = 0; i < b.count; i++) {
            assertEquals(ParticleKind.RAIN_RIPPLE.id, b.kind[i]);
        }
    }

    @Test
    void snowSettlesAndMelts() {
        ParticleState a = new ParticleState(16);
        ParticleState b = new ParticleState(16);
        a.append(ParticleKind.SNOW, 0, 64.05, 0, 0, -0.1f, 0, 0.08f, 500, -1, 0, 3);
        Simulation.step(a, b, emptyQueue(), FLAT, inputs(16));
        assertEquals(1, b.count);
        assertTrue(b.y[0] >= 64.0, "rests on the ground");
        assertTrue(b.lifetime[0] < 500, "melts soon after landing");
    }

    @Test
    void theCapHoldsDuringAStep() {
        ParticleState a = new ParticleState(32);
        ParticleState b = new ParticleState(32);
        for (int i = 0; i < 20; i++) {
            a.append(ParticleKind.DUST_SPECK, 0, 70, 0, 0, 0, 0, 0.1f, 100, -1, 0, i);
        }
        SpawnQueue queue = emptyQueue();
        for (int i = 0; i < 20; i++) {
            queue.add(ParticleKind.DUST_SPECK, 0, 70, 0, 0, 0, 0, 0.1f, 100, -1, 0, i);
        }
        Simulation.step(a, b, queue, FLAT, inputs(25));
        assertEquals(25, b.count);
        assertEquals(25, b.categoryCounts[ParticleCategory.AMBIENT.ordinal()]);
        // Lowering the cap (Maximum to Reduced, say) trims what is alive on the next step.
        Simulation.step(b, a, emptyQueue(), FLAT, inputs(10));
        assertEquals(10, a.count);
    }

    @Test
    void particlesFarFromTheCameraAreRemoved() {
        ParticleState a = new ParticleState(16);
        ParticleState b = new ParticleState(16);
        a.append(ParticleKind.DUST_SPECK, 500, 70, 0, 0, 0, 0, 0.1f, 100, -1, 0, 1);
        a.append(ParticleKind.DUST_SPECK, 3, 70, 0, 0, 0, 0, 0.1f, 100, -1, 0, 2);
        Simulation.step(a, b, emptyQueue(), FLAT, inputs(16));
        assertEquals(1, b.count);
        assertEquals(3.0, b.x[0], 0.5);
    }

    @Test
    void windCarriesCoupledKinds() {
        ParticleState a = new ParticleState(16);
        ParticleState b = new ParticleState(16);
        a.append(ParticleKind.SAND, 0, 70, 0, 0, 0, 0, 0.06f, 100, -1, 0, 1);
        Simulation.Inputs windy = inputs(16);
        windy.windX = 0.3f;
        ParticleState current = a;
        ParticleState next = b;
        for (int tick = 0; tick < 20; tick++) {
            Simulation.step(current, next, emptyQueue(), FLAT, windy);
            ParticleState swap = current;
            current = next;
            next = swap;
        }
        assertTrue(current.x[0] > 2.0, "sand moves downwind");
    }

    @Test
    void sandstormGrainsOutrunTheWind() {
        ParticleState a = new ParticleState(16);
        ParticleState b = new ParticleState(16);
        a.append(ParticleKind.SAND, 0, 80, 0, 0, 0, 0, 0.06f, 100, -1, 0, 1);
        a.append(ParticleKind.SANDSTORM_GRAIN, 0, 80, 0, 0, 0, 0, 0.05f, 100, -1, 0, 1);
        Simulation.Inputs windy = inputs(16);
        windy.windX = 0.3f;
        ParticleState current = a;
        ParticleState next = b;
        for (int tick = 0; tick < 30; tick++) {
            Simulation.step(current, next, emptyQueue(), FLAT, windy);
            ParticleState swap = current;
            current = next;
            next = swap;
        }
        assertTrue(current.velocityX[1] > current.velocityX[0] * 1.8f, "grains settle well above the wind's speed: "
            + current.velocityX[1] + " against " + current.velocityX[0]);
    }

    @Test
    void dripsHangThenFallAndAlwaysSplash() {
        ParticleState a = new ParticleState(64);
        ParticleState b = new ParticleState(64);
        for (int seed = 0; seed < 16; seed++) {
            a.append(ParticleKind.RAIN_DRIP, -1 - seed * 0.1, 66.0, 0, 0, 0, 0, 0.05f, 80, -1, 0, seed);
        }
        for (int tick = 0; tick < ParticleKind.RAIN_DRIP.hangTicks; tick++) {
            Simulation.step(a, b, emptyQueue(), FLAT, inputs(64));
            ParticleState swap = a;
            a = b;
            b = swap;
        }
        assertEquals(16, a.count);
        assertEquals(66.0, a.y[0], "still hanging where it formed");
        for (int tick = 0; tick < 40; tick++) {
            Simulation.step(a, b, emptyQueue(), FLAT, inputs(64));
            ParticleState swap = a;
            a = b;
            b = swap;
            if (a.count > 0 && a.kind[0] == ParticleKind.RAIN_SPLASH.id) {
                break;
            }
        }
        int splashes = 0;
        for (int i = 0; i < a.count; i++) {
            if (a.kind[i] == ParticleKind.RAIN_SPLASH.id) {
                splashes++;
            }
        }
        assertEquals(16, splashes, "every drip leaves a splash on dry ground");
    }

    @Test
    void wallStreaksEndWithTheirLifetimeAndNeverSplash() {
        // RainDetails gives a streak the lifetime that brings it to the ground in front of its face.
        // The ground check doesn't apply: a pane's face lies inside the pane's own column, below its top.
        ParticleState a = new ParticleState(16);
        ParticleState b = new ParticleState(16);
        a.append(ParticleKind.WALL_STREAK, -1, 63.5, 0, 0, -0.05f, 0, 0.03f, 30, -1, 0, 1);
        a.append(ParticleKind.WALL_STREAK, -1, 66.0, 0, 0, -0.05f, 0, 0.03f, 1, -1, 0, 2);
        Simulation.step(a, b, emptyQueue(), FLAT, inputs(16));
        assertEquals(1, b.count, "the one below its column's top slides on, the one whose time is up is gone, nothing splashes");
        assertEquals(ParticleKind.WALL_STREAK.id, b.kind[0]);
    }
}
