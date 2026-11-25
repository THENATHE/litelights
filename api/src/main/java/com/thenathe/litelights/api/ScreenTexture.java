package com.thenathe.litelights.api;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * A screen-sized texture at the screen's size divided by a whole number, made on first use and
 * again only when the window size changes, so a pass never allocates per frame.
 */
public interface ScreenTexture extends AutoCloseable {
    /** The texture for a screen of this size, made again only if the size changed. */
    GpuTextureView ensure(int screenWidth, int screenHeight);

    /** The texture made by the last {@link #ensure}; throws before the first. */
    GpuTexture texture();

    /** Its view; throws before the first {@link #ensure}. */
    GpuTextureView view();

    /** The texture's size, 1 before the first {@link #ensure}. */
    int width();

    int height();

    @Override
    void close();
}
