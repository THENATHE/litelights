package com.thenathe.litelights.render.shadow;

/**
 * How much of the sky's light a full shadow takes away, from the extra darkness slider (stored as
 * {@code shadows.sun_brightness}, 0 to 1). The shader multiplies it by Darkness.
 *
 * <p>At 0 a full shadow keeps 54 % of the sky light, the look since final-fixes. At 1 it keeps 4 %,
 * so a shadow in full sun is nearly black. In between the light kept falls geometrically, which the
 * eye reads as an even darkening along the slider; it is smooth and only ever goes down. Block light
 * (torches, lava, held lights) is never part of the sky's share, so it keeps its strength.
 */
public final class ShadowDepth {
    /** Share of the sky light a full shadow keeps at 0 % and at 100 %. */
    static final float KEPT_AT_NONE = 0.54f;
    static final float KEPT_AT_FULL = 0.04f;

    private ShadowDepth() {
    }

    /** The share of sky light a full shadow removes, 0.46 at 0 % up to 0.96 at 100 %. */
    public static float forExtraDarkness(float extraDarkness) {
        float amount = Math.clamp(extraDarkness, 0.0f, 1.0f);
        double kept = KEPT_AT_NONE * Math.pow(KEPT_AT_FULL / KEPT_AT_NONE, amount);
        return (float) (1.0 - kept);
    }
}
