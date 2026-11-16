package com.thenathe.litelights.render.api;

import com.thenathe.litelights.config.Feature;
import com.thenathe.litelights.config.RowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFeatureStatusTest {
    // A feature nothing else in the tests touches, with a row of its own.
    private static final RenderFeature FEATURE = RenderFeature.ENCHANTMENT_OUTLINES;
    private static final Feature ROW = Feature.of("outlines");

    @AfterEach
    void forget() {
        RenderFeatureStatus.clearCompileFailures();
        RenderFeatureStatus.succeeded(FEATURE);
        ROW.markReady();
    }

    @Test
    void compileFailureWaitsThenRetries() {
        RenderFeatureStatus.compileFailed(FEATURE, "broken", false);
        assertFalse(RenderFeatureStatus.mayRun(FEATURE));
        assertInstanceOf(RowState.Failed.class, ROW.state());
        RenderFeatureStatus.retryNow(FEATURE);
        assertTrue(RenderFeatureStatus.mayRun(FEATURE));
    }

    @Test
    void featureTrippingOverItsBrokenPipelineKeepsTheCompileReason() {
        RenderFeatureStatus.compileFailed(FEATURE, "broken", false);
        RenderFeatureStatus.failed(FEATURE, new IllegalStateException("Couldn't find compiled pipeline"));
        assertEquals("broken", RenderFeatureStatus.problem(FEATURE));
    }

    @Test
    void oneRequestCompilingTwiceCountsOnce() throws InterruptedException {
        // Counted three times, this would give up; counted once, it retries after the first delay.
        RenderFeatureStatus.compileFailed(FEATURE, "broken", false);
        RenderFeatureStatus.compileFailed(FEATURE, "broken", false);
        RenderFeatureStatus.compileFailed(FEATURE, "broken", false);
        assertFalse(RenderFeatureStatus.mayRun(FEATURE));
        Thread.sleep(5_100L);
        assertTrue(RenderFeatureStatus.mayRun(FEATURE));
    }

    @Test
    void repeatedCompileFailuresWaitForRetryOrReload() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            RenderFeatureStatus.compileFailed(FEATURE, "broken", false);
            // Separate tries, not one request compiling twice.
            Thread.sleep(1_050L);
        }
        assertFalse(RenderFeatureStatus.mayRun(FEATURE));
        assertInstanceOf(RowState.Failed.class, ROW.state());

        RenderFeatureStatus.clearCompileFailures();
        assertTrue(RenderFeatureStatus.mayRun(FEATURE));
        assertInstanceOf(RowState.Ready.class, ROW.state());
    }

    @Test
    void readyRowStaysFailedWhileACompileFailureStands() {
        RenderFeatureStatus.compileFailed(FEATURE, "broken", false);
        RenderFeatureStatus.markReady("outlines");
        assertInstanceOf(RowState.Failed.class, ROW.state());
    }

    @Test
    void unsupportedVersionOutlivesAReload() {
        RenderFeature sodium = RenderFeature.SODIUM_TERRAIN;
        RenderFeatureStatus.markUnsupported(sodium, "unsupported");
        RenderFeatureStatus.clearCompileFailures();
        assertFalse(RenderFeatureStatus.mayRun(sodium));
    }
}
