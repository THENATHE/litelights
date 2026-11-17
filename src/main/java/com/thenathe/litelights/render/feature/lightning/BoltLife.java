package com.thenathe.litelights.render.feature.lightning;

/**
 * What LiteLights adds to vanilla's lightning bolt render state (mixed in by
 * {@code mixin.skyfog.LightningBoltRenderStateMixin}): where the bolt is in its life, so the
 * branching bolt can flicker and dim between its strikes.
 */
public interface BoltLife {
    /** Vanilla's countdown: 0 or more while the bolt is lit, below 0 in the dark moments between strikes. */
    int litelights$life();

    float litelights$partialTick();

    void litelights$setLife(int life, float partialTick);
}
