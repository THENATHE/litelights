package com.thenathe.litelights.render.feature.weather;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Weather mods LiteLights steps aside for, so no effect is ever drawn twice (PLAN section 4).
 *
 * <ul>
 *   <li><b>Particle Rain</b> draws rain, snow and desert sand as particles: LiteLights' rain, snow
 *   (including dark pale-garden rain and blizzards) and sandstorm particles step aside, and
 *   vanilla's rain is left to it. Mist, fog, sky and lighting stay.</li>
 *   <li><b>Immersive Storms</b> brings its own sandstorms, storm fog and wind-blown dust: LiteLights'
 *   sandstorm particles, sand wind, ambient wind grit and Storm atmosphere's fog step aside.</li>
 *   <li><b>Particle Rain</b> also draws water streaks where rain hits a wall: LiteLights' wall
 *   streaks step aside. It has no drips (per its public page, 2026-11-15), so those stay.</li>
 *   <li><b>Weather Glass</b> draws rain, frost, snow, dust and breath on glass: all of LiteLights'
 *   weather on glass steps aside.</li>
 * </ul>
 */
public final class WeatherCompat {
    public static final String PARTICLE_RAIN_ID = "particlerain";
    public static final String IMMERSIVE_STORMS_ID = "immersive-storms";
    /**
     * Weather Glass's Fabric mod id isn't stated on its page and its jar is never opened (concepts
     * only, DECISIONS glass16); its settings file is {@code weatherglass.properties}, so that id
     * comes first, with the other usual spellings in case.
     */
    public static final String[] WEATHER_GLASS_IDS = {"weatherglass", "weather_glass", "weather-glass"};
    public static final String WEATHER_GLASS = "Weather Glass";
    public static final String PARTICLE_RAIN = "Particle Rain";
    public static final String IMMERSIVE_STORMS = "Immersive Storms";
    /** Named on rows that need both mods to step aside. */
    public static final String PARTICLE_RAIN_AND_IMMERSIVE_STORMS = PARTICLE_RAIN + " and " + IMMERSIVE_STORMS;

    private static final boolean PARTICLE_RAIN_LOADED = FabricLoader.getInstance().isModLoaded(PARTICLE_RAIN_ID);
    private static final boolean IMMERSIVE_STORMS_LOADED = FabricLoader.getInstance().isModLoaded(IMMERSIVE_STORMS_ID);
    private static final boolean WEATHER_GLASS_LOADED = anyLoaded(WEATHER_GLASS_IDS);

    private WeatherCompat() {
    }

    /** Rain and snow (and with them dark rain, splashes and blizzards) belong to Particle Rain. */
    public static boolean precipitationHandledElsewhere() {
        return PARTICLE_RAIN_LOADED;
    }

    /** Sandstorm particles belong to Immersive Storms or Particle Rain, whichever is there. */
    public static boolean sandstormsHandledElsewhere() {
        return PARTICLE_RAIN_LOADED || IMMERSIVE_STORMS_LOADED;
    }

    /** Wind-blown sand and grit belong to Immersive Storms. */
    public static boolean windHandledElsewhere() {
        return IMMERSIVE_STORMS_LOADED;
    }

    /** Storm atmosphere (sandstorm fog, blizzards, swamp fog, ambient wind) belongs to Immersive Storms. */
    public static boolean stormAtmosphereHandledElsewhere() {
        return IMMERSIVE_STORMS_LOADED;
    }

    /**
     * Everything the weather density scales (rain, snow and their mist, sandstorms, sand wind and
     * grit) is drawn by other mods: only with both loaded.
     */
    public static boolean densityHandledElsewhere() {
        return precipitationHandledElsewhere() && sandstormsHandledElsewhere() && windHandledElsewhere();
    }

    /** Water streaks on walls belong to Particle Rain. */
    public static boolean wallStreaksHandledElsewhere() {
        return PARTICLE_RAIN_LOADED;
    }

    /** Rain, frost, snow, dust and breath on glass belong to Weather Glass. */
    public static boolean glassHandledElsewhere() {
        return WEATHER_GLASS_LOADED;
    }

    private static boolean anyLoaded(String[] ids) {
        for (String id : ids) {
            if (FabricLoader.getInstance().isModLoaded(id)) {
                return true;
            }
        }
        return false;
    }

    /** The mod named on the sandstorm row: Immersive Storms when both are loaded, as storms are its theme. */
    public static String sandstormHandler() {
        return IMMERSIVE_STORMS_LOADED ? IMMERSIVE_STORMS : PARTICLE_RAIN;
    }
}
