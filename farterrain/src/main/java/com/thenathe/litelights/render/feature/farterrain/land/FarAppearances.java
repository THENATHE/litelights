package com.thenathe.litelights.render.feature.farterrain.land;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * The sprites far terrain's faces show, each given a small id once (1 and up; 0 is none). A quad
 * carries the id; the GPU's appearance table turns it into the sprite's place in the game's block
 * atlas, and is made again when the atlas is stitched again, so the ids never change while a world
 * is open. Thread-safe.
 */
public final class FarAppearances {
    /** The sprite's texels with little alpha show its mean colour instead (leaves, glass). */
    public static final int FLAG_CUTOUT = 1;
    /** Water: its faces aren't shaded by direction (far_land.fsh). */
    public static final int FLAG_WATER = 2;

    public record Appearance(Identifier sprite, int flags) {
    }

    private final Map<Appearance, Integer> ids = new ConcurrentHashMap<>();
    private final List<Appearance> byId = new ArrayList<>();
    private volatile int revision;

    public FarAppearances() {
        byId.add(null);
    }

    public int idOf(Identifier sprite, int flags) {
        Appearance appearance = new Appearance(sprite, flags);
        Integer id = ids.get(appearance);
        if (id != null) {
            return id;
        }
        synchronized (byId) {
            id = ids.get(appearance);
            if (id == null) {
                if (byId.size() > 0xFFFF) {
                    return 1;
                }
                id = byId.size();
                byId.add(appearance);
                ids.put(appearance, id);
                revision++;
            }
            return id;
        }
    }

    /** Grows with every new appearance, so the table knows to upload again. */
    public int revision() {
        return revision;
    }

    /** Every appearance by id (index 0 is null). */
    public List<Appearance> snapshot() {
        synchronized (byId) {
            return new ArrayList<>(byId);
        }
    }
}
