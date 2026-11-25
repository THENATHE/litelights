package com.thenathe.litelights.render.feature.clouds;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.thenathe.litelights.clouds.mesh.FaceFormat;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The faces on the GPU: two texel buffers, one drawn while the other takes the next build a slice
 * a frame, then they swap. A build is about 8 MB at 500,000 faces; written at once that is a stall
 * of a few milliseconds on some drivers, in slices it is nothing. Render thread only.
 */
final class CloudFaceBuffer implements AutoCloseable {
    /** How much of a build goes up in one frame. */
    private static final int SLICE_BYTES = 1024 * 1024;

    private @Nullable GpuBuffer front;
    private @Nullable GpuBuffer back;
    private int frontFaces;

    // The build being uploaded into the back buffer.
    private @Nullable ByteBuffer pending;
    private int pendingFaces;
    private int uploaded;

    /** The buffer to draw, or null before the first build is up. */
    @Nullable GpuBuffer drawn() {
        return frontFaces > 0 ? front : null;
    }

    int drawnFaces() {
        return front != null ? frontFaces : 0;
    }

    /** Starts uploading a build; its bytes must stay untouched until {@link #uploadSome} says it is done. */
    void begin(ByteBuffer faces, int faceCount) {
        pending = faces;
        pendingFaces = faceCount;
        uploaded = 0;
        long needed = Math.max(FaceFormat.BYTES, (long) faceCount * FaceFormat.BYTES);
        if (back == null || back.size() < needed) {
            if (back != null) {
                back.close();
            }
            // Room to grow, so a sky filling in doesn't reallocate every build.
            long size = Math.max(needed + needed / 2, 64L * 1024L);
            back = RenderSystem.getDevice().createBuffer(() -> "LiteLights dynamic cloud faces",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, size);
        }
    }

    boolean uploading() {
        return pending != null;
    }

    /**
     * Uploads the next slice of the pending build; true once all of it is in the back buffer.
     *
     * @param all upload the rest at once (the first build, or after a jump, when nothing is drawn yet)
     */
    boolean uploadSome(boolean all) {
        ByteBuffer faces = pending;
        GpuBuffer target = back;
        if (faces == null || target == null) {
            return true;
        }
        int total = pendingFaces * FaceFormat.BYTES;
        while (uploaded < total) {
            int length = Math.min(all ? total - uploaded : SLICE_BYTES, total - uploaded);
            ByteBuffer slice = faces.slice(uploaded, length);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(uploaded, length), slice);
            uploaded += length;
            if (!all) {
                break;
            }
        }
        return uploaded >= total;
    }

    /** Draws the uploaded build from now on; the old one becomes the back buffer for the next. */
    void swap() {
        GpuBuffer old = front;
        front = back;
        back = old;
        frontFaces = pendingFaces;
        pending = null;
        pendingFaces = 0;
        uploaded = 0;
    }

    /** Lets go of an upload that won't be shown (a jump made it stale). */
    void dropPending() {
        pending = null;
        pendingFaces = 0;
        uploaded = 0;
    }

    @Override
    public void close() {
        if (front != null) {
            front.close();
        }
        if (back != null) {
            back.close();
        }
        front = null;
        back = null;
        frontFaces = 0;
        dropPending();
    }
}
