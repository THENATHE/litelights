package com.thenathe.litelights.render.feature.water.splash;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Iterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/**
 * Watches the player and mobs near the camera for the moment they fall or jump into water, and
 * throws a splash scaled by how fast they hit it. Speeds come from each entity's position change
 * per tick, which works the same for the local player and for other entities whose positions are
 * interpolated from server updates.
 */
public final class SplashTracker {
    private static final double RANGE = 48.0;
    /** Wakes draws its own splashes for boats and swimming mobs; only the player keeps ours. */
    private final boolean wakesLoaded;

    private static final class Tracked {
        double lastX;
        double lastY;
        double lastZ;
        boolean wasInWater;
        long seenTick;
    }

    private final Int2ObjectOpenHashMap<Tracked> tracked = new Int2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private long tick;
    private int splashCount;

    public SplashTracker(boolean wakesLoaded) {
        this.wakesLoaded = wakesLoaded;
    }

    /** Once per client tick. {@code advanced} picks the Advanced splash over the Basic one. */
    public void tick(ClientLevel level, Vec3 camera, float particleDensity, boolean advanced, SplashEffects effects) {
        tick++;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator() || entity.isPassenger() || entity.distanceToSqr(camera) > RANGE * RANGE) {
                continue;
            }
            Tracked state = tracked.get(entity.getId());
            boolean inWater = entity.isInWater();
            if (state == null) {
                state = new Tracked();
                tracked.put(entity.getId(), state);
            } else if (state.seenTick == tick - 1) {
                double verticalSpeed = entity.getY() - state.lastY;
                if (SplashPlan.entered(state.wasInWater, inWater, verticalSpeed) && splashes(entity)) {
                    double surface = surfaceAbove(level, entity);
                    if (!Double.isNaN(surface)) {
                        // Where the entity's feet crossed the surface between the last tick and this one.
                        double through = Math.clamp((state.lastY - surface) / (state.lastY - entity.getY()), 0.0, 1.0);
                        double x = state.lastX + (entity.getX() - state.lastX) * through;
                        double z = state.lastZ + (entity.getZ() - state.lastZ) * through;
                        if (advanced) {
                            AdvancedSplashPlan plan = AdvancedSplashPlan.of(verticalSpeed, entity.getBbWidth(), particleDensity);
                            if (plan != null) {
                                effects.advancedSplash(level, x, surface, z, plan);
                                splashCount++;
                            }
                        } else {
                            SplashPlan plan = SplashPlan.of(verticalSpeed, entity.getBbWidth(), particleDensity);
                            if (plan != null) {
                                effects.splash(level, x, surface, z, plan);
                                splashCount++;
                            }
                        }
                    }
                }
            }
            state.lastX = entity.getX();
            state.lastY = entity.getY();
            state.lastZ = entity.getZ();
            state.wasInWater = inWater;
            state.seenTick = tick;
        }
        if (tick % 40 == 0) {
            forgetUnseen();
        }
    }

    public void clear() {
        tracked.clear();
    }

    /** Splashes thrown since start-up, for the QA harness. */
    public int splashCount() {
        return splashCount;
    }

    private boolean splashes(Entity entity) {
        if (entity instanceof AbstractBoat) {
            return false;
        }
        return !wakesLoaded || entity instanceof Player;
    }

    /** The top of the water the entity just went into, or NaN if there is none at its feet. */
    private double surfaceAbove(ClientLevel level, Entity entity) {
        cursor.set(entity.getX(), entity.getY() + 0.2, entity.getZ());
        if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
            cursor.setY(cursor.getY() - 1);
            if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
                return Double.NaN;
            }
        }
        for (int i = 0; i < 4 && level.getFluidState(cursor.above()).is(FluidTags.WATER); i++) {
            cursor.setY(cursor.getY() + 1);
        }
        return cursor.getY() + level.getFluidState(cursor).getHeight(level, cursor);
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
