package com.thenathe.litelights.render.feature.climate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.junit.jupiter.api.Test;

/** Almanac's newer calls are only made where the installed Almanac has them. */
class AlmanacVersionTest {
    private static Optional<Version> almanac(String version) throws VersionParsingException {
        return Optional.of(Version.parse(version));
    }

    @Test
    void almanac013AndLaterHaveTheChangeTick() throws VersionParsingException {
        assertTrue(AlmanacVersion.atLeast(almanac("0.1.3+26.3"), AlmanacVersion.CHANGE_TICK));
        assertTrue(AlmanacVersion.atLeast(almanac("0.1.4+26.3"), AlmanacVersion.CHANGE_TICK));
        assertTrue(AlmanacVersion.atLeast(almanac("0.2.0+26.4"), AlmanacVersion.CHANGE_TICK));
    }

    @Test
    void almanac012FallsBack() throws VersionParsingException {
        assertFalse(AlmanacVersion.atLeast(almanac("0.1.2+26.3"), AlmanacVersion.CHANGE_TICK));
        assertFalse(AlmanacVersion.atLeast(almanac("0.1.3-beta.1+26.3"), AlmanacVersion.CHANGE_TICK), "a pre-release of 0.1.3");
    }

    @Test
    void theMinecraftPartDoesntCount() throws VersionParsingException {
        assertTrue(AlmanacVersion.atLeast(almanac("0.1.3+26.2"), AlmanacVersion.CHANGE_TICK));
    }

    @Test
    void withoutAlmanacNothingNewerIsUsed() {
        assertFalse(AlmanacVersion.atLeast(Optional.empty(), AlmanacVersion.CHANGE_TICK));
    }
}
