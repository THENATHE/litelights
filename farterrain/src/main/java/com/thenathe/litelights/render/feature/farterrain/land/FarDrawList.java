package com.thenathe.litelights.render.feature.farterrain.land;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * One frame's draws, per quad page: first vertex and vertex count, in both forms {@code multiDraw}
 * takes (separate lists on OpenGL, interleaved pairs on Vulkan). Reused every frame; the buffers
 * grow and are never made again per frame. Render thread only.
 */
final class FarDrawList implements AutoCloseable {
    static final class PageDraws {
        IntBuffer firsts = MemoryUtil.memAllocInt(256);
        IntBuffer counts = MemoryUtil.memAllocInt(256);
        IntBuffer interleaved = MemoryUtil.memAllocInt(512);
        int draws;
        long vertices;

        void add(int firstVertex, int vertexCount) {
            if (draws == firsts.capacity()) {
                firsts = MemoryUtil.memRealloc(firsts, draws * 2);
                counts = MemoryUtil.memRealloc(counts, draws * 2);
                interleaved = MemoryUtil.memRealloc(interleaved, draws * 4);
            }
            firsts.put(draws, firstVertex);
            counts.put(draws, vertexCount);
            interleaved.put(draws * 2, firstVertex);
            interleaved.put(draws * 2 + 1, vertexCount);
            draws++;
            vertices += vertexCount;
        }

        void free() {
            MemoryUtil.memFree(firsts);
            MemoryUtil.memFree(counts);
            MemoryUtil.memFree(interleaved);
        }
    }

    private final List<PageDraws> pages = new ArrayList<>();

    void clear() {
        for (PageDraws page : pages) {
            page.draws = 0;
            page.vertices = 0;
        }
    }

    void add(int page, int firstVertex, int vertexCount) {
        while (pages.size() <= page) {
            pages.add(new PageDraws());
        }
        pages.get(page).add(firstVertex, vertexCount);
    }

    List<PageDraws> pages() {
        return pages;
    }

    int draws() {
        int total = 0;
        for (PageDraws page : pages) {
            total += page.draws;
        }
        return total;
    }

    long quads() {
        long total = 0;
        for (PageDraws page : pages) {
            total += page.vertices / 6;
        }
        return total;
    }

    boolean isEmpty() {
        return draws() == 0;
    }

    @Override
    public void close() {
        for (PageDraws page : pages) {
            page.free();
        }
        pages.clear();
    }
}
