package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings.ParticleDensity;
import java.util.Arrays;

/**
 * New particles asked for during one tick, handed to the simulation with the next step. Spawners
 * call {@link #room} first so the cap holds before anything is queued; {@link #add} refuses once
 * the queue or the cap is full.
 */
public final class SpawnQueue {
    /** Dense rain in a thunderstorm asks for about a thousand drops a tick; Fill at a distance asks more. */
    static final int BASE_CAPACITY = 4096;

    int count;
    private int capacity;
    double[] x;
    double[] y;
    double[] z;
    float[] velocityX;
    float[] velocityY;
    float[] velocityZ;
    float[] size;
    int[] lifetime;
    int[] color;
    int[] light;
    int[] seed;
    ParticleKind[] kind;
    private final int[] queuedPerCategory = new int[ParticleCategory.values().length];

    private ParticleDensity amount = ParticleDensity.OFF;
    private final float[] capScales = ParticleBudget.unscaled();
    // The caps for this tick, worked out once in begin: room() is asked for every spawn attempt.
    private final int[] categoryCaps = new int[ParticleCategory.values().length];
    private int totalCap;
    private int[] alivePerCategory = new int[ParticleCategory.values().length];
    private int aliveTotal;

    public SpawnQueue() {
        allocate(BASE_CAPACITY);
    }

    /**
     * Makes room for {@code wanted} spawns a tick (never below the base capacity). Only between
     * ticks: whatever is queued is dropped.
     */
    void ensureCapacity(int wanted) {
        int target = Math.max(BASE_CAPACITY, wanted);
        if (target != capacity) {
            allocate(target);
        }
    }

    int capacity() {
        return capacity;
    }

    private void allocate(int newCapacity) {
        capacity = newCapacity;
        count = 0;
        x = new double[newCapacity];
        y = new double[newCapacity];
        z = new double[newCapacity];
        velocityX = new float[newCapacity];
        velocityY = new float[newCapacity];
        velocityZ = new float[newCapacity];
        size = new float[newCapacity];
        lifetime = new int[newCapacity];
        color = new int[newCapacity];
        light = new int[newCapacity];
        seed = new int[newCapacity];
        kind = new ParticleKind[newCapacity];
    }

    /** Starts a tick's spawning against the live counts of the published state, every category at its base share. */
    void begin(ParticleDensity currentAmount, int[] alivePerCategoryNow, int aliveTotalNow) {
        Arrays.fill(capScales, 1.0f);
        start(currentAmount, alivePerCategoryNow, aliveTotalNow);
    }

    /** As above, with each category's share of the cap multiplied by its scale (by ordinal; see {@link ParticleBudget}). */
    void begin(ParticleDensity currentAmount, float[] currentCapScales, int[] alivePerCategoryNow, int aliveTotalNow) {
        System.arraycopy(currentCapScales, 0, capScales, 0, capScales.length);
        start(currentAmount, alivePerCategoryNow, aliveTotalNow);
    }

    private void start(ParticleDensity currentAmount, int[] alivePerCategoryNow, int aliveTotalNow) {
        count = 0;
        Arrays.fill(queuedPerCategory, 0);
        amount = currentAmount;
        alivePerCategory = alivePerCategoryNow;
        aliveTotal = aliveTotalNow;
        totalCap = ParticleBudget.totalCap(amount, capScales);
        for (ParticleCategory category : ParticleCategory.values()) {
            categoryCaps[category.ordinal()] = ParticleBudget.categoryCap(amount, category, capScales);
        }
    }

    /** How many more of this category may be queued this tick. */
    public int room(ParticleCategory category) {
        return Math.min(roomInCap(category), capacity - count);
    }

    /**
     * How many more of this category the cap allows, leaving out how full this tick's queue is:
     * for spawners that keep part of the cap free for others.
     */
    public int roomInCap(ParticleCategory category) {
        int ordinal = category.ordinal();
        int categoryRoom = categoryCaps[ordinal] - alivePerCategory[ordinal] - queuedPerCategory[ordinal];
        int totalRoom = totalCap - aliveTotal - count;
        return Math.max(0, Math.min(categoryRoom, totalRoom));
    }

    /** The most this category may hold at the current amount. */
    public int categoryCap(ParticleCategory category) {
        return categoryCaps[category.ordinal()];
    }

    /** Spawn rates are multiplied by this (Full 1, Sparse less, Off 0). */
    public float rateScale() {
        return ParticleBudget.spawnScale(amount);
    }

    /** Fireflies' spawn rates are multiplied by this: they follow the amount less steeply. */
    public float fireflyRateScale() {
        return ParticleBudget.fireflySpawnScale(amount);
    }

    public boolean add(ParticleKind particleKind, double posX, double posY, double posZ,
                       float speedX, float speedY, float speedZ, float particleSize, int particleLifetime,
                       int particleColor, int particleLight, int particleSeed) {
        if (room(particleKind.category) <= 0) {
            return false;
        }
        int index = count++;
        kind[index] = particleKind;
        x[index] = posX;
        y[index] = posY;
        z[index] = posZ;
        velocityX[index] = speedX;
        velocityY[index] = speedY;
        velocityZ[index] = speedZ;
        size[index] = particleSize;
        lifetime[index] = particleLifetime;
        color[index] = particleColor;
        light[index] = particleLight;
        seed[index] = particleSeed;
        queuedPerCategory[particleKind.category.ordinal()]++;
        return true;
    }
}
