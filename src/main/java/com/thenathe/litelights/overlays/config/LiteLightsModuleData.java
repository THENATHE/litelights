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

    /** Kept under {@code modules.overlays_unreadable}, where nothing reads or replaces it. */
    @Override
    public void keepUnreadable(JsonObject data) {
        OverlayConfigStore.replaceContents(LiteLightsConfig.module(OverlayConfigStore.MODULE_ID + "_unreadable"), data);
        LiteLightsConfig.save();
    }
}
