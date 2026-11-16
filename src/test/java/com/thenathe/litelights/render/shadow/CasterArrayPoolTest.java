package com.thenathe.litelights.render.shadow;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasterArrayPoolTest {
    @Test
    void arraysComeInPowerOfTwoSizesLargeEnough() {
        CasterArrayPool pool = new CasterArrayPool();
        for (int bytes : new int[] {1, 48, CasterArrayPool.MIN_BYTES, CasterArrayPool.MIN_BYTES + 1, 12_000, 100_000,
            CasterArrayPool.MAX_POOLED_BYTES}) {
            byte[] array = pool.take(bytes);
            assertTrue(array.length >= bytes, bytes + " bytes");
            assertTrue(array.length < 2 * Math.max(bytes, CasterArrayPool.MIN_BYTES), bytes + " bytes");
            assertEquals(1, Integer.bitCount(array.length));
        }
    }

    @Test
    void aGivenBackArrayIsTakenAgain() {
        CasterArrayPool pool = new CasterArrayPool();
        byte[] first = pool.take(10_000);
        pool.give(first);
        assertEquals(first.length, pool.heldBytes());
        assertSame(first, pool.take(9_000));
        assertEquals(0, pool.heldBytes());
        assertNotSame(first, pool.take(9_000));
    }

    @Test
    void meshesLargerThanThePoolDealInGetExactArraysThatAreNotKept() {
        CasterArrayPool pool = new CasterArrayPool();
        int bytes = CasterArrayPool.MAX_POOLED_BYTES + 48;
        byte[] array = pool.take(bytes);
        assertEquals(bytes, array.length);
        pool.give(array);
        pool.give(new byte[0]);
        pool.give(new byte[48]);
        assertEquals(0, pool.heldBytes());
    }

    @Test
    void thePoolHoldsNoMoreThanItsBudget() {
        CasterArrayPool pool = new CasterArrayPool();
        long arrays = CasterArrayPool.BUDGET_BYTES / CasterArrayPool.MAX_POOLED_BYTES + 3;
        for (int i = 0; i < arrays; i++) {
            pool.give(new byte[CasterArrayPool.MAX_POOLED_BYTES]);
        }
        assertEquals(CasterArrayPool.BUDGET_BYTES, pool.heldBytes());
    }

    /** Writes the same random quads into a writer, so two writers can be compared. */
    private static CasterMesh randomMesh(CasterMeshWriter writer, long seed, int quads) {
        Random random = new Random(seed);
        writer.reset();
        for (int quad = 0; quad < quads; quad++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                writer.vertex(random.nextFloat() * 20 - 2, random.nextFloat() * 16, random.nextFloat() * 16,
                    random.nextFloat(), random.nextFloat(), random.nextInt(2));
            }
            writer.endQuad();
        }
        return writer.finish(seed);
    }

    @Test
    void aReusedArrayGivesTheSameMeshAsAFreshOne() {
        CasterArrayPool reused = new CasterArrayPool();
        CasterMeshWriter reusingWriter = new CasterMeshWriter(reused);
        // Fill the pool with arrays full of leftovers, as earlier, larger meshes leave them.
        for (int bytes = CasterArrayPool.MIN_BYTES; bytes <= 64 << 10; bytes *= 2) {
            for (int i = 0; i < 4; i++) {
                byte[] leftover = new byte[bytes];
                Arrays.fill(leftover, (byte) 0x5A);
                reused.give(leftover);
            }
        }
        for (long seed = 1; seed <= 40; seed++) {
            int quads = 1 + (int) (seed * 37 % 700);
            CasterMesh fresh = randomMesh(new CasterMeshWriter(new CasterArrayPool()), seed, quads);
            CasterMesh again = randomMesh(reusingWriter, seed, quads);
            assertEquals(fresh.quadCount(), again.quadCount());
            assertEquals(fresh.hash(), again.hash());
            assertEquals(fresh.byteCount(), again.byteCount());
            assertEquals(quads * CasterMesh.QUAD_SIZE, again.byteCount());
            assertArrayEquals(Arrays.copyOf(fresh.vertices(), fresh.byteCount()), Arrays.copyOf(again.vertices(), again.byteCount()),
                "seed " + seed);
            reused.give(again.vertices());
        }
    }

    @Test
    void anEmptyMeshTakesNoArray() {
        CasterArrayPool pool = new CasterArrayPool();
        pool.give(pool.take(100));
        long held = pool.heldBytes();
        CasterMesh mesh = new CasterMeshWriter(pool).finish(3L);
        assertTrue(mesh.isEmpty());
        assertEquals(0, mesh.byteCount());
        assertEquals(held, pool.heldBytes());
    }
}
