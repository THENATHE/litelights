package com.thenathe.litelights.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.config.Pages;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credits are hearts beside their rows (credit-hearts-115): each page credit in the lang file is
 * one a page shows, and a credited row keeps room for its heart. CreditKeysTest checks that every
 * row credit belongs to an option.
 */
class CreditHeartsTest {
    @BeforeAll
    static void registerPages() {
        BuiltInPages.register();
    }

    @Test
    void thePageCreditsHaveTheirPagesAndTheirText() throws IOException {
        JsonObject english = english("/assets/litelights/lang/en_us.json");
        assertEquals("litelights.credit.overlays", PageModel.pageCreditKey(Pages.OVERLAYS));
        assertEquals("litelights.credit.far_terrain", PageModel.pageCreditKey(BuiltInPages.FAR_TERRAIN_PAGE));
        for (String key : english.keySet()) {
            if (key.startsWith("litelights.credit.") && !key.equals("litelights.credit.heart")) {
                assertTrue(key.equals(PageModel.pageCreditKey(Pages.OVERLAYS)) || key.equals(PageModel.pageCreditKey(BuiltInPages.FAR_TERRAIN_PAGE)),
                        key + " is a page credit no page shows");
            }
        }
        assertTrue(english.has("litelights.credit.heart"), "the heart's name for the narrator");
    }

    @Test
    void aCreditedRowKeepsRoomForItsHeart() {
        for (int room : new int[] {40, 120, 260}) {
            assertEquals(room, ScreenLayout.labelRoom(room, false, false), "no heart, no room taken");
            int heart = ScreenLayout.HEART_ROOM - ScreenLayout.HEART_MARGIN;
            assertEquals(room - heart, ScreenLayout.labelRoom(room, true, false), "a credited row keeps room for its heart");
            assertEquals(room - ScreenLayout.CHANGED_ROOM, ScreenLayout.labelRoom(room, false, true));
            assertEquals(room - heart - ScreenLayout.CHANGED_ROOM, ScreenLayout.labelRoom(room, true, true),
                    "the heart and the changed marker both");
        }
        // The heart is drawn 7 pixels wide, 4 after the name (CreditHeart draws 1 in from its box at 3),
        // and the name's room ends NAME_GAP before the row's own control (row-layout-116): a name
        // filling its room, then the heart and the changed marker, still end clear of the control.
        assertTrue(ScreenLayout.HEART_ROOM >= 4 + 7);
        for (int controlLeft : new int[] {60, 140, 400}) {
            int nameEnd = ScreenLayout.labelRoom(ScreenLayout.nameRoom(controlLeft), true, true);
            int markersEnd = nameEnd + ScreenLayout.HEART_ROOM + ScreenLayout.CHANGED_ROOM;
            assertTrue(markersEnd <= controlLeft - 2, "the heart and the marker end before the control at " + controlLeft);
        }
    }

    private static JsonObject english(String file) throws IOException {
        try (var stream = CreditHeartsTest.class.getResourceAsStream(file)) {
            assertNotNull(stream, file);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
