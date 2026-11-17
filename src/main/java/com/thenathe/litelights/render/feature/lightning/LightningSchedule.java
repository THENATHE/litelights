package com.thenathe.litelights.render.feature.lightning;

import com.thenathe.litelights.render.LiteMath;
import com.thenathe.litelights.render.feature.weather.SkyEventSchedule;

/**
 * When and where lightning flashes inside the clouds. Like the sky events, everything comes from the
 * world's hashed seed and the game time, never from wall time or a random generator with state, so
 * every player on a world sees the same flash at the same moment in the same place, and a restart
 * changes nothing. The thunder level, which the server sends to every client, sets how many flashes
 * there are.
 *
 * <p>The sky is cut into square cells of {@link #CELL_BLOCKS} on the ground, and time into beats of
 * {@link #BEAT_TICKS}. Each cell has at most one flash a beat, starting at a hashed moment in it: a
 * patch of cloud lit from within by one to four quick flickers, sometimes spreading as it goes.
 * A flash may run on into the next beat, so the beat before is asked too. How busy a cell is
 * changes every {@link #SPELL_BEATS} beats, so a storm has its lively spells and its lulls.
 */
public final class LightningSchedule {
    public static final int CELL_BLOCKS = 640;
    public static final int BEAT_TICKS = 60;
    /** The cells looked at: the camera's and this many on every side, so flashes up to about a kilometre away show. */
    public static final int CELL_REACH = 1;
    public static final int MAX_FLICKERS = 4;
    /** A cell's liveliness holds for this many beats (two minutes). */
    static final int SPELL_BEATS = 40;
    /** The chance of a flash in a cell's beat at full thunder and average liveliness. */
    static final double FLASH_CHANCE = 0.2;
    /** A flicker rises to its peak over this share of its length, then dies away. */
    private static final float RISE = 0.12f;
    /** How long a flicker's light lasts, in multiples of its length. */
    static final float FLICKER_TAIL = 1.6f;

    private static final long SALT = 0x6C69676874L;

    private LightningSchedule() {
    }

    /** One flash: filled in by {@link #flash}, read with {@link #brightness}, {@link #centreX} and the like. */
    public static final class Flash {
        long cell;
        long startTick;
        double x;
        double z;
        float driftX;
        float driftZ;
        float startRadius;
        float endRadius;
        float length;
        int flickers;
        final float[] flickerStart = new float[MAX_FLICKERS];
        final float[] flickerLength = new float[MAX_FLICKERS];
        final float[] flickerPeak = new float[MAX_FLICKERS];

        public long startTick() {
            return startTick;
        }

        public int flickers() {
            return flickers;
        }

        /** How many ticks after its start the flash's last light is gone. */
        public float length() {
            return length;
        }

        /** Whether the flash moves across the sky as it flickers, rather than staying in one patch. */
        public boolean spreads() {
            return driftX != 0.0f || driftZ != 0.0f;
        }
    }

    /** How strong a storm is for lightning: none in light thunder, full well before the thunder peaks. */
    public static float storminess(float thunder) {
        return LiteMath.smoothstep(0.35f, 0.9f, thunder);
    }

    /** The cell a block coordinate is in. */
    public static int cellOf(double coordinate) {
        return (int) Math.floor(coordinate / CELL_BLOCKS);
    }

    /** The beat a game tick is in. */
    public static long beatOf(long tick) {
        return Math.floorDiv(tick, BEAT_TICKS);
    }

    /**
     * Fills {@code out} with the flash of a cell in a beat, if it has one at this storminess
     * ({@link #storminess}). Returns whether it does.
     */
    public static boolean flash(long seed, int cellX, int cellZ, long beat, float storminess, Flash out) {
        if (storminess <= 0.0f) {
            return false;
        }
        long cell = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        long cellHash = SkyEventSchedule.mix(seed ^ SALT, cell);
        double liveliness = 0.25 + 1.5 * SkyEventSchedule.unit(SkyEventSchedule.mix(cellHash, Math.floorDiv(beat, SPELL_BEATS)));
        long hash = SkyEventSchedule.mix(cellHash ^ 0x666C617368L, beat);
        if (SkyEventSchedule.unit(hash) >= FLASH_CHANCE * liveliness * storminess) {
            return false;
        }
        out.cell = cell;
        out.startTick = beat * BEAT_TICKS + (long) Math.floor(unit(hash, 1) * BEAT_TICKS);
        out.x = ((double) cellX + unit(hash, 2)) * CELL_BLOCKS;
        out.z = ((double) cellZ + unit(hash, 3)) * CELL_BLOCKS;

        // One flicker now and then, mostly two or three in quick succession, rarely four.
        double count = unit(hash, 4);
        out.flickers = count < 0.3 ? 1 : count < 0.65 ? 2 : count < 0.95 ? 3 : 4;
        float at = 0.0f;
        for (int flicker = 0; flicker < out.flickers; flicker++) {
            long flickerHash = SkyEventSchedule.mix(hash, 16 + flicker);
            if (flicker > 0) {
                at += out.flickerLength[flicker - 1] + 1.0f + (float) unit(flickerHash, 1) * 4.0f;
            }
            out.flickerStart[flicker] = at;
            out.flickerLength[flicker] = 1.5f + (float) unit(flickerHash, 2) * 2.5f;
            // Later flickers are often weaker, but now and then one is the brightest.
            out.flickerPeak[flicker] = (float) (flicker == 0 ? 0.65 + 0.35 * unit(flickerHash, 3) : 0.35 + 0.65 * unit(flickerHash, 3));
        }
        int last = out.flickers - 1;
        out.length = out.flickerStart[last] + out.flickerLength[last] * FLICKER_TAIL;

        // A third of the flashes crawl across the deck as they flicker, widening; the rest stay put.
        if (unit(hash, 5) < 0.35) {
            double heading = unit(hash, 6) * Math.PI * 2.0;
            float reach = 80.0f + (float) unit(hash, 7) * 160.0f;
            out.driftX = (float) Math.sin(heading) * reach;
            out.driftZ = (float) -Math.cos(heading) * reach;
            out.startRadius = 45.0f + (float) unit(hash, 8) * 25.0f;
            out.endRadius = out.startRadius + 60.0f + (float) unit(hash, 9) * 60.0f;
        } else {
            out.driftX = 0.0f;
            out.driftZ = 0.0f;
            out.startRadius = 45.0f + (float) unit(hash, 8) * 65.0f;
            out.endRadius = out.startRadius * 1.15f;
        }
        return true;
    }

    /** The flash's brightness at a game time (ticks, with the partial tick), 0 before and after it. */
    public static float brightness(Flash flash, double tick) {
        float since = (float) (tick - flash.startTick);
        if (since < 0.0f || since > flash.length) {
            return 0.0f;
        }
        float brightest = 0.0f;
        for (int flicker = 0; flicker < flash.flickers; flicker++) {
            float phase = (since - flash.flickerStart[flicker]) / flash.flickerLength[flicker];
            brightest = Math.max(brightest, flash.flickerPeak[flicker] * pulse(phase));
        }
        return brightest;
    }

    /** One flicker's shape over its length: a sharp rise, then a quick fall, gone by {@link #FLICKER_TAIL}. */
    static float pulse(float phase) {
        if (phase <= 0.0f || phase >= FLICKER_TAIL) {
            return 0.0f;
        }
        if (phase < RISE) {
            return phase / RISE;
        }
        float fall = (phase - RISE) / (FLICKER_TAIL - RISE);
        return (float) Math.exp(-3.5 * fall) * (1.0f - fall);
    }

    /** How far through its life the flash is at a game time, 0 to 1, for its drift and widening. */
    public static float progress(Flash flash, double tick) {
        return LiteMath.clamp01((float) ((tick - flash.startTick) / Math.max(flash.length, 1.0f)));
    }

    public static double centreX(Flash flash, double tick) {
        return flash.x + flash.driftX * progress(flash, tick);
    }

    public static double centreZ(Flash flash, double tick) {
        return flash.z + flash.driftZ * progress(flash, tick);
    }

    public static float radius(Flash flash, double tick) {
        return LiteMath.lerp(flash.startRadius, flash.endRadius, progress(flash, tick));
    }

    private static double unit(long hash, long salt) {
        return SkyEventSchedule.unit(SkyEventSchedule.mix(hash, salt));
    }
}
