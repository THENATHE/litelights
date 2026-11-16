package com.thenathe.litelights.render.feature.weather;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.feature.particles.ColumnGrid;
import com.thenathe.litelights.render.feature.particles.ParticleBudget;
import com.thenathe.litelights.render.feature.particles.ParticleCategory;
import com.thenathe.litelights.render.feature.particles.ParticleKind;
import com.thenathe.litelights.render.feature.particles.SpawnQueue;
import com.thenathe.litelights.render.feature.particles.Surroundings;
import com.thenathe.litelights.render.feature.water.GlassWindowGrid;
import com.thenathe.litelights.render.feature.water.WindowGlass;
import com.thenathe.litelights.render.material.MaterialClassifier;
import com.thenathe.litelights.render.material.MaterialIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Small things the rain does (drip and streak ideas by pigcart, Particle Rain; concepts only): water
 * dripping from wet eaves, ledges and leaves, which keeps on for a while after the rain as things
 * dry ({@link SurfaceWeather#wetness}), splashing where it lands, and short streaks running down hard
 * walls and glass in the rain (all the rain glass gets since 0.1.7). Where they may form is
 * {@link RainDetailRules}; this picks random columns all round the camera each tick (the same in every direction, so a quick turn finds them too), within
 * the Weather distance, and queues what it finds into the weather share of the particle budget.
 *
 * <p>They are LiteLights' own small particles beside the rain, not a replacement for it, so they
 * form whatever Rain and snow is set to, Vanilla included (decide16), as the rain mist does.
 */
final class RainDetails {
    /** Spawn area radius at Near, in blocks; small things far off can't be seen. */
    private static final float DRIP_RADIUS = 14.0f;
    private static final float STREAK_RADIUS = 16.0f;
    /** How far above and below the camera drips and streaks are looked for. */
    private static final int HEIGHT_REACH = 12;
    // Attempts per tick at full strength and the most that may form in one tick, at Near.
    // Most columns hold no eave or wall, so many are tried: a try costs a few array reads, and only
    // a column that might hold one reads blocks.
    private static final float DRIP_ATTEMPTS = 120.0f;
    private static final int MOST_DRIPS = 6;
    private static final float STREAK_ATTEMPTS = 160.0f;
    private static final int MOST_STREAKS = 10;
    /** Drips under trees are thinner than at eaves, where a whole roof's water gathers. */
    private static final float LEAF_DRIP_SHARE = 0.35f;
    private static final int DRIP_COLOR = 0xC8C4D2EC;
    private static final int STREAK_COLOR = 0x90C4D2EC;
    private static final Direction[] SIDES = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
    /** One view for every tick (client thread only). */
    private static final BlockView VIEW = new BlockView();

    private RainDetails() {
    }

    /** Called from {@link WeatherParticles#spawn} with the same rate scale the rain uses. */
    static void spawn(Surroundings s, SpawnQueue queue, ColumnGrid grid, float scale) {
        RenderSettings.Weather weather = s.settings.weather();
        SurfaceWeather surfaces = GlassWeather.get().surfaces();
        BlockView view = VIEW.use(s, grid);
        float thickness = weather.density().thickness;
        if (weather.rainDrips() && surfaces.wetness() > 0.02f) {
            drips(s, queue, grid, view, surfaces, scale * thickness);
        }
        if (weather.wallStreaks() && !WeatherCompat.wallStreaksHandledElsewhere() && surfaces.falling() > 0.05f) {
            streaks(s, queue, grid, view, surfaces, scale * thickness);
        }
    }

    private static void drips(Surroundings s, SpawnQueue queue, ColumnGrid grid, BlockView view, SurfaceWeather surfaces, float scale) {
        RandomSource random = s.random;
        // Strongest while it rains; afterwards they follow how wet things still are, so they taper off.
        float strength = surfaces.wetness() * (0.55f + 0.45f * surfaces.falling());
        int attempts = ParticleBudget.countFromRate(DRIP_ATTEMPTS * strength * scale, random.nextFloat());
        int most = Math.max(1, Math.round(MOST_DRIPS * scale));
        float radius = DRIP_RADIUS * s.weatherReach;
        int formed = 0;
        for (int i = 0; i < attempts && formed < most && queue.room(ParticleCategory.WEATHER) > 0; i++) {
            double x = s.cameraX + (random.nextFloat() * 2.0f - 1.0f) * radius;
            double z = s.cameraZ + (random.nextFloat() * 2.0f - 1.0f) * radius;
            if (WeatherParticles.previewedFall(grid.precipitationAt(x, z)) != ColumnGrid.PRECIPITATION_RAIN) {
                continue;
            }
            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            RainDetailRules.DripSpot spot = RainDetailRules.dripSpot(view, blockX, blockZ);
            if (spot == null || Math.abs(spot.underside() - s.cameraY) > HEIGHT_REACH
                || spot.leaves() && random.nextFloat() > LEAF_DRIP_SHARE) {
                continue;
            }
            // At an edge the drip hangs just inside the rim, toward the open side; under leaves anywhere.
            double dripX = blockX + edgeOffset(spot.towardX(), random);
            double dripZ = blockZ + edgeOffset(spot.towardZ(), random);
            double dripY = spot.underside() - 0.04;
            queue.add(ParticleKind.RAIN_DRIP, dripX, dripY, dripZ, 0.0f, 0.0f, 0.0f, 0.06f + random.nextFloat() * 0.015f,
                80, DRIP_COLOR, s.lightAt(dripX, dripY - 0.5, dripZ), random.nextInt());
            formed++;
        }
    }

    private static double edgeOffset(int toward, RandomSource random) {
        if (toward > 0) {
            return 0.82 + random.nextFloat() * 0.12;
        }
        if (toward < 0) {
            return 0.06 + random.nextFloat() * 0.12;
        }
        return 0.15 + random.nextFloat() * 0.7;
    }

    private static void streaks(Surroundings s, SpawnQueue queue, ColumnGrid grid, BlockView view, SurfaceWeather surfaces, float scale) {
        RandomSource random = s.random;
        int attempts = ParticleBudget.countFromRate(STREAK_ATTEMPTS * surfaces.falling() * scale, random.nextFloat());
        int most = Math.max(1, Math.round(MOST_STREAKS * scale));
        float radius = STREAK_RADIUS * s.weatherReach;
        int lowest = (int) Math.floor(s.cameraY) - HEIGHT_REACH;
        int highest = (int) Math.floor(s.cameraY) + HEIGHT_REACH;
        int formed = 0;
        for (int i = 0; i < attempts && formed < most && queue.room(ParticleCategory.WEATHER) > 0; i++) {
            double x = s.cameraX + (random.nextFloat() * 2.0f - 1.0f) * radius;
            double z = s.cameraZ + (random.nextFloat() * 2.0f - 1.0f) * radius;
            if (WeatherParticles.previewedFall(grid.precipitationAt(x, z)) != ColumnGrid.PRECIPITATION_RAIN) {
                continue;
            }
            int airX = (int) Math.floor(x);
            int airZ = (int) Math.floor(z);
            // Any side of the column may be a wall; start at a random one.
            int first = random.nextInt(SIDES.length);
            int[] range = null;
            int dx = 0;
            int dz = 0;
            for (int turn = 0; turn < SIDES.length && range == null; turn++) {
                Direction side = SIDES[(first + turn) % SIDES.length];
                dx = side.getStepX();
                dz = side.getStepZ();
                range = RainDetailRules.wallRange(view, airX, airZ, dx, dz, lowest, highest);
            }
            // The face looks back from the wall toward this column.
            if (range == null || random.nextFloat() > RainDetailRules.windShare(-dx, -dz, s.wind[0], s.wind[1])) {
                continue;
            }
            int y = range[0] + random.nextInt(range[1] - range[0] + 1);
            RainDetailRules.StreakFace face = RainDetailRules.streakFace(view, airX, airZ, dx, dz, y);
            if (face == null) {
                continue;
            }
            int ground = view.rainTop(airX, airZ);
            addStreak(s, queue, random, face, airX, y, airZ, dx, dz, ground);
            formed++;
            // Glass gets half as many again as a wall, inside the same budget.
            if (face.glass() && random.nextFloat() < RainDetailRules.GLASS_STREAK_SHARE - 1.0f && formed < most
                && queue.room(ParticleCategory.WEATHER) > 0) {
                addStreak(s, queue, random, face, airX, y, airZ, dx, dz, ground);
                formed++;
            }
        }
    }

    /**
     * A streak on the wall block at height y beside column (airX, airZ) along (dx, dz), sliding down
     * until it reaches the ground in front of the face or its time runs out.
     */
    private static void addStreak(Surroundings s, SpawnQueue queue, RandomSource random, RainDetailRules.StreakFace face,
            int airX, int y, int airZ, int dx, int dz, int ground) {
        // On the face's plane (a pane's lies inside its block), a hair out into the air so the
        // streak never cuts into the wall.
        double along = face.from() + 0.1 + random.nextFloat() * (face.to() - face.from() - 0.2);
        double out = face.inset() - 0.035;
        double faceX = dx == 0 ? airX + along : dx > 0 ? airX + 1.0 + out : airX - out;
        double faceZ = dz == 0 ? airZ + along : dz > 0 ? airZ + 1.0 + out : airZ - out;
        double startY = y + 0.25 + random.nextFloat() * 0.75;
        float speed = -(0.035f + random.nextFloat() * 0.03f);
        int toGround = (int) ((startY - ground) / -speed);
        int lifetime = Math.min(16 + random.nextInt(18), toGround);
        queue.add(ParticleKind.WALL_STREAK, faceX, startY, faceZ, 0.0f, speed, 0.0f, 0.028f, lifetime,
            STREAK_COLOR, s.lightAt(faceX, startY, faceZ), random.nextInt());
    }

    /** The rules' view of the world: the column grid's rain tops and the client's blocks. */
    private static final class BlockView implements RainDetailRules.World {
        private Surroundings s;
        private ColumnGrid grid;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        BlockView use(Surroundings surroundings, ColumnGrid columns) {
            s = surroundings;
            grid = columns;
            return this;
        }

        @Override
        public int rainTop(int x, int z) {
            double top = grid.groundHeight(x + 0.5, z + 0.5);
            return top == Double.NEGATIVE_INFINITY ? RainDetailRules.UNKNOWN : (int) top;
        }

        @Override
        public RainDetailRules.Block block(int x, int y, int z) {
            BlockState state = s.level.getBlockState(cursor.set(x, y, z));
            if (state.is(BlockTags.LEAVES)) {
                return RainDetailRules.Block.LEAVES;
            }
            boolean open = state.getFluidState().isEmpty() && state.getCollisionShape(s.level, cursor).isEmpty();
            return open ? RainDetailRules.Block.OPEN : RainDetailRules.Block.SOLID;
        }

        @Override
        public RainDetailRules.StreakFace streakFace(int x, int y, int z, int dx, int dz) {
            BlockState state = s.level.getBlockState(cursor.set(x, y, z));
            int glass = WindowGlass.kind(state);
            if (glass != 0) {
                // Weather Glass draws its own rain on glass.
                if (WeatherCompat.glassHandledElsewhere()) {
                    return null;
                }
                if ((glass & GlassWindowGrid.FULL) != 0) {
                    return RainDetailRules.StreakFace.GLASS_BLOCK;
                }
                return RainDetailRules.paneFace((glass & GlassWindowGrid.NORTH) != 0, (glass & GlassWindowGrid.EAST) != 0,
                    (glass & GlassWindowGrid.SOUTH) != 0, (glass & GlassWindowGrid.WEST) != 0, dx, dz);
            }
            if (!state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                return null;
            }
            int material = MaterialClassifier.forBlock(state);
            if (material != MaterialIds.DEFAULT && material != MaterialIds.METAL) {
                return null;
            }
            Direction face = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
            return state.isFaceSturdy(s.level, cursor, face) ? RainDetailRules.StreakFace.HARD_WALL : null;
        }
    }
}
