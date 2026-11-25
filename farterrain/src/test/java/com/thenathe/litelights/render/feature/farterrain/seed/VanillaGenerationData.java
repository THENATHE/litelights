package com.thenathe.litelights.render.feature.farterrain.seed;

import java.util.concurrent.ForkJoinPool;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.repository.ServerPacksSource;

/** Vanilla's world generation loaded once for the tests, the way InstalledGenerationData loads a server's. */
final class VanillaGenerationData {
    private static InstalledGenerationData loaded;

    private VanillaGenerationData() {
    }

    static synchronized InstalledGenerationData get() {
        if (loaded == null) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            loaded = InstalledGenerationData.load(ServerPacksSource.createVanillaTrustedRepository(), ForkJoinPool.commonPool());
        }
        return loaded;
    }
}
