package com.thenathe.litelights.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.SectionPos;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * The cached sun (or moon) shadow map: two depth textures, the one shaders read (front) and the one
 * a redraw goes into (back).
 *
 * <p>A redraw is spread over at least {@link #MIN_REDRAW_FRAMES} frames, and over more when there
 * are many casters (about {@link #SECTIONS_PER_FRAME} sections a frame, at most
 * {@link #MAX_REDRAW_FRAMES} frames), so no single frame pays for the whole map. When it is done the two swap, and for a quarter of a
 * second shaders blend the old map into the new one ({@link #fadeWeight}); after that only one map
 * is read again.
 */
public final class SunShadowMap implements AutoCloseable {
    private static final int MIN_REDRAW_FRAMES = 3;
    private static final int MAX_REDRAW_FRAMES = 10;
    private static final int SECTIONS_PER_FRAME = 700;
    private static final long FADE_NANOS = 250_000_000L;
    /** How far casters can sit above or below the map's centre along the light, in blocks. */
    private static final float DEPTH_RADIUS_EXTRA = 448.0f;

    private final LongArrayList batch = new LongArrayList();
    private final int size;
    private final int shadowDistance;
    private GpuTexture front;
    private GpuTextureView frontView;
    private GpuTexture back;
    private GpuTextureView backView;

    private final LightSpace frontSpace = new LightSpace();
    private final LightSpace previousSpace = new LightSpace();
    private final LightSpace drawingSpace = new LightSpace();
    private boolean hasFront;
    private boolean hasPrevious;
    private long fadeStartNanos;
    private boolean smoothFade;
    /** The redraw under way was started in a hurry: drawn in one frame and swapped in without a fade. */
    private boolean hurriedRedraw;

    /** Sections still to be drawn into the back map, and how many frames the redraw has left. */
    private final LongArrayList drawQueue = new LongArrayList();
    private int drawFramesLeft;
    private int redrawFrames;
    private int drawPosition;

    public SunShadowMap(int size, int shadowDistance) {
        this.size = size;
        this.shadowDistance = shadowDistance;
        var device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;
        front = device.createTexture("LiteLights sun shadow A", usage, GpuFormat.D32_FLOAT, size, size, 1, 1);
        try {
            back = device.createTexture("LiteLights sun shadow B", usage, GpuFormat.D32_FLOAT, size, size, 1, 1);
        } catch (RuntimeException error) {
            // Out of video memory for the second texture: free the first, so a smaller tier can try.
            front.close();
            throw error;
        }
        frontView = device.createTextureView(front);
        backView = device.createTextureView(back);
    }

    public int size() {
        return size;
    }

    public int shadowDistance() {
        return shadowDistance;
    }

    public double coverageRadius() {
        return ShadowRedrawRules.coverageRadius(shadowDistance);
    }

    public double blocksPerTexel() {
        return coverageRadius() * 2.0 / size;
    }

    public boolean isDrawing() {
        return drawFramesLeft > 0;
    }

    public boolean hasMap() {
        return hasFront;
    }

    /** The light direction the front map was drawn with, or null before the first draw. */
    public @Nullable Vector3fc drawnLightDirection() {
        return hasFront ? frontSpace.lightDirection() : null;
    }

    public Vector3d frontAnchor() {
        return frontSpace.anchor();
    }

    public LightSpace frontSpace() {
        return frontSpace;
    }

    public LightSpace previousSpace() {
        return previousSpace;
    }

    public LightSpace drawingSpace() {
        return drawingSpace;
    }

    public @Nullable GpuTextureView frontView() {
        return hasFront ? frontView : null;
    }

    public @Nullable GpuTextureView previousView() {
        return isFading() ? backView : null;
    }

    public GpuTextureView drawTarget() {
        return backView;
    }

    /** Ends a fade at once: the front map alone is read from now on. */
    public void endFade() {
        smoothFade = false;
    }

    /** Whether the front map is still being blended in from the previous one. */
    public boolean isFading() {
        return hasPrevious && smoothFade && System.nanoTime() - fadeStartNanos < FADE_NANOS;
    }

    /** Weight of the front map while fading: 0 right after the swap, 1 once the fade is over. */
    public float fadeWeight() {
        if (!isFading()) {
            return 1.0f;
        }
        float t = (System.nanoTime() - fadeStartNanos) / (float) FADE_NANOS;
        return Math.clamp(t, 0.0f, 1.0f);
    }

    /**
     * Starts a redraw centred on the camera with the current light. Picks the sections whose
     * casters can land inside the map: the shadow pass culls its own sections and never asks
     * Sodium or vanilla which sections are visible. A hurried redraw (the time of day jumped or
     * races) is drawn in one frame and swapped in without a fade, so the shadows snap to the sun;
     * starting one while another is under way starts over with the new light.
     */
    public void beginRedraw(Vector3f light, Vector3d camera, ShadowCasterStore store, boolean zeroToOneDepth, boolean hurried) {
        double radius = coverageRadius();
        drawingSpace.set(light, camera, radius, size, (float) radius + DEPTH_RADIUS_EXTRA, zeroToOneDepth);
        drawQueue.clear();
        // A section's farthest point from its centre is about 13.9 blocks.
        double reach = radius + 14.0;
        store.forEachBuilt(casters -> {
            long key = casters.sectionKey();
            double x = SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8.0;
            double y = SectionPos.sectionToBlockCoord(SectionPos.y(key)) + 8.0;
            double z = SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8.0;
            if (Math.abs(drawingSpace.lightRight(x, y, z)) <= reach && Math.abs(drawingSpace.lightUp(x, y, z)) <= reach) {
                drawQueue.add(key);
            }
        });
        drawPosition = 0;
        redrawFrames = hurried ? 1
            : Math.clamp((drawQueue.size() + SECTIONS_PER_FRAME - 1) / SECTIONS_PER_FRAME, MIN_REDRAW_FRAMES, MAX_REDRAW_FRAMES);
        drawFramesLeft = redrawFrames;
        hurriedRedraw = hurried;
    }

    /** The sections to draw this frame; empty once the redraw is complete. The list is reused next frame. */
    public LongArrayList takeThisFramesSections() {
        batch.clear();
        if (drawFramesLeft <= 0) {
            return batch;
        }
        int remaining = drawQueue.size() - drawPosition;
        int count = (remaining + drawFramesLeft - 1) / drawFramesLeft;
        for (int i = 0; i < count; i++) {
            batch.add(drawQueue.getLong(drawPosition + i));
        }
        drawPosition += count;
        drawFramesLeft--;
        return batch;
    }

    /** Whether this frame's batch is the first of the redraw, so the back map must be cleared. */
    public boolean isFirstRedrawFrame() {
        return drawFramesLeft == redrawFrames;
    }

    /** Ends the redraw: the back map becomes the front one and the old front fades out. */
    public void finishRedraw(boolean smooth) {
        previousSpace.copyFrom(frontSpace);
        hasPrevious = hasFront;
        frontSpace.copyFrom(drawingSpace);
        hasFront = true;

        GpuTexture texture = front;
        GpuTextureView view = frontView;
        front = back;
        frontView = backView;
        back = texture;
        backView = view;

        smoothFade = smooth && !hurriedRedraw;
        fadeStartNanos = System.nanoTime();
        drawQueue.clear();
    }

    @Override
    public void close() {
        frontView.close();
        backView.close();
        front.close();
        back.close();
    }
}
