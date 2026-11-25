package com.thenathe.litelights.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.config.RowState;
import com.thenathe.litelights.gui.CompatibilityNotices.Shown;
import com.thenathe.litelights.gui.CompatibilityNotices.Status;
import com.thenathe.litelights.overlays.sodium.QuadFacingFix;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Compatibility notices show for each state of the fixes behind them. */
class CompatibilityNoticesTest {
    @Test
    void theFaceCullingFixIsActiveOnlyOnceItIsIn() {
        Status applied = CompatibilityNotices.facingFix(QuadFacingFix.Status.APPLIED);
        assertEquals(Shown.ACTIVE, applied.shown());
        assertNull(applied.reasonKey());
        assertEquals(RowState.READY, applied.rowState());
    }

    @Test
    void theFaceCullingFixSaysWhyItIsNotNeeded() {
        assertFacing(QuadFacingFix.Status.NO_SODIUM, Shown.NOT_NEEDED, "no_sodium");
        assertFacing(QuadFacingFix.Status.NO_CONTINUITY, Shown.NOT_NEEDED, "no_continuity");
        assertFacing(QuadFacingFix.Status.FIXED_IN_SODIUM, Shown.NOT_NEEDED, "fixed_in_sodium");
    }

    @Test
    void theFaceCullingFixSaysWhyItIsSwitchedOff() {
        assertFacing(QuadFacingFix.Status.UNKNOWN_SODIUM, Shown.SWITCHED_OFF, "unknown_sodium");
        assertFacing(QuadFacingFix.Status.NOT_CHECKED, Shown.SWITCHED_OFF, "unknown_sodium");
        assertFacing(QuadFacingFix.Status.FAILED, Shown.SWITCHED_OFF, "failed");
    }

    @Test
    void theWakesFixIsActiveWithWakesAndNotNeededWithout() {
        assertEquals(Status.ACTIVE, CompatibilityNotices.wakesFix(true, false));
        Status without = CompatibilityNotices.wakesFix(false, false);
        assertEquals(Shown.NOT_NEEDED, without.shown());
        assertEquals(new RowState.Unavailable("litelights.compat.wakes_fix.no_wakes"), without.rowState());
    }

    @Test
    void aFailedWakesFixShowsAsSwitchedOff() {
        for (boolean wakes : new boolean[] {true, false}) {
            Status failed = CompatibilityNotices.wakesFix(wakes, true);
            assertEquals(Shown.SWITCHED_OFF, failed.shown());
            assertEquals("litelights.compat.wakes_fix.failed", failed.reasonKey());
        }
    }

    @Test
    void everyNoticeHasItsTextAndTheTemporaryHoverText() throws IOException {
        JsonObject english;
        try (var stream = getClass().getResourceAsStream("/assets/litelights/lang/en_us.json")) {
            english = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (Shown shown : Shown.values()) {
            assertTrue(english.has("litelights.compat." + shown.name().toLowerCase(Locale.ROOT)), shown.name());
        }
        for (QuadFacingFix.Status fix : QuadFacingFix.Status.values()) {
            String reason = CompatibilityNotices.facingFix(fix).reasonKey();
            assertTrue(reason == null || english.has(reason), fix.name());
        }
        assertTrue(english.has(CompatibilityNotices.wakesFix(false, false).reasonKey()));
        assertTrue(english.has(CompatibilityNotices.wakesFix(true, true).reasonKey()));
        assertEquals("Temporary", english.get("litelights.compat.facing_fix.tag").getAsString());
        assertEquals("Temporary", english.get("litelights.compat.wakes_fix.tag").getAsString());
        assertEquals("This is a fix for a current bug between Continuity and Sodium, and will be removed once the fix is "
            + "implemented in either of those mods.", english.get("litelights.compat.facing_fix.tag.tooltip").getAsString());
        assertEquals("This is a fix for a current bug between Wakes and Improved Transparency, and will be removed once the fix "
            + "is implemented in Wakes.", english.get("litelights.compat.wakes_fix.tag.tooltip").getAsString());
    }

    private static void assertFacing(QuadFacingFix.Status fix, Shown shown, String reason) {
        Status status = CompatibilityNotices.facingFix(fix);
        assertEquals(shown, status.shown(), fix.name());
        assertEquals(new RowState.Unavailable("litelights.compat.facing_fix." + reason), status.rowState(), fix.name());
    }
}
