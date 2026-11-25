package com.thenathe.litelights.clouds.field;

import java.util.ArrayList;
import java.util.List;

/**
 * The sky where a weather mod decides where it rains (Almanac's regional weather): its storms
 * become the formations that carry rain, snow and thunder cloud, at their place and size at the
 * tick, and the fair-weather cloud between them is LiteLights' own lattice, from the same hashed
 * seed every player on the world has. The wind is the weather mod's ({@link IntegratedWind}).
 *
 * <p>Each storm becomes a rain deck over its whole area, whole over its rain core and ending at its
 * edge ({@link CloudFormation.Edge#STORM}), dark by its strength and topped by its cloud top; a
 * thunderstorm also gets cumulonimbus towers over its inner part, with their anvil. Both grow and
 * shrink with the storm and travel with it, so the cloud arrives before the rain (its outer fifth
 * is cloud only), stands over the rain while it falls, and goes on after it.
 *
 * <p>A storm's cloud top is a game height (nothing falls above it, and the build limit is 320), and
 * the drawn clouds are on LiteLights' own scale, kilometres tall. So the top only places the storm
 * within its type's range of heights: the lowest storm tops Almanac 0.4.0 gives (200 blocks, 250
 * for a thunderstorm) at the bottom of the range and the highest (240, 320) at its top. A drawn top
 * is never under the storm's own, so no rain falls on anyone over a drawn cloud.
 */
public final class RegionalSky {
    /** Almanac 0.4.0's storm cloud tops, from its storm maths (docs/DECISIONS.md, round 15, item 11). */
    static final float LOWEST_TOP = 200.0f;
    static final float HIGHEST_TOP = 240.0f;
    static final float LOWEST_THUNDER_TOP = 250.0f;
    static final float HIGHEST_THUNDER_TOP = 320.0f;
    /** How far over the storm's own top a drawn top is at least, in blocks. */
    private static final float OVER_THE_TOP = 16.0f;
    /** The share of the deck's area its cloud covers when whole: the vanilla deck's. */
    private static final float DECK_COVERAGE = 0.97f;
    /** The share of a thunderstorm's area its towers stand on. */
    private static final float TOWER_SHARE = 0.85f;
    private static final float TOWER_COVERAGE = 0.6f;
    /**
     * The towers are whole inside this share of their area: about the inner half of the storm,
     * where a thunderstorm thunders.
     */
    private static final float TOWERS_WHOLE_INSIDE = 0.6f;

    private RegionalSky() {
    }

    /**
     * The sky at a tick around a place.
     *
     * @param seed       the world's hashed seed (as {@link VanillaWeatherSky#sky} takes it)
     * @param baseHeight vanilla's cloud height in this dimension: the floor
     * @param tick       the weather mod's weather tick
     * @param storms     the storms whose cloud can come within reach, at the presence to draw them
     * @param wind       the weather mod's wind at cloud height
     * @param reach      fair-weather formations that can't reach this far from ({@code centreX}, {@code centreZ}) are left out
     */
    public static CloudSky sky(long seed, float baseHeight, double tick, List<RegionalStorm> storms, CloudDrift wind,
                               double centreX, double centreZ, double reach) {
        long skySeed = VanillaWeatherSky.skySeed(seed);
        List<CloudFormation> formations = new ArrayList<>();
        VanillaWeatherSky.fairWeather(skySeed, baseHeight, tick, centreX, centreZ, reach, formations);
        for (RegionalStorm storm : storms) {
            if (storm.aliveAt(tick) && storm.presence() > 0.0f) {
                formations.addAll(formations(storm, baseHeight));
            }
        }
        return new CloudSky(skySeed, baseHeight, wind, formations);
    }

    /** The formations one storm is drawn as: its deck, and for a thunderstorm its towers. */
    public static List<CloudFormation> formations(RegionalStorm storm, float baseHeight) {
        List<CloudFormation> formations = new ArrayList<>(2);
        formations.add(deck(storm, baseHeight));
        if (storm.thunder()) {
            formations.add(towers(storm, baseHeight));
        }
        return formations;
    }

    private static CloudFormation deck(RegionalStorm storm, float baseHeight) {
        CloudType.Heights heights = CloudType.NIMBOSTRATUS.heights();
        float share = share(storm.cloudTop(), LOWEST_TOP, HIGHEST_TOP);
        float top = baseHeight + heights.top() + (share * 2.0f - 1.0f) * heights.topVariation();
        float base = baseHeight + heights.base();
        // Bigger storms have bigger lumps; a small one is still a deck rather than one lump.
        float lumps = (float) Math.clamp(storm.radius() / 1_000.0, 0.6, 1.6);
        // Darker than LiteLights' own rain deck (0.35 to 0.8), which is only ever seen from under it: a
        // storm is seen from outside too, and should read as a dark mass there.
        float darkness = Math.min(1.0f, 0.55f + 0.45f * storm.strength() + (storm.thunder() ? 0.2f : 0.0f));
        return formation(storm, storm.id(), CloudType.NIMBOSTRATUS, storm.radius(), DECK_COVERAGE, base,
            Math.max(top, storm.cloudTop() + OVER_THE_TOP), lumps, darkness, rainRate(storm), (float) storm.rainCoreShare());
    }

    private static CloudFormation towers(RegionalStorm storm, float baseHeight) {
        CloudType.Heights heights = CloudType.CUMULONIMBUS.heights();
        float share = share(storm.cloudTop(), LOWEST_THUNDER_TOP, HIGHEST_THUNDER_TOP);
        float ceiling = baseHeight + heights.top() + (share * 2.0f - 1.0f) * heights.topVariation();
        float base = baseHeight + heights.base();
        // Towers as wide as the storm allows: a small summer storm doesn't hold the widest.
        float towerSize = (float) Math.clamp(storm.radius() / 1_500.0, 0.4, 1.2);
        float darkness = 0.75f + 0.25f * storm.strength();
        return formation(storm, CloudNoise.hash(storm.id(), 0x70E5L), CloudType.CUMULONIMBUS, storm.radius() * TOWER_SHARE,
            TOWER_COVERAGE, base, Math.max(ceiling, storm.cloudTop() + OVER_THE_TOP), towerSize, darkness, 1.0f, TOWERS_WHOLE_INSIDE);
    }

    private static CloudFormation formation(RegionalStorm storm, long id, CloudType type, double radius, float coverage, float base,
                                            float top, float sizeScale, float strength, float rainRate, float rainCoreShare) {
        return new CloudFormation(id, type, storm.birthX(), storm.birthZ(), storm.velocityX(), storm.velocityZ(), storm.birthTick(),
            storm.lifeTicks(), (float) storm.growShare(), (float) storm.growShare(), radius, (float) storm.stretch(), (float) storm.angle(),
            coverage, base, Math.max(top, base + 8.0f), sizeScale, Math.clamp(strength, 0.0f, 1.0f), Precipitation.BY_BIOME,
            Math.clamp(rainRate, 0.0f, 1.0f), Math.clamp(rainCoreShare, 0.0f, 1.0f), storm.presence(), CloudFormation.Edge.STORM);
    }

    /** As Almanac rains under a storm: heavier storms rain harder, and even the lightest is plainly rain. */
    private static float rainRate(RegionalStorm storm) {
        return 0.55f + 0.45f * storm.strength();
    }

    private static float share(float value, float lowest, float highest) {
        return Math.clamp((value - lowest) / (highest - lowest), 0.0f, 1.0f);
    }
}
