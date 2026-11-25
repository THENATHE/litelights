package com.thenathe.litelights.api;

/**
 * The Almanac versions LiteLights checks for, with {@link ClimateAccess#almanacAtLeast}. When the
 * installed Almanac is too old for LiteLights, everything the two share is off and LiteLights says
 * so; no code is kept for older Almanacs.
 */
public final class AlmanacVersions {
    /**
     * The oldest Almanac LiteLights shares anything with: Almanac 1.0.0, released together with
     * LiteLights 1.1.0. It has everything the two share: the regional weather
     * ({@code regionalWeather}, {@code stormsNear}, {@code weatherTick} and {@code prevailingWind},
     * whose storms the Dynamic clouds draw where they are, and {@code snowLayersAt}, the snow by
     * place far land takes), the snow in layers ({@code snowLayers}, which the visual snow's layers
     * follow near and far), the season's climate ({@code seasonClimateOf}), the far-terrain lookups
     * and the bare lines that far snow reads.
     */
    public static final String MINIMUM = "1.0.0";

    private AlmanacVersions() {
    }
}
