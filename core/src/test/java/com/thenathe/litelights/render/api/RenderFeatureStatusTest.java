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
        RenderFeatureStatus.retryNow(FEATURE);
        RenderFeatureStatus.succeeded(FEATURE);
        ROW.markReady();
    }

    @Test
    void successWhileWaitingToRetryKeepsTheFailure() {
        // The 0.1.7 log spam: a feature that skipped its work while waiting reported success, the
        // failure was forgotten, and it failed again on the next frame, at attempt 1 every time.
        RenderFeatureStatus.failed(FEATURE, new IllegalStateException("OpenGL error 1280"));
        RenderFeatureStatus.succeeded(FEATURE);
        assertFalse(RenderFeatureStatus.mayRun(FEATURE));
        assertInstanceOf(RowState.Failed.class, ROW.state());
        assertEquals("IllegalStateException: OpenGL error 1280", RenderFeatureStatus.problem(FEATURE));
    }

    @Test
    void successAfterTheRetryDelayClearsTheFailure() {
        RenderFeatureStatus.failed(FEATURE, new IllegalStateException("OpenGL error 1280"));
        RenderFeatureStatus.retryNow(FEATURE);
        assertTrue(RenderFeatureStatus.mayRun(FEATURE));
        RenderFeatureStatus.succeeded(FEATURE);
        assertEquals(null, RenderFeatureStatus.problem(FEATURE));
        assertInstanceOf(RowState.Ready.class, ROW.state());
    }

    @Test
    void failingAgainAfterTheDelayWaitsLonger() {
        RenderFeatureStatus.failed(FEATURE, new IllegalStateException("first"));
        RenderFeatureStatus.succeeded(FEATURE);
        RenderFeatureStatus.retryNow(FEATURE);
        RenderFeatureStatus.failed(FEATURE, new IllegalStateException("second"));
        // Still the same failure, now the second attempt: the next wait is 10 s, not 5 s again.
        assertFalse(RenderFeatureStatus.mayRun(FEATURE));
        assertEquals("IllegalStateException: second", RenderFeatureStatus.problem(FEATURE));
        assertEquals(2, RenderFeatureStatus.failuresForTesting(FEATURE));
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
