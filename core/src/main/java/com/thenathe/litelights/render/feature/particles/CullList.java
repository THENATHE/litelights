package com.thenathe.litelights.render.feature.particles;

/**
 * The particles a frame may draw, picked by the worker after each step so the render thread walks
 * only these instead of every live particle (most of which are behind the camera, beyond their
 * range or lost in a storm's fog). Indices into the state, in order, so the quads come out in the
 * same order as from a walk over every particle.
 *
 * <p>The worker can't know the frames' cameras, so it picks for the camera and view of the last
 * frame before the tick, widened by margins: a distance the camera may move and an angle it may turn
 * before the list is replaced, both grown from how fast it moved and turned over the last tick. A
 * frame uses the list only if its own camera stays inside those margins ({@link #covers}); otherwise
 * it walks every particle, as before. So a list can make a frame cheaper but never leaves out a
 * particle that frame would draw.
 *
 * <p>Why that holds: the renderer's first test keeps a particle only if it lies within
 * {@code viewMargin * quickReach} blocks of the frame's view cone (the cone of its half-diagonal
 * angle, which holds the view pyramid; ParticleRenderer works both out from the view-projection). A frame camera at
 * most {@code moveMargin} from the list's, looking at most the cone's spare angle away, has its cone
 * inside the list's cone moved by that distance. So any particle the frame keeps is within
 * {@code viewMargin * quickReach + moveMargin} of the list's cone, which is the test here. The range and
 * the storm fog's limit are tested the same way, with the particle's motion over the tick added,
 * since frames test where it is between the last two ticks.
 */
final class CullList {
    /** What a list is picked for: a camera, its view, and the margins a frame may move within. */
    static final class Basis {
        /** False until a frame has been drawn: no view to pick for, so every particle is listed. */
        boolean known;
        double cameraX;
        double cameraY;
        double cameraZ;
        float forwardX;
        float forwardY;
        float forwardZ;
        /** Half-angle of the cone of directions kept, in radians: the view's half-diagonal plus the turn margin. */
        float coneAngle;
        /** How far a frame's camera may be from this one, in blocks. */
        float moveMargin;
        /** Blocks from the view cone a particle may be and still pass the renderer's view test, per block of its quick reach. */
        float viewMargin;
        /** Soft particles further than this from a frame's camera are hidden by the storm fog; infinite without fog. */
        float softLimit = Float.POSITIVE_INFINITY;
        /** The category range scales the list was picked with (the Weather and Particle distances). */
        final float[] rangeScales = ParticleBudget.unscaled();

        void set(Basis other) {
            known = other.known;
            cameraX = other.cameraX;
            cameraY = other.cameraY;
            cameraZ = other.cameraZ;
            forwardX = other.forwardX;
            forwardY = other.forwardY;
            forwardZ = other.forwardZ;
            coneAngle = other.coneAngle;
            moveMargin = other.moveMargin;
            viewMargin = other.viewMargin;
            softLimit = other.softLimit;
            System.arraycopy(other.rangeScales, 0, rangeScales, 0, rangeScales.length);
        }
    }

    final int[] indices;
    int count;
    /** Whether the list belongs to its state's particles as they are now. */
    boolean valid;
    final Basis basis = new Basis();

    CullList(int capacity) {
        indices = new int[capacity];
    }

    /** Picks the particles of {@code state} a frame within {@code wanted}'s margins may draw. Worker thread. */
    void pick(ParticleState state, Basis wanted) {
        basis.set(wanted);
        count = 0;
        valid = wanted.known;
        if (!valid) {
            return;
        }
        double cameraX = wanted.cameraX;
        double cameraY = wanted.cameraY;
        double cameraZ = wanted.cameraZ;
        float forwardX = wanted.forwardX;
        float forwardY = wanted.forwardY;
        float forwardZ = wanted.forwardZ;
        boolean everyDirection = wanted.coneAngle >= (float) Math.PI;
        float coneCos = (float) Math.cos(wanted.coneAngle);
        float coneSin = (float) Math.sin(wanted.coneAngle);
        float moveMargin = wanted.moveMargin;
        float viewMargin = wanted.viewMargin;
        float softLimit = wanted.softLimit;
        for (int i = 0; i < state.count; i++) {
            ParticleKind kind = ParticleKind.byId(state.kind[i]);
            float x = (float) (state.x[i] - cameraX);
            float y = (float) (state.y[i] - cameraY);
            float z = (float) (state.z[i] - cameraZ);
            // The tick's motion, measured generously (the sum of its parts is never less than its length).
            float motion = (float) (Math.abs(state.x[i] - state.previousX[i]) + Math.abs(state.y[i] - state.previousY[i])
                + Math.abs(state.z[i] - state.previousZ[i]));
            float slack = moveMargin + motion;
            float distanceSquared = x * x + y * y + z * z;
            float reach = ParticleReach.range(kind, wanted.rangeScales) + slack;
            // Haze's range is over the ground, as in the renderer.
            float rangeSquared = kind.haze != ParticleKind.Haze.NONE ? x * x + z * z : distanceSquared;
            if (rangeSquared > reach * reach) {
                continue;
            }
            if (kind.layer == ParticleKind.Layer.SOFT && softLimit != Float.POSITIVE_INFINITY) {
                float hidden = softLimit + slack;
                if (distanceSquared > hidden * hidden) {
                    continue;
                }
            }
            if (!everyDirection && !nearCone(x, y, z, distanceSquared, forwardX, forwardY, forwardZ, coneCos, coneSin,
                    viewMargin * ParticleRenderer.quickReach(kind) + moveMargin)) {
                continue;
            }
            indices[count++] = i;
        }
    }

    /**
     * Whether a frame with this camera, view direction (unit), view half-diagonal angle, view margin,
     * storm fog limit and range scales may use the list: its camera and view lie within the list's
     * margins.
     */
    boolean covers(double cameraX, double cameraY, double cameraZ, float forwardX, float forwardY, float forwardZ, float viewAngle,
                   float viewMargin, float softHidden, float[] rangeScales) {
        if (!valid || viewMargin > basis.viewMargin) {
            return false;
        }
        double movedX = cameraX - basis.cameraX;
        double movedY = cameraY - basis.cameraY;
        double movedZ = cameraZ - basis.cameraZ;
        if (movedX * movedX + movedY * movedY + movedZ * movedZ > (double) basis.moveMargin * basis.moveMargin) {
            return false;
        }
        if (softHidden > basis.softLimit) {
            return false;
        }
        for (int category = 0; category < rangeScales.length; category++) {
            if (rangeScales[category] != basis.rangeScales[category]) {
                return false;
            }
        }
        if (basis.coneAngle >= (float) Math.PI) {
            return true;
        }
        float cos = Math.clamp(forwardX * basis.forwardX + forwardY * basis.forwardY + forwardZ * basis.forwardZ, -1.0f, 1.0f);
        return (float) Math.acos(cos) + viewAngle <= basis.coneAngle;
    }

    /**
     * Whether a point (relative to the cone's apex) lies within {@code margin} of the cone around
     * the unit axis with this half-angle (given by its cosine and sine). In the plane through the
     * axis and the point, the point's distance to the cone's side is
     * {@code radial * cos - along * sin} when its foot lies on the side, and its distance to the apex
     * when the foot would lie behind it.
     */
    static boolean nearCone(float x, float y, float z, float distanceSquared, float axisX, float axisY, float axisZ, float cos, float sin,
                            float margin) {
        float along = x * axisX + y * axisY + z * axisZ;
        float radial = (float) Math.sqrt(Math.max(0.0f, distanceSquared - along * along));
        float outside = radial * cos - along * sin;
        if (outside <= 0.0f) {
            return true;
        }
        if (along * cos + radial * sin >= 0.0f) {
            return outside <= margin;
        }
        return distanceSquared <= margin * margin;
    }
}
