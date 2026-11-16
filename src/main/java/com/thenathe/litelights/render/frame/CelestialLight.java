package com.thenathe.litelights.render.frame;

import com.thenathe.litelights.render.LiteMath;
import org.joml.Vector3f;

/**
 * Where the sun and moon are and what light they give, from the angles the sky renderer uses.
 *
 * <p>The sky draws the sun by rotating the up vector by the sun angle around X, then by -90
 * degrees around Y, so the direction toward the sun is {@code (-sin a, cos a, 0)}: overhead at
 * noon, due east (+X) at sunrise.
 */
public final class CelestialLight {
    /** Below this elevation (sine) a body gives no shadows; shadows fade in above it. */
    private static final float HORIZON_FADE_START = 0.0f;
    private static final float HORIZON_FADE_END = 0.15f;
    /** Moonlight casts much fainter shadows than sunlight. */
    private static final float MOON_SHADOW_STRENGTH = 0.35f;
    /**
     * The night tone starts as the sun reaches the horizon (sine 0.02, about 1 degree up) and is
     * full once it is about 7 degrees below. Starting it with daylight's fade, 14 degrees up, put
     * a cool grey over the golden hour.
     */
    private static final float NIGHT_TONE_START = 0.02f;
    private static final float NIGHT_TONE_FULL = -0.12f;

    private final Vector3f sunDirection = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f moonDirection = new Vector3f(0.0f, -1.0f, 0.0f);
    private final Vector3f shadowLightDirection = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f sunColor = new Vector3f(1.0f);
    private final Vector3f ambientColor = new Vector3f(1.0f);
    private boolean moonCastsShadows;
    private float lightStrength;
    private float daylight;
    private float nightTone;

    /**
     * @param sunAngle radians, as in the sky render state
     * @param moonAngle radians
     * @param rain rain level 0..1
     * @param thunder thunder level 0..1
     * @param sunlightColour the sunlight colour setting (1 = default warmth at sunset, 0 = white light)
     */
    public void update(float sunAngle, float moonAngle, float rain, float thunder, float sunlightColour) {
        directionFromAngle(sunAngle, sunDirection);
        directionFromAngle(moonAngle, moonDirection);

        float sunElevation = sunDirection.y;
        float moonElevation = moonDirection.y;
        float sunFade = LiteMath.smoothstep(HORIZON_FADE_START, HORIZON_FADE_END, sunElevation);
        float moonFade = LiteMath.smoothstep(HORIZON_FADE_START, HORIZON_FADE_END, moonElevation);
        float weather = (1.0f - 0.65f * rain) * (1.0f - 0.25f * thunder);

        moonCastsShadows = sunElevation < 0.0f && moonElevation > 0.0f;
        if (moonCastsShadows) {
            shadowLightDirection.set(moonDirection);
            lightStrength = moonFade * MOON_SHADOW_STRENGTH * weather;
        } else {
            shadowLightDirection.set(sunDirection);
            lightStrength = sunFade * weather;
        }

        daylight = LiteMath.smoothstep(-0.1f, 0.25f, sunElevation);
        nightTone = 1.0f - LiteMath.smoothstep(NIGHT_TONE_FULL, NIGHT_TONE_START, sunElevation);
        sunColorFor(sunElevation, sunlightColour, sunColor);
        if (moonCastsShadows) {
            // Moonlight: a faint cool white instead of the sun's colour.
            sunColor.set(0.86f, 0.92f, 1.08f);
        }
        ambientColorFor(daylight, sunlightColour, ambientColor);
    }

    /**
     * The sun angle (radians) for a day time in ticks, by the overworld's classic celestial curve:
     * noon (6000) overhead, the curve a little flattened so days are slightly longer than nights.
     */
    public static float sunAngleForDayTime(long dayTime) {
        double fraction = (dayTime / 24000.0 - 0.25) - Math.floor(dayTime / 24000.0 - 0.25);
        double eased = 0.5 - Math.cos(fraction * Math.PI) / 2.0;
        return (float) ((fraction * 2.0 + eased) / 3.0 * Math.PI * 2.0);
    }

    static void directionFromAngle(float angle, Vector3f out) {
        out.set((float) -Math.sin(angle), (float) Math.cos(angle), 0.0f);
    }

    /**
     * Sunlight is near white high in the sky and turns warm close to the horizon. The sunlight colour setting scales how
     * far the colour moves away from white.
     */
    static void sunColorFor(float elevation, float sunlightColour, Vector3f out) {
        float low = 1.0f - LiteMath.smoothstep(0.0f, 0.45f, elevation);
        float red = 1.06f + 0.06f * low;
        float green = 1.02f - 0.16f * low;
        float blue = 0.96f - 0.36f * low;
        out.set(
            1.0f + (red - 1.0f) * sunlightColour,
            1.0f + (green - 1.0f) * sunlightColour,
            1.0f + (blue - 1.0f) * sunlightColour);
    }

    /**
     * The sky's light where the sun doesn't reach: cooler than white by day, so shadows read as
     * shade under a blue sky rather than as plain darkening.
     */
    static void ambientColorFor(float daylight, float sunlightColour, Vector3f out) {
        float coolness = 0.18f * sunlightColour * daylight;
        out.set(1.0f - coolness, 1.0f - coolness * 0.35f, 1.0f + coolness * 0.4f);
    }

    public Vector3f sunDirection() {
        return sunDirection;
    }

    public Vector3f moonDirection() {
        return moonDirection;
    }

    /** Direction toward the body that casts shadows right now (the sun, or the moon at night). */
    public Vector3f shadowLightDirection() {
        return shadowLightDirection;
    }

    public boolean moonCastsShadows() {
        return moonCastsShadows;
    }

    /** 0 when shadows should vanish (body at the horizon, heavy rain), 1 at full sun. */
    public float lightStrength() {
        return lightStrength;
    }

    public float daylight() {
        return daylight;
    }

    /** How much of the night colour tone applies: 0 while the sun is up, 1 once it is well down. */
    public float nightTone() {
        return nightTone;
    }

    public Vector3f sunColor() {
        return sunColor;
    }

    public Vector3f ambientColor() {
        return ambientColor;
    }
}
