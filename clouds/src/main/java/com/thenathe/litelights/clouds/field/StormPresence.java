package com.thenathe.litelights.clouds.field;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of a weather mod's storms to draw, and how much of each: a storm that comes as storms do
 * (born from nothing, or drifting in from beyond the reach) is drawn whole, since it grows by itself;
 * one that appears whole in view (placed by an operator's {@code /weather}) closes in at the pace
 * LiteLights' own rain deck does, and one that goes while it is still alive (cleared by
 * {@code /weather clear}) breaks up as the deck does, so no storm pops in or out. Storms over a view
 * that is new (joining, a jump, the first list) are drawn whole at once, as the sky is when a
 * player joins in the rain.
 *
 * <p>Fed the weather mod's storms near the camera every second or so ({@link #update}) and stepped
 * once a client tick ({@link #tick}). Not for sharing between threads: {@link #storms} hands out a
 * copy.
 */
public final class StormPresence {
    /** Per tick, how fast a storm that appeared whole closes in, and one cleared away breaks up: the rain deck's paces. */
    static final float COMES_IN = CloudWeather.RAIN_IN;
    static final float GOES_OUT = CloudWeather.RAIN_OUT;
    /** A storm first seen smaller than this share of its full size is one being born: it needs no fading in. */
    static final double NEWBORN_SIZE = 0.05;

    private final Map<Long, Shown> shown = new LinkedHashMap<>();
    private boolean fresh = true;
    private List<RegionalStorm> drawn = List.of();

    /** The next list is a new view: its storms are drawn whole at once. */
    public void startAfresh() {
        fresh = true;
    }

    /** Forgets every storm, for a new world or dimension. */
    public void clear() {
        shown.clear();
        drawn = List.of();
        fresh = true;
    }

    /**
     * Takes the storms near the camera now.
     *
     * @param storms  every storm whose cloud comes within the clouds' reach (and some way past it)
     * @param tick    the weather tick they were asked for
     * @param inView  how far from the camera a storm's cloud may show, in blocks: a storm newly
     *                listed beyond it drifted in from out of sight and is drawn whole
     */
    public void update(List<RegionalStorm> storms, double tick, double cameraX, double cameraZ, double inView) {
        Set<Long> listed = new HashSet<>();
        for (RegionalStorm storm : storms) {
            listed.add(storm.id());
            Shown entry = shown.get(storm.id());
            if (entry != null) {
                entry.storm = storm;
                entry.target = 1.0f;
                continue;
            }
            double distance = Math.hypot(storm.centreX(tick) - cameraX, storm.centreZ(tick) - cameraZ) - storm.extent() * storm.size(tick);
            boolean comesAsStormsDo = fresh || storm.size(tick) <= NEWBORN_SIZE || distance > inView;
            shown.put(storm.id(), new Shown(storm, comesAsStormsDo ? 1.0f : 0.0f));
        }
        for (Iterator<Shown> entries = shown.values().iterator(); entries.hasNext(); ) {
            Shown entry = entries.next();
            if (listed.contains(entry.storm.id())) {
                continue;
            }
            // Gone from the list while it lives: cleared, or drifted out of the list's reach (where breaking up
            // shows nothing). In a new view it isn't there to break up.
            if (fresh || !entry.storm.aliveAt(tick)) {
                entries.remove();
            } else {
                entry.target = 0.0f;
            }
        }
        fresh = false;
        publish();
    }

    /** One client tick: storms closing in or breaking up move on. */
    public void tick() {
        boolean changed = false;
        for (Iterator<Shown> entries = shown.values().iterator(); entries.hasNext(); ) {
            Shown entry = entries.next();
            float before = entry.presence;
            entry.presence = CloudWeather.step(entry.presence, entry.target, COMES_IN, GOES_OUT);
            if (entry.presence <= 0.0f && entry.target <= 0.0f) {
                entries.remove();
                changed = true;
            } else {
                changed |= entry.presence != before;
            }
        }
        if (changed) {
            publish();
        }
    }

    /** The storms to draw, each at its presence. */
    public List<RegionalStorm> storms() {
        return drawn;
    }

    private void publish() {
        List<RegionalStorm> storms = new ArrayList<>(shown.size());
        for (Shown entry : shown.values()) {
            if (entry.presence > 0.0f) {
                storms.add(entry.storm.withPresence(entry.presence));
            }
        }
        drawn = List.copyOf(storms);
    }

    private static final class Shown {
        RegionalStorm storm;
        float presence;
        float target = 1.0f;

        Shown(RegionalStorm storm, float presence) {
            this.storm = storm;
            this.presence = presence;
        }
    }
}
