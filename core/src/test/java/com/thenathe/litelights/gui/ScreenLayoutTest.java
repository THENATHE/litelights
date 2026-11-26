package com.thenathe.litelights.gui;

import com.thenathe.litelights.gui.widget.ResetWidget;
import com.thenathe.litelights.gui.widget.ToggleWidget;
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

    /**
     * row-layout-116: the cost column is flush right, the reset just before it and the controls
     * before that, and a row's name runs up to its own control, so a switch's name gets more room
     * than a slider's or a choice's.
     */
    @Test
    void aSwitchLeavesItsNameMoreRoomThanAWideControl() {
        for (int[] window : WINDOWS) {
            for (boolean full : new boolean[] {false, true}) {
                ScreenLayout layout = ScreenLayout.of(window[0], window[1], full, 13).withCostColumn(38);
                assertEquals(layout.listW(), layout.costDx() + layout.costW(), "the cost column ends at the list's edge");
                assertTrue(layout.resetDx() + ResetWidget.SIZE < layout.costDx(), "the reset comes before the cost");
                assertTrue(layout.controlEndDx() < layout.resetDx(), "the control comes before the reset");
                int switchRoom = ScreenLayout.nameRoom(layout.controlEndDx() - ToggleWidget.WIDTH);
                int sliderRoom = ScreenLayout.nameRoom(layout.controlDx());
                assertTrue(switchRoom > sliderRoom, "a switch's name has more room: " + switchRoom + " against " + sliderRoom);
                assertEquals(layout.controlW() - ToggleWidget.WIDTH, switchRoom - sliderRoom);
                assertTrue(sliderRoom > 0, "a slider's row still has room for a name at " + window[0] + "x" + window[1]);
            }
        }
    }

    @Test
    void theCostShowsAsDotsOnlyOnTheNarrowestLists() {
        ScreenLayout docked = ScreenLayout.of(960, 540, false, 13).withCostColumn(38);
        assertTrue(docked.costWords(), "docked at 1920x1080 (GUI scale 2) the costs are words");
        assertEquals(38, docked.costW());
        ScreenLayout narrow = new ScreenLayout(false, 0, 0, 200, 200, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, true, 0,
                0, 0, ScreenLayout.COST_WORDS_MIN_LIST - 1, 200, 0, 0, 0, 180, 38).withCostColumn(38);
        assertEquals(ScreenLayout.COST_DOT_W, narrow.costW());
    }
}
