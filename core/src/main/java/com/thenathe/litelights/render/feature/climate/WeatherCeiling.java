package com.thenathe.litelights.render.feature.climate;

/**
 * How high the weather shown at the camera reaches under Almanac's regional weather. Almanac
 * gives each storm its own cloud top, in the game's own heights, and nothing falls above it; its
 * rain level at the camera drops to nothing as the camera rises past that top. LiteLights draws
 * the storm's cloud from the Cloud height setting instead, so with the clouds raised the cloud the
 * rain falls from floats well above Almanac's top, and flying up to it crossed a band of dry air
 * under it (weather-ceiling-117).
 *
 * <p>The rule: the rain reaches the higher of Almanac's top and the top of the cloud drawn over
 * the camera (the Dynamic clouds' rain cloud, or the Fancy or Flat layer). Never lower than
 * Almanac's own, so lowering the clouds takes no rain away; never above both, so no rain shows
 * where Almanac says nothing falls unless a drawn cloud is up there too.
 */
public final class WeatherCeiling {
    /** Vanilla's pace for the rain and thunder levels, a share a tick; Almanac's levels follow it too. */
    static final float EASE = 0.01f;

    private WeatherCeiling() {
    }

    /**
     * The highest the weather reaches, in world y.
     *
     * @param almanacTop the top of Almanac's storm over the camera, NaN with no storm over it
     * @param drawnTop   the top of the drawn cloud the rain falls from over the camera; negative
     *                   infinity where clouds are drawn but none is over the camera, NaN with no
     *                   clouds drawn
     * @return NaN where neither limits it
     */
    static float of(float almanacTop, float drawnTop) {
        if (Float.isNaN(almanacTop)) {
            return drawnTop;
        }
        if (Float.isNaN(drawnTop)) {
            return almanacTop;
        }
        return Math.max(almanacTop, drawnTop);
    }

    /**
     * Where to ask Almanac for the weather falling past the camera: at the camera, or just under
     * the storm's top while the camera is over it, the highest place Almanac still lets it fall.
     */
    static double askedHeight(double cameraY, float almanacTop) {
        if (Float.isNaN(almanacTop)) {
            return cameraY;
        }
        return Math.min(cameraY, almanacTop - 1.0);
    }

    /** The level to head for: what falls, under the ceiling; nothing over it. */
    static float target(float falling, double cameraY, float ceiling) {
        if (Float.isNaN(ceiling) || cameraY <= ceiling) {
            return falling;
        }
        return 0.0f;
    }

    /** One tick of the level's move toward its target, at vanilla's pace. */
    static float ease(float current, float target) {
        return current + Math.clamp(target - current, -EASE, EASE);
    }
}
