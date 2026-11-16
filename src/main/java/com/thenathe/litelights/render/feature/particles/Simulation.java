package com.thenathe.litelights.render.feature.particles;

/**
 * One tick of particle motion: reads one {@link ParticleState} and writes the next, then adds the
 * tick's new particles. Plain arithmetic over arrays, no game objects, so it runs on a worker
 * thread and in unit tests.
 */
final class Simulation {
    /** Settled snow melts this many ticks after landing. */
    private static final int MELT_TICKS = 24;
    /** Particles this far beyond their kind's range from the camera are removed. */
    private static final float REMOVAL_MARGIN = 8.0f;

    /** Where ground contact is checked. Implemented by {@link ColumnGrid}. */
    interface Ground {
        double groundHeight(double x, double z);

        boolean isWaterSurface(double x, double z);
    }

    /** Values that hold for the whole step. */
    static final class Inputs {
        double cameraX;
        double cameraY;
        double cameraZ;
        float windX;
        float windZ;
        int cap;
        /** Each category's range is multiplied by this (by ordinal): the Weather and Particle distances. */
        final float[] rangeScales = ParticleBudget.unscaled();
    }

    private Simulation() {
    }

    static void step(ParticleState from, ParticleState to, SpawnQueue spawns, Ground ground, Inputs inputs) {
        to.clear();
        int cap = Math.min(inputs.cap, to.capacity);
        for (int i = 0; i < from.count && to.count < cap; i++) {
            ParticleKind kind = ParticleKind.byId(from.kind[i]);
            int age = from.age[i] + 1;
            if (age >= from.lifetime[i]) {
                continue;
            }
            int seed = from.seed[i];
            if (age <= kind.hangTicks) {
                // Still forming (a drip under an eave): it stays put and only ages.
                copy(from, i, to, from.x[i], from.y[i], from.z[i], from.velocityX[i], from.velocityY[i], from.velocityZ[i],
                    age, from.lifetime[i], kind);
                continue;
            }
            float speedX = from.velocityX[i];
            float speedY = from.velocityY[i] - kind.gravity;
            float speedZ = from.velocityZ[i];
            if (kind.windCoupling > 0.0f) {
                speedX += (inputs.windX * kind.windBoost - speedX) * kind.windCoupling;
                speedZ += (inputs.windZ * kind.windBoost - speedZ) * kind.windCoupling;
            }
            if (kind.wanderStrength > 0.0f) {
                // Each particle wanders on its own phase, from its seed, so neighbours don't move in step.
                float phase = age * kind.wanderRate + (seed & 1023) * 0.0614f;
                speedX += (float) Math.sin(phase) * kind.wanderStrength;
                speedY += (float) Math.sin(phase * 1.31f + 1.7f) * kind.wanderStrength * 0.5f;
                speedZ += (float) Math.cos(phase * 0.87f + 0.4f) * kind.wanderStrength;
            }
            speedX *= kind.drag;
            speedY *= kind.drag;
            speedZ *= kind.drag;

            double x = from.x[i] + speedX;
            double y = from.y[i] + speedY;
            double z = from.z[i] + speedZ;
            int lifetime = from.lifetime[i];

            double offsetX = x - inputs.cameraX;
            double offsetZ = z - inputs.cameraZ;
            float removal = ParticleReach.range(kind, inputs.rangeScales) + REMOVAL_MARGIN;
            if (offsetX * offsetX + offsetZ * offsetZ > removal * removal || Math.abs(y - inputs.cameraY) > removal * 2) {
                continue;
            }

            if (kind.ground != ParticleKind.Ground.NONE) {
                double groundY = ground.groundHeight(x, z);
                if (y < groundY) {
                    if (kind.ground == ParticleKind.Ground.DIE) {
                        continue;
                    }
                    if (kind.ground == ParticleKind.Ground.SPLASH) {
                        splash(to, cap, ground, kind, x, groundY, z, from.light[i], from.color[i], seed);
                        continue;
                    }
                    // SETTLE: lie on the ground and melt away.
                    y = groundY + 0.02;
                    speedX = 0.0f;
                    speedY = 0.0f;
                    speedZ = 0.0f;
                    lifetime = Math.min(lifetime, age + MELT_TICKS);
                }
            }

            copy(from, i, to, x, y, z, speedX, speedY, speedZ, age, lifetime, kind);
        }
        for (int i = 0; i < spawns.count && to.count < cap; i++) {
            to.append(spawns.kind[i], spawns.x[i], spawns.y[i], spawns.z[i], spawns.velocityX[i], spawns.velocityY[i],
                spawns.velocityZ[i], spawns.size[i], spawns.lifetime[i], spawns.color[i], spawns.light[i], spawns.seed[i]);
        }
    }

    /** Writes particle {@code i} of {@code from} into {@code to} with its new position, motion and age. */
    private static void copy(ParticleState from, int i, ParticleState to, double x, double y, double z,
                             float speedX, float speedY, float speedZ, int age, int lifetime, ParticleKind kind) {
        int index = to.count++;
        to.previousX[index] = from.x[i];
        to.previousY[index] = from.y[i];
        to.previousZ[index] = from.z[i];
        to.x[index] = x;
        to.y[index] = y;
        to.z[index] = z;
        to.velocityX[index] = speedX;
        to.velocityY[index] = speedY;
        to.velocityZ[index] = speedZ;
        to.size[index] = from.size[i];
        to.age[index] = age;
        to.lifetime[index] = lifetime;
        to.seed[index] = from.seed[i];
        to.color[index] = from.color[i];
        to.light[index] = from.light[i];
        to.kind[index] = from.kind[i];
        to.categoryCounts[kind.category.ordinal()]++;
    }

    /** A drop landing: some kinds leave a splash (drips always do), and on water a ripple too. */
    private static void splash(ParticleState to, int cap, Ground ground, ParticleKind kind, double x, double groundY, double z,
                               int light, int dropColor, int seed) {
        int roll = (seed * 0x9E3779B1) >>> 28;
        if (roll >= kind.splashChance || to.count >= cap) {
            return;
        }
        // Splashes take the drop's colour but stay fairly opaque, so dark rain splashes dark.
        int splashColor = (dropColor & 0x00FFFFFF) | 0xC0000000;
        if (ground.isWaterSurface(x, z)) {
            // Water's surface sits about a ninth of a block under the top of its block.
            to.append(ParticleKind.RAIN_RIPPLE, x, groundY - 0.1, z, 0.0f, 0.0f, 0.0f, 0.32f * kind.splashScale, 9, splashColor, light, seed);
        } else {
            to.append(ParticleKind.RAIN_SPLASH, x, groundY + 0.12 * kind.splashScale, z, 0.0f, 0.0f, 0.0f, 0.12f * kind.splashScale, 5,
                splashColor, light, seed);
        }
    }
}
