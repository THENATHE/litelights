package com.thenathe.litelights.render.feature.water.splash;

import com.thenathe.litelights.render.LiteMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Spawns the water particles: jump splashes (droplets, a foam ring, ripples) and the foam, mist and
 * spray where falling water lands. Particles go through vanilla's particle engine, so they sort,
 * light and cull like any other.
 */
public final class SplashEffects {
    /** Ticks after the splash at which each ripple starts. */
    private static final int[] RIPPLE_DELAYS = {3, 9, 16};

    private static final int WHITE = 0xFFFFFF;

    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** A jump splash centred on (x, z) at the water surface. */
    public void splash(ClientLevel level, double x, double surfaceY, double z, SplashPlan plan) {
        int foam = foamColour(level, x, surfaceY, z);
        int droplet = LiteMath.mixRgb(foam, 0xC8E2F6, 0.5f);
        double rim = 0.3 * plan.widthScale();
        for (int i = 0; i < plan.droplets(); i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double outward = (0.4 + 0.6 * random.nextDouble()) * plan.dropletSpeed() * 0.6;
            double up = (0.6 + 0.6 * random.nextDouble()) * plan.dropletSpeed() * 1.8;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            add(new DropletParticle(level, x + cos * rim, surfaceY + 0.05, z + sin * rim,
                cos * outward, up, sin * outward, 0.05f + random.nextFloat() * 0.05f, droplet));
        }
        // A column of spray straight up from the centre of a hard landing.
        for (int i = 0; i < plan.columnDroplets(); i++) {
            double spread = 0.04;
            add(new DropletParticle(level, x + random.nextGaussian() * 0.1, surfaceY + 0.1, z + random.nextGaussian() * 0.1,
                random.nextGaussian() * spread, 0.35 + 0.1 * plan.energy() + random.nextDouble() * 0.15,
                random.nextGaussian() * spread, 0.07f + random.nextFloat() * 0.05f, droplet));
        }
        add(new SurfaceParticle(level, x, surfaceY + 0.02, z, WaterParticleSprites.Set.RING,
            plan.ringStart(), plan.ringEnd(), plan.ringLifetime(), 0, 0.95f, foam));
        for (int i = 0; i < plan.ripples(); i++) {
            float end = plan.rippleEnd() * (1.0f + 0.35f * i);
            add(new SurfaceParticle(level, x, surfaceY + 0.015, z, WaterParticleSprites.Set.RIPPLE,
                plan.ringStart(), end, plan.rippleLifetime(), RIPPLE_DELAYS[i], 0.6f, foam));
        }
    }

    /**
     * An Advanced jump splash at the exact landing point (x, z) on the water surface: a square ring
     * of water-blue and white spreading out, a fainter one after it on hard landings, water thrown
     * up and falling back, and droplets.
     */
    public void advancedSplash(ClientLevel level, double x, double surfaceY, double z, AdvancedSplashPlan plan) {
        int tint = LiteMath.mixRgb(WHITE, BiomeColors.getAverageWaterColor(level, cursor.set(x, surfaceY, z)), 0.2f);
        add(new SquareRingParticle(level, x, surfaceY + 0.02, z, plan.ringReach(), plan.ringLifetime(), 0, 0.95f, tint));
        if (plan.secondRing()) {
            add(new SquareRingParticle(level, x, surfaceY + 0.018, z, plan.ringReach() * 0.7f, plan.ringLifetime(), 5, 0.6f, tint));
        }
        add(new SplashCrownParticle(level, x, surfaceY, z, plan.crownPeak(), plan.crownRiseTicks(), plan.crownFallTicks(), tint));
        int droplet = LiteMath.mixRgb(tint, 0x8CC0F0, 0.35f);
        for (int i = 0; i < plan.droplets(); i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double outward = (0.2 + 0.5 * random.nextDouble()) * plan.dropletSpeed() * 0.5;
            double up = (0.7 + 0.6 * random.nextDouble()) * plan.dropletSpeed() * 2.0;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            add(new DropletParticle(level, x + cos * 0.15, surfaceY + 0.1, z + sin * 0.15,
                cos * outward, up, sin * outward, 0.05f + random.nextFloat() * 0.05f, droplet));
        }
    }

    /**
     * One tick of churn where falling water lands. {@code drop} is how far it fell in blocks;
     * {@code amount} the particle amount's spawn scale ({@link com.thenathe.litelights.render.feature.particles.ParticleBudget#spawnScale}).
     */
    public void waterfallTick(ClientLevel level, int blockX, int blockY, int blockZ, int drop, float amount) {
        cursor.set(blockX, blockY, blockZ);
        var fluid = level.getFluidState(cursor);
        if (fluid.isEmpty()) {
            return;
        }
        double surfaceY = blockY + fluid.getHeight(level, cursor);
        float strength = Math.clamp(drop / 12.0f, 0.15f, 1.5f) * amount;
        int foam = foamColour(level, blockX + 0.5, surfaceY, blockZ + 0.5);
        if (random.nextFloat() < 0.45f * strength) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double speed = 0.015 + random.nextDouble() * 0.025;
            add(new SurfaceParticle(level, blockX + 0.5 + Math.cos(angle) * 0.4, surfaceY + 0.02, blockZ + 0.5 + Math.sin(angle) * 0.4,
                WaterParticleSprites.Set.FOAM, 0.25f + 0.1f * strength, 0.6f + 0.4f * strength, 40 + random.nextInt(30), 0, 0.9f, foam)
                .drift(Math.cos(angle) * speed, Math.sin(angle) * speed));
        }
        if (random.nextFloat() < 0.3f * strength) {
            add(new MistParticle(level, blockX + random.nextDouble(), surfaceY + random.nextDouble() * 0.4, blockZ + random.nextDouble(),
                0.35f + 0.35f * strength, 0.28f + 0.12f * Math.min(1.0f, strength), WHITE));
        }
        if (random.nextFloat() < 0.6f * strength) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double outward = 0.04 + random.nextDouble() * 0.06;
            add(new DropletParticle(level, blockX + 0.5 + Math.cos(angle) * 0.3, surfaceY + 0.05, blockZ + 0.5 + Math.sin(angle) * 0.3,
                Math.cos(angle) * outward, 0.12 + random.nextDouble() * 0.12 * strength, Math.sin(angle) * outward,
                0.04f + random.nextFloat() * 0.04f, LiteMath.mixRgb(foam, 0xC8E2F6, 0.5f)));
        }
    }

    /**
     * Spray off a fast boat's bow, at (x, z) on the water: a few droplets thrown up and out to either
     * side, more the faster it goes. {@code speed} is in blocks per tick (0.4 at full speed);
     * {@code amount} the particle amount's spawn scale.
     */
    public void bowSpray(ClientLevel level, double x, double surfaceY, double z, double headingX, double headingZ,
                         double speed, float amount) {
        float strength = (float) Math.clamp((speed - 0.2) / 0.2, 0.0, 1.25) * amount;
        int droplet = LiteMath.mixRgb(foamColour(level, x, surfaceY, z), 0xC8E2F6, 0.4f);
        for (int side = -1; side <= 1; side += 2) {
            if (random.nextFloat() >= 0.9f * strength) {
                continue;
            }
            // Out over the bow's shoulder: forward with the boat, sideways and up.
            double sidewaysX = -headingZ * side;
            double sidewaysZ = headingX * side;
            double out = 0.06 + random.nextDouble() * 0.06;
            double forward = speed * (0.5 + 0.3 * random.nextDouble());
            add(new DropletParticle(level, x + sidewaysX * 0.45, surfaceY + 0.1, z + sidewaysZ * 0.45,
                headingX * forward + sidewaysX * out, 0.12 + random.nextDouble() * 0.1 * strength, headingZ * forward + sidewaysZ * out,
                0.04f + random.nextFloat() * 0.04f, droplet));
        }
        if (random.nextFloat() < 0.12f * strength) {
            add(new MistParticle(level, x + (random.nextDouble() - 0.5) * 0.6, surfaceY + 0.15, z + (random.nextDouble() - 0.5) * 0.6,
                0.25f + 0.15f * strength, 0.22f, WHITE));
        }
    }

    /** Foam is white with a touch of the biome's water colour, so it sits in its water. */
    private int foamColour(ClientLevel level, double x, double y, double z) {
        cursor.set(x, y, z);
        return LiteMath.mixRgb(WHITE, BiomeColors.getAverageWaterColor(level, cursor), 0.15f);
    }

    private static void add(Particle particle) {
        Minecraft.getInstance().particleEngine.add(particle);
    }
}
