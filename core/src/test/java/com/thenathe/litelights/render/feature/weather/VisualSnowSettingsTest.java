package com.thenathe.litelights.render.feature.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.thenathe.litelights.render.RenderSettings;
import com.thenathe.litelights.render.RenderSettings.SnowyLeavesLook;
import com.thenathe.litelights.render.RenderSettings.VisualSnow;
import org.junit.jupiter.api.Test;

/** The visual snow's master switch and rows, as the shaders and meshes get them. */
class VisualSnowSettingsTest {
    @Test
    void withTheMasterOffNothingCounts() {
        VisualSnow off = VisualSnow.of(false, true, true, true, true, SnowyLeavesLook.TOPS, true, true);
        assertEquals(VisualSnow.DEFAULTS, off, "every row reads off, and the leaves keep today's look");
        assertEquals(0, SnowyLeaves.visualBits(off, true));
        assertEquals(RenderSettings.Weather.DEFAULTS.visualSnow(), VisualSnow.DEFAULTS);
    }

    @Test
    void eachRowIsItsOwnBit() {
        VisualSnow all = VisualSnow.of(true, true, true, true, true, SnowyLeavesLook.TOPS, true, true);
        assertEquals(SnowyLeaves.VISUAL_ON | SnowyLeaves.VISUAL_GRASS | SnowyLeaves.VISUAL_LEAF_TOPS | SnowyLeaves.VISUAL_FAN_TREES
            | SnowyLeaves.VISUAL_FAN_OVERHANGS, SnowyLeaves.visualBits(all, true));
        VisualSnow none = VisualSnow.of(true, false, false, false, false, SnowyLeavesLook.WHOLE, false, false);
        assertEquals(SnowyLeaves.VISUAL_ON, SnowyLeaves.visualBits(none, true));
        assertFalse(none.blocks());
    }

    @Test
    void theSnowUnderTreesNeedsSnowyLeavesAndGround() {
        VisualSnow trees = VisualSnow.of(true, false, false, true, false, SnowyLeavesLook.WHOLE, false, false);
        assertEquals(SnowyLeaves.VISUAL_ON | SnowyLeaves.VISUAL_FAN_TREES, SnowyLeaves.visualBits(trees, true));
        assertEquals(SnowyLeaves.VISUAL_ON, SnowyLeaves.visualBits(trees, false));
    }
}
