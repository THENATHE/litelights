package com.thenathe.litelights.render.feature.water;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.thenathe.litelights.LiteLightsClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The large water and lava textures (Big Water's vanilla-style textures by Tec, CC0), packed into
 * one strip of frames with its mip levels as {@link FluidStripLayout} describes, ready for the
 * {@link FluidDataTexture}. They are read through the resource manager, so a resource pack can
 * replace them, and decoded on a background thread when first needed and after every resource
 * reload.
 */
final class LargeFluidTextures {
    private static final String FOLDER = "textures/fluid/";

    /** The strip, and the average colour of the water's still and flowing textures (for Enhanced colours). */
    record Prepared(FluidStripLayout layout, int[][] mipPixels, float[] waterStillMean, float[] waterFlowMean) {
    }

    private record Frames(NativeImage image, FluidStripLayout.Source source) {
        int size() {
            return image.getWidth();
        }
    }

    private boolean stale = true;
    private @Nullable CompletableFuture<Prepared> loading;

    /** After a resource reload: read the files again the next time they're wanted. */
    void requestReload() {
        stale = true;
    }

    /**
     * Once per frame while large textures are on, on the render thread. Starts loading when needed,
     * and returns the strip once, when it is ready to go to the GPU.
     */
    @Nullable Prepared update() {
        Prepared ready = null;
        if (loading != null && loading.isDone()) {
            CompletableFuture<Prepared> finished = loading;
            loading = null;
            ready = finished.join();
        }
        if (stale && loading == null) {
            stale = false;
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            loading = CompletableFuture.supplyAsync(() -> prepare(resources), Util.backgroundExecutor());
        }
        return ready;
    }

    private static Prepared prepare(ResourceManager resources) {
        // Every image read is closed however this ends, also when a later one fails to load.
        List<Frames> opened = new ArrayList<>(4);
        try {
            Frames waterStill = open(resources, "water_still", opened);
            Frames waterFlow = open(resources, "water_flow", opened);
            Frames lavaStill = open(resources, "lava_still", opened);
            Frames lavaFlow = open(resources, "lava_flow", opened);
            int stillSize = waterStill.size();
            int flowSize = waterFlow.size();
            FluidStripLayout strip = FluidStripLayout.of(stillSize, flowSize,
                waterStill.source(), waterFlow.source(), lavaStill.source(), lavaFlow.source());
            int width = strip.width();
            int[] pixels = new int[width * strip.height()];
            copyFrames(waterStill, strip.waterStill(), stillSize, strip.waterStillColumn(), pixels, width);
            copyFrames(waterFlow, strip.waterFlow(), flowSize, strip.waterFlowColumn(), pixels, width);
            copyFrames(lavaStill, strip.lavaStill(), stillSize, strip.lavaStillColumn(), pixels, width);
            copyFrames(lavaFlow, strip.lavaFlow(), flowSize, strip.lavaFlowColumn(), pixels, width);
            int[][] mips = new int[strip.mipLevels()][];
            mips[0] = pixels;
            for (int level = 1; level < mips.length; level++) {
                mips[level] = halve(mips[level - 1], width >> (level - 1), strip.height() >> (level - 1));
            }
            LiteLightsClient.LOG.info("Large fluid textures ready: {}x{}, {} mip levels", width, strip.height(), mips.length);
            return new Prepared(strip, mips, SpriteMean.of(waterStill.image().getPixelsABGR()),
                SpriteMean.of(waterFlow.image().getPixelsABGR()));
        } finally {
            for (Frames frames : opened) {
                frames.image().close();
            }
        }
    }

    private static Frames open(ResourceManager resources, String name, List<Frames> opened) {
        Frames frames = read(resources, name);
        opened.add(frames);
        return frames;
    }

    private static Frames read(ResourceManager resources, String name) {
        Identifier image = LiteLightsClient.id(FOLDER + name + ".png");
        Identifier metadata = LiteLightsClient.id(FOLDER + name + ".png.mcmeta");
        try {
            Resource imageResource = resources.getResourceOrThrow(image);
            NativeImage frames;
            try (InputStream stream = imageResource.open()) {
                frames = NativeImage.read(stream);
            }
            try {
                return describe(frames, image, resources.getResource(metadata));
            } catch (IOException | RuntimeException failure) {
                frames.close();
                throw failure;
            }
        } catch (IOException error) {
            throw new IllegalStateException("Couldn't read " + image, error);
        }
    }

    /** Reads a fluid texture's animation settings and checks it is a column of square frames. */
    private static Frames describe(NativeImage frames, Identifier image, Optional<Resource> metadataResource) throws IOException {
        int ticksPerFrame = 1;
        boolean interpolate = false;
        if (metadataResource.isPresent()) {
            try (Reader reader = metadataResource.get().openAsReader()) {
                JsonObject animation = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animation");
                if (animation != null) {
                    ticksPerFrame = animation.has("frametime") ? Math.max(1, animation.get("frametime").getAsInt()) : 1;
                    interpolate = animation.has("interpolate") && animation.get("interpolate").getAsBoolean();
                }
            }
        }
        if (frames.getHeight() % frames.getWidth() != 0) {
            throw new IllegalStateException(image + " is not a column of square frames");
        }
        return new Frames(frames, new FluidStripLayout.Source(frames.getHeight() / frames.getWidth(), ticksPerFrame, interpolate));
    }

    /**
     * Copies the kept frames into their column. A file whose frames are a different size from the
     * column's (a resource pack mixing sizes) is scaled to fit by nearest texel.
     */
    private static void copyFrames(Frames frames, FluidStripLayout.Animation animation, int columnSize, int columnX, int[] pixels, int width) {
        int[] source = frames.image().getPixelsABGR();
        int sourceSize = frames.size();
        for (int frame = 0; frame < animation.frames(); frame++) {
            int sourceTop = frame * animation.sourceStep() * sourceSize;
            int targetTop = frame * columnSize;
            for (int y = 0; y < columnSize; y++) {
                int sourceRow = (sourceTop + y * sourceSize / columnSize) * sourceSize;
                int targetRow = (targetTop + y) * width + columnX;
                for (int x = 0; x < columnSize; x++) {
                    pixels[targetRow + x] = source[sourceRow + x * sourceSize / columnSize];
                }
            }
        }
    }

    /** The next mip level: each texel the average of a 2 x 2 block, channel by channel. */
    private static int[] halve(int[] pixels, int width, int height) {
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        int[] result = new int[halfWidth * halfHeight];
        for (int y = 0; y < halfHeight; y++) {
            for (int x = 0; x < halfWidth; x++) {
                int a = pixels[(2 * y) * width + 2 * x];
                int b = pixels[(2 * y) * width + 2 * x + 1];
                int c = pixels[(2 * y + 1) * width + 2 * x];
                int d = pixels[(2 * y + 1) * width + 2 * x + 1];
                int averaged = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int sum = ((a >>> shift) & 0xFF) + ((b >>> shift) & 0xFF) + ((c >>> shift) & 0xFF) + ((d >>> shift) & 0xFF);
                    averaged |= ((sum + 2) / 4) << shift;
                }
                result[y * halfWidth + x] = averaged;
            }
        }
        return result;
    }
}
