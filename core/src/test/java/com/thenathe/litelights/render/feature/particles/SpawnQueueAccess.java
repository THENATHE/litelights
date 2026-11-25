package com.thenathe.litelights.render.feature.particles;

import com.thenathe.litelights.render.RenderSettings.ParticleDensity;

/** For tests in other packages: opens a spawn queue for a tick and reads what was queued. */
public final class SpawnQueueAccess {
    private SpawnQueueAccess() {
    }

    /** A new tick at Full with nothing alive and the categories' caps unscaled. */
    public static void begin(SpawnQueue queue) {
        queue.begin(ParticleDensity.FULL, ParticleBudget.unscaled(), new int[ParticleCategory.values().length], 0);
    }

    /** How many particles of this kind are queued. */
    public static int queued(SpawnQueue queue, ParticleKind kind) {
        int count = 0;
        for (int i = 0; i < queue.count; i++) {
            if (queue.kind[i] == kind) {
                count++;
            }
        }
        return count;
    }
}
