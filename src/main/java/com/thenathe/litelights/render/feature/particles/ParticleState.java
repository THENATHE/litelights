package com.thenathe.litelights.render.feature.particles;

import java.util.Arrays;

/**
 * Every live particle at one tick, one array per field so a step walks memory in order and nothing
 * is allocated per particle. Each particle keeps its position at this tick and the tick before, so
 * frames between ticks can interpolate.
 *
 * <p>Two of these take turns: the simulation reads one (published, also read by the renderer) and
 * writes the next.
 */
final class ParticleState {
    final int capacity;
    int count;

    final double[] x;
    final double[] y;
    final double[] z;
    final double[] previousX;
    final double[] previousY;
    final double[] previousZ;
    final float[] velocityX;
    final float[] velocityY;
    final float[] velocityZ;
    final float[] size;
    final int[] age;
    final int[] lifetime;
    final int[] seed;
    /** ARGB; alpha is the particle's full opacity before fading. */
    final int[] color;
    /** Packed block and sky light, as vanilla's light coordinates. */
    final int[] light;
    final byte[] kind;
    /** Live particles per {@link ParticleCategory}, by ordinal. */
    final int[] categoryCounts = new int[ParticleCategory.values().length];
    /** The particles frames may draw, picked by the worker right after the step that wrote this state. */
    final CullList drawable;

    ParticleState(int capacity) {
        this.capacity = capacity;
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
        previousX = new double[capacity];
        previousY = new double[capacity];
        previousZ = new double[capacity];
        velocityX = new float[capacity];
        velocityY = new float[capacity];
        velocityZ = new float[capacity];
        size = new float[capacity];
        age = new int[capacity];
        lifetime = new int[capacity];
        seed = new int[capacity];
        color = new int[capacity];
        light = new int[capacity];
        kind = new byte[capacity];
        drawable = new CullList(capacity);
    }

    /**
     * A state of another capacity holding this one's particles, as many as fit, with no cull list
     * yet. For when the settings change how many may be alive; never while a step runs.
     */
    ParticleState resized(int newCapacity) {
        ParticleState copy = new ParticleState(newCapacity);
        int kept = Math.min(count, newCapacity);
        System.arraycopy(x, 0, copy.x, 0, kept);
        System.arraycopy(y, 0, copy.y, 0, kept);
        System.arraycopy(z, 0, copy.z, 0, kept);
        System.arraycopy(previousX, 0, copy.previousX, 0, kept);
        System.arraycopy(previousY, 0, copy.previousY, 0, kept);
        System.arraycopy(previousZ, 0, copy.previousZ, 0, kept);
        System.arraycopy(velocityX, 0, copy.velocityX, 0, kept);
        System.arraycopy(velocityY, 0, copy.velocityY, 0, kept);
        System.arraycopy(velocityZ, 0, copy.velocityZ, 0, kept);
        System.arraycopy(size, 0, copy.size, 0, kept);
        System.arraycopy(age, 0, copy.age, 0, kept);
        System.arraycopy(lifetime, 0, copy.lifetime, 0, kept);
        System.arraycopy(seed, 0, copy.seed, 0, kept);
        System.arraycopy(color, 0, copy.color, 0, kept);
        System.arraycopy(light, 0, copy.light, 0, kept);
        System.arraycopy(kind, 0, copy.kind, 0, kept);
        copy.count = kept;
        for (int i = 0; i < kept; i++) {
            copy.categoryCounts[ParticleKind.byId(kind[i]).category.ordinal()]++;
        }
        return copy;
    }

    void clear() {
        count = 0;
        Arrays.fill(categoryCounts, 0);
        drawable.valid = false;
    }

    /** Adds a particle that has just appeared: no motion to interpolate yet. Returns its index. */
    int append(ParticleKind particleKind, double posX, double posY, double posZ, float speedX, float speedY, float speedZ,
               float particleSize, int particleLifetime, int particleColor, int particleLight, int particleSeed) {
        int index = count++;
        x[index] = posX;
        y[index] = posY;
        z[index] = posZ;
        previousX[index] = posX;
        previousY[index] = posY;
        previousZ[index] = posZ;
        velocityX[index] = speedX;
        velocityY[index] = speedY;
        velocityZ[index] = speedZ;
        size[index] = particleSize;
        age[index] = 0;
        lifetime[index] = particleLifetime;
        color[index] = particleColor;
        light[index] = particleLight;
        seed[index] = particleSeed;
        kind[index] = (byte) particleKind.id;
        categoryCounts[particleKind.category.ordinal()]++;
        return index;
    }
}
