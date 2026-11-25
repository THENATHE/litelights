package com.thenathe.litelights.render.timing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbScheduleTest {
    @Test
    void sidesAlternateEveryBlockStartingOnA() {
        AbSchedule schedule = new AbSchedule(8, 2, 100);
        assertEquals(AbSchedule.Side.A, schedule.sideOf(100));
        assertEquals(AbSchedule.Side.A, schedule.sideOf(107));
        assertEquals(AbSchedule.Side.B, schedule.sideOf(108));
        assertEquals(AbSchedule.Side.B, schedule.sideOf(115));
        assertEquals(AbSchedule.Side.A, schedule.sideOf(116));
        assertEquals(3, schedule.block(124));
    }

    @Test
    void flipsAtTheStartOfEveryBlockOnly() {
        AbSchedule schedule = new AbSchedule(8, 2, 100);
        assertTrue(schedule.flipsAt(100));
        assertFalse(schedule.flipsAt(101));
        assertTrue(schedule.flipsAt(108));
        assertFalse(schedule.flipsAt(99));
    }

    @Test
    void settlingFramesAfterEachFlipAreNotMeasured() {
        AbSchedule schedule = new AbSchedule(8, 2, 100);
        assertFalse(schedule.measured(100));
        assertFalse(schedule.measured(101));
        assertTrue(schedule.measured(102));
        assertTrue(schedule.measured(107));
        assertFalse(schedule.measured(108));
        assertFalse(schedule.measured(109));
        assertTrue(schedule.measured(110));
    }

    @Test
    void framesBeforeTheRunAreNeverMeasured() {
        AbSchedule schedule = new AbSchedule(4, 0, 50);
        assertFalse(schedule.covers(49));
        assertFalse(schedule.measured(49));
        assertTrue(schedule.measured(50));
        assertEquals(0, schedule.block(10));
    }

    @Test
    void aBlockMustLeaveAFrameToMeasure() {
        assertThrows(IllegalArgumentException.class, () -> new AbSchedule(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AbSchedule(4, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new AbSchedule(4, -1, 0));
    }
}
