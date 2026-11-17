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
                    ashHaze(s, queue, 0.3f * hazeScale);
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
        float mistRate = 0.6f * queue.rateScale();
        float mistAcross = 56.0f;
        if (HazeField.wanted(s.settings)) {
            offerEndMist(s, mistRate, mistAcross);
            return;
        }
        boolean clumped = s.settings.weather().mistStyle() == RenderSettings.MistStyle.PUFFS;
        int mist = ParticleBudget.countFromRate(clumped ? mistRate * HazeClumps.RATE_SCALE : mistRate, random.nextFloat());
        // Moving fast, the square leads the camera and a share of the mist goes ahead; high over an
        // island it widens (HazeLead), so flying over the islands keeps mist below.
        HazeLead.lead(s, LEAD);
        float widened = mistAcross + 2.0f * HazeLead.widening(s.cameraY - endGround(s));
        for (int i = 0; i < mist && queue.room(ParticleCategory.END) > 0; i++) {
            double offsetX = (random.nextFloat() - 0.5f) * widened;
            double offsetZ = (random.nextFloat() - 0.5f) * widened;
            if (HazeLead.speed(s) > 0.0f) {
                // The wedge ahead: the same square's spots, turned toward the travel for a share of them.
                float angle = HazeLead.angle(s, random);
                double reach = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
                offsetX = Math.cos(angle) * reach;
                offsetZ = Math.sin(angle) * reach;
            }
            int x = (int) Math.floor(s.cameraX + LEAD[0] + offsetX);
            int z = (int) Math.floor(s.cameraZ + LEAD[1] + offsetZ);
            if (!s.level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int ground = s.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (ground <= s.level.getMinY()) {
                continue;
            }
            if (clumped && !HazeClumps.keep(x, z, s.hazeDriftX, s.hazeDriftZ, random.nextFloat())) {
                continue;
            }
            double y = ground + 0.3 + random.nextFloat() * 1.4;
            queue.add(ParticleKind.END_MIST, x + random.nextFloat(), y, z + random.nextFloat(), 0.0f, 0.0f, 0.0f,
                1.8f + random.nextFloat() * 1.2f, 220 + random.nextInt(80), END_MIST_COLOR, s.lightAt(x, y, z), random.nextInt());
        }
    }

    private static final double[] LEAD = new double[2];

    /** The top of the island under the camera, or the camera's own height over the void. */
    private static double endGround(Surroundings s) {
        int x = (int) Math.floor(s.cameraX);
        int z = (int) Math.floor(s.cameraZ);
        if (!s.level.hasChunk(x >> 4, z >> 4)) {
            return s.cameraY;
        }
        int ground = s.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return ground <= s.level.getMinY() ? s.cameraY : ground;
    }

    /** Ash haze puffs' size on average over their life (2.4 to 3.9 blocks, growing by 1.4), and their lifetime (220 to 330 ticks). */
    private static final float ASH_HAZE_SIZE = 3.15f * 1.2f;
    private static final int ASH_HAZE_LIFETIME = 275;
    /** End mist's size on average over its life (1.8 to 3 blocks, growing by 1.3), and its lifetime (220 to 300 ticks). */
    private static final float END_MIST_SIZE = 2.4f * 1.15f;
    private static final int END_MIST_LIFETIME = 260;

    /** Basalt deltas' ash haze: puffs in the open air around the camera, or the Mist field's share of them. */
    private static void ashHaze(Surroundings s, SpawnQueue queue, float rate) {
        if (HazeField.wanted(s.settings)) {
            // The puffs fill a box 30 blocks across and 10 high around the camera, each reaching about a third of its size past it.
            float layer = 10.0f + ASH_HAZE_SIZE * 0.6f;
            float extinction = HazeField.extinction(rate, ASH_HAZE_LIFETIME, (HAZE_COLOR >>> 24) / 255.0f, ASH_HAZE_SIZE, 30.0f * 30.0f, layer);
            HazeField.get().offer(ParticleKind.Haze.ASH, extinction, HAZE_COLOR, -0.5f * layer, 0.5f * layer,
                s.lightAt(s.cameraX, s.cameraY, s.cameraZ));
            return;
        }
        boolean clumped = s.settings.weather().mistStyle() == RenderSettings.MistStyle.PUFFS;
        air(s, queue, ParticleKind.ASH_HAZE, clumped ? rate * HazeClumps.RATE_SCALE : rate, 30.0f, 10.0f, HAZE_COLOR, 2.4f, 1.5f, 220, false,
            clumped);
    }

    /**
     * The Mist field's share of the End's low mist. It lies on the islands wherever they are, and
     * the void between them has none (the field's map of the land), so it is offered over the void too.
     */
    private static void offerEndMist(Surroundings s, float rate, float across) {
        // Puff centres 0.3 to 1.7 blocks up, each reaching about a quarter of its size above that.
        float top = 1.7f + END_MIST_SIZE * 0.25f;
        float extinction = HazeField.extinction(rate, END_MIST_LIFETIME, (END_MIST_COLOR >>> 24) / 255.0f, END_MIST_SIZE, across * across, top);
        double ground = endGround(s);
        HazeField.get().offer(ParticleKind.Haze.END, extinction, END_MIST_COLOR, 0.0f, top, s.lightAt(s.cameraX, Math.min(ground + 1.0, s.cameraY), s.cameraZ));
    }

    /**
     * Spawns a kind in open air around the camera: within {@code across} blocks sideways (times the
     * Particle distance's reach, unless the kind keeps its Near area) and {@code high} up or down. With {@code onGround}, only just
     * above solid ground.
     */
    private static void air(Surroundings s, SpawnQueue queue, ParticleKind kind, float rate, float nearAcross, float high,
                            int color, float size, float sizeSpread, int lifetime, boolean onGround) {
        air(s, queue, kind, rate, nearAcross, high, color, size, sizeSpread, lifetime, onGround, false);
    }

    /** As above; {@code clumped} keeps a share of the puffs by where they gather (HazeClumps). */
    private static void air(Surroundings s, SpawnQueue queue, ParticleKind kind, float rate, float nearAcross, float high,
                            int color, float size, float sizeSpread, int lifetime, boolean onGround, boolean clumped) {
        RandomSource random = s.random;
        float across = kind.fixedRange ? nearAcross : nearAcross * s.ambientReach;
        int count = ParticleBudget.countFromRate(rate, random.nextFloat());
        // Haze leads a fast camera (HazeLead): the box runs ahead along the travel.
        if (kind.haze != ParticleKind.Haze.NONE) {
            HazeLead.lead(s, LEAD);
        } else {
            LEAD[0] = 0.0;
            LEAD[1] = 0.0;
        }
        for (int i = 0; i < count && queue.room(kind.category) > 0; i++) {
            double x = s.cameraX + LEAD[0] + (random.nextFloat() - 0.5f) * across;
            double y = s.cameraY + (random.nextFloat() - 0.5f) * high;
            double z = s.cameraZ + LEAD[1] + (random.nextFloat() - 0.5f) * across;
            if (!s.level.getBlockState(s.cursor.set(x, y, z)).isAir()) {
                continue;
            }
            if (clumped && !HazeClumps.keep(x, z, s.hazeDriftX, s.hazeDriftZ, random.nextFloat())) {
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
