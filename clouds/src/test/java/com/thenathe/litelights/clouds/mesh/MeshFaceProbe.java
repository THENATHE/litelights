package com.thenathe.litelights.clouds.mesh;

import com.thenathe.litelights.clouds.field.CloudColumn;
import com.thenathe.litelights.clouds.field.CloudField;
import com.thenathe.litelights.clouds.field.VanillaWeatherSky;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Faces of one second's change while rain sets in, over 2 km square, by kind of growth; printed
 * only, and only with {@code -Plitelights.clouds.probe=true} (docs/CLOUDS.md, Cost).
 */
class MeshFaceProbe {
    @Test
    void facesWhileTheDeckCloses() {
        Assumptions.assumeTrue(Boolean.getBoolean("litelights.clouds.probe"), "a probe, run only when asked for");
        for (float rain : new float[] {0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f}) {
            float next = rain + 20.0f / 700.0f;
            double tick = 30_000.0;
            CloudField before = new CloudField(VanillaWeatherSky.sky(0x51CE5EEDL, 192.0f, tick, rain, 0.0f, 0.0, 0.0, 4_096.0));
            CloudField after = new CloudField(VanillaWeatherSky.sky(0x51CE5EEDL, 192.0f, tick + 20.0, next, 0.0f, 0.0, 0.0, 4_096.0));
            int[] counts = new int[10];
            int total = 0;
            for (int cz = -4; cz < 4; cz++) {
                for (int cx = -4; cx < 4; cx++) {
                    CloudCells a = sample(before, tick, cx, cz);
                    CloudCells b = sample(after, tick + 20.0, cx, cz);
                    FaceList faces = new FaceList();
                    CloudMesher.mesh(a, b, 0, faces);
                    total += faces.size();
                    for (int i = 0; i < faces.size(); i++) {
                        int look = faces.look(i);
                        int state = FaceFormat.state(look);
                        if (state == FaceFormat.STABLE) {
                            counts[0]++;
                        } else {
                            counts[2 + FaceFormat.growFrom(look) + (FaceFormat.widens(look) ? 7 : 0) > 9 ? 9 : 2 + FaceFormat.growFrom(look)]++;
                        }
                    }
                }
            }
            System.out.println(String.format(Locale.ROOT, "rain %.2f: faces %d, stable %d, from middle %d, down %d, up %d, sides %d, widening %d",
                rain, total, counts[0], counts[1], counts[2], counts[3], counts[4] + counts[5] + counts[6] + counts[7], counts[9]));
        }
    }

    private static CloudCells sample(CloudField field, double tick, int cx, int cz) {
        double min = 256.0;
        CloudField.Area area = field.area(tick, cx * min - 8, cz * min - 8, (cx + 1) * min + 8, (cz + 1) * min + 8);
        return CloudCells.sample(area, cx, cz, 192.0f, 8, new CloudColumn());
    }
}
