package com.thenathe.litelights.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HashingTest {
    @Test
    void matchesSplitMix64() {
        // SplitMix64 seeded with 0: its first two outputs are the finaliser of one and two golden-ratio steps.
        assertEquals(0xE220A8397B1DCDAFL, Hashing.mix64(0x9E3779B97F4A7C15L));
        assertEquals(0x6E789E6AA1B965F4L, Hashing.mix64(0x9E3779B97F4A7C15L * 2));
    }
}
