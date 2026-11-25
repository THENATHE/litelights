package com.thenathe.litelights.render.feature.lightpost;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The maths of {@code shaders/post/taa.fsh} in Java, so the tests can check reprojection, depth
 * comparison and the colour clip against known answers. Keep the two in step.
 */
final class TaaMirror {
    static final float SKY = -1.0f;

    private TaaMirror() {
    }

    /** {@code lite_position_from_depth} (post_common.glsl). */
    static Vector3f positionFromDepth(Matrix4fc inverseViewProjection, float u, float v, float depth, boolean zeroToOne) {
        float ndcZ = zeroToOne ? depth : depth * 2.0f - 1.0f;
        Vector4f position = inverseViewProjection.transform(new Vector4f(u * 2.0f - 1.0f, v * 2.0f - 1.0f, ndcZ, 1.0f));
        return new Vector3f(position.x, position.y, position.z).div(position.w);
    }

    /** {@code viewDepth}: view depth in blocks from a reversed device depth, from the inverse matrix's w row. */
    static float viewDepth(Matrix4fc inverseViewProjection, float depth, boolean zeroToOne) {
        if (depth <= 0.0f) {
            return SKY;
        }
        float ndcZ = zeroToOne ? depth : depth * 2.0f - 1.0f;
        return 1.0f / Math.max(inverseViewProjection.m23() * ndcZ + inverseViewProjection.m33(), 1.0e-9f);
    }

    /** {@code previousOf}: last frame's screen position (x, y) and view depth (z, or SKY) of a pixel's surface. */
    static Vector3f previousOf(Matrix4fc previousViewProjection, Matrix4fc inverseViewProjection, float u, float v, float depth,
                               boolean zeroToOne) {
        Vector4f clip;
        if (depth <= 0.0f) {
            Vector3f near = positionFromDepth(inverseViewProjection, u, v, 1.0f, zeroToOne);
            Vector3f far = positionFromDepth(inverseViewProjection, u, v, 0.001f, zeroToOne);
            Vector3f direction = far.sub(near).normalize();
            clip = previousViewProjection.transform(new Vector4f(direction, 0.0f));
        } else {
            clip = previousViewProjection.transform(new Vector4f(positionFromDepth(inverseViewProjection, u, v, depth, zeroToOne), 1.0f));
        }
        if (clip.w <= 1.0e-6f) {
            return new Vector3f(-1.0f, -1.0f, SKY);
        }
        return new Vector3f(clip.x / clip.w * 0.5f + 0.5f, clip.y / clip.w * 0.5f + 0.5f, depth <= 0.0f ? SKY : clip.w);
    }

    /**
     * {@code sameSurface}: how sure (0..1) that a surface expected at this view depth was among last
     * frame's view depths around its old spot (the 3x3 there; SKY for sky).
     */
    static float sameSurface(float[] previousDepths, float expected, float tolerance) {
        float nearest = 1.0e9f;
        float farthest = 0.0f;
        boolean sky = false;
        for (float depth : previousDepths) {
            if (depth <= 0.0f) {
                sky = true;
            } else {
                nearest = Math.min(nearest, depth);
                farthest = Math.max(farthest, depth);
            }
        }
        if (expected <= 0.0f) {
            return sky ? 1.0f : 0.0f;
        }
        if (sky) {
            farthest = 1.0e9f;
        }
        float allowed = tolerance * expected + 0.02f;
        float outside = Math.max(nearest - expected, expected - farthest);
        return 1.0f - smoothstep(allowed, allowed * 2.0f, outside);
    }

    /** {@code clipToBox}: moves a colour toward the box's centre until it is inside. */
    static Vector3f clipToBox(Vector3f colour, Vector3f low, Vector3f high) {
        Vector3f centre = new Vector3f(low).add(high).mul(0.5f);
        Vector3f halfSize = new Vector3f(high).sub(low).mul(0.5f).add(1.0e-4f, 1.0e-4f, 1.0e-4f);
        Vector3f offset = new Vector3f(colour).sub(centre);
        float largest = Math.max(Math.abs(offset.x / halfSize.x), Math.max(Math.abs(offset.y / halfSize.y), Math.abs(offset.z / halfSize.z)));
        return largest > 1.0f ? centre.add(offset.div(largest)) : new Vector3f(colour);
    }

    /**
     * The clip box of a 3x3 neighbourhood, in YCoCg: its range narrowed to the mean plus or minus
     * {@code spread} standard deviations. Returns {low, high}.
     */
    static Vector3f[] box(Vector3f[] neighbourhoodRgb, float spread) {
        Vector3f sum = new Vector3f();
        Vector3f squares = new Vector3f();
        Vector3f low = new Vector3f(1.0e9f);
        Vector3f high = new Vector3f(-1.0e9f);
        for (Vector3f rgb : neighbourhoodRgb) {
            Vector3f ycocg = toYCoCg(rgb);
            sum.add(ycocg);
            squares.add(new Vector3f(ycocg).mul(ycocg));
            low.min(ycocg);
            high.max(ycocg);
        }
        float count = neighbourhoodRgb.length;
        Vector3f mean = new Vector3f(sum).div(count);
        Vector3f deviation = new Vector3f(squares).div(count).sub(new Vector3f(mean).mul(mean));
        deviation.set((float) Math.sqrt(Math.max(deviation.x, 0.0f)), (float) Math.sqrt(Math.max(deviation.y, 0.0f)),
            (float) Math.sqrt(Math.max(deviation.z, 0.0f)));
        Vector3f boxLow = new Vector3f(mean).sub(new Vector3f(deviation).mul(spread)).max(low);
        Vector3f boxHigh = new Vector3f(mean).add(new Vector3f(deviation).mul(spread)).min(high);
        return new Vector3f[] {boxLow, boxHigh};
    }

    /**
     * The new frame's weight once the camera moves ({@code newWeight} in main): the still weight up
     * to {@code movingWeight}, reached at {@code movingPixels} pixels a frame, never below the still one.
     */
    static float newFrameWeight(float stillWeight, float movingWeight, float movingPixels, float movement) {
        return Math.max(stillWeight, stillWeight + (movingWeight - stillWeight) * smoothstep(1.0f, movingPixels, movement));
    }

    /** {@code catmullRom} in one dimension: a row of pixel values read at a position in pixels (centres at +0.5). */
    static float catmullRom(float[] row, float position) {
        float centre = (float) Math.floor(position - 0.5f) + 0.5f;
        float f = position - centre;
        float w0 = f * (-0.5f + f * (1.0f - 0.5f * f));
        float w1 = 1.0f + f * f * (-2.5f + 1.5f * f);
        float w2 = f * (0.5f + f * (2.0f - 1.5f * f));
        float w3 = f * f * (-0.5f + 0.5f * f);
        int first = (int) Math.floor(centre) - 1;
        return w0 * at(row, first) + w1 * at(row, first + 1) + w2 * at(row, first + 2) + w3 * at(row, first + 3);
    }

    /** A bilinear (here linear) read of a row at a position in pixels. */
    static float bilinear(float[] row, float position) {
        float x = position - 0.5f;
        int left = (int) Math.floor(x);
        float f = x - left;
        return at(row, left) * (1.0f - f) + at(row, left + 1) * f;
    }

    /** The moving history's sharpen: by what a bilinear read at the same spot loses. */
    static float sharpenedHistory(float catmullRom, float bilinear, float strength, float movement) {
        return Math.max(catmullRom + (catmullRom - bilinear) * strength * smoothstep(0.05f, 0.5f, movement), 0.0f);
    }

    private static float at(float[] row, int index) {
        return row[Math.clamp(index, 0, row.length - 1)];
    }

    static Vector3f toYCoCg(Vector3f c) {
        return new Vector3f(0.25f * c.x + 0.5f * c.y + 0.25f * c.z, 0.5f * c.x - 0.5f * c.z, -0.25f * c.x + 0.5f * c.y - 0.25f * c.z);
    }

    static Vector3f fromYCoCg(Vector3f c) {
        return new Vector3f(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
