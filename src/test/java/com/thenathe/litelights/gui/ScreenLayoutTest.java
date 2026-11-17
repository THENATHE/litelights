package com.thenathe.litelights.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The description box and the rows above it (followb097): the box never grows over the row it
 * describes, and the list can scroll its last row clear of the box at its largest.
 */
class ScreenLayoutTest {
    private static final int[][] WINDOWS = {{960, 540}, {640, 360}, {427, 240}, {1920, 1080}, {480, 270}};

    @Test
    void theBoxStopsBelowTheDescribedRow() {
        for (int[] window : WINDOWS) {
            for (boolean full : new boolean[] {false, true}) {
                ScreenLayout layout = ScreenLayout.of(window[0], window[1], full, 13);
                if (layout.describeMaxH() == 0) {
                    continue;
                }
                for (int rowBottom = layout.listY(); rowBottom <= layout.bodyBottom(); rowBottom += 3) {
                    ScreenLayout fitted = layout.withDescribeBelow(10_000, rowBottom);
                    boolean roomForTheSmallest = rowBottom <= layout.bodyBottom() - 4 - ScreenLayout.DESCRIBE_MIN_H;
                    if (roomForTheSmallest) {
                        assertTrue(fitted.listBottom() >= rowBottom, "the row stays in the list: " + window[0] + "x" + window[1]);
                    } else {
                        assertEquals(ScreenLayout.DESCRIBE_MIN_H, fitted.describeH(), "no room: the box stays at its smallest");
                    }
                    assertTrue(fitted.describeH() <= layout.describeMaxH());
                    assertEquals(fitted.describeY(), fitted.listBottom() + 4);
                }
                ScreenLayout high = layout.withDescribeBelow(10_000, layout.listY() + 20);
                assertEquals(layout.describeMaxH(), high.describeH(), "a row near the top leaves the box all its room");
                ScreenLayout shortText = layout.withDescribeBelow(40, layout.listY() + 20);
                assertEquals(Math.max(ScreenLayout.DESCRIBE_MIN_H, Math.min(40, layout.describeMaxH())), shortText.describeH());
            }
        }
    }

    @Test
    void theListScrollsAgainstItsShortestHeight() {
        for (int[] window : WINDOWS) {
            ScreenLayout layout = ScreenLayout.of(window[0], window[1], false, 13);
            ScreenLayout largest = layout.withDescribeH(10_000);
            assertEquals(largest.listH(), layout.shortestListH(), "the list's height under the largest box");
            assertEquals(largest.listH(), largest.shortestListH(), "whatever the box's size now");
            assertTrue(layout.shortestListH() <= layout.listH());
        }
    }
}
