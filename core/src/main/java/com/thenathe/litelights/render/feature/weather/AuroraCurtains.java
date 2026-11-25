package com.thenathe.litelights.render.feature.weather;

/**
 * The aurora's curtains as geometry. Like the real thing, each band is a sheet of light hanging
 * from about 100 km up to about 300 km, a few hundred kilometres away toward the north, running
 * east to west for a thousand kilometres or more and folding back and forth along its length.
 *
 * <p>The sheets are placed where they would really be, scaled down toward the camera by one
 * factor, so the projection does the rest: far bands lie low and flat along the horizon, near ones
 * stand tall, the ends bend down into the horizon with the Earth's curve, and the rays of a band
 * overhead meet near the zenith. A band is one strip of quads from its lower edge to its top; the
 * shader draws the rays and the colours from bottom to top.
 *
 * <p>The folds are broad and irregular: two fold waves of unrelated lengths add up, and a slow
 * swell of their depth along the band leaves some stretches deeply folded and others nearly
 * straight, so no run of evenly spaced humps shows along a band's lower edge.
 *
 * <p>Where the line of sight runs along a sheet, through more of the glowing air, the sheet looks
 * brighter: that is what makes the folds stand out as bright bends in photographs. Each vertex
 * carries that factor to the shader.
 */
final class AuroraCurtains {
    /** Takes one vertex: position in blocks from the camera, rays along and height up the curtain, colour bytes, alpha. */
    interface Sink {
        void vertex(float x, float y, float z, float u, float v, int rgb, float alpha);
    }

    static final int MAX_BANDS = 5;
    static final int SEGMENTS = 128;
    static final int MAX_QUADS = MAX_BANDS * SEGMENTS;

    /** Kilometres to blocks: the farthest point lands about a hundred blocks out, inside every far plane. */
    static final float BLOCKS_PER_KM = 0.06f;
    static final float LOWER_EDGE_KM = 100.0f;
    static final float TOP_KM = 300.0f;
    private static final float EARTH_RADIUS_KM = 6371.0f;
    /** One ray width per this many kilometres of curtain. */
    private static final float RAY_KM = 4.0f;
    /** The most a sheet seen edge-on is brightened. */
    static final float MAX_EDGE_ON = 3.0f;
    /** How bright each band is next to the main one. */
    private static final float[] BAND_BRIGHTNESS = {1.0f, 0.7f, 0.55f, 0.8f, 0.6f};
    /** A brightening runs along a band in windows of this many seconds, on some of them. */
    private static final float SURGE_SECONDS = 45.0f;

    private final float[] east = new float[SEGMENTS + 1];
    private final float[] north = new float[SEGMENTS + 1];
    private final float[] bottomX = new float[SEGMENTS + 1];
    private final float[] bottomY = new float[SEGMENTS + 1];
    private final float[] bottomZ = new float[SEGMENTS + 1];
    private final float[] topX = new float[SEGMENTS + 1];
    private final float[] topY = new float[SEGMENTS + 1];
    private final float[] topZ = new float[SEGMENTS + 1];
    private final float[] rays = new float[SEGMENTS + 1];
    private final float[] bottomAlpha = new float[SEGMENTS + 1];
    private final float[] topAlpha = new float[SEGMENTS + 1];
    private final int[] colours = new int[SEGMENTS + 1];

    /**
     * How many bands a night's aurora has: three on a quiet night, up to five on a strong one,
     * the last of them high overhead.
     */
    static int bandCount(float strength) {
        if (strength >= 0.82f) {
            return 5;
        }
        return strength >= 0.6f ? 4 : 3;
    }

    /**
     * Writes tonight's curtains. {@code shape} is the night's hash, {@code strength} the night's
     * own strength (0.45 to 1, which decides how many bands and how deep the folds),
     * {@code brightness} what the sky lets through now (strength times coldness and darkness),
     * {@code bearing} where the curtains face (radians from north, turning east) and
     * {@code seconds} the shader clock. Returns the number of quads written.
     */
    int build(long shape, float strength, float brightness, float bearing, float seconds, Sink sink) {
        float eastX = (float) Math.cos(bearing);
        float eastZ = (float) Math.sin(bearing);
        float northX = (float) Math.sin(bearing);
        float northZ = -(float) Math.cos(bearing);
        int quads = 0;
        int bands = bandCount(strength);
        for (int band = 0; band < bands; band++) {
            long hash = SkyEventSchedule.mix(shape, band);
            float distance = bandDistance(band, hash);
            float halfLength = 900.0f + 700.0f * unit(hash, 1);
            float centre = (unit(hash, 2) - 0.5f) * 600.0f;
            // The folds: the sheet's foot swings north and south along two waves of unrelated
            // lengths, the shorter one shallower, both deepened and flattened by a slow swell of
            // their depth along the band. Along the main wave the sheet also swings east and west,
            // so where that wave runs deep it folds back over itself.
            float foldWave = (float) (Math.PI * 2.0 / (650.0 + 450.0 * unit(hash, 3)));
            float foldDepth = (30.0f + 60.0f * unit(hash, 4)) * (0.6f + 0.4f * strength);
            float foldBack = (0.5f + 0.9f * unit(hash, 5)) / foldWave;
            float rippleWave = foldWave * (1.7f + 0.6f * unit(hash, 18));
            float rippleDepth = foldDepth * (0.25f + 0.25f * unit(hash, 19));
            float depthWave = (float) (Math.PI * 2.0 / (1500.0 + 1500.0 * unit(hash, 20)));
            float swellWave = (float) (Math.PI * 2.0 / (700.0 + 700.0 * unit(hash, 6)));
            float swellDepth = 30.0f + 90.0f * unit(hash, 7);
            // Slow drift of the folds along the band, a kilometre or two a second.
            float foldDrift = (0.02f + 0.03f * unit(hash, 8)) * (unit(hash, 9) < 0.5f ? -1.0f : 1.0f);
            float swellDrift = (0.006f + 0.01f * unit(hash, 10)) * (unit(hash, 11) < 0.5f ? -1.0f : 1.0f);
            float foldPhase = unit(hash, 12) * 6.2832f + foldDrift * seconds;
            float ripplePhase = unit(hash, 21) * 6.2832f - 0.7f * foldDrift * seconds;
            float depthPhase = unit(hash, 22) * 6.2832f + 0.3f * swellDrift * seconds;
            float swellPhase = unit(hash, 13) * 6.2832f + swellDrift * seconds;
            float edgePhase = unit(hash, 14) * 6.2832f;
            // A slow breath of the whole band, and now and then a brightening that runs along it.
            float breath = 0.85f + 0.15f * (float) Math.sin(seconds * (0.12f + 0.1f * unit(hash, 15)) + edgePhase);
            float alpha = brightness * BAND_BRIGHTNESS[band] * breath;
            float surgeWindow = (float) Math.floor(seconds / SURGE_SECONDS);
            boolean surging = unit(SkyEventSchedule.mix(hash, (long) surgeWindow), 0) < 0.6f;
            float surgeAt = centre - halfLength + 2.0f * halfLength * (seconds / SURGE_SECONDS - surgeWindow);
            float surgeStrength = surging ? 0.9f * (float) Math.sin(Math.PI * (seconds / SURGE_SECONDS - surgeWindow)) : 0.0f;
            // Magenta along the lower edge on every night, stronger on strong nights; faint red tops.
            int fringe = colourByte(0.45f + 0.55f * strength * (0.6f + 0.4f * unit(hash, 16)));
            int redTop = colourByte(0.35f + 0.65f * unit(hash, 17));

            for (int point = 0; point <= SEGMENTS; point++) {
                float s = alongBand(centre, halfLength, point);
                float foldAngle = s * foldWave + foldPhase;
                float depth = 0.55f + 0.45f * (float) Math.sin(s * depthWave + depthPhase);
                east[point] = s + foldBack * depth * (float) Math.cos(foldAngle);
                north[point] = distance + depth * (foldDepth * (float) Math.sin(foldAngle)
                    + rippleDepth * (float) Math.sin(s * rippleWave + ripplePhase))
                    + swellDepth * (float) Math.sin(s * swellWave + swellPhase);
            }
            for (int point = 0; point <= SEGMENTS; point++) {
                float along = point / (float) SEGMENTS;
                float s = alongBand(centre, halfLength, point);
                float pointEast = east[point];
                float pointNorth = north[point];
                // The direction the sheet runs, for how edge-on it is seen.
                int before = Math.max(point - 1, 0);
                int after = Math.min(point + 1, SEGMENTS);
                float alongEast = east[after] - east[before];
                float alongNorth = north[after] - north[before];

                float drop = (pointEast * pointEast + pointNorth * pointNorth) / (2.0f * EARTH_RADIUS_KM);
                float lower = LOWER_EDGE_KM + 8.0f * (float) Math.sin(s * 0.004f + seconds * 0.03f + edgePhase) - drop;
                float upper = TOP_KM - drop;
                float worldX = (pointEast * eastX + pointNorth * northX) * BLOCKS_PER_KM;
                float worldZ = (pointEast * eastZ + pointNorth * northZ) * BLOCKS_PER_KM;
                bottomX[point] = worldX;
                bottomY[point] = lower * BLOCKS_PER_KM;
                bottomZ[point] = worldZ;
                topX[point] = worldX;
                topY[point] = upper * BLOCKS_PER_KM;
                topZ[point] = worldZ;
                rays[point] = s / RAY_KM;

                float ground = (float) Math.sqrt(pointEast * pointEast + pointNorth * pointNorth);
                float edgeOn = edgeOnBrightening(alongEast, alongNorth, pointEast, pointNorth);
                float surge = 1.0f + surgeStrength * (float) Math.exp(-sq((s - surgeAt) / 250.0f));
                float shown = alpha * endFade(along) * surge;
                bottomAlpha[point] = shown * horizonFade(lower, ground);
                topAlpha[point] = shown * horizonFade(upper, ground);
                int boost = colourByte((edgeOn - 1.0f) / (MAX_EDGE_ON - 1.0f));
                colours[point] = fringe << 16 | redTop << 8 | boost;
            }
            for (int point = 0; point < SEGMENTS; point++) {
                int next = point + 1;
                if (Math.max(Math.max(bottomAlpha[point], topAlpha[point]), Math.max(bottomAlpha[next], topAlpha[next])) <= 0.002f) {
                    continue;
                }
                sink.vertex(bottomX[point], bottomY[point], bottomZ[point], rays[point], 0.0f, colours[point], bottomAlpha[point]);
                sink.vertex(topX[point], topY[point], topZ[point], rays[point], 1.0f, colours[point], topAlpha[point]);
                sink.vertex(topX[next], topY[next], topZ[next], rays[next], 1.0f, colours[next], topAlpha[next]);
                sink.vertex(bottomX[next], bottomY[next], bottomZ[next], rays[next], 0.0f, colours[next], bottomAlpha[next]);
                quads++;
            }
        }
        return quads;
    }

    /** Kilometres along a band, from its centre, at one of its points. */
    private static float alongBand(float centre, float halfLength, int point) {
        return centre + (point / (float) SEGMENTS * 2.0f - 1.0f) * halfLength;
    }

    /** How far north of the camera a band hangs, in km: the main band, one behind it, a far one low on the horizon, and high ones on strong nights. */
    static float bandDistance(int band, long hash) {
        float roll = unit(hash, 0);
        return switch (band) {
            case 0 -> 200.0f + 180.0f * roll;
            case 1 -> 420.0f + 160.0f * roll;
            case 2 -> 650.0f + 300.0f * roll;
            case 3 -> 70.0f + 80.0f * roll;
            default -> -40.0f + 90.0f * roll;
        };
    }

    /**
     * How much brighter a sheet looks where the line of sight runs along it: one over the sine of
     * the angle between the sheet and the view, up to {@link #MAX_EDGE_ON}. Seen across, 1.
     */
    static float edgeOnBrightening(float alongEast, float alongNorth, float viewEast, float viewNorth) {
        float alongLength = (float) Math.sqrt(alongEast * alongEast + alongNorth * alongNorth);
        float viewLength = (float) Math.sqrt(viewEast * viewEast + viewNorth * viewNorth);
        if (alongLength < 1.0e-6f || viewLength < 1.0e-6f) {
            return 1.0f;
        }
        float sine = Math.abs(alongEast * viewNorth - alongNorth * viewEast) / (alongLength * viewLength);
        return Math.min(MAX_EDGE_ON, 1.0f / Math.max(sine, 1.0f / MAX_EDGE_ON));
    }

    /** Light from low over the horizon crosses more air: dimmer there, and gone below it. */
    static float horizonFade(float height, float ground) {
        float sine = height / (float) Math.sqrt(height * height + ground * ground);
        return smoothstep(-0.02f, 0.06f, sine) * (0.5f + 0.5f * smoothstep(0.0f, 0.3f, sine));
    }

    private static float endFade(float along) {
        return smoothstep(0.0f, 1.0f, Math.min(1.0f, Math.min(along, 1.0f - along) * 6.0f));
    }

    private static float smoothstep(float from, float to, float value) {
        float t = Math.clamp((value - from) / (to - from), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float sq(float value) {
        return value * value;
    }

    private static int colourByte(float value) {
        return Math.round(Math.clamp(value, 0.0f, 1.0f) * 255.0f);
    }

    private static float unit(long hash, long salt) {
        return (float) SkyEventSchedule.unit(SkyEventSchedule.mix(hash, salt));
    }
}
