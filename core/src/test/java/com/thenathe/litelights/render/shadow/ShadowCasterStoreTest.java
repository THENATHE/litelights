package com.thenathe.litelights.render.shadow;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowCasterStoreTest {
    /** A page that only knows its size; nothing is read back here. */
    private static final class FakePage implements GpuBuffer {
        final long size;
        boolean closed;

        FakePage(long size) {
            this.size = size;
        }

        @Override
        public long size() {
            return size;
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

    private final List<FakePage> pages = new ArrayList<>();
    private final ShadowCasterStore store = new ShadowCasterStore(new CasterBufferPool(new CasterBufferPool.Device() {
        @Override
        public GpuBuffer createPage(long bytes) {
            FakePage page = new FakePage(bytes);
            pages.add(page);
            return page;
        }

        @Override
        public void write(GpuBuffer page, long offset, byte[] data, int length) {
        }
    }));

    private static final long SECTION = SectionPos.asLong(3, 4, -5);

    private static CasterMesh mesh(long key, int quads, long hash) {
        return new CasterMesh(key, new byte[quads * CasterMesh.QUAD_SIZE], quads, hash);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void aMeshBuiltForTheCurrentLevelIsKept() {
        store.finishedBuild(mesh(SECTION, 3, 11L), store.generation());
        store.collectFinished();
        assertNotNull(store.get(SECTION));
        assertEquals(1, pages.size());
    }

    @Test
    void aMeshFinishedAfterALevelChangeIsDropped() {
        // The race: a worker started building for the old dimension; the level changes; only then
        // does the worker hand its mesh over. Before 1.0 the worker checked the generation and then
        // queued the mesh, so a change between the two let it through.
        long startedIn = store.generation();
        store.clear();
        store.finishedBuild(mesh(SECTION, 3, 11L), startedIn);
        store.collectFinished();
        assertNull(store.get(SECTION));
        assertEquals(0, store.builtCount());
        assertTrue(pages.isEmpty(), "no GPU memory for the old level's mesh");
    }

    @Test
    void releasingFreesTheMeshesAndMarksTheirSectionsForRebuilding() {
        long other = SectionPos.asLong(0, 0, 0);
        store.finishedBuild(mesh(SECTION, 3, 11L), store.generation());
        store.finishedBuild(mesh(other, 2, 12L), store.generation());
        store.finishedBuild(CasterMesh.empty(SectionPos.asLong(9, 9, 9)), store.generation());
        store.collectFinished();
        assertEquals(2, store.builtCount());
        long startedBefore = store.generation();

        store.releaseMeshes();
        assertEquals(0, store.builtCount());
        assertEquals(0, store.memory().buffers());
        assertTrue(pages.getFirst().closed);
        assertEquals(2, store.dirtyCount(), "the two sections with casters are built again; the empty one stays known");

        // A build that was running when shadows went off doesn't land either.
        store.finishedBuild(mesh(SECTION, 3, 11L), startedBefore);
        store.collectFinished();
        assertEquals(0, store.builtCount());
    }
}
