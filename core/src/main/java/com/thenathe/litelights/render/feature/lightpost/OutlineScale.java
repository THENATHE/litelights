package com.thenathe.litelights.render.feature.lightpost;

/**
 * How thick the enchantment outline is around something drawn in the level (an item held in third
 * person or by someone else, worn armour): the first-person outline, which the Size and Diffusion
 * settings describe, scaled by how big the thing shows on screen next to an item held in first
 * person. So a sword three blocks away gets the outline of the first-person sword seen from three
 * blocks away. {@code outline_level_prepare.fsh} works out the same per pixel; change both together.
 */
final class OutlineScale {
    /**
     * How far in front of the eye a first-person item would stand in the level to show at the same
     * size, in blocks, with both views at the same field of view. Measured on the held sword
     * (docs/screenshots/enchant15/): its texels are about 50 pixels across in first person and
     * 12 on an armour stand three blocks away.
     */
    static final float HAND_ITEM_DISTANCE = 0.72f;
    /** Vanilla draws the hand at a 70 degree field of view, whatever the FOV setting. */
    static final float HAND_FOV_DEGREES = 70.0f;
    /**
     * The thinnest blur, in mask pixels (half a screen pixel each way): about one and a half screen
     * pixels. Thinner, the glow wouldn't reach past the item's own pixels and a distant outline
     * would vanish instead of thinning.
     */
    static final float MIN_SIGMA = 0.75f;

    private OutlineScale() {
    }

    /**
     * The distance at which something in the level shows as big as it would in first person.
     *
     * @param levelProjectionScale the level projection's vertical scale, 1 / tan(fov / 2)
     */
    static float referenceDistance(float levelProjectionScale) {
        float handProjectionScale = (float) (1.0 / Math.tan(Math.toRadians(HAND_FOV_DEGREES) / 2.0));
        return HAND_ITEM_DISTANCE * levelProjectionScale / handProjectionScale;
    }

    /**
     * The blur's spread (a Gaussian's sigma, in mask pixels) for a surface this far from the eye:
     * the first-person spread shrunk with the distance, never wider than the first-person one and
     * never thinner than {@link #MIN_SIGMA} (unless the first-person one is thinner still).
     */
    static float sigma(float referenceSigma, float distance, float referenceDistance) {
        float scale = distance <= referenceDistance ? 1.0f : referenceDistance / distance;
        return Math.max(referenceSigma * scale, Math.min(MIN_SIGMA, referenceSigma));
    }
}
