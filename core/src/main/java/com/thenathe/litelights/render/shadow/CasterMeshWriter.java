package com.thenathe.litelights.render.shadow;

import java.util.Arrays;

/**
 * Packs caster quads into the {@link CasterMesh} vertex layout and hashes them as it goes.
 * Not thread-safe; each worker thread uses its own.
 */
final class CasterMeshWriter {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final CasterArrayPool arrays;
    private byte[] data = new byte[CasterMesh.QUAD_SIZE * 256];
    private int size;
    private int quadCount;
    private long hash = FNV_OFFSET;

    CasterMeshWriter(CasterArrayPool arrays) {
        this.arrays = arrays;
    }

    void reset() {
        size = 0;
        quadCount = 0;
        hash = FNV_OFFSET;
    }

    /** Adds one vertex: section-local position in blocks, atlas texture coordinates, flags. */
    void vertex(float x, float y, float z, float u, float v, int flags) {
        ensureCapacity(CasterMesh.VERTEX_SIZE);
        putShort(encodePosition(x));
        putShort(encodePosition(y));
        putShort(encodePosition(z));
        putShort(flags);
        putShort(encodeUnit(u));
        putShort(encodeUnit(v));
    }

    void endQuad() {
        quadCount++;
    }

    CasterMesh finish(long sectionKey) {
        if (quadCount == 0) {
            return CasterMesh.empty(sectionKey);
        }
        if (size != quadCount * CasterMesh.QUAD_SIZE) {
            throw new IllegalStateException(size + " bytes written for " + quadCount + " quads");
        }
        byte[] vertices = arrays.take(size);
        System.arraycopy(data, 0, vertices, 0, size);
        return new CasterMesh(sectionKey, vertices, quadCount, hash ^ quadCount);
    }

    static int encodePosition(float blocks) {
        int value = Math.round((blocks + CasterMesh.POSITION_OFFSET) * CasterMesh.POSITION_SCALE);
        return Math.clamp(value, 0, 0xFFFF);
    }

    static int encodeUnit(float value) {
        return Math.clamp(Math.round(value * 65535.0f), 0, 0xFFFF);
    }

    private void putShort(int value) {
        byte low = (byte) value;
        byte high = (byte) (value >>> 8);
        data[size++] = low;
        data[size++] = high;
        hash = (hash ^ (low & 0xFF)) * FNV_PRIME;
        hash = (hash ^ (high & 0xFF)) * FNV_PRIME;
    }

    private void ensureCapacity(int extra) {
        if (size + extra > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
        }
    }
}
