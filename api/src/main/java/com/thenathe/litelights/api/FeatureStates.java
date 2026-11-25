package com.thenathe.litelights.api;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * Feature health and gating (docs/ARCHITECTURE.md, "Feature states and errors" and "Per-dimension
 * switches"): what may run this frame, failures with their retries, and the rows that show them.
 */
public interface FeatureStates {
    /** Whether the feature may run now: never failed, or its retry delay has passed. */
    boolean mayRun(ModuleFeature feature);

    /** Switches the feature off, shows the reason with a Retry on its row, and retries it later. */
    void failed(ModuleFeature feature, Throwable error);

    /** The feature drew: clears a state left on its row by an earlier failure. */
    void succeeded(ModuleFeature feature);

    /** Reports a compile failure of these pipelines on the feature's row. During client start-up. */
    void claimPipelines(ModuleFeature feature, RenderPipeline... pipelines);

    /** Greys out the settings rows of this settings feature id, with the reason. */
    void markUnavailable(String settingsFeature, String reason);

    /** Whether the current dimension's switch lets this kind of effect run. */
    boolean dimensionAllows(DimensionAspect aspect);
}
