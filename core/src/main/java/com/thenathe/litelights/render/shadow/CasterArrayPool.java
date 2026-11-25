package com.thenathe.litelights.render.shadow;

import java.util.ArrayDeque;

/**
 * Vertex arrays for caster meshes, given back once a mesh has been uploaded or dropped, so the
 * workers don't allocate a new array for every rebuild (light changes rebuild sections all the
 * time). Arrays come in power-of-two sizes from {@link #MIN_BYTES} to {@link #MAX_POOLED_BYTES};
 * a mesh larger than that gets an array of its own, which is never kept.
 *
 * <p>Thread-safe: worker threads take arrays and the render thread gives them back. An array that
 * is given back must no longer be read or written by anyone.
 */
final class CasterArrayPool {
    static final int MIN_BYTES = 4 << 10;
    static final int MAX_POOLED_BYTES = 1 << 20;
    /**
     * Bytes held for reuse, at most. A few hundred typical meshes (12 KiB on average) are in flight
     * while chunks stream in, so this covers them; anything beyond is left to the garbage collector.
     */
    static final long BUDGET_BYTES = 8L << 20;

    private static final int SIZE_CLASSES = Integer.numberOfTrailingZeros(MAX_POOLED_BYTES / MIN_BYTES) + 1;

    @SuppressWarnings("unchecked")
    private final ArrayDeque<byte[]>[] free = new ArrayDeque[SIZE_CLASSES];
    private long heldBytes;

    CasterArrayPool() {
        for (int i = 0; i < SIZE_CLASSES; i++) {
            free[i] = new ArrayDeque<>();
        }
    }

    /** An array of at least {@code bytes} bytes, with whatever an earlier mesh left in it. */
    synchronized byte[] take(int bytes) {
        if (bytes > MAX_POOLED_BYTES) {
            return new byte[bytes];
        }
        int sizeClass = sizeClass(bytes);
        byte[] array = free[sizeClass].pollLast();
        if (array == null) {
            return new byte[MIN_BYTES << sizeClass];
        }
        heldBytes -= array.length;
        return array;
    }

    /** Hands an array back for reuse; arrays the pool doesn't deal in are simply dropped. */
    synchronized void give(byte[] array) {
        int length = array.length;
        if (length < MIN_BYTES || length > MAX_POOLED_BYTES || Integer.bitCount(length) != 1
            || heldBytes + length > BUDGET_BYTES) {
            return;
        }
        free[sizeClass(length)].addLast(array);
        heldBytes += length;
    }

    synchronized long heldBytes() {
        return heldBytes;
    }

    /** The smallest class whose arrays hold {@code bytes}. */
    private static int sizeClass(int bytes) {
        int units = Math.max(1, (bytes + MIN_BYTES - 1) / MIN_BYTES);
        return 32 - Integer.numberOfLeadingZeros(units - 1);
    }
}
