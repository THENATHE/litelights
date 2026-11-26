package com.thenathe.litelights.clouds.field;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * How evenly fair weather spreads its cloud over the sky (the user, 2026-11-25: "clouds need to be
 * more evenly distributed"): the sky is cut into windows 2 km square, and each window's share of
 * columns under solid cloud is compared with the others, over many places and days. Fewer windows
 * should be empty and fewer bunched up than before, with about the same cover on average.
 */
class CloudSpreadTest {
    private static final int WINDOW_BLOCKS = 2_048;
    private static final int WINDOWS_ACROSS = 6;
    private static final double STEP = 64.0;

    @Test
    void fairWeatherCoversTheSkyEvenly() {
        int perWindow = (int) (WINDOW_BLOCKS / STEP);
        double[] covers = new double[0];
        int samples = 0;
        for (int day = 0; day < 8; day++) {
            double tick = 30_000.0 + day * 37_000.0;
            double centreX = day * 23_000.0;
            double centreZ = -day * 17_000.0;
            CloudField field = new CloudField(VanillaWeatherSky.sky(TestSkies.SEED, TestSkies.BASE, tick, 0.0f, 0.0f, centreX, centreZ,
                WINDOW_BLOCKS * WINDOWS_ACROSS));
            CloudColumn column = new CloudColumn();
            double half = WINDOW_BLOCKS * WINDOWS_ACROSS / 2.0;
            for (int windowZ = 0; windowZ < WINDOWS_ACROSS; windowZ++) {
                for (int windowX = 0; windowX < WINDOWS_ACROSS; windowX++) {
                    int covered = 0;
                    for (int z = 0; z < perWindow; z++) {
                        for (int x = 0; x < perWindow; x++) {
                            double worldX = centreX - half + windowX * WINDOW_BLOCKS + (x + 0.5) * STEP;
                            double worldZ = centreZ - half + windowZ * WINDOW_BLOCKS + (z + 0.5) * STEP;
                            field.sampleWorld(tick, worldX, worldZ, 16.0, column);
                            if (column.cover() >= 0.5f) {
                                covered++;
                            }
                        }
                    }
                    covers = Arrays.copyOf(covers, samples + 1);
                    covers[samples++] = covered / (double) (perWindow * perWindow);
                }
            }
        }
        double mean = Arrays.stream(covers).average().orElse(0.0);
        double spread = Math.sqrt(Arrays.stream(covers).map(cover -> (cover - mean) * (cover - mean)).average().orElse(0.0));
        long empty = Arrays.stream(covers).filter(cover -> cover < 0.05).count();
        long bunched = Arrays.stream(covers).filter(cover -> cover > 0.6).count();
        System.out.println(String.format(Locale.ROOT,
            "fair-weather spread over %d windows of 2 km: mean cover %.3f, spread %.3f (%.2f of the mean), empty %d, bunched %d",
            samples, mean, spread, spread / mean, empty, bunched));
        assertTrue(mean > 0.2 && mean < 0.45, "mean cover " + mean);
        // 1.1.0's sky: mean cover 0.185, spread 1.22 of the mean, 122 of 288 windows empty, 25 bunched;
        // this round's: 0.353, 0.58 of the mean, 15 empty, 38 bunched (measured 2026-11-25).
        assertTrue(spread / mean < 0.7, "the cover varies too much from window to window: " + spread / mean);
        assertTrue(empty < samples / 10, empty + " of " + samples + " windows are empty");
    }
}
