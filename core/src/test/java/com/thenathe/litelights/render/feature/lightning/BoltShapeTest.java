package com.thenathe.litelights.render.feature.lightning;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoltShapeTest {
    @Test
    void theMainChannelRunsFromTheCloudBaseDownToTheStrikePoint() {
        for (long seed = 0; seed < 500; seed++) {
            float height = 60.0f + seed % 200;
            BoltShape.Channel main = BoltShape.grow(seed * 0x9E3779B97F4A7C15L, height).channels().getFirst();
            float[] points = main.points();
            assertTrue(main.main());
            assertEquals(BoltShape.MAIN_SEGMENTS, main.segments());
            assertEquals(height, points[1], 1.0e-3f, "it starts at the cloud base");
            int last = points.length - 3;
            assertEquals(0.0f, points[last], 1.0e-3f);
            assertEquals(0.0f, points[last + 1], 1.0e-3f);
            assertEquals(0.0f, points[last + 2], 1.0e-3f, "it ends where vanilla's bolt strikes");
            for (int point = 3; point < points.length; point += 3) {
                assertTrue(points[point + 1] < points[point - 2], "it always heads down");
                float sideways = (float) Math.hypot(points[point] - points[point - 3], points[point + 2] - points[point - 1]);
                assertTrue(sideways < height, "no segment flies off sideways");
            }
        }
    }

    @Test
    void aSeedAlwaysGrowsTheSameBoltAndAnotherSeedAnother() {
        BoltShape first = BoltShape.grow(42L, 120.0f);
        BoltShape again = BoltShape.grow(42L, 120.0f);
        BoltShape other = BoltShape.grow(43L, 120.0f);
        assertEquals(first.channels().size(), again.channels().size());
        for (int channel = 0; channel < first.channels().size(); channel++) {
            assertArrayEquals(first.channels().get(channel).points(), again.channels().get(channel).points());
        }
        assertFalse(java.util.Arrays.equals(first.channels().getFirst().points(), other.channels().getFirst().points()));
    }

    @Test
    void twoToFourForksThatLeaveTheChannelHighUpAndStayAboveTheGround() {
        int[] forkCounts = new int[BoltShape.MOST_FORKS + 1];
        for (long seed = 0; seed < 1000; seed++) {
            BoltShape shape = BoltShape.grow(seed, 130.0f);
            List<BoltShape.Channel> channels = shape.channels();
            int forks = channels.size() - 1;
            assertTrue(forks >= 2 && forks <= BoltShape.MOST_FORKS);
            forkCounts[forks]++;
            float[] main = channels.getFirst().points();
            for (BoltShape.Channel fork : channels.subList(1, channels.size())) {
                assertFalse(fork.main());
                float[] points = fork.points();
                assertTrue(startsOnMain(points, main), "a fork leaves the main channel");
                assertTrue(points[1] > 130.0f * 0.3f, "forks leave in the upper part");
                for (int point = 1; point < points.length; point += 3) {
                    assertTrue(points[point] >= 1.0f - 1.0e-4f, "a fork stays above the ground");
                }
                assertTrue(fork.endWidth() < fork.startWidth() && fork.endLight() < fork.startLight(), "forks thin and fade");
            }
        }
        for (int forks = 2; forks <= BoltShape.MOST_FORKS; forks++) {
            assertTrue(forkCounts[forks] > 100, forks + " forks: " + forkCounts[forks]);
        }
    }

    @Test
    void aBoltIsAFewDozenQuads() {
        for (long seed = 0; seed < 200; seed++) {
            BoltShape shape = BoltShape.grow(seed, 150.0f);
            int quads = 0;
            for (BoltShape.Channel channel : shape.channels()) {
                // The main channel: a glow on either side and the core; a fork: its soft core's two halves.
                quads += channel.segments() * (channel.main() ? 3 : 2);
            }
            assertTrue(quads <= BoltShape.MAIN_SEGMENTS * 3 + BoltShape.MOST_FORKS * BoltShape.FORK_SEGMENTS * 2);
            assertTrue(quads <= 76, quads + " quads");
        }
    }

    @Test
    void aBoltFlaresWhileLitAndDimsBetweenStrikes() {
        assertEquals(1.0f, BranchingBolts.light(1, 0.5f));
        assertEquals(0.85f, BranchingBolts.light(0, 0.5f));
        float previous = 1.0f;
        for (int life = -1; life > -12; life--) {
            for (float partial = 0.0f; partial < 1.0f; partial += 0.25f) {
                float light = BranchingBolts.light(life, partial);
                assertTrue(light <= previous && light >= 0.12f, "dims toward its glow: " + light);
                previous = light;
            }
        }
        assertTrue(previous < 0.13f);
    }

    private static boolean startsOnMain(float[] fork, float[] main) {
        for (int point = 0; point < main.length; point += 3) {
            if (main[point] == fork[0] && main[point + 1] == fork[1] && main[point + 2] == fork[2]) {
                return true;
            }
        }
        return false;
    }
}
