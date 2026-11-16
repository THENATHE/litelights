package com.thenathe.litelights.render.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasterBufferPoolTest {
    /** A GPU buffer kept in memory, so tests can read back what the pool wrote. */
    private static final class FakeBuffer implements GpuBuffer {
        final byte[] bytes;
        boolean closed;

        FakeBuffer(long size) {
            bytes = new byte[(int) size];
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public int usage() {
            return USAGE_VERTEX | USAGE_COPY_DST;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final List<FakeBuffer> created = new ArrayList<>();
    private final CasterBufferPool pool = new CasterBufferPool(new CasterBufferPool.Device() {
        @Override
        public GpuBuffer createPage(long bytes) {
            FakeBuffer buffer = new FakeBuffer(bytes);
            created.add(buffer);
            return buffer;
        }

        @Override
        public void write(GpuBuffer page, long offset, byte[] data, int length) {
            System.arraycopy(data, 0, ((FakeBuffer) page).bytes, (int) offset, length);
        }
    });

    private CasterBufferPool.Slot store(byte[] vertices) {
        return pool.store(vertices, vertices.length);
    }

    private static byte[] mesh(int quads, int fill) {
        byte[] vertices = new byte[quads * 4 * CasterMesh.VERTEX_SIZE];
        Arrays.fill(vertices, (byte) fill);
        return vertices;
    }

    private static long offset(CasterBufferPool.Slot slot) {
        return slot.allocation().getOffsetFromHeap();
    }

    @Test
    void meshesShareOnePageOnVertexBoundariesWithoutOverlapping() {
        List<CasterBufferPool.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            slots.add(store(mesh(1 + i % 7, i + 1)));
        }
        assertEquals(1, pool.pageCount());
        slots.sort((a, b) -> Long.compare(offset(a), offset(b)));
        for (int i = 0; i < slots.size(); i++) {
            CasterBufferPool.Slot slot = slots.get(i);
            assertEquals(0, offset(slot) % CasterMesh.VERTEX_SIZE);
            assertEquals(offset(slot), (long) slot.firstVertex() * CasterMesh.VERTEX_SIZE);
            if (i > 0) {
                CasterBufferPool.Slot previous = slots.get(i - 1);
                assertTrue(offset(previous) + previous.bytes() <= offset(slot));
            }
        }
    }

    @Test
    void writtenVerticesLandInTheirRange() {
        byte[] vertices = mesh(3, 42);
        CasterBufferPool.Slot slot = store(vertices);
        byte[] page = ((FakeBuffer) slot.buffer()).bytes;
        int from = (int) offset(slot);
        assertArrayEquals(vertices, Arrays.copyOfRange(page, from, from + vertices.length));
    }

    @Test
    void onlyTheMeshPartOfALongerArrayIsStored() {
        byte[] vertices = mesh(5, 7);
        int bytes = 2 * 4 * CasterMesh.VERTEX_SIZE;
        CasterBufferPool.Slot slot = pool.store(vertices, bytes);
        assertEquals(bytes, slot.bytes());
        assertEquals(bytes, pool.usedBytes());
        byte[] page = ((FakeBuffer) slot.buffer()).bytes;
        int from = (int) offset(slot);
        assertArrayEquals(Arrays.copyOf(vertices, bytes), Arrays.copyOfRange(page, from, from + bytes));
    }

    @Test
    void aReleasedRangeIsReusedOnlyAfterTheDelay() {
        CasterBufferPool.Slot first = store(mesh(4, 1));
        pool.release(first);
        for (int frame = 0; frame < CasterBufferPool.REUSE_DELAY_FRAMES - 1; frame++) {
            pool.endFrame();
            CasterBufferPool.Slot meanwhile = store(mesh(4, 2));
            assertNotEquals(offset(first), offset(meanwhile), "reused while a frame in flight could still read it");
        }
        pool.endFrame();
        assertEquals(pool.usedBytes(), (CasterBufferPool.REUSE_DELAY_FRAMES - 1) * mesh(4, 0).length);
    }

    @Test
    void aMeshLargerThanAPageGetsItsOwnPageWhichGoesWhenEmpty() {
        store(mesh(1, 1));
        int bigQuads = (int) (CasterBufferPool.PAGE_BYTES / (4 * CasterMesh.VERTEX_SIZE)) + 10;
        CasterBufferPool.Slot big = store(mesh(bigQuads, 2));
        assertEquals(2, pool.pageCount());
        assertTrue(big.buffer().size() >= big.bytes());
        pool.release(big);
        for (int frame = 0; frame <= CasterBufferPool.REUSE_DELAY_FRAMES; frame++) {
            pool.endFrame();
        }
        assertEquals(1, pool.pageCount());
        assertTrue(((FakeBuffer) big.buffer()).closed);
        assertFalse(created.getFirst().closed);
    }

    @Test
    void clearingReleasesEveryPage() {
        store(mesh(2, 1));
        pool.clear();
        assertEquals(0, pool.pageCount());
        assertEquals(0, pool.usedBytes());
        assertTrue(created.stream().allMatch(buffer -> buffer.closed));
    }
}
