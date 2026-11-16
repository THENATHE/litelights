package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.frame.FrameFlags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The Nether's and the End's air.
 *
 * <p>The Nether follows the particle settings (PLAN section 5): the amount scales it, and each
 * biome's effect goes with the ambient switch closest to it. Basalt deltas' ash haze, ash and
 * embers go with dust specks; crimson forests' glowing drips and motes, and warped forests'
 * spores, go with spores; soul sand valleys' wisps go with fireflies, the other glowing drifters.
 *
 * <p>The End has its own switch (Dimensions, End, Particles): violet motes and specks rise
 * through the air, and mist hangs low over the islands.
 */
final class DimensionAmbience {
    private static final int HAZE_COLOR = 0x78ACA8AC;
    private static final int ASH_COLOR = 0xD0D8D4D0;
    private static final int NETHER_EMBER_COLOR = 0xFFFF8A30;
    private static final int CRIMSON_DRIP_COLOR = 0xFFFF3A2A;
    private static final int CRIMSON_MOTE_COLOR = 0xFFFF5A44;
    private static final int WARPED_SPORE_COLOR = 0xFF3CEACB;
    private static final int SOUL_WISP_COLOR = 0xFF78E4FF;
    private static final int END_MOTE_COLOR = 0xFFC69AFF;
    private static final int END_SPECK_COLOR = 0xFFE8D6FF;
    private static final int END_MIST_COLOR = 0x80B08CD6;

    private DimensionAmbience() {
    }

    static void spawn(Surroundings s, SpawnQueue queue) {
        if (s.cameraInWater) {
            return;
        }
        if (s.dimensionKind == FrameFlags.DIMENSION_NETHER) {
            nether(s, queue);
        } else if (s.dimensionKind == FrameFlags.DIMENSION_END && s.settings.dimensions().endMotes()) {
            end(s, queue);
        }
    }

    private static void nether(Surroundings s, SpawnQueue queue) {
        RenderSettings.Particles particles = s.settings.particles();
        float scale = queue.rateScale() * s.ambientFill;
        // Haze is the air close by: it keeps its Near count at every distance.
        float hazeScale = queue.rateScale();
        switch (s.climate) {
            case BASALT_DELTAS -> {
                if (particles.dustSpecks()) {
                    air(s, queue, ParticleKind.ASH_HAZE, 0.3f * hazeScale, 30.0f, 10.0f, HAZE_COLOR, 2.4f, 1.5f, 220, false);
                    air(s, queue, ParticleKind.ASH, 3.0f * scale, 24.0f, 12.0f, ASH_COLOR, 0.05f, 0.02f, 180, false);
                    air(s, queue, ParticleKind.NETHER_EMBER, 0.8f * scale, 24.0f, 10.0f, NETHER_EMBER_COLOR, 0.04f, 0.015f, 120, false);
                }
            }
            case CRIMSON_FOREST -> {
                if (particles.spores()) {
                    drips(s, queue, 4.0f * scale);
                    air(s, queue, ParticleKind.CRIMSON_MOTE, 1.2f * scale, 24.0f, 10.0f, CRIMSON_MOTE_COLOR, 0.045f, 0.015f, 200, false);
                }
            }
            case WARPED_FOREST -> {
                if (particles.spores()) {
                    air(s, queue, ParticleKind.WARPED_SPORE, 1.8f * scale, 26.0f, 12.0f, WARPED_SPORE_COLOR, 0.045f, 0.02f, 220, false);
                }
            }
            case SOUL_SAND_VALLEY -> {
                if (particles.fireflySwarms()) {
                    air(s, queue, ParticleKind.SOUL_WISP, 2.0f * scale, 28.0f, 6.0f, SOUL_WISP_COLOR, 0.16f, 0.06f, 70, true);
                }
            }
            default -> {
                if (particles.dustSpecks()) {
                    air(s, queue, ParticleKind.ASH, 0.6f * scale, 24.0f, 10.0f, ASH_COLOR, 0.05f, 0.02f, 180, false);
                    air(s, queue, ParticleKind.NETHER_EMBER, 0.3f * scale, 24.0f, 10.0f, NETHER_EMBER_COLOR, 0.04f, 0.015f, 120, false);
                }
            }
        }
    }

    private static void end(Surroundings s, SpawnQueue queue) {
        float scale = queue.rateScale() * s.ambientFill;
        air(s, queue, ParticleKind.END_MOTE, 0.9f * scale, 30.0f, 14.0f, END_MOTE_COLOR, 0.05f, 0.02f, 240, false);
        air(s, queue, ParticleKind.END_SPECK, 1.6f * scale, 18.0f, 8.0f, END_SPECK_COLOR, 0.03f, 0.01f, 160, false);
        RandomSource random = s.random;
        // Mist hangs low over the islands close by: Near's area and count at every distance.
        int mist = ParticleBudget.countFromRate(0.6f * queue.rateScale(), random.nextFloat());
        float mistAcross = 56.0f;
        for (int i = 0; i < mist && queue.room(ParticleCategory.END) > 0; i++) {
            int x = (int) Math.floor(s.cameraX + (random.nextFloat() - 0.5f) * mistAcross);
            int z = (int) Math.floor(s.cameraZ + (random.nextFloat() - 0.5f) * mistAcross);
            if (!s.level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int ground = s.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (ground <= s.level.getMinY() || Math.abs(ground - s.cameraY) > 18.0f) {
                continue;
            }
            double y = ground + 0.3 + random.nextFloat() * 1.4;
            queue.add(ParticleKind.END_MIST, x + random.nextFloat(), y, z + random.nextFloat(), 0.0f, 0.0f, 0.0f,
                1.8f + random.nextFloat() * 1.2f, 220 + random.nextInt(80), END_MIST_COLOR, s.lightAt(x, y, z), random.nextInt());
        }
    }

    /**
     * Spawns a kind in open air around the camera: within {@code across} blocks sideways (times the
     * Particle distance's reach, unless the kind keeps its Near area) and {@code high} up or down. With {@code onGround}, only just
     * above solid ground.
     */
    private static void air(Surroundings s, SpawnQueue queue, ParticleKind kind, float rate, float nearAcross, float high,
                            int color, float size, float sizeSpread, int lifetime, boolean onGround) {
        RandomSource random = s.random;
        float across = kind.fixedRange ? nearAcross : nearAcross * s.ambientReach;
        int count = ParticleBudget.countFromRate(rate, random.nextFloat());
        for (int i = 0; i < count && queue.room(kind.category) > 0; i++) {
            double x = s.cameraX + (random.nextFloat() - 0.5f) * across;
            double y = s.cameraY + (random.nextFloat() - 0.5f) * high;
            double z = s.cameraZ + (random.nextFloat() - 0.5f) * across;
            if (!s.level.getBlockState(s.cursor.set(x, y, z)).isAir()) {
                continue;
            }
            if (onGround) {
                if (s.level.getBlockState(s.cursor.set(x, y - 1.0, z)).isAir()) {
                    continue;
                }
                y = Math.floor(y) + 0.15;
            }
            int light = kind.emissive ? LightCoordsUtil.FULL_BRIGHT : s.lightAt(x, y, z);
            queue.add(kind, x, y, z, 0.0f, 0.0f, 0.0f, size + random.nextFloat() * sizeSpread,
                lifetime + random.nextInt(Math.max(1, lifetime / 2)), color, light, random.nextInt());
        }
    }

    /** Glowing drips form under the crimson canopy and fall. */
    private static void drips(Surroundings s, SpawnQueue queue, float rate) {
        RandomSource random = s.random;
        int count = ParticleBudget.countFromRate(rate, random.nextFloat());
        float across = 24.0f * s.ambientReach;
        for (int i = 0; i < count && queue.room(ParticleCategory.NETHER) > 0; i++) {
            double x = s.cameraX + (random.nextFloat() - 0.5f) * across;
            double y = s.cameraY + random.nextFloat() * 14.0f;
            double z = s.cameraZ + (random.nextFloat() - 0.5f) * across;
            if (!s.level.getBlockState(s.cursor.set(x, y, z)).isAir() || s.level.getBlockState(s.cursor.set(x, y + 1.0, z)).isAir()) {
                continue;
            }
            double dripY = Math.floor(y) + 0.9;
            queue.add(ParticleKind.CRIMSON_DRIP, Math.floor(x) + 0.2 + random.nextFloat() * 0.6, dripY,
                Math.floor(z) + 0.2 + random.nextFloat() * 0.6, 0.0f, 0.0f, 0.0f, 0.06f, 40 + random.nextInt(20),
                CRIMSON_DRIP_COLOR, LightCoordsUtil.FULL_BRIGHT, random.nextInt());
        }
    }
}
