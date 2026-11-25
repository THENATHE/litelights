package com.thenathe.litelights.render.feature.climate;

import com.thenathe.litelights.api.AlmanacVersions;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * Which Almanac is installed. Below {@link AlmanacVersions#MINIMUM} LiteLights shares nothing with
 * it: the weather goes by each biome's own climate, far snow has no source, and
 * {@link AlmanacNotice} says why. Almanac's docs/INTEGRATION.md ("Checking the version") suggests
 * the check; the far terrain module asks through ClimateAccess. Names no Almanac class, so it is
 * safe to load anywhere.
 */
public final class AlmanacVersion {
    private static Optional<Version> installed;

    private AlmanacVersion() {
    }

    /** Whether the installed Almanac is this version or later; false without Almanac. */
    public static boolean atLeast(String wanted) {
        return atLeast(installed(), wanted);
    }

    /** Whether an Almanac new enough to share LiteLights' weather and far snow is installed. */
    public static boolean shared() {
        return atLeast(AlmanacVersions.MINIMUM);
    }

    /** The installed Almanac's version when it is too old to share anything with LiteLights. */
    public static Optional<String> tooOld() {
        return installed().filter(version -> !atLeast(Optional.of(version), AlmanacVersions.MINIMUM))
            .map(Version::getFriendlyString);
    }

    private static Optional<Version> installed() {
        if (installed == null) {
            installed = FabricLoader.getInstance().getModContainer(LocalClimate.ALMANAC_ID)
                .map(almanac -> almanac.getMetadata().getVersion());
        }
        return installed;
    }

    /** Almanac's versions look like {@code 0.2.0+26.3}; the part after {@code +} doesn't count. */
    static boolean atLeast(Optional<Version> version, String wanted) {
        if (version.isEmpty()) {
            return false;
        }
        try {
            return version.get().compareTo(Version.parse(wanted)) >= 0;
        } catch (VersionParsingException unreadable) {
            return false;
        }
    }
}
