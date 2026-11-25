package com.thenathe.litelights.clouds.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * A growable list of faces in {@link FaceFormat}, four ints each. A chunk's faces are kept with
 * chunk-local positions, so a chunk meshed once can be placed in any later generation.
 */
public final class FaceList {
    private int[] words;
    private int count;

    public FaceList() {
        this(1_024);
    }

    public FaceList(int capacity) {
        words = new int[Math.max(FaceFormat.WORDS, capacity * FaceFormat.WORDS)];
    }

    public void add(int position, int look, int shade, int shadeAndHeight) {
        if ((count + 1) * FaceFormat.WORDS > words.length) {
            words = Arrays.copyOf(words, words.length * 2);
        }
        int at = count * FaceFormat.WORDS;
        words[at] = position;
        words[at + 1] = look;
        words[at + 2] = shade;
        words[at + 3] = shadeAndHeight;
        count++;
    }

    public int size() {
        return count;
    }

    public int position(int face) {
        return words[face * FaceFormat.WORDS];
    }

    public int look(int face) {
        return words[face * FaceFormat.WORDS + 1];
    }

    /** Word 2: three corners' shading normals. */
    public int word2(int face) {
        return words[face * FaceFormat.WORDS + 2];
    }

    /** Word 3: the last corner's normal, the corners' depths and the face's height in cells. */
    public int word3(int face) {
        return words[face * FaceFormat.WORDS + 3];
    }

    public void clear() {
        count = 0;
    }

    /**
     * Writes every face into {@code out} at its position, in the GPU's byte order, moved by
     * ({@code dx}, {@code dz}) cubes and, unless {@code fade} is {@link FaceFormat#FADE_NONE},
     * marked as fading with its chunk.
     */
    public void writeTo(ByteBuffer out, int dx, int dz, int fade) {
        ByteBuffer ordered = out.order() == ByteOrder.nativeOrder() ? out : out.duplicate().order(ByteOrder.nativeOrder());
        IntBuffer target = ordered.asIntBuffer();
        for (int face = 0; face < count; face++) {
            int at = face * FaceFormat.WORDS;
            int look = words[at + 1];
            target.put(dx == 0 && dz == 0 ? words[at] : FaceFormat.moved(words[at], dx, dz));
            target.put(fade == FaceFormat.FADE_NONE ? look : FaceFormat.faded(look, fade));
            target.put(words[at + 2]);
            target.put(words[at + 3]);
        }
        out.position(out.position() + count * FaceFormat.BYTES);
    }
}
