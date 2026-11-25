package com.thenathe.litelights.render.timing;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbStatisticsTest {
    private static final double TOLERANCE = 1.0e-9;

    @Test
    void eachSideGetsItsMeanMedianAndOnePercentLow() {
        AbStatistics statistics = new AbStatistics();
        // 200 frames a side: A at 4 ms with two slow 20 ms frames, B at 3 ms with two at 10 ms.
        for (int i = 0; i < 200; i++) {
            statistics.add("frame", AbSchedule.Side.A, 0, i < 2 ? 20.0 : 4.0);
            statistics.add("frame", AbSchedule.Side.B, 1, i < 2 ? 10.0 : 3.0);
        }
        AbStatistics.Summary summary = statistics.summaries().getFirst();
        assertEquals(200, summary.a().samples());
        assertEquals((2 * 20.0 + 198 * 4.0) / 200, summary.a().meanMs(), TOLERANCE);
        assertEquals(4.0, summary.a().medianMs(), TOLERANCE);
        // The slowest 1 % of 200 frames is two frames.
        assertEquals(20.0, summary.a().onePercentLowMs(), TOLERANCE);
        assertEquals(10.0, summary.b().onePercentLowMs(), TOLERANCE);
        assertEquals(-10.0, summary.onePercentLowDifferenceMs(), TOLERANCE);
        assertEquals(summary.b().meanMs() - summary.a().meanMs(), summary.meanDifferenceMs(), TOLERANCE);
    }

    @Test
    void pairedDifferenceFollowsTheSceneDriftingOverTheRun() {
        // The scene gets 1 ms slower every pair of blocks; B is always 0.5 ms cheaper than the A just before it.
        AbStatistics statistics = new AbStatistics();
        for (int pair = 0; pair < 10; pair++) {
            double base = 5.0 + pair;
            for (int frame = 0; frame < 6; frame++) {
                statistics.add("gpu.frame", AbSchedule.Side.A, 2L * pair, base);
                statistics.add("gpu.frame", AbSchedule.Side.B, 2L * pair + 1, base - 0.5);
            }
        }
        AbStatistics.Summary summary = statistics.summaries().getFirst();
        assertEquals(10, summary.pairs());
        assertEquals(-0.5, summary.pairedDifferenceMs(), TOLERANCE);
        assertEquals(0.0, summary.pairedErrorMs(), TOLERANCE);
    }

    @Test
    void pairedErrorShrinksWithMorePairs() {
        double fewPairs = pairedErrorWithNoise(4);
        double manyPairs = pairedErrorWithNoise(64);
        assertTrue(manyPairs < fewPairs, manyPairs + " should be below " + fewPairs);
    }

    private static double pairedErrorWithNoise(int pairs) {
        AbStatistics statistics = new AbStatistics();
        for (int pair = 0; pair < pairs; pair++) {
            double noise = pair % 2 == 0 ? 0.2 : -0.2;
            statistics.add("frame", AbSchedule.Side.A, 2L * pair, 5.0);
            statistics.add("frame", AbSchedule.Side.B, 2L * pair + 1, 5.0 + noise);
        }
        return statistics.summaries().getFirst().pairedErrorMs();
    }

    @Test
    void aBlockWithoutItsPartnerIsNotPaired() {
        AbStatistics statistics = new AbStatistics();
        statistics.add("redraw.gpu", AbSchedule.Side.A, 0, 2.0);
        statistics.add("redraw.gpu", AbSchedule.Side.B, 3, 1.0);
        AbStatistics.Summary summary = statistics.summaries().getFirst();
        assertEquals(0, summary.pairs());
        assertTrue(Double.isNaN(summary.pairedDifferenceMs()));
        assertEquals(-1.0, summary.meanDifferenceMs(), TOLERANCE);
    }

    @Test
    void aSideWithoutSamplesHasNoTimes() {
        AbStatistics statistics = new AbStatistics();
        statistics.add("frame.redraw", AbSchedule.Side.B, 1, 7.0);
        AbStatistics.Summary summary = statistics.summaries().getFirst();
        assertEquals(0, summary.a().samples());
        assertTrue(Double.isNaN(summary.a().meanMs()));
        assertTrue(Double.isNaN(summary.meanDifferenceMs()));
    }

    @Test
    void metricsKeepTheOrderTheyFirstAppearedIn() {
        AbStatistics statistics = new AbStatistics();
        statistics.add("frame", AbSchedule.Side.A, 0, 1.0);
        statistics.add("gpu.frame", AbSchedule.Side.A, 0, 1.0);
        statistics.add("post.BLOOM", AbSchedule.Side.A, 0, 1.0);
        statistics.add("frame", AbSchedule.Side.B, 1, 1.0);
        List<String> names = statistics.summaries().stream().map(AbStatistics.Summary::metric).toList();
        assertEquals(List.of("frame", "gpu.frame", "post.BLOOM"), names);
        statistics.clear();
        assertTrue(statistics.isEmpty());
    }
}
