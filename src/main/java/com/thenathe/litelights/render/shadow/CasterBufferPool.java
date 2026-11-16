package com.thenathe.litelights.render.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU storage for the sections' caster meshes: a few large vertex buffers ("pages"), each split up
 * with Minecraft's own TLSF allocator, instead of one buffer per section. A redraw binds a page once
 * and draws each section in it with a base vertex.
 *
 * <p>Every range starts on a vertex boundary, so its first vertex is its offset divided by
 * {@link CasterMesh#VERTEX_SIZE}. A freed range is only handed out again after
 * {@link #REUSE_DELAY_FRAMES} frames, so no frame still on the GPU can read a range while it is being
 * rewritten. A page left completely empty is released, except the first.
 */
final class CasterBufferPool implements AutoCloseable {
    /**
     * 4 MiB holds a few hundred typical sections (12 KiB on average). 8 MiB pages reserved about the
     * same in the benchmark scenes (64 to 80 MiB for 59 to 68 MiB of meshes), so the smaller page keeps
     * the worst case down. A mesh larger than a page gets a page of its own size.
     */
    static final long PAGE_BYTES = 4L << 20;
    static final int REUSE_DELAY_FRAMES = 4;

    /** Creates pages and fills ranges of them; the real one talks to the GPU, tests use a fake. */
    interface Device {
        GpuBuffer createPage(long bytes);

        void write(GpuBuffer page, long offset, byte[] data);
    }

    /** One page: a GPU buffer and the allocator that splits it up. */
    static final class Page extends TlsfAllocator.Heap {
        final GpuBuffer buffer;
        final TlsfAllocator allocator;

        private Page(GpuBuffer buffer) {
            super(buffer.size());
            this.buffer = buffer;
            this.allocator = new TlsfAllocator(this);
        }
    }

    /** Where one section's mesh lives. */
    record Slot(Page page, TlsfAllocator.Allocation allocation, int bytes) {
        GpuBuffer buffer() {
            return page.buffer;
        }

        int firstVertex() {
            return (int) (allocation.getOffsetFromHeap() / CasterMesh.VERTEX_SIZE);
        }
    }

    private record PendingFree(Slot slot, long freeAtFrame) {
    }

    private final Device device;
    private final List<Page> pages = new ArrayList<>();
    private final ArrayDeque<PendingFree> pendingFrees = new ArrayDeque<>();
    private long frame;
    private long usedBytes;

    CasterBufferPool(Device device) {
        this.device = device;
    }

    static CasterBufferPool onGpu() {
        return new CasterBufferPool(new Device() {
            @Override
            public GpuBuffer createPage(long bytes) {
                return RenderSystem.getDevice().createBuffer(() -> "LiteLights shadow casters",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes);
            }

            @Override
            public void write(GpuBuffer page, long offset, byte[] data) {
                ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
                try {
                    buffer.put(data).flip();
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(page.slice(offset, data.length), buffer);
                } finally {
                    MemoryUtil.memFree(buffer);
                }
            }
        });
    }

    /** Stores a mesh's vertices and returns where they went. */
    Slot store(byte[] vertices) {
        Slot slot = allocate(vertices.length);
        device.write(slot.buffer(), slot.allocation().getOffsetFromHeap(), vertices);
        usedBytes += vertices.length;
        return slot;
    }

    private Slot allocate(int bytes) {
        for (Page page : pages) {
            TlsfAllocator.Allocation allocation = page.allocator.allocate(bytes, CasterMesh.VERTEX_SIZE);
            if (allocation != null) {
                return new Slot(page, allocation, bytes);
            }
        }
        // The allocator looks for a free block of the request's size class, which can be up to an
        // eighth larger than the request, so a page made for one big mesh needs that much room.
        Page page = new Page(device.createPage(Math.max(PAGE_BYTES, bytes + bytes / 4L + 4096L)));
        pages.add(page);
        TlsfAllocator.Allocation allocation = page.allocator.allocate(bytes, CasterMesh.VERTEX_SIZE);
        if (allocation == null) {
            throw new IllegalStateException("A new shadow caster page couldn't hold " + bytes + " bytes");
        }
        return new Slot(page, allocation, bytes);
    }

    /** Gives a slot back; its range is reused after a few frames. */
    void release(Slot slot) {
        usedBytes -= slot.bytes();
        pendingFrees.add(new PendingFree(slot, frame + REUSE_DELAY_FRAMES));
    }

    /** Once per frame: frees the ranges whose delay has passed and drops pages left empty. */
    void endFrame() {
        frame++;
        boolean freedAny = false;
        while (!pendingFrees.isEmpty() && pendingFrees.peekFirst().freeAtFrame() <= frame) {
            Slot slot = pendingFrees.pollFirst().slot();
            slot.page().allocator.free(slot.allocation());
            freedAny = true;
        }
        if (freedAny) {
            releaseEmptyPages();
        }
    }

    private void releaseEmptyPages() {
        for (int i = pages.size() - 1; i >= 1; i--) {
            Page page = pages.get(i);
            if (page.allocator.isCompletelyFree()) {
                page.buffer.close();
                pages.remove(i);
            }
        }
    }

    int pageCount() {
        return pages.size();
    }

    long reservedBytes() {
        long bytes = 0L;
        for (Page page : pages) {
            bytes += page.buffer.size();
        }
        return bytes;
    }

    long usedBytes() {
        return usedBytes;
    }

    @Override
    public void close() {
        clear();
    }

    /** Releases every page at once (level change, shutdown). Slots handed out before are invalid. */
    void clear() {
        for (Page page : pages) {
            page.buffer.close();
        }
        pages.clear();
        pendingFrees.clear();
        usedBytes = 0L;
    }
}
