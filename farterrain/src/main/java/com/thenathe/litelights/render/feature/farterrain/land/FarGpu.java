package com.thenathe.litelights.render.feature.farterrain.land;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Far terrain's video memory: the quads in pages of {@link #PAGE_BYTES} (each a texel buffer, split
 * with Minecraft's own TLSF allocator), the node table (each tile mesh's origin and level, which
 * its quads point at) and the appearance table (each appearance's sprite in the block atlas).
 * Nothing exists until the first mesh arrives. A freed range or node is handed out again only
 * {@link #REUSE_DELAY_FRAMES} frames later, so no frame still on the GPU reads it while it is
 * rewritten. Render thread only.
 */
final class FarGpu implements AutoCloseable {
    /** 2 million quads a page: well inside every driver's texel buffer limit. */
    static final long PAGE_BYTES = 32L << 20;
    static final int NODE_CAPACITY = 1 << 16;
    static final int APPEARANCE_CAPACITY = 1 << 16;
    private static final int REUSE_DELAY_FRAMES = 4;

    static final class Page extends TlsfAllocator.Heap {
        final GpuBuffer buffer;
        final TlsfAllocator allocator;
        final int index;

        Page(GpuBuffer buffer, int index) {
            super(buffer.size());
            this.buffer = buffer;
            this.allocator = new TlsfAllocator(this);
            this.index = index;
        }
    }

    /** Where one mesh's quads are. */
    record Slot(Page page, TlsfAllocator.Allocation allocation, int quads, int node) {
        int firstQuad() {
            return (int) (allocation.getOffsetFromHeap() / FarQuad.BYTES);
        }
    }

    private record PendingFree(@Nullable Slot slot, int node, long freeAtFrame) {
    }

    private final List<Page> pages = new ArrayList<>();
    private final ArrayDeque<Integer> freeNodes = new ArrayDeque<>();
    private final ArrayDeque<PendingFree> pendingFrees = new ArrayDeque<>();
    private @Nullable GpuBuffer nodes;
    private @Nullable GpuBuffer appearanceTable;
    private int nextNode;
    private long frame;
    private long usedBytes;
    private int appearanceRevision = -1;
    private int atlasGeneration = -1;
    private int appearanceCount;

    /** Room for a mesh of this many quads within the budget, or null if there is none. */
    @Nullable Slot allocate(int quads, long budgetBytes) {
        int node = takeNode();
        if (node < 0) {
            return null;
        }
        long bytes = (long) quads * FarQuad.BYTES;
        for (Page page : pages) {
            TlsfAllocator.Allocation allocation = page.allocator.allocate(bytes, FarQuad.BYTES);
            if (allocation != null) {
                usedBytes += bytes;
                return new Slot(page, allocation, quads, node);
            }
        }
        long pageBytes = Math.max(PAGE_BYTES, bytes + bytes / 4 + 4096);
        if (reservedBytes() + pageBytes > budgetBytes) {
            freeNodes.addFirst(node);
            return null;
        }
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "LiteLights far terrain quads",
            GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, pageBytes);
        Page page = new Page(buffer, pages.size());
        pages.add(page);
        TlsfAllocator.Allocation allocation = page.allocator.allocate(bytes, FarQuad.BYTES);
        if (allocation == null) {
            freeNodes.addFirst(node);
            return null;
        }
        usedBytes += bytes;
        return new Slot(page, allocation, quads, node);
    }

    private int takeNode() {
        if (!freeNodes.isEmpty()) {
            return freeNodes.pollFirst();
        }
        return nextNode < NODE_CAPACITY ? nextNode++ : -1;
    }

    /** Writes a mesh's quads, pointed at its node, and the node: the tile's origin in blocks and its level. */
    void upload(Slot slot, int[] quads, int originX, int originY, int originZ, int level) {
        for (int offset = 0; offset < quads.length; offset += FarQuad.INTS) {
            FarQuad.setNode(quads, offset, slot.node());
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        int bytes = quads.length * 4;
        ByteBuffer data = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
        try {
            data.asIntBuffer().put(quads);
            data.limit(bytes);
            encoder.writeToBuffer(slot.page().buffer.slice(slot.allocation().getOffsetFromHeap(), bytes), data);
        } finally {
            MemoryUtil.memFree(data);
        }
        ByteBuffer node = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        try {
            node.putInt(originX).putInt(originY).putInt(originZ).putInt(level).flip();
            encoder.writeToBuffer(nodes().slice((long) slot.node() * 16, 16), node);
        } finally {
            MemoryUtil.memFree(node);
        }
    }

    /**
     * Writes a node: the tile's origin in blocks, and in {@code w} its level (bits 0 to 3), its fade
     * (bits 4 and 5: 0 none, 1 in, 2 out) and when the fade began (from bit 6, in hundredths of a
     * second since the world opened).
     */
    void writeNode(int node, int originX, int originY, int originZ, int w) {
        ByteBuffer data = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        try {
            data.putInt(originX).putInt(originY).putInt(originZ).putInt(w).flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(nodes().slice((long) node * 16, 16), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    GpuBuffer nodes() {
        if (nodes == null) {
            nodes = RenderSystem.getDevice().createBuffer(() -> "LiteLights far terrain nodes",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, (long) NODE_CAPACITY * 16);
        }
        return nodes;
    }

    /**
     * The appearance table, written again when appearances were added or the atlas was stitched
     * again: per id, the sprite's corner and size in atlas texels and its flags.
     */
    GpuBuffer appearances(FarAppearances appearances, TextureAtlas atlas, int atlasGeneration) {
        if (appearanceTable == null) {
            appearanceTable = RenderSystem.getDevice().createBuffer(() -> "LiteLights far terrain appearances",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, (long) APPEARANCE_CAPACITY * 16);
        }
        int revision = appearances.revision();
        if (revision != appearanceRevision || atlasGeneration != this.atlasGeneration) {
            List<FarAppearances.Appearance> all = appearances.snapshot();
            int count = Math.min(all.size(), APPEARANCE_CAPACITY);
            int atlasWidth = atlas.getTexture().getWidth(0);
            int atlasHeight = atlas.getTexture().getHeight(0);
            ByteBuffer table = MemoryUtil.memAlloc(count * 16).order(ByteOrder.nativeOrder());
            try {
                for (int id = 0; id < count; id++) {
                    FarAppearances.Appearance appearance = all.get(id);
                    if (appearance == null) {
                        table.putInt(0).putInt(1 | 1 << 16).putInt(0).putInt(0);
                        continue;
                    }
                    TextureAtlasSprite sprite = atlas.getSprite(appearance.sprite());
                    int width = Math.max(1, sprite.contents().width());
                    int height = Math.max(1, sprite.contents().height());
                    // The atlas pads every sprite, and getX()/getY() give the padded slot; the sprite
                    // itself starts where its UV does, as the near terrain and the material texture
                    // (MaterialAtlas) see it.
                    int x = Math.round(sprite.getU0() * atlasWidth);
                    int y = Math.round(sprite.getV0() * atlasHeight);
                    table.putInt(x | y << 16).putInt(width | height << 16).putInt(appearance.flags()).putInt(0);
                }
                table.flip();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(appearanceTable.slice(0, (long) count * 16), table);
            } finally {
                MemoryUtil.memFree(table);
            }
            appearanceRevision = revision;
            this.atlasGeneration = atlasGeneration;
            appearanceCount = count;
        }
        return appearanceTable;
    }

    /** Gives a mesh's range and node back; reused a few frames later. */
    void release(Slot slot) {
        usedBytes -= (long) slot.quads() * FarQuad.BYTES;
        pendingFrees.add(new PendingFree(slot, slot.node(), frame + REUSE_DELAY_FRAMES));
    }

    void endFrame() {
        frame++;
        while (!pendingFrees.isEmpty() && pendingFrees.peekFirst().freeAtFrame() <= frame) {
            PendingFree free = pendingFrees.pollFirst();
            if (free.slot() != null) {
                free.slot().page().allocator.free(free.slot().allocation());
            }
            freeNodes.add(free.node());
        }
    }

    List<Page> pages() {
        return pages;
    }

    long usedBytes() {
        return usedBytes;
    }

    long reservedBytes() {
        long bytes = 0;
        for (Page page : pages) {
            bytes += page.buffer.size();
        }
        if (nodes != null) {
            bytes += nodes.size();
        }
        if (appearanceTable != null) {
            bytes += appearanceTable.size();
        }
        return bytes;
    }

    int appearanceCount() {
        return appearanceCount;
    }

    boolean exists() {
        return !pages.isEmpty() || nodes != null || appearanceTable != null;
    }

    @Override
    public void close() {
        for (Page page : pages) {
            page.buffer.close();
        }
        pages.clear();
        if (nodes != null) {
            nodes.close();
            nodes = null;
        }
        if (appearanceTable != null) {
            appearanceTable.close();
            appearanceTable = null;
        }
        freeNodes.clear();
        pendingFrees.clear();
        nextNode = 0;
        usedBytes = 0;
        appearanceRevision = -1;
        atlasGeneration = -1;
    }
}
