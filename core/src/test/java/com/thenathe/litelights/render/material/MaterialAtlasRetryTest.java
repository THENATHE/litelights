package com.thenathe.litelights.render.material;

import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialAtlasRetryTest {
    /** Counts the builds started; the first {@code failures} of them fail, the rest never finish. */
    private static final class FailingBuilder implements MaterialAtlas.Builder {
        int started;
        int failures;

        FailingBuilder(int failures) {
            this.failures = failures;
        }

        @Override
        public CompletableFuture<MaterialAtlas.Built> start(Map<Identifier, MaterialAtlas.SpriteArea> sprites,
                                                            int atlasWidth, int atlasHeight) {
            started++;
            if (started <= failures) {
                return CompletableFuture.failedFuture(new IllegalStateException("build " + started + " failed"));
            }
            return new CompletableFuture<>();
        }
    }

    @AfterEach
    void forget() {
        RenderFeatureStatus.retryNow(RenderFeature.MATERIALS);
        RenderFeatureStatus.succeeded(RenderFeature.MATERIALS);
    }

    @Test
    void aFailedBuildIsTriedAgainAfterTheRetryDelay() {
        FailingBuilder builder = new FailingBuilder(1);
        MaterialAtlas atlas = new MaterialAtlas(builder);
        atlas.onAtlasUploaded(1024, 512, Map.of());

        atlas.update(true);
        assertEquals(1, builder.started, "the first frame with a level starts a build");

        atlas.update(true);
        assertFalse(atlas.isBuilding(), "the failed build is collected");
        assertTrue(RenderFeatureStatus.problem(RenderFeature.MATERIALS).contains("build 1 failed"));
        atlas.update(true);
        assertEquals(1, builder.started, "nothing starts while the retry delay runs");

        // The delay is 5 s; the Retry button (or the time passing) lets it run again.
        RenderFeatureStatus.retryNow(RenderFeature.MATERIALS);
        atlas.update(true);
        assertEquals(2, builder.started, "the build is tried again without a new atlas or level");
        assertTrue(atlas.isBuilding());
    }

    @Test
    void aRetryWaitsForALevel() {
        FailingBuilder builder = new FailingBuilder(1);
        MaterialAtlas atlas = new MaterialAtlas(builder);
        atlas.onAtlasUploaded(1024, 512, Map.of());
        atlas.update(true);
        atlas.update(true);
        RenderFeatureStatus.retryNow(RenderFeature.MATERIALS);

        atlas.update(false);
        assertEquals(1, builder.started, "block tags come from the server, so no build without a level");
        atlas.update(true);
        assertEquals(2, builder.started);
    }

    @Test
    void anUnchangedAtlasIsBuiltOnce() {
        FailingBuilder builder = new FailingBuilder(0);
        MaterialAtlas atlas = new MaterialAtlas(builder);
        atlas.onAtlasUploaded(1024, 512, Map.of());
        for (int frame = 0; frame < 5; frame++) {
            atlas.update(true);
        }
        assertEquals(1, builder.started);
    }
}
