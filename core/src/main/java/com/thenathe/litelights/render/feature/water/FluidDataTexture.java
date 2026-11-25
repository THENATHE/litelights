package com.thenathe.litelights.render.feature.water;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.thenathe.litelights.render.feature.water.wake.WakeField;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * One texture for the column map and the large fluid textures, to keep the water stream at three
 * texture units (see WaterBindings). The column map sits in the corner (256 x 256 at the origin,
 * mip 0 only) with the rain tops for weather on glass under it (256 x 256 from y 256,
 * {@link RainTopGrid}), the glass windows under those (256 x 128 from y 512,
 * {@link GlassWindowGrid}), the shore field for the ocean waves under them (256 x 256 from y 640,
 * {@link ShoreField}), the wakes under that (256 x 256 from y 896, {@link WakeField}) and where leaves
 * lie under trees under those (256 x 256 from y 1152, {@link GroundLeavesGrid}) and where snow gathers
 * on leaves and under trees at the bottom (256 x 256 from y 1408, {@link SnowCoverGrid}); the large
 * textures' frame strip sits to their right, mipmapped. Until the large textures are wanted the
 * texture is just the seven maps.
 */
final class FluidDataTexture implements AutoCloseable {
    static final int COLUMN_REGION = FluidColumnGrid.SIZE;
    /** The rain tops start this many texels down, below the column map. */
    static final int RAIN_TOP_Y = COLUMN_REGION;
    /** The glass windows start this many texels down, below the rain tops. */
    static final int GLASS_WINDOW_Y = RAIN_TOP_Y + RainTopGrid.SIZE;
    /** The shore field starts this many texels down, below the glass windows. */
    static final int SHORE_FIELD_Y = GLASS_WINDOW_Y + GlassWindowGrid.ROWS;
    /** The wakes start this many texels down, below the shore field. */
    static final int WAKE_FIELD_Y = SHORE_FIELD_Y + ShoreField.SIZE;
    /** Where leaves lie under trees starts this many texels down, below the wakes. */
    static final int GROUND_LEAVES_Y = WAKE_FIELD_Y + WakeField.SIZE;
    /** Where snow gathers on leaves and under trees starts this many texels down, below the ground leaves. */
    static final int SNOW_COVER_Y = GROUND_LEAVES_Y + GroundLeavesGrid.SIZE;
    private static final int MAPS_HEIGHT = SNOW_COVER_Y + SnowCoverGrid.SIZE;

    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private @Nullable FluidStripLayout strip;
    private int width;
    private int height;
    /** Counts the textures made, so a region uploaded into an earlier one is known to be gone. */
    private int generation;

    /** Creates the column-map-only texture if there is none yet. True when the columns must be uploaded again. */
    boolean ensure() {
        if (texture != null) {
            return false;
        }
        create(COLUMN_REGION, MAPS_HEIGHT, 1);
        return true;
    }

    /** Rebuilds the texture with the strip beside the column map and uploads the strip's mip levels. */
    void setStrip(FluidStripLayout layout, int[][] mipPixels) {
        int levels = mipPixels.length;
        create(COLUMN_REGION + layout.width(), Math.max(MAPS_HEIGHT, layout.height()), levels);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int level = 0; level < levels; level++) {
            int[] pixels = mipPixels[level];
            ByteBuffer data = MemoryUtil.memAlloc(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            try {
                data.asIntBuffer().put(pixels);
                encoder.writeToTexture(texture, data, level, 0, COLUMN_REGION >> level, 0, layout.width() >> level, layout.height() >> level);
            } finally {
                MemoryUtil.memFree(data);
            }
        }
        strip = layout;
    }

    /** Writes one chunk's 16 x 16 texels of the column map. */
    void writeColumns(int x, int z, ByteBuffer texels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, x, z, 16, 16);
    }

    /** Writes one chunk's 16 x 16 texels of the rain tops. */
    void writeRainTops(int x, int z, ByteBuffer texels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, x, RAIN_TOP_Y + z, 16, 16);
    }

    /** Writes one chunk's 16 x 16 texels of where leaves lie under trees. */
    void writeGroundLeaves(int x, int z, ByteBuffer texels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, x, GROUND_LEAVES_Y + z, 16, 16);
    }

    /** Writes one chunk's 16 x 16 texels of where snow gathers on leaves and under trees. */
    void writeSnowCover(int x, int z, ByteBuffer texels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, x, SNOW_COVER_Y + z, 16, 16);
    }

    /** Writes the whole shore field, its texels in the texture's own (wrapped) order. */
    void writeShoreField(ByteBuffer texels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, 0, SHORE_FIELD_Y, ShoreField.SIZE, ShoreField.SIZE);
    }

    /** Writes the whole glass window table. */
    void writeGlassWindows(ByteBuffer texels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, 0, GLASS_WINDOW_Y,
            GlassWindowGrid.WIDTH, GlassWindowGrid.ROWS);
    }

    /** Writes a rectangle of the wakes (texture coordinates within their region), its texels row by row. */
    void writeWakes(int x, int z, int width, int height, ByteBuffer texels) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, texels, 0, 0, x, WAKE_FIELD_Y + z, width, height);
    }

    int generation() {
        return generation;
    }

    @Nullable GpuTextureView view() {
        return view;
    }

    @Nullable FluidStripLayout strip() {
        return strip;
    }

    /** Texture u where a column of the strip starts. */
    float stripU(int stripX) {
        return (COLUMN_REGION + stripX) / (float) width;
    }

    float u(int texels) {
        return texels / (float) width;
    }

    float v(int texels) {
        return texels / (float) height;
    }

    private void create(int newWidth, int newHeight, int mipLevels) {
        close();
        var device = RenderSystem.getDevice();
        texture = device.createTexture("LiteLights fluid data", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM, newWidth, newHeight, 1, mipLevels);
        view = device.createTextureView(texture);
        width = newWidth;
        height = newHeight;
        generation++;
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
        strip = null;
    }
}
