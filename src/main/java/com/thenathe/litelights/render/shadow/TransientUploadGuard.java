package com.thenathe.litelights.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.DeviceInfo;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the sun shadow map's draws safe on OpenGL drivers without persistent mapping (macOS's OpenGL
 * 4.1, and others without {@code ARB_buffer_storage}).
 *
 * <p>There renderpearl's fallback transient memory, where push constants live, gives a frame's blocks
 * back for reuse as soon as the frame is submitted and fills them through unsynchronized maps, while
 * the GPU may still be drawing that frame. A draw can then read push constants written by the next
 * frame. Most draws are redrawn every frame, so that is one wrong frame; the sun shadow map keeps what
 * it draws until its next redraw, so a section drawn with another draw's matrix stayed as a false,
 * blocky shadow (found on Mesa made to look like a Mac, DECISIONS compat095).
 *
 * <p>After a frame that drew casters, the next frame starts by waiting until the GPU has finished
 * that one, before anything can reuse its blocks. Within a frame the blocks are only handed out
 * forward, so nothing earlier can overwrite them. Everywhere else this does nothing.
 */
final class TransientUploadGuard {
    private final boolean needed;
    private @Nullable GpuFence casterFrame;

    TransientUploadGuard() {
        this(RenderSystem.getDevice().getDeviceInfo());
    }

    TransientUploadGuard(DeviceInfo device) {
        needed = needsGuard(device.backendName(), device.features().persistentMapping());
    }

    static boolean needsGuard(String backendName, boolean persistentMapping) {
        return "OpenGL".equals(backendName) && !persistentMapping;
    }

    /** After this frame's caster draws: the next frame waits for this one. */
    void afterDraws() {
        if (needed && casterFrame == null) {
            casterFrame = RenderSystem.getDevice().createCommandEncoder().createFence();
        }
    }

    /** First thing in a frame: if the previous frame drew casters, waits until the GPU is done with it. */
    void frameStarting() {
        if (casterFrame == null) {
            return;
        }
        try {
            casterFrame.awaitCompletion(GpuFence.NO_TIMEOUT);
        } catch (IllegalStateException sameFrame) {
            // No frame was submitted since the draws (renderpearl can't wait on the frame being
            // recorded): nothing has been handed back for reuse yet, so wait next time instead.
            return;
        }
        casterFrame.close();
        casterFrame = null;
    }
}
