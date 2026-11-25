package com.thenathe.litelights.api;

/**
 * A module's entrypoint, listed under {@value #ENTRYPOINT} in fabric.mod.json. Core starts every
 * module once, during client start-up, after the API is installed and at the point in core's own
 * start-up where the module's listeners belong (docs/ARCHITECTURE.md, "Modules").
 */
public interface LiteLightsModule {
    String ENTRYPOINT = "litelights:module";

    void initialize();
}
