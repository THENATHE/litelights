package com.thenathe.litelights.render.feature.climate;

import com.thenathe.almanac.api.AlmanacApi;
import com.thenathe.almanac.api.Storm;
import com.thenathe.almanac.api.WeatherAt;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Almanac's storm over the camera, through its public API ({@code regionalWeather},
 * {@code stormsNear}, {@code weatherAt}; Almanac 0.4.0 and later). Reached only through
 * {@link RegionalRain} once LiteLights takes its climate from Almanac, so nothing here loads
 * without it.
 */
final class AlmanacOverhead {
    /** How far from the camera's column a storm's cloud may end and still count as over it, in blocks. */
    private static final double OVER_THE_CAMERA = 1.0;

    /**
     * The weather falling past the camera.
     *
     * @param rain    the rain or snow level just under the storm's top, or at the camera under it
     * @param thunder the thunder level there
     */
    record Falling(float rain, float thunder) {
    }

    private AlmanacOverhead() {
    }

    static boolean regional(ClientLevel level) {
        return AlmanacApi.regionalWeather(level);
    }

    /** The highest top of the storms over the camera ({@code Storm.cloudTop}: nothing falls above it), NaN with none. */
    static float stormTop(ClientLevel level, Vec3 camera) {
        float top = Float.NaN;
        for (Storm storm : AlmanacApi.stormsNear(level, camera.x, camera.z, OVER_THE_CAMERA)) {
            top = Float.isNaN(top) ? storm.cloudTop() : Math.max(top, storm.cloudTop());
        }
        return top;
    }

    /** What falls past the camera, asked just under the storm's top while the camera is over it. */
    static Falling falling(ClientLevel level, Vec3 camera, float stormTop) {
        double askedY = WeatherCeiling.askedHeight(camera.y, stormTop);
        Optional<WeatherAt> weather = AlmanacApi.weatherAt(level, BlockPos.containing(camera.x, askedY, camera.z));
        if (weather.isEmpty()) {
            return new Falling(level.getRainLevel(1.0f), level.getThunderLevel(1.0f));
        }
        return new Falling(weather.get().rain(), weather.get().thunder());
    }
}
