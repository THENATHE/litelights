package com.thenathe.litelights.util;

/** Hashing shared by the overlays and the sky events. */
public final class Hashing {
    private Hashing() {
    }

    /**
     * SplitMix64's finaliser: spreads every bit of {@code value} over the whole result. Exported
     * overlay packs and the sky-event schedule depend on its exact output, so it must never change.
     */
    public static long mix64(long value) {
        long h = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
