package com.thenathe.litelights.overlays.config;

import com.google.gson.JsonObject;
import com.thenathe.litelights.config.LiteLightsConfig;

/** The overlays module's object in {@code config/litelights.json}, through the shared LiteLights config. */
public final class LiteLightsModuleData implements OverlayConfigStore.ModuleData {
    @Override
    public boolean exists() {
        return LiteLightsConfig.hasModule(OverlayConfigStore.MODULE_ID);
    }

    @Override
    public JsonObject read() {
        return LiteLightsConfig.module(OverlayConfigStore.MODULE_ID);
    }

    @Override
    public void write(JsonObject data) {
        OverlayConfigStore.replaceContents(LiteLightsConfig.module(OverlayConfigStore.MODULE_ID), data);
        LiteLightsConfig.save();
    }

    @Override
    public JsonObject readBeside(String key) {
        return LiteLightsConfig.hasModule(key) ? LiteLightsConfig.module(key) : null;
    }

    /** Kept under {@code modules.<key>}, where nothing reads or replaces it. */
    @Override
    public void writeBeside(String key, JsonObject data) {
        OverlayConfigStore.replaceContents(LiteLightsConfig.module(key), data);
        LiteLightsConfig.save();
    }
}
