package com.thenathe.litelights.render.feature.particles;

/**
 * Groups of particle kinds that share a slice of the particle cap, so heavy rain can't starve
 * fireflies or fire, and a busy Nether can't use up the whole cap on haze.
 */
public enum ParticleCategory {
    WEATHER(0.62f),
    AMBIENT(0.2f),
    FIRE(0.16f),
    NETHER(0.45f),
    END(0.4f);

    /** The most this category may hold, as a share of the total cap. Shares may add up past 1. */
    final float share;

    ParticleCategory(float share) {
        this.share = share;
    }
}
