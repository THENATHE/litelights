package com.thenathe.litelights.render.timing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The samples of an interleaved A/B timing run, per metric and side, and what they add up to.
 *
 * <p>Besides each side's mean, median and 1 % low, the difference is also worked out block by
 * block: each side-B block is paired with the side-A block just before it, and the mean of those
 * pair differences, with its standard error, is the answer that is least moved by the scene
 * drifting over the run (chunks finishing, the GPU clock changing).
 */
public final class AbStatistics {
    /** Enough for well over an hour at 200 frames a second; later samples are dropped. */
    static final int MAX_SAMPLES_PER_SIDE = 1_000_000;

    private final Map<String, Metric> metrics = new LinkedHashMap<>();

    public void add(String metric, AbSchedule.Side side, long block, double millis) {
        metrics.computeIfAbsent(metric, name -> new Metric()).add(side, block, millis);
    }

    public boolean isEmpty() {
        return metrics.isEmpty();
    }

    public void clear() {
        metrics.clear();
    }

    /** One summary per metric, in the order the metrics first had a sample. */
    public List<Summary> summaries() {
        List<Summary> result = new ArrayList<>();
        for (Map.Entry<String, Metric> entry : metrics.entrySet()) {
            result.add(entry.getValue().summary(entry.getKey()));
        }
        return result;
    }

    /** One side of one metric; every time is in milliseconds. A side without samples has NaN times. */
    public record SideSummary(int samples, double meanMs, double medianMs, double onePercentLowMs) {
        static SideSummary of(double[] samples, int count) {
            if (count == 0) {
                return new SideSummary(0, Double.NaN, Double.NaN, Double.NaN);
            }
            double[] sorted = Arrays.copyOf(samples, count);
            Arrays.sort(sorted);
            double total = 0.0;
            for (double sample : sorted) {
                total += sample;
            }
            // "1 % low" as a time, as the QA frame timer gives it: the mean of the slowest 1 %.
            int slowest = Math.max(1, count / 100);
            double slowestTotal = 0.0;
            for (int i = count - slowest; i < count; i++) {
                slowestTotal += sorted[i];
            }
            return new SideSummary(count, total / count, sorted[count / 2], slowestTotal / slowest);
        }
    }

    /**
     * A metric's two sides and their difference, B minus A (negative: B is faster).
     *
     * @param meanDifferenceMs B's mean minus A's
     * @param onePercentLowDifferenceMs B's 1 % low minus A's
     * @param pairs side-A and side-B blocks next to each other that both had samples
     * @param pairedDifferenceMs the mean over those pairs of B's block mean minus A's
     * @param pairedErrorMs the standard error of that mean (NaN with fewer than two pairs)
     */
    public record Summary(String metric, SideSummary a, SideSummary b, double meanDifferenceMs, double onePercentLowDifferenceMs,
                          int pairs, double pairedDifferenceMs, double pairedErrorMs) {
    }

    private static final class Metric {
        private final Samples a = new Samples();
        private final Samples b = new Samples();
        /** Per block: the sum of its samples and how many there were. */
        private final Map<Long, double[]> blocks = new HashMap<>();

        void add(AbSchedule.Side side, long block, double millis) {
            (side == AbSchedule.Side.A ? a : b).add(millis);
            double[] totals = blocks.computeIfAbsent(block, key -> new double[2]);
            totals[0] += millis;
            totals[1] += 1.0;
        }

        Summary summary(String name) {
            SideSummary sideA = SideSummary.of(a.values, a.count);
            SideSummary sideB = SideSummary.of(b.values, b.count);
            List<Double> differences = new ArrayList<>();
            for (Map.Entry<Long, double[]> entry : blocks.entrySet()) {
                long block = entry.getKey();
                if (block % 2 != 1) {
                    continue;
                }
                double[] before = blocks.get(block - 1);
                if (before != null) {
                    double[] after = entry.getValue();
                    differences.add(after[0] / after[1] - before[0] / before[1]);
                }
            }
            double pairedMean = Double.NaN;
            double pairedError = Double.NaN;
            if (!differences.isEmpty()) {
                double total = 0.0;
                for (double difference : differences) {
                    total += difference;
                }
                pairedMean = total / differences.size();
                if (differences.size() > 1) {
                    double squares = 0.0;
                    for (double difference : differences) {
                        squares += (difference - pairedMean) * (difference - pairedMean);
                    }
                    double variance = squares / (differences.size() - 1);
                    pairedError = Math.sqrt(variance / differences.size());
                }
            }
            return new Summary(name, sideA, sideB, sideB.meanMs() - sideA.meanMs(), sideB.onePercentLowMs() - sideA.onePercentLowMs(),
                differences.size(), pairedMean, pairedError);
        }
    }

    /** A growable list of doubles, capped at {@link #MAX_SAMPLES_PER_SIDE}. */
    private static final class Samples {
        private double[] values = new double[256];
        private int count;

        void add(double value) {
            if (count == MAX_SAMPLES_PER_SIDE) {
                return;
            }
            if (count == values.length) {
                values = Arrays.copyOf(values, Math.min(values.length * 2, MAX_SAMPLES_PER_SIDE));
            }
            values[count++] = value;
        }
    }
}
