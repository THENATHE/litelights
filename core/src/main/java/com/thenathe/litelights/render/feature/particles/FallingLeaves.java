package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.mixin.particles.FallingParticlesLeavesBlockAccessor;
import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import com.thenathe.litelights.render.feature.vegetation.LeafColours;
import com.thenathe.litelights.render.frame.FrameClock;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.ParticleUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingParticlesLeavesBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Falling leaves on LiteLights' wind (Particles page, {@code particles.falling_leaves}).
 *
 * <p>The leaves are vanilla's own falling-leaf particles, so they look exactly like the ones vanilla
 * drops from oak, birch, cherry and the rest. Three things change while the row is on:
 * <ul>
 *   <li>leaf blocks that drop none of their own (vanilla's spruce, and modded leaves built on
 *   vanilla's plain leaves) drop tinted leaves in their own colour, as often as oak does;</li>
 *   <li>in wind and gusts more leaves let go, from every leaf block ({@link LeafWind#release});</li>
 *   <li>every falling leaf, vanilla's included, drifts with the wind ({@code FallingParticleMixin}):
 *   nearly straight down on a calm day, carried sideways in a storm's gusts, and cherry petals,
 *   which vanilla sends off one fixed way, follow the wind's heading instead.</li>
 * </ul>
 * The extra leaves follow LiteLights' particle amount (Sparse drops 40 % of them, Off none) and at
 * most {@link #MOST_EXTRA_PER_TICK} are added a tick, so a storm over a cherry grove can't flood
 * vanilla's particle engine. With Falling Leaves (the mod) installed, its leaves are left to it.
 *
 * <p>Client thread only: the client tick sets the wind, then vanilla's block animation and particle
 * ticks call in through the mixins.
 */
public final class FallingLeaves {
    public static final String FEATURE = "particles.falling_leaves";
    private static final String FALLING_LEAVES_MOD = "fallingleaves";
    private static final String FALLING_LEAVES_NAME = "Falling Leaves";
    /** How often leaves without falling leaves of their own drop one: oak's chance per animation tick. */
    static final float OWN_LEAF_CHANCE = 0.01f;
    /** LiteLights adds at most this many leaves a tick at Full. */
    static final int MOST_EXTRA_PER_TICK = 4;

    private static final boolean HANDLED_ELSEWHERE = FabricLoader.getInstance().isModLoaded(FALLING_LEAVES_MOD);
    /** Whether a leaves class animates with LeavesBlock's own animateTick, and so drops no falling leaves of its own. */
    private static final ClassValue<Boolean> PLAIN_LEAVES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("animateTick", BlockState.class, Level.class, BlockPos.class, RandomSource.class)
                    .getDeclaringClass() == LeavesBlock.class;
            } catch (NoSuchMethodException unexpected) {
                return false;
            }
        }
    };

    private static final RandomSource RANDOM = RandomSource.create();
    private static final BlockPos.MutableBlockPos BELOW = new BlockPos.MutableBlockPos();
    /** steer's answer, reused: particles tick one at a time on the client thread. */
    private static final double[] STEERED = new double[3];

    private static boolean active;
    private static float windX;
    private static float windZ;
    private static float strength;
    /** The wind's heading less vanilla's middle cherry flow direction (30 degrees), as a rotation. */
    private static double flowCos = 1.0;
    private static double flowSin;
    private static float amountScale;
    private static int extraLeft;
    /** QA pictures: the leaves' wind strength (0 calm to 1 the strongest storm) in place of the world's, or negative for the world's. */
    private static volatile float strengthForQa = -1.0f;

    // QA readings: leaves added and particles steered since the last reset, and the time spent.
    private static long leavesAdded;
    private static long leavesSteered;
    private static long nanos;
    private static double carriedBlocks;

    private FallingLeaves() {
    }

    /** Called once from ParticleSystem.register. */
    static void register() {
        if (HANDLED_ELSEWHERE) {
            Feature.of(FEATURE).markHandledBy(FALLING_LEAVES_NAME);
        }
    }

    /** Each client tick, after the surroundings are worked out: this tick's wind and settings. */
    static void update(Surroundings surroundings, RenderSettings.ParticleDensity amount) {
        active = !HANDLED_ELSEWHERE && amount != RenderSettings.ParticleDensity.OFF && surroundings.effectsAllowed
            && surroundings.settings.particles().fallingLeaves() && RenderFeatureStatus.mayRun(RenderFeature.PARTICLES);
        if (!active) {
            return;
        }
        windX = surroundings.wind[0];
        windZ = surroundings.wind[1];
        float forced = strengthForQa;
        if (forced >= 0.0f) {
            float speed = (float) Math.sqrt(windX * windX + windZ * windZ);
            float wanted = LeafWind.CALM + forced * (LeafWind.STORM - LeafWind.CALM);
            windX *= wanted / Math.max(speed, 1.0e-6f);
            windZ *= wanted / Math.max(speed, 1.0e-6f);
        }
        strength = LeafWind.strength(windX, windZ);
        double turn = Math.atan2(windZ, windX) - Math.toRadians(30.0);
        flowCos = Math.cos(turn);
        flowSin = Math.sin(turn);
        amountScale = ParticleBudget.spawnScale(amount);
        extraLeft = Math.round(MOST_EXTRA_PER_TICK * amountScale);
    }

    /** Nothing this stream draws is on, or there is no world: vanilla's leaves are left alone. */
    static void stop() {
        active = false;
    }

    /**
     * The end of a leaf block's animation tick (LeavesBlockMixin), which vanilla calls for random
     * blocks near the player: may drop one more falling leaf.
     */
    public static void afterLeavesTick(LeavesBlock block, BlockState state, Level level, BlockPos pos) {
        if (!active || extraLeft <= 0) {
            return;
        }
        long started = System.nanoTime();
        float gust = LeafWind.gust(pos.getX() + 0.5, pos.getZ() + 0.5, FrameClock.animationSeconds());
        float release = LeafWind.release(strength, gust);
        float chance;
        if (block instanceof FallingParticlesLeavesBlock dropping) {
            // Vanilla already dropped its share; only the wind's extra is added.
            chance = ((FallingParticlesLeavesBlockAccessor) dropping).litelights$leafParticleChance() * (release - 1.0f);
        } else if (PLAIN_LEAVES.get(block.getClass())) {
            chance = OWN_LEAF_CHANCE * release;
        } else {
            chance = 0.0f;
        }
        if (RANDOM.nextFloat() < chance * amountScale && openBelow(level, pos)) {
            extraLeft--;
            leavesAdded++;
            if (block instanceof FallingParticlesLeavesBlock dropping) {
                ((FallingParticlesLeavesBlockAccessor) dropping).litelights$spawnFallingLeavesParticle(level, pos, RANDOM);
            } else {
                ColorParticleOption leaf = ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, ownLeafColour(state, level, pos));
                ParticleUtils.spawnParticleBelow(level, pos, RANDOM, leaf);
            }
        }
        nanos += System.nanoTime() - started;
    }

    /**
     * For QA pictures: blow the falling leaves' wind at this strength (0 calm to 1 the strongest
     * storm) whatever the weather, so a windy day can be pictured without the storm's rain and fog;
     * negative gives the world's wind back.
     */
    public static void setStrengthForQa(float strength) {
        strengthForQa = strength;
    }

    /** Whether falling leaves are steered by the wind this tick. */
    public static boolean steering() {
        return active;
    }

    /**
     * Turns a falling leaf's move for this tick into one on the wind, returned as x, y, z in an
     * array that the next call reuses. {@code flowsAway} is vanilla's cherry petals' fixed drift, which turns to the wind's
     * heading; the wind then carries the leaf by its share of the wind at the leaf's place, once it
     * has dropped clear of its canopy ({@code ageTicks}, see {@link LeafWind#lettingGo}).
     */
    public static double[] steer(double x, double z, double moveX, double moveY, double moveZ, boolean flowsAway, int ageTicks) {
        long started = System.nanoTime();
        if (flowsAway) {
            double turnedX = moveX * flowCos - moveZ * flowSin;
            moveZ = moveX * flowSin + moveZ * flowCos;
            moveX = turnedX;
        }
        float gust = LeafWind.gust(x, z, FrameClock.animationSeconds());
        float carry = LeafWind.carry(strength, gust) * LeafWind.lettingGo(ageTicks);
        STEERED[0] = moveX + windX * carry;
        STEERED[1] = moveY + LeafWind.lift(strength, gust, moveY) * LeafWind.lettingGo(ageTicks);
        STEERED[2] = moveZ + windZ * carry;
        leavesSteered++;
        carriedBlocks += Math.sqrt(windX * windX + windZ * windZ) * carry;
        nanos += System.nanoTime() - started;
        return STEERED;
    }

    /** The colour a leaf block without falling leaves of its own drops them in: its tint, or its texture's colour if it has none. */
    private static int ownLeafColour(BlockState state, Level level, BlockPos pos) {
        int tint = level.getClientLeafTintColor(pos);
        if (tint != -1 || !(level instanceof ClientLevel clientLevel)) {
            return tint;
        }
        return LeafColours.leaves(state, clientLevel, pos);
    }

    /** Vanilla's rule: a leaf only falls if the block below doesn't close the leaves' underside. */
    private static boolean openBelow(Level level, BlockPos pos) {
        BELOW.setWithOffset(pos, Direction.DOWN);
        BlockState below = level.getBlockState(BELOW);
        return !Block.isFaceFull(below.getCollisionShape(level, BELOW), Direction.UP);
    }

    /**
     * QA: leaves added, leaf moves steered, the milliseconds spent and the blocks the wind carried
     * the leaves on average per move, since the last call, which resets them.
     */
    public static double[] takeReadings() {
        double[] readings = {leavesAdded, leavesSteered, nanos / 1.0e6, leavesSteered == 0 ? 0.0 : carriedBlocks / leavesSteered};
        leavesAdded = 0L;
        leavesSteered = 0L;
        nanos = 0L;
        carriedBlocks = 0.0;
        return readings;
    }
}
