package com.thenathe.litelights.render.feature.climate;

import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * Which Almanac is installed, so calls a later Almanac added are only made where they exist (a
 * missing method throws NoSuchMethodError in play). LiteLights' fabric.mod.json refuses Almanac
 * before 0.1.2; everything newer than that is checked here, as Almanac's docs/INTEGRATION.md
 * ("Checking the version") suggests. Names no Almanac class, so it is safe to load anywhere.
 */
public final class AlmanacVersion {
    /** {@code precipitationOf}, {@code farSnowChangedTick}, and near snow forming in patches over the thaw time. */
    public static final String CHANGE_TICK = "0.1.3";

    private static Optional<Version> installed;

    private AlmanacVersion() {
    }

    /** Whether the installed Almanac is this version or later; false without Almanac. */
    public static boolean atLeast(String wanted) {
        if (installed == null) {
            installed = FabricLoader.getInstance().getModContainer(LocalClimate.ALMANAC_ID)
                .map(almanac -> almanac.getMetadata().getVersion());
        }
        return atLeast(installed, wanted);
    }

    /** Almanac's versions look like {@code 0.1.3+26.3}; the part after {@code +} doesn't count. */
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
