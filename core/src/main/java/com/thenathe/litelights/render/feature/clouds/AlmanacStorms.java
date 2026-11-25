package com.thenathe.litelights.render.feature.clouds;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.Storm;
import com.thenathe.almanac.api.Wind;
import com.thenathe.litelights.clouds.field.IntegratedWind;
import com.thenathe.litelights.clouds.field.RegionalStorm;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Almanac's regional weather for the Dynamic clouds, through its public API (0.4.0's
 * {@code regionalWeather}, {@code stormsNear}, {@code weatherTick} and {@code prevailingWind};
 * Almanac's docs/INTEGRATION.md, "Added in 0.4.0"). Reached only through {@link DynamicClouds#regionalWeather},
 * once LiteLights takes its climate from an Almanac new enough for this (AlmanacVersions.MINIMUM),
 * so nothing here loads without one. On a client every call is safe from any thread.
 */
final class AlmanacStorms {
    /** Almanac's share of a storm's life spent growing ({@code Storm.GROWTH}), read from its storms; NaN until one is seen. */
    private static double growShare = Double.NaN;

    private AlmanacStorms() {
    }

    /** Whether Almanac's storms decide the weather in the level now (false before the server's weather has arrived). */
    static boolean regional(ClientLevel level) {
        return AlmanacApi.regionalWeather(level);
    }

    /** The weather tick now: the game time less any time the weather stood still. */
    static double weatherTick(ClientLevel level, float partialTicks) {
        return AlmanacApi.weatherTick(level, partialTicks).orElse(level.getGameTime() + (double) partialTicks);
    }

    /** Every storm whose cloud comes within {@code radius} of a place now, as plain numbers. */
    static List<RegionalStorm> near(ClientLevel level, double x, double z, double radius) {
        List<Storm> storms = AlmanacApi.stormsNear(level, x, z, radius);
        List<RegionalStorm> near = new ArrayList<>(storms.size());
        for (Storm storm : storms) {
            near.add(of(storm));
        }
        return near;
    }

    /** One of Almanac's storms as the clouds take it, drawn whole. */
    static RegionalStorm of(Storm storm) {
        return new RegionalStorm(storm.id(), storm.birthX(), storm.birthZ(), storm.birthTick(), storm.lifeTicks(), storm.velocityX(),
            storm.velocityZ(), storm.radius(), storm.stretch(), storm.angle(), storm.strength(), storm.thunder(), storm.cloudTop(),
            growShare(storm), Storm.RAIN_CORE, 1.0f);
    }

    /**
     * The share of a storm's life it spends growing, from the storm's own size early in its life
     * rather than compiled in from {@code Storm.GROWTH}, so a later Almanac's share is followed. It
     * is the same for every storm, so it is worked out once.
     */
    static double growShare(Storm storm) {
        double known = growShare;
        if (!Double.isNaN(known)) {
            return known;
        }
        double early = 0.01;
        double size = storm.size(storm.birthTick() + storm.lifeTicks() * early);
        double share = size > 0.0 ? Math.clamp(early / size, early, 0.5) : 0.29;
        growShare = share;
        return share;
    }

    /**
     * Almanac's prevailing wind at cloud height for a level, summed into a drift (Almanac gives its
     * velocity at any weather tick; the clouds need how far it has carried them).
     */
    static IntegratedWind wind(ClientLevel level) {
        return new IntegratedWind((tick, out) -> {
            Optional<Wind> wind = AlmanacApi.prevailingWind(level, tick);
            out[0] = wind.map(Wind::velocityX).orElse(0.0);
            out[1] = wind.map(Wind::velocityZ).orElse(0.0);
        });
    }
}
