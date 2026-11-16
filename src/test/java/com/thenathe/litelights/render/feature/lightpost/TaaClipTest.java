package com.thenathe.litelights.render.feature.lightpost;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TAA's colour clip (taa.fsh, through TaaMirror): what history it keeps and what it lets go. */
class TaaClipTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void yCoCgGoesBackToTheSameColour() {
        for (Vector3f colour : new Vector3f[] {new Vector3f(0.2f, 0.7f, 0.1f), new Vector3f(1.0f, 0.0f, 0.5f), new Vector3f(0.0f)}) {
            Vector3f back = TaaMirror.fromYCoCg(TaaMirror.toYCoCg(colour));
            assertEquals(colour.x, back.x, EPSILON);
            assertEquals(colour.y, back.y, EPSILON);
            assertEquals(colour.z, back.z, EPSILON);
        }
    }

    @Test
    void historyInsideTheBoxIsKeptAsItIs() {
        Vector3f low = new Vector3f(0.2f, -0.1f, -0.1f);
        Vector3f high = new Vector3f(0.6f, 0.1f, 0.1f);
        Vector3f inside = new Vector3f(0.4f, 0.05f, -0.02f);
        assertEquals(inside, TaaMirror.clipToBox(inside, low, high));
    }

    @Test
    void historyOutsideMovesStraightTowardTheCentreOntoTheBox() {
        Vector3f low = new Vector3f(0.2f, -0.1f, -0.1f);
        Vector3f high = new Vector3f(0.6f, 0.1f, 0.1f);
        Vector3f centre = new Vector3f(0.4f, 0.0f, 0.0f);
        Vector3f outside = new Vector3f(0.9f, 0.05f, -0.05f);
        Vector3f clipped = TaaMirror.clipToBox(outside, low, high);
        // On the box's face...
        assertEquals(0.6f, clipped.x, 1.0e-3f);
        // ...and on the line from the centre to the old colour, so its hue doesn't swing.
        Vector3f toOld = new Vector3f(outside).sub(centre).normalize();
        Vector3f toClipped = new Vector3f(clipped).sub(centre).normalize();
        assertEquals(1.0f, toOld.dot(toClipped), 1.0e-4f);
    }

    @Test
    void insideOneFlatTexelTheHistoryTakesTheTexelsColour() {
        // A magnified block texture: all nine pixels lie in one texel. History that picked up the
        // neighbouring texel's colour while the camera moved is pulled back onto this texel's.
        Vector3f texel = new Vector3f(0.55f, 0.35f, 0.2f);
        Vector3f[] neighbourhood = new Vector3f[9];
        java.util.Arrays.fill(neighbourhood, texel);
        Vector3f[] box = TaaMirror.box(neighbourhood, 1.25f);
        Vector3f bled = TaaMirror.toYCoCg(new Vector3f(0.3f, 0.6f, 0.2f));
        Vector3f kept = TaaMirror.fromYCoCg(TaaMirror.clipToBox(bled, box[0], box[1]));
        assertEquals(texel.x, kept.x, 1.0e-3f);
        assertEquals(texel.y, kept.y, 1.0e-3f);
        assertEquals(texel.z, kept.z, 1.0e-3f);
    }

    @Test
    void atATexelEdgeBothTexelsAreAllowed() {
        // Three columns of dark texel and six of light: the edge's own colours stay in range, so
        // the settled anti-aliased edge isn't clipped away.
        Vector3f dark = new Vector3f(0.1f, 0.1f, 0.12f);
        Vector3f light = new Vector3f(0.8f, 0.78f, 0.7f);
        Vector3f[] neighbourhood = {dark, light, light, dark, light, light, dark, light, light};
        Vector3f[] box = TaaMirror.box(neighbourhood, 1.25f);
        Vector3f halfway = TaaMirror.toYCoCg(new Vector3f(dark).lerp(light, 0.5f));
        assertEquals(halfway, TaaMirror.clipToBox(halfway, box[0], box[1]));
    }

    @Test
    void theStandardDeviationNarrowsTheBoxAgainstAStrayPixel() {
        // One bright pixel (a raindrop) among eight grey ones: the range alone would keep a ghost
        // of a drop that has gone; the box around the mean doesn't reach it.
        Vector3f grey = new Vector3f(0.4f, 0.42f, 0.4f);
        Vector3f drop = new Vector3f(0.95f, 0.95f, 1.0f);
        Vector3f[] neighbourhood = {grey, grey, grey, grey, drop, grey, grey, grey, grey};
        Vector3f[] box = TaaMirror.box(neighbourhood, 1.25f);
        Vector3f oldDrop = TaaMirror.toYCoCg(drop);
        Vector3f clipped = TaaMirror.clipToBox(oldDrop, box[0], box[1]);
        float reachedOfTheWay = (clipped.x - TaaMirror.toYCoCg(grey).x) / (oldDrop.x - TaaMirror.toYCoCg(grey).x);
        assertTrue(reachedOfTheWay < 0.6f, "kept " + reachedOfTheWay + " of the way to the drop");
    }
}
