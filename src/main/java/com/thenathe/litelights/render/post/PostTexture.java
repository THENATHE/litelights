package com.thenathe.litelights.render.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/**
 * A screen-sized texture for a post pass, at the screen's size divided by a whole number. It is
 * created on first use and only made again when the window size changes, so passes never
 * allocate per frame.
 */
public final class PostTexture implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
        | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;

    private final String label;
    private final GpuFormat format;
    private final int divisor;
    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;

    public PostTexture(String label, GpuFormat format, int divisor) {
        this.label = label;
        this.format = format;
        this.divisor = divisor;
    }

    /** The texture for a screen of this size, made again only if the size changed. */
    public GpuTextureView ensure(int screenWidth, int screenHeight) {
        int width = Math.max(1, screenWidth / divisor);
        int height = Math.max(1, screenHeight / divisor);
        if (texture == null || view == null || texture.isClosed() || texture.getWidth(0) != width || texture.getHeight(0) != height) {
            close();
            var device = RenderSystem.getDevice();
            texture = device.createTexture("LiteLights " + label, USAGE, format, width, height, 1, 1);
            view = device.createTextureView(texture);
        }
        return view;
    }

    public GpuTexture texture() {
        if (texture == null) {
            throw new IllegalStateException(label + " used before ensure()");
        }
        return texture;
    }

    public GpuTextureView view() {
        if (view == null) {
            throw new IllegalStateException(label + " used before ensure()");
        }
        return view;
    }

    public int width() {
        return texture == null ? 1 : texture.getWidth(0);
    }

    public int height() {
        return texture == null ? 1 : texture.getHeight(0);
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }
}
