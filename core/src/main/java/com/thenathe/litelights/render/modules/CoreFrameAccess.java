package com.thenathe.litelights.render.modules;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.thenathe.litelights.api.CameraView;
import com.thenathe.litelights.api.FrameAccess;
import com.thenathe.litelights.render.LiteLightsRenderer;

/** The frame the renderer is filling, for the modules. */
final class CoreFrameAccess implements FrameAccess {
    @Override
    public int featureFlags() {
        return LiteLightsRenderer.get().frame().featureFlags;
    }

    @Override
    public void addFeatureFlags(int bits) {
        LiteLightsRenderer.get().frame().featureFlags |= bits;
    }

    @Override
    public void bindWorldUniforms(RenderPass pass) {
        LiteLightsRenderer.get().bindWorldUniforms(pass);
    }

    @Override
    public CameraView camera() {
        return LiteLightsRenderer.get().cameraView();
    }

    @Override
    public float daylight() {
        return LiteLightsRenderer.get().frame().sunColor.w;
    }
}
