package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.post.PostTexture;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

/**
 * Finds far water by what the far mod draws in its translucent pass, rather than by guessing from
 * the shape of the land: whatever translucent geometry covers the ground is water (or, rarely,
 * stained glass or ice), at any height, lakes and rivers included.
 *
 * <ul>
 *   <li><b>Distant Horizons</b> writes its water's depth. Its depth texture is copied just before its
 *   translucent pass ({@code DhApiBeforeRenderPassEvent}), so the far-terrain pass has both the
 *   ground under the water and the water's surface: where they differ there is water, and the
 *   difference is how thick it is.</li>
 *   <li><b>Voxy</b> has no API and draws its water without depth, so its depth only ever holds the
 *   ground. Its colour is copied just before and just after its translucent pass (an optional
 *   mixin at that call in Voxy's pipeline, {@code mixin.voxy}): where the two differ there is water.
 *   The surface height is then found from the shore around it, in the far-terrain pass.</li>
 * </ul>
 * Nothing is captured unless far water needs it this frame, and when a capture is missing (an older
 * or newer far mod, Distant Horizons drawing opaque and translucent in one pass) the pass falls back
 * to finding the sea by its height.
 */
final class FarWaterCapture implements AutoCloseable {
    private static final int GL_RGBA8 = 0x8058;

    private final PostTexture voxyBefore = new PostTexture("far water, Voxy before translucents", GpuFormat.RGBA8_UNORM, 1);
    private final PostTexture voxyAfter = new PostTexture("far water, Voxy after translucents", GpuFormat.RGBA8_UNORM, 1);
    private @Nullable GpuTexture distantHorizonsFloor;
    private @Nullable GpuTextureView distantHorizonsFloorView;

    /** Set once a frame: whether far water wants a capture, and the screen size it must match. */
    private boolean wanted;
    private int width;
    private int height;

    private boolean voxyBeforeTaken;
    private boolean voxyAfterTaken;
    private boolean distantHorizonsTaken;
    private @Nullable String voxyProblem;

    /** For QA and the describe line. */
    private long voxyCaptures;
    private long distantHorizonsCaptures;
    private long distantHorizonsCombinedPasses;

    /** Called at the start of each frame, before either far mod draws. */
    void prepare(boolean wantedThisFrame, int screenWidth, int screenHeight) {
        wanted = wantedThisFrame;
        width = screenWidth;
        height = screenHeight;
        voxyBeforeTaken = false;
        voxyAfterTaken = false;
        distantHorizonsTaken = false;
        if (wanted) {
            // Made here, outside the far mods' own GL work, so nothing is allocated in the middle of it.
            voxyBefore.ensure(width, height);
            voxyAfter.ensure(width, height);
        }
    }

    // --- Voxy (from mixin.voxy, on the render thread, inside Voxy's pipeline) ---

    void voxyBeforeTranslucents() {
        if (wanted && voxyProblem == null) {
            voxyBeforeTaken = copyBoundColour(voxyBefore);
        }
    }

    void voxyAfterTranslucents() {
        if (wanted && voxyProblem == null && voxyBeforeTaken) {
            voxyAfterTaken = copyBoundColour(voxyAfter);
            if (voxyAfterTaken) {
                voxyCaptures++;
            }
        }
    }

    /**
     * Copies the colour attachment of the framebuffer Voxy is drawing into, as it is now. Only GL
     * state is read and only a texture copy is issued, so no binding Voxy relies on changes.
     */
    private boolean copyBoundColour(PostTexture into) {
        GLCapabilities capabilities = GL.getCapabilities();
        if (!hasDirectCopy(capabilities.OpenGL45, capabilities.GL_ARB_direct_state_access, capabilities.OpenGL43, capabilities.GL_ARB_copy_image)) {
            // Calling them anyway would jump to a null function: a native crash, not an exception.
            return voxyGaveUp("the OpenGL driver lacks the direct state access queries and image copy it needs (OpenGL 4.5)");
        }
        int framebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0 || !(into.texture() instanceof GlTexture target)) {
            return false;
        }
        int type = GL45C.glGetNamedFramebufferAttachmentParameteri(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL11C.GL_TEXTURE) {
            return voxyGaveUp("its colour target isn't a texture");
        }
        int source = GL45C.glGetNamedFramebufferAttachmentParameteri(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int sourceWidth = GL45C.glGetTextureLevelParameteri(source, 0, GL11C.GL_TEXTURE_WIDTH);
        int sourceHeight = GL45C.glGetTextureLevelParameteri(source, 0, GL11C.GL_TEXTURE_HEIGHT);
        int format = GL45C.glGetTextureLevelParameteri(source, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        if (format != GL_RGBA8) {
            return voxyGaveUp("its colour target isn't RGBA8 (" + format + ")");
        }
        if (sourceWidth != width || sourceHeight != height) {
            // Voxy drawing at a scaled resolution: no pixel-for-pixel match with the screen this frame.
            return false;
        }
        GL43C.glCopyImageSubData(source, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
            target.glId(), GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
        return true;
    }

    /**
     * Whether the driver has what {@link #copyBoundColour} calls: OpenGL 4.5's direct state access
     * queries and OpenGL 4.3's image copy, each core or as its extension. macOS's OpenGL 4.1 has
     * neither, though Voxy doesn't run there anyway.
     */
    static boolean hasDirectCopy(boolean openGl45, boolean directStateAccess, boolean openGl43, boolean copyImage) {
        return (openGl45 || directStateAccess) && (openGl43 || copyImage);
    }

    private boolean voxyGaveUp(String reason) {
        voxyProblem = reason;
        LiteLightsClient.LOG.warn("Far water can't read Voxy's translucent pass ({}); the sea is found by its height instead", reason);
        return false;
    }

    boolean voxyCaptured() {
        return voxyBeforeTaken && voxyAfterTaken;
    }

    GpuTextureView voxyBeforeView() {
        return voxyBefore.view();
    }

    GpuTextureView voxyAfterView() {
        return voxyAfter.view();
    }

    // --- Distant Horizons (from DistantHorizonsHook, inside its render) ---

    /** Before Distant Horizons' translucent pass: its depth texture holds the ground under the water. */
    void distantHorizonsBeforeTranslucents(GpuTexture depth) {
        if (!wanted) {
            return;
        }
        int depthWidth = depth.getWidth(0);
        int depthHeight = depth.getHeight(0);
        GpuTexture floor = distantHorizonsFloor;
        if (floor == null || floor.isClosed() || floor.getWidth(0) != depthWidth || floor.getHeight(0) != depthHeight
            || floor.getFormat() != depth.getFormat()) {
            closeDistantHorizons();
            var device = RenderSystem.getDevice();
            floor = device.createTexture("LiteLights far water floor (Distant Horizons)",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, depth.getFormat(), depthWidth, depthHeight, 1, 1);
            distantHorizonsFloor = floor;
            distantHorizonsFloorView = device.createTextureView(floor);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, floor, 0, 0, 0, 0, 0, depthWidth, depthHeight);
        distantHorizonsTaken = true;
        distantHorizonsCaptures++;
    }

    /** Distant Horizons drew opaque and translucent terrain in one pass: nothing to tell apart. */
    void distantHorizonsCombinedPass() {
        distantHorizonsCombinedPasses++;
    }

    boolean distantHorizonsCaptured() {
        return distantHorizonsTaken && distantHorizonsFloorView != null;
    }

    @Nullable GpuTextureView distantHorizonsFloorView() {
        return distantHorizonsFloorView;
    }

    String describe() {
        return "farWaterCapture{voxy=" + voxyCaptures + (voxyProblem != null ? " (" + voxyProblem + ")" : "")
            + ", distantHorizons=" + distantHorizonsCaptures + ", distantHorizonsCombined=" + distantHorizonsCombinedPasses + "}";
    }

    private void closeDistantHorizons() {
        if (distantHorizonsFloorView != null) {
            distantHorizonsFloorView.close();
            distantHorizonsFloorView = null;
        }
        if (distantHorizonsFloor != null) {
            distantHorizonsFloor.close();
            distantHorizonsFloor = null;
        }
    }

    @Override
    public void close() {
        voxyBefore.close();
        voxyAfter.close();
        closeDistantHorizons();
    }
}
