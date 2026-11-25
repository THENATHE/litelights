package com.thenathe.litelights.render.feature.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.api.AlmanacVersions;
import java.util.Optional;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.junit.jupiter.api.Test;

/**
 * LiteLights shares its weather, far snow, visual snow and the Dynamic clouds' storms only with
 * Almanac 1.0.0 or later, released with LiteLights 1.1.0; beside an older one, 0.4.1 included, it
 * shares nothing, and keeps no code for it.
 */
class AlmanacVersionTest {
    private static boolean shared(String version) throws VersionParsingException {
        return AlmanacVersion.atLeast(Optional.of(Version.parse(version)), AlmanacVersions.MINIMUM);
    }

    @Test
    void theOneMinimumIsAlmanac100() {
        assertEquals("1.0.0", AlmanacVersions.MINIMUM);
    }

    @Test
    void almanac100AndLaterShare() throws VersionParsingException {
        assertTrue(shared("1.0.0+26.3"));
        assertTrue(shared("1.0.1+26.3"));
        assertTrue(shared("1.1.0+26.3"));
        assertTrue(shared("2.0.0+26.3"));
        assertTrue(shared("1.0.0"));
    }

    @Test
    void olderAlmanacsShareNothing() throws VersionParsingException {
        assertFalse(shared("0.4.1+26.3"), "the last before 1.0.0");
        assertFalse(shared("0.4.0+26.3"));
        assertFalse(shared("0.9.9+26.3"));
        assertFalse(shared("0.3.0+26.3"));
        assertFalse(shared("0.2.0+26.3"));
        assertFalse(shared("0.1.7+26.3"));
        assertFalse(shared("0.4.1.1+26.3"), "a fourth number still comes before 1.0.0");
        assertFalse(shared("1.0.0-beta.1+26.3"), "a pre-release of 1.0.0");
    }

    @Test
    void theMinecraftPartDoesntCount() throws VersionParsingException {
        assertTrue(shared("1.0.0+26.2"));
        assertFalse(shared("0.4.1+26.4"));
    }

    @Test
    void withoutAlmanacNothingIsShared() {
        assertFalse(AlmanacVersion.atLeast(Optional.empty(), AlmanacVersions.MINIMUM));
    }
}
