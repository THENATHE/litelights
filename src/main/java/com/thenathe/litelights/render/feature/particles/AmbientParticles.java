package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.frame.FrameFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Life in the air of the overworld (and of modded dimensions with effects on): fireflies over
 * grass and water on warm nights, dust specks where light falls, pollen over flowers by day, and
 * spores in forests, by mushrooms and in caves (glowing ones in lush caves).
 *
 * <p>Each kind picks a few random spots around the camera every tick, whatever way it faces, and
 * spawns only where its place fits, like vanilla's own ambient particles; with the rates below that
 * keeps a steady, modest number of each alive, and a quick turn finds the air behind as full as
 * the view. The Particle distance widens every area by its reach; with Fill the rates grow with the
 * area, except fireflies', which keep their count at every distance.
 */
final class AmbientParticles {
    private static final int FIREFLY_COLOR = 0xFFD6FF66;
    private static final int DUST_COLOR = 0xFFF2EAD6;
    private static final int POLLEN_COLOR = 0xE6FFE07A;
    private static final int SPORE_COLOR = 0xB4E4E6CC;
    private static final int PALE_SPORE_COLOR = 0xC0DCDCDA;
    private static final int JUNGLE_SPORE_COLOR = 0xB4CCEAA2;
    private static final int MUSHROOM_SPORE_COLOR = 0xC8E6D2E0;
    private static final int CAVE_SPORE_COLOR = 0x96C8CABE;
    private static final int LUSH_SPORE_COLOR = 0xFFA4EC64;
    /**
     * Firefly spawn attempts a tick at full night. Halved in 0.1.5 (from 2): a meadow at Full
     * swarmed. Fireflies also follow the amount less steeply than the others (ParticleBudget).
     */
    private static final float FIREFLY_RATE = 1.0f;

    private AmbientParticles() {
    }

    static void spawn(Surroundings s, SpawnQueue queue) {
        if (!s.effectsAllowed || s.cameraInWater || s.dimensionKind == FrameFlags.DIMENSION_NETHER
            || s.dimensionKind == FrameFlags.DIMENSION_END) {
            return;
        }
        RenderSettings.Particles particles = s.settings.particles();
        float scale = queue.rateScale() * s.ambientFill;
        if (particles.fireflySwarms()) {
            fireflies(s, queue, queue.fireflyRateScale());
        }
        if (particles.dustSpecks()) {
            dust(s, queue, scale);
        }
        if (particles.pollen()) {
            pollen(s, queue, scale);
        }
        if (particles.spores()) {
            spores(s, queue, scale);
        }
    }

    private static boolean fireflyClimate(BiomeClimate climate) {
        return climate == BiomeClimate.TEMPERATE || climate == BiomeClimate.FOREST || climate == BiomeClimate.JUNGLE
            || climate == BiomeClimate.SWAMP || climate == BiomeClimate.SAVANNA || climate == BiomeClimate.OCEAN
            || climate == BiomeClimate.MUSHROOM;
    }

    private static void fireflies(Surroundings s, SpawnQueue queue, float scale) {
        if (s.daylight > 0.3f || s.rain > 0.3f || s.underground || !fireflyClimate(s.climate)) {
            return;
        }
        // Dusk brings the first few; full night the most.
        float night = Math.min(1.0f, (0.3f - s.daylight) / 0.2f);
        RandomSource random = s.random;
        float across = 36.0f * s.ambientReach;
        int attempts = ParticleBudget.countFromRate(FIREFLY_RATE * night * scale, random.nextFloat());
        for (int i = 0; i < attempts && queue.room(ParticleCategory.AMBIENT) > 0; i++) {
            int x = (int) Math.floor(s.cameraX + (random.nextFloat() - 0.5f) * across);
            int z = (int) Math.floor(s.cameraZ + (random.nextFloat() - 0.5f) * across);
            if (!s.level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int top = s.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(top - s.cameraY) > 14.0f * s.ambientReach) {
                continue;
            }
            BlockPos.MutableBlockPos cursor = s.cursor.set(x, top - 1, z);
            BlockState below = s.level.getBlockState(cursor);
            boolean grassy = below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.MOSS_BLOCK) || below.is(Blocks.MUD);
            boolean water = s.level.getFluidState(cursor).is(FluidTags.WATER);
            if (!grassy && !water) {
                continue;
            }
            double y = top + 0.3 + random.nextFloat() * 1.8;
            queue.add(ParticleKind.FIREFLY, x + random.nextFloat(), y, z + random.nextFloat(), 0.0f, 0.0f, 0.0f,
                0.12f, 120 + random.nextInt(120), FIREFLY_COLOR, LightCoordsUtil.FULL_BRIGHT, random.nextInt());
        }
    }

    /** Specks catch the light: they show where blocks or the sun light the air, and stay out of the dark. */
    private static void dust(Surroundings s, SpawnQueue queue, float scale) {
        RandomSource random = s.random;
        int attempts = ParticleBudget.countFromRate(3.0f * scale, random.nextFloat());
        float across = 18.0f * s.ambientReach;
        for (int i = 0; i < attempts && queue.room(ParticleCategory.AMBIENT) > 0; i++) {
            double x = s.cameraX + (random.nextFloat() - 0.5f) * across;
            double y = s.cameraY + (random.nextFloat() - 0.4f) * 8.0f;
            double z = s.cameraZ + (random.nextFloat() - 0.5f) * across;
            BlockPos.MutableBlockPos cursor = s.cursor.set(x, y, z);
            if (!s.level.getBlockState(cursor).isAir()) {
                continue;
            }
            int light = LightCoordsUtil.getLightCoords(s.level, cursor);
            int blockLight = LightCoordsUtil.block(light);
            int skyLight = LightCoordsUtil.sky(light);
            // Sunlit specks need sunbeams: overcast rain leaves only the ones a lamp lights.
            float brightness = Math.max(blockLight / 15.0f, skyLight / 15.0f * s.daylight * (1.0f - s.rain));
            if (brightness < 0.45f) {
                continue;
            }
            // Indoors, by a window or a torch, they read best; out in the open they thin out.
            if (skyLight == 15 && blockLight < 8 && random.nextFloat() < 0.7f) {
                continue;
            }
            int alpha = (int) (110 + 120 * brightness);
            queue.add(ParticleKind.DUST_SPECK, x, y, z, 0.0f, 0.0f, 0.0f, 0.06f, 120 + random.nextInt(120),
                (alpha << 24) | (DUST_COLOR & 0x00FFFFFF), light, random.nextInt());
        }
    }

    private static void pollen(Surroundings s, SpawnQueue queue, float scale) {
        if (s.daylight < 0.4f || s.rain > 0.3f || s.underground) {
            return;
        }
        RandomSource random = s.random;
        int attempts = ParticleBudget.countFromRate(8.0f * scale, random.nextFloat());
        float across = 28.0f * s.ambientReach;
        for (int i = 0; i < attempts && queue.room(ParticleCategory.AMBIENT) > 0; i++) {
            int x = (int) Math.floor(s.cameraX + (random.nextFloat() - 0.5f) * across);
            int z = (int) Math.floor(s.cameraZ + (random.nextFloat() - 0.5f) * across);
            if (!s.level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int top = s.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(top - s.cameraY) > 12.0f * s.ambientReach || !s.level.getBlockState(s.cursor.set(x, top, z)).is(BlockTags.FLOWERS)) {
                continue;
            }
            double y = top + 0.5 + random.nextFloat() * 0.8;
            queue.add(ParticleKind.POLLEN, x + 0.2 + random.nextFloat() * 0.6, y, z + 0.2 + random.nextFloat() * 0.6,
                0.0f, 0.004f, 0.0f, 0.04f, 100 + random.nextInt(80), POLLEN_COLOR, s.lightAt(x, y, z), random.nextInt());
        }
    }

    private static void spores(Surroundings s, SpawnQueue queue, float scale) {
        RandomSource random = s.random;
        boolean cave = s.underground;
        float rate;
        int color;
        ParticleKind kind = ParticleKind.SPORE;
        if (s.climate == BiomeClimate.LUSH_CAVES) {
            rate = 1.0f;
            color = LUSH_SPORE_COLOR;
            kind = ParticleKind.GLOW_SPORE;
        } else if (cave) {
            rate = 0.6f;
            color = CAVE_SPORE_COLOR;
        } else if (s.climate == BiomeClimate.MUSHROOM) {
            rate = 1.4f;
            color = MUSHROOM_SPORE_COLOR;
        } else if (s.climate == BiomeClimate.FOREST || s.climate == BiomeClimate.PALE_GARDEN || s.climate == BiomeClimate.JUNGLE
            || s.climate == BiomeClimate.SWAMP) {
            rate = 0.9f;
            color = s.climate == BiomeClimate.PALE_GARDEN ? PALE_SPORE_COLOR
                : s.climate == BiomeClimate.JUNGLE ? JUNGLE_SPORE_COLOR : SPORE_COLOR;
        } else {
            // Elsewhere spores only rise near mushrooms.
            mushroomSpores(s, queue, scale);
            return;
        }
        int attempts = ParticleBudget.countFromRate(rate * scale, random.nextFloat());
        float across = 26.0f * s.ambientReach;
        for (int i = 0; i < attempts && queue.room(ParticleCategory.AMBIENT) > 0; i++) {
            double x = s.cameraX + (random.nextFloat() - 0.5f) * across;
            double y = s.cameraY + (random.nextFloat() - 0.3f) * 12.0f;
            double z = s.cameraZ + (random.nextFloat() - 0.5f) * across;
            if (!s.level.getBlockState(s.cursor.set(x, y, z)).isAir()) {
                continue;
            }
            queue.add(kind, x, y, z, 0.0f, 0.0f, 0.0f, 0.045f, 200 + random.nextInt(120), color, s.lightAt(x, y, z), random.nextInt());
        }
    }

    private static void mushroomSpores(Surroundings s, SpawnQueue queue, float scale) {
        RandomSource random = s.random;
        int attempts = ParticleBudget.countFromRate(10.0f * scale, random.nextFloat());
        float across = 20.0f * s.ambientReach;
        for (int i = 0; i < attempts && queue.room(ParticleCategory.AMBIENT) > 0; i++) {
            int x = (int) Math.floor(s.cameraX + (random.nextFloat() - 0.5f) * across);
            int y = (int) Math.floor(s.cameraY + (random.nextFloat() - 0.5f) * 10.0f);
            int z = (int) Math.floor(s.cameraZ + (random.nextFloat() - 0.5f) * across);
            BlockState state = s.level.getBlockState(s.cursor.set(x, y, z));
            if (!state.is(Blocks.BROWN_MUSHROOM) && !state.is(Blocks.RED_MUSHROOM)) {
                continue;
            }
            double spawnY = y + 0.6;
            queue.add(ParticleKind.SPORE, x + 0.5, spawnY, z + 0.5, 0.0f, 0.006f, 0.0f, 0.04f, 160 + random.nextInt(80),
                MUSHROOM_SPORE_COLOR, s.lightAt(x, spawnY, z), random.nextInt());
        }
    }
}
