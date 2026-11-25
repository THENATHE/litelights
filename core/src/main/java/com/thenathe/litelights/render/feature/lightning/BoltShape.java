package com.thenathe.litelights.render.feature.lightning;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The shape of one branching bolt: a jagged main channel from the cloud base down to the strike
 * point, and a few forks that leave it in its upper part and thin out and fade as they go. Grown
 * from vanilla's bolt seed, so a bolt keeps its shape while it is lit and takes a new one when it
 * strikes again (vanilla gives it a new seed then).
 *
 * <p>Positions are in blocks relative to the strike point, y up. The main channel is a random walk
 * pinned at both ends (the first point under the cloud, the last on the ground), which gives the
 * familiar kinked look at every scale of the segments.
 */
final class BoltShape {
    static final int MAIN_SEGMENTS = 12;
    static final int FORK_SEGMENTS = 5;
    static final int MOST_FORKS = 4;
    /** How far the channel strays sideways per segment, as a share of the segment's height. */
    private static final float KINK = 0.55f;

    /**
     * One channel, from where it starts down to its tip: xyz of each point in turn. Width and light
     * are shares of the main channel's, at its start and at its tip.
     */
    record Channel(float[] points, float startWidth, float endWidth, float startLight, float endLight, boolean main) {
        int segments() {
            return points.length / 3 - 1;
        }
    }

    private final List<Channel> channels;

    private BoltShape(List<Channel> channels) {
        this.channels = channels;
    }

    List<Channel> channels() {
        return channels;
    }

    /** How many segments the whole bolt has. */
    int segments() {
        int segments = 0;
        for (Channel channel : channels) {
            segments += channel.segments();
        }
        return segments;
    }

    /** The bolt grown from a seed, {@code height} blocks from the strike point up to the cloud base. */
    static BoltShape grow(long seed, float height) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Channel> channels = new ArrayList<>(1 + MOST_FORKS);
        float[] main = mainChannel(random, height);
        channels.add(new Channel(main, 0.8f, 1.0f, 1.0f, 1.0f, true));
        int forks = 2 + random.nextInt(MOST_FORKS - 1);
        for (int fork = 0; fork < forks; fork++) {
            channels.add(fork(random, main, height));
        }
        return new BoltShape(List.copyOf(channels));
    }

    private static float[] mainChannel(SplittableRandom random, float height) {
        float step = height / MAIN_SEGMENTS;
        float topX = (float) random.nextGaussian() * height * 0.08f;
        float topZ = (float) random.nextGaussian() * height * 0.08f;
        float[] walkX = new float[MAIN_SEGMENTS + 1];
        float[] walkZ = new float[MAIN_SEGMENTS + 1];
        for (int point = 1; point <= MAIN_SEGMENTS; point++) {
            walkX[point] = walkX[point - 1] + (float) random.nextGaussian() * step * KINK;
            walkZ[point] = walkZ[point - 1] + (float) random.nextGaussian() * step * KINK;
        }
        float[] points = new float[(MAIN_SEGMENTS + 1) * 3];
        for (int point = 0; point <= MAIN_SEGMENTS; point++) {
            float along = point / (float) MAIN_SEGMENTS;
            // The walk less its straight line to the end, so the channel ends where it should.
            float x = topX * (1.0f - along) + walkX[point] - walkX[MAIN_SEGMENTS] * along;
            float z = topZ * (1.0f - along) + walkZ[point] - walkZ[MAIN_SEGMENTS] * along;
            boolean inside = point > 0 && point < MAIN_SEGMENTS;
            float y = height * (1.0f - along) + (inside ? (float) (random.nextDouble() - 0.5) * step * 0.5f : 0.0f);
            points[point * 3] = x;
            points[point * 3 + 1] = y;
            points[point * 3 + 2] = z;
        }
        return points;
    }

    /** A fork from a point in the upper two thirds of the main channel, heading down and out. */
    private static Channel fork(SplittableRandom random, float[] main, float height) {
        int from = 1 + random.nextInt((int) (MAIN_SEGMENTS * 0.6f));
        float startX = main[from * 3];
        float startY = main[from * 3 + 1];
        float startZ = main[from * 3 + 2];
        float alongX = main[from * 3 + 3] - startX;
        float alongY = main[from * 3 + 4] - startY;
        float alongZ = main[from * 3 + 5] - startZ;
        float alongLength = (float) Math.sqrt(alongX * alongX + alongY * alongY + alongZ * alongZ);
        double heading = random.nextDouble() * Math.PI * 2.0;
        float spread = 0.5f + (float) random.nextDouble() * 0.9f;
        float directionX = alongX / alongLength + (float) Math.cos(heading) * spread;
        float directionY = alongY / alongLength;
        float directionZ = alongZ / alongLength + (float) Math.sin(heading) * spread;
        float directionLength = (float) Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        float length = height * (0.1f + 0.25f * (float) random.nextDouble());
        float step = length / FORK_SEGMENTS;

        float[] points = new float[(FORK_SEGMENTS + 1) * 3];
        points[0] = startX;
        points[1] = startY;
        points[2] = startZ;
        for (int point = 1; point <= FORK_SEGMENTS; point++) {
            int at = point * 3;
            points[at] = points[at - 3] + directionX / directionLength * step + (float) random.nextGaussian() * step * KINK;
            // Forks droop a little as they go.
            points[at + 1] = Math.max(1.0f, points[at - 2] + directionY / directionLength * step - step * 0.15f * point
                + (float) (random.nextDouble() - 0.5) * step * 0.5f);
            points[at + 2] = points[at - 1] + directionZ / directionLength * step + (float) random.nextGaussian() * step * KINK;
        }
        return new Channel(points, 0.55f, 0.12f, 0.85f, 0.0f, false);
    }
}
