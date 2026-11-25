package com.thenathe.litelights.render.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOitRenderTypesTest {
    @AfterEach
    void stopReplaying() {
        NoOitRenderTypes.endReplay();
    }

    @Test
    void ordinaryDrawsAreUntouchedOutsideTheReplay() {
        // The mob shadow map, the outline mask and Improved Transparency off all draw this way.
        assertFalse(NoOitRenderTypes.skipOrdinaryDraw(true));
        assertFalse(NoOitRenderTypes.skipOrdinaryDraw(false));
        assertFalse(NoOitRenderTypes.drawingOne());
    }

    @Test
    void theReplayDrawsOnlyWhatTheOitStagesLeftOut() {
        NoOitRenderTypes.beginReplay();
        assertTrue(NoOitRenderTypes.skipOrdinaryDraw(true), "drawn by the OIT stages already");
        assertFalse(NoOitRenderTypes.drawingOne());
        assertFalse(NoOitRenderTypes.skipOrdinaryDraw(false), "left out of the OIT stages");
        assertTrue(NoOitRenderTypes.drawingOne());
        NoOitRenderTypes.ordinaryDrawDone();
        assertFalse(NoOitRenderTypes.drawingOne(), "particles and text after it are dropped again");
    }

    @Test
    void endingTheReplayLetsEverythingThroughAgain() {
        NoOitRenderTypes.beginReplay();
        NoOitRenderTypes.skipOrdinaryDraw(false);
        NoOitRenderTypes.endReplay();
        assertFalse(NoOitRenderTypes.drawingOne());
        assertFalse(NoOitRenderTypes.skipOrdinaryDraw(true));
    }
}
