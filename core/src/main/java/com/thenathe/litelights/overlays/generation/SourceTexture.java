package com.thenathe.litelights.overlays.generation;

import com.mojang.blaze3d.platform.NativeImage;
import com.thenathe.litelights.overlays.pack.PackStack;
import java.io.IOException;
import net.minecraft.resources.Identifier;

/**
 * A block texture as overlays use it: one square frame, ARGB, row by row.
 *
 * <p>Any square size works, power of two or not. An animated texture (a strip of square frames
 * taller than it is wide) contributes its first frame, so its overlay is a still image. A texture
 * that is neither square nor such a strip is reported and left out.
 *
 * <p>A texture larger than the build will ever draw is scaled down as it loads (see
 * {@link #load}), so a 4096x pack doesn't hold 64 MiB per texture for the whole build.
 *
 * @param loadedSize the size the pack's texture had before any scaling on load
 */
public record SourceTexture(String id, int size, int[] pixels, boolean animated, int loadedSize) {
    /** Below this an edge has no room for a shape. */
    public static final int SMALLEST_SIZE = 4;

    public SourceTexture(String id, int size, int[] pixels, boolean animated) {
        this(id, size, pixels, animated, size);
    }

    /** Thrown when a texture can't be used; the message is shown to the player. */
    public static final class UnusableTextureException extends Exception {
        public UnusableTextureException(String reason) {
            super(reason);
        }
    }

    /**
     * Reads a texture's first frame. One larger than {@code largestSize} (a "huge" texture: past the
     * build's Largest overlay size, at most 512x) comes back scaled down to it right away, exactly as
     * the build would have scaled it before drawing; textures at or under it are left as they are.
     */
    public static SourceTexture load(PackStack packs, String textureId, int largestSize) throws UnusableTextureException {
        byte[] bytes;
        try {
            Identifier texture = Identifier.parse(textureId);
            bytes = packs.read(texture.withPath(path -> "textures/" + path + ".png"));
        } catch (IOException | RuntimeException failure) {
            throw new UnusableTextureException("couldn't read " + textureId);
        }
        if (bytes == null) {
            throw new UnusableTextureException("missing texture " + textureId);
        }
        try (NativeImage image = NativeImage.read(bytes)) {
            int width = image.getWidth();
            int height = image.getHeight();
            if (height < width || height % width != 0) {
                throw new UnusableTextureException(textureId + " is " + width + "x" + height
                    + ", neither square nor a strip of square animation frames");
            }
            if (width < SMALLEST_SIZE) {
                throw new UnusableTextureException(textureId + " is " + width + "x, too small for an edge");
            }
            int[] pixels = new int[width * width];
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = image.getPixel(x, y);
                }
            }
            SourceTexture loaded = new SourceTexture(textureId, width, pixels, height > width);
            return width > largestSize ? loaded.scaledTo(largestSize) : loaded;
        } catch (IOException | RuntimeException failure) {
            throw new UnusableTextureException("couldn't decode " + textureId);
        }
    }

    /**
     * This texture at a smaller size, each new texel the average of the block of texels it covers.
     * Only used when a texture is larger than the size cap allows.
     */
    public SourceTexture scaledTo(int target) {
        if (target >= size) {
            return this;
        }
        int[] scaled = new int[target * target];
        for (int y = 0; y < target; y++) {
            int fromY = y * size / target;
            int toY = Math.max(fromY + 1, (y + 1) * size / target);
            for (int x = 0; x < target; x++) {
                int fromX = x * size / target;
                int toX = Math.max(fromX + 1, (x + 1) * size / target);
                scaled[y * target + x] = average(fromX, toX, fromY, toY);
            }
        }
        return new SourceTexture(id, target, scaled, animated, loadedSize);
    }

    /**
     * This texture with every texel cleared where {@code above}, drawn over it, is solid. A texture
     * of another size is laid over this one stretched to fit, texel by nearest texel.
     */
    public SourceTexture withoutTexelsUnder(SourceTexture above) {
        int[] visible = pixels.clone();
        for (int y = 0; y < size; y++) {
            int aboveY = y * above.size() / size;
            for (int x = 0; x < size; x++) {
                int aboveX = x * above.size() / size;
                if ((above.pixels()[aboveY * above.size() + aboveX] >>> 24) >= 128) {
                    visible[y * size + x] = 0;
                }
            }
        }
        return new SourceTexture(id, size, visible, animated, loadedSize);
    }

    private int average(int fromX, int toX, int fromY, int toY) {
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        int opaque = 0;
        for (int y = fromY; y < toY; y++) {
            for (int x = fromX; x < toX; x++) {
                int texel = pixels[y * size + x];
                count++;
                if ((texel >>> 24) >= 128) {
                    opaque++;
                    red += (texel >> 16) & 0xFF;
                    green += (texel >> 8) & 0xFF;
                    blue += texel & 0xFF;
                }
            }
        }
        // Cutout: the texel is solid when most of what it covers was, coloured by the solid part.
        if (opaque * 2 < count || opaque == 0) {
            return 0;
        }
        return 0xFF000000 | (int) (red / opaque) << 16 | (int) (green / opaque) << 8 | (int) (blue / opaque);
    }
}
