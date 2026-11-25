package com.thenathe.litelights.render.feature.water.wake;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Iterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Finds what disturbs the water near the camera each client tick: boats on it, players and mobs
 * swimming at its surface, and anything falling into it. Speeds come from each entity's change of
 * position since the last tick, which works the same for the local player and for other entities
 * whose positions arrive from the server.
 */
public final class WakeSources {
    /** Entities further than this from the window's centre (horizontally) can't reach the window. */
    private static final double RANGE = WakeField.BLOCKS / 2.0 + 4.0;
    /** Faster than this, in blocks per tick, is a teleport, not a wake. */
    private static final double TELEPORT = 2.5;
    /** A boat throws spray from its bow faster than this, in blocks per tick. */
    private static final double SPRAY_SPEED = 0.2;
    private static final double TAU = Math.PI * 2.0;

    /** The water surface's height in a block column, or NaN where there is no open water. */
    @FunctionalInterface
    public interface WaterSurface {
        double surfaceY(int x, int z);
    }

    /** Spray thrown up at a fast boat's bow, for the particles. */
    @FunctionalInterface
    public interface BowSpray {
        void spray(double x, double surfaceY, double z, double headingX, double headingZ, double speed);
    }

    private static final class Tracked {
        double lastX;
        double lastY;
        double lastZ;
        boolean wasInWater;
        long seenTick;
        final float[] paddlePhase = new float[2];
    }

    private final Int2ObjectOpenHashMap<Tracked> tracked = new Int2ObjectOpenHashMap<>();
    private long tick;
    private int boats;
    private int swimmers;
    private int entries;

    /**
     * Adds this tick's sources to {@code out}, for the window centred on {@code camera} (the window's
     * centre, a little ahead of the real camera). {@code strength} is the Wake strength setting;
     * {@code spray} is null when no spray is wanted (particles off).
     */
    public void collect(ClientLevel level, Vec3 camera, double strength, WaterSurface water, WakeSourceList out, BowSpray spray) {
        tick++;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator() || entity.isPassenger() || out.isFull()) {
                continue;
            }
            double dx = entity.getX() - camera.x;
            double dz = entity.getZ() - camera.z;
            if (dx * dx + dz * dz > RANGE * RANGE) {
                continue;
            }
            Tracked state = tracked.get(entity.getId());
            if (state == null) {
                state = new Tracked();
                tracked.put(entity.getId(), state);
                state.wasInWater = entity.isInWater();
            } else if (state.seenTick == tick - 1) {
                read(entity, state, strength, water, out, spray);
            }
            state.lastX = entity.getX();
            state.lastY = entity.getY();
            state.lastZ = entity.getZ();
            state.wasInWater = entity.isInWater();
            state.seenTick = tick;
        }
        if (tick % 40 == 0) {
            forgetUnseen();
        }
    }

    public void clear() {
        tracked.clear();
    }

    /** Boats, swimmers and entries that made sources since start-up, for the QA harness. */
    public int boatTicks() {
        return boats;
    }

    public int swimmerTicks() {
        return swimmers;
    }

    public int entries() {
        return entries;
    }

    private void read(Entity entity, Tracked state, double strength, WaterSurface water, WakeSourceList out, BowSpray spray) {
        double x = entity.getX();
        double z = entity.getZ();
        double moveX = x - state.lastX;
        double moveZ = z - state.lastZ;
        double speed = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (speed > TELEPORT || Math.abs(entity.getY() - state.lastY) > TELEPORT) {
            return;
        }
        double surface = water.surfaceY((int) Math.floor(x), (int) Math.floor(z));
        if (Double.isNaN(surface)) {
            return;
        }
        if (entity instanceof AbstractBoat boat) {
            // Afloat: a boat's bottom sits a little under the surface.
            if (Math.abs(boat.getY() - surface) > 0.7) {
                return;
            }
            double yaw = Math.toRadians(boat.getYRot());
            double headingX = speed > 0.03 ? moveX / speed : -Math.sin(yaw);
            double headingZ = speed > 0.03 ? moveZ / speed : Math.cos(yaw);
            int before = out.size();
            WakeShapes.boat(out, state.lastX, state.lastZ, x, z, headingX, headingZ, speed, strength);
            paddles(boat, state, strength, out);
            if (out.size() > before) {
                boats++;
            }
            if (spray != null && speed > SPRAY_SPEED) {
                spray.spray(x + headingX * 1.1, surface, z + headingZ * 1.1, headingX, headingZ, speed);
            }
            return;
        }
        boolean inWater = entity.isInWater();
        double verticalSpeed = entity.getY() - state.lastY;
        if (inWater && !state.wasInWater && verticalSpeed < 0.0) {
            // Where its feet crossed the surface between the last tick and this one.
            double through = state.lastY == entity.getY() ? 1.0
                : Math.clamp((state.lastY - surface) / (state.lastY - entity.getY()), 0.0, 1.0);
            int before = out.size();
            WakeShapes.entry(out, state.lastX + moveX * through, state.lastZ + moveZ * through, entity.getBbWidth(), verticalSpeed, strength);
            if (out.size() > before) {
                entries++;
            }
            return;
        }
        // Swimming at the surface: its feet under the water and its top not far below it.
        boolean atSurface = entity.getY() < surface + 0.05 && entity.getY() + entity.getBbHeight() > surface - 0.3;
        if (inWater && atSurface && speed > WakeShapes.MINIMUM_SPEED) {
            int before = out.size();
            WakeShapes.swimmer(out, state.lastX, state.lastZ, x, z, moveX / speed, moveZ / speed, speed, entity.getBbWidth(), strength);
            if (out.size() > before) {
                swimmers++;
            }
        }
    }

    /** A paddle dipping into the water sends out a small ring, once a stroke. */
    private static void paddles(AbstractBoat boat, Tracked state, double strength, WakeSourceList out) {
        double yaw = Math.toRadians(boat.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        for (int side = 0; side < 2; side++) {
            float phase = (float) floorModTau(boat.getRowingTime(side, 1.0f));
            float before = state.paddlePhase[side];
            state.paddlePhase[side] = phase;
            boolean dipped = boat.getPaddleState(side) && before < Math.PI && phase >= Math.PI;
            if (dipped) {
                // The boat's left is (forwardZ, -forwardX); the paddles reach a block out, a little aft.
                double toLeft = side == AbstractBoat.PADDLE_LEFT ? 0.95 : -0.95;
                double paddleX = boat.getX() + forwardZ * toLeft - forwardX * 0.2;
                double paddleZ = boat.getZ() - forwardX * toLeft - forwardZ * 0.2;
                WakeShapes.paddle(out, paddleX, paddleZ, strength);
            }
        }
    }

    private static double floorModTau(double angle) {
        double wrapped = angle % TAU;
        return wrapped < 0.0 ? wrapped + TAU : wrapped;
    }

    private void forgetUnseen() {
        Iterator<Int2ObjectMap.Entry<Tracked>> entries = tracked.int2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().seenTick != tick) {
                entries.remove();
            }
        }
    }
}
