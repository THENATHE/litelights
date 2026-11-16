package com.thenathe.litelights.render.frame;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CelestialLightTest {
    @Test
    void noonSunIsOverheadAndCastsFullShadows() {
        CelestialLight light = new CelestialLight();
        light.update(0.0f, (float) Math.PI, 0, 0, 1);
        assertEquals(1.0f, light.sunDirection().y, 1e-5);
        assertEquals(1.0f, light.lightStrength(), 1e-5);
        assertFalse(light.moonCastsShadows());
    }

    @Test
    void moonTakesOverAtNightWithWeakerShadows() {
        CelestialLight light = new CelestialLight();
        light.update((float) Math.PI, 0.0f, 0, 0, 1);
        assertTrue(light.moonCastsShadows());
        assertEquals(1.0f, light.shadowLightDirection().y, 1e-5);
        assertTrue(light.lightStrength() < 0.5f);
    }

    @Test
    void shadowsFadeAtTheHorizonAndInRain() {
        CelestialLight light = new CelestialLight();
        light.update((float) Math.toRadians(89.5), (float) Math.toRadians(269.5), 0, 0, 1);
        assertTrue(light.lightStrength() < 0.05f);
        light.update(0.0f, (float) Math.PI, 1, 0, 1);
        assertTrue(light.lightStrength() < 0.5f);
    }

    @Test
    void dayTimeMatchesTheSkyCurve() {
        assertEquals(0.0f, CelestialLight.sunAngleForDayTime(6000), 1e-5);
        assertEquals(Math.PI, CelestialLight.sunAngleForDayTime(18000), 1e-4);
        float sunset = CelestialLight.sunAngleForDayTime(12000);
        // At 12000 the sun is still a little above the horizon, as in vanilla (about 78 degrees).
        assertTrue(sunset > Math.toRadians(70) && sunset < Math.toRadians(90));
    }

    @Test
    void nightToneWaitsForTheSunToSet() {
        CelestialLight light = new CelestialLight();
        // Golden hour, the sun about 8 degrees up: no night tone yet.
        light.update((float) Math.toRadians(82.0), (float) Math.toRadians(262.0), 0, 0, 1);
        assertEquals(0.0f, light.nightTone(), 1e-6);
        // Sun 4 degrees down: on its way in.
        light.update((float) Math.toRadians(94.0), (float) Math.toRadians(274.0), 0, 0, 1);
        assertTrue(light.nightTone() > 0.2f && light.nightTone() < 0.9f);
        // Midnight: full.
        light.update((float) Math.PI, 0.0f, 0, 0, 1);
        assertEquals(1.0f, light.nightTone(), 1e-6);
    }

    @Test
    void noSunlightColourGivesWhiteSunlight() {
        org.joml.Vector3f color = new org.joml.Vector3f();
        CelestialLight.sunColorFor(0.05f, 0.0f, color);
        assertEquals(1.0f, color.x, 1e-6);
        assertEquals(1.0f, color.y, 1e-6);
        assertEquals(1.0f, color.z, 1e-6);
        CelestialLight.sunColorFor(0.05f, 1.0f, color);
        assertTrue(color.x > color.z, "warm near the horizon");
    }
}
