package com.thenathe.litelights.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-dimension switches for dimensions from other mods. LiteLights can't know them in advance,
 * so a dimension's switches are created the first time the player enters it, and remembered in
 * litelights.json so they are there from then on. Every switch starts on.
 */
public final class DimensionOptions {
    /** What can be switched off per dimension. */
    public enum Aspect { SKY, SHADOWS, REFLECTIONS, LIGHT, BLOOM, OUTLINES, EFFECTS, WEATHER }

    private static final String MODULE = "dimensions";
    private static final Map<String, Map<Aspect, BoolOption>> KNOWN = new LinkedHashMap<>();

    private DimensionOptions() {
    }

    static synchronized void init() {
        JsonElement known = LiteLightsConfig.module(MODULE).get("known");
        if (known != null && known.isJsonArray()) {
            for (JsonElement id : known.getAsJsonArray()) {
                if (id.isJsonPrimitive()) {
                    register(id.getAsString());
                }
            }
        }
    }

    /**
     * Called when the player enters a dimension. Vanilla dimensions are ignored; a new modded one
     * gets its switches and is remembered (saved with the next save).
     */
    public static synchronized void notice(String dimensionId) {
        if (dimensionId.startsWith("minecraft:") || KNOWN.containsKey(dimensionId)) {
            return;
        }
        register(dimensionId);
        JsonObject module = LiteLightsConfig.module(MODULE);
        JsonArray known = module.has("known") && module.get("known").isJsonArray() ? module.getAsJsonArray("known") : new JsonArray();
        known.add(dimensionId);
        module.add("known", known);
        LiteLightsConfig.save();
    }

    /** Whether an aspect is on in a dimension. Vanilla dimensions and unseen ones are always on. */
    public static synchronized boolean isOn(String dimensionId, Aspect aspect) {
        Map<Aspect, BoolOption> switches = KNOWN.get(dimensionId);
        return switches == null || switches.get(aspect).on();
    }

    public static synchronized Map<String, Map<Aspect, BoolOption>> known() {
        Map<String, Map<Aspect, BoolOption>> copy = new LinkedHashMap<>();
        KNOWN.forEach((id, switches) -> copy.put(id, Map.copyOf(switches)));
        return copy;
    }

    private static void register(String dimensionId) {
        if (KNOWN.containsKey(dimensionId)) {
            return;
        }
        String section = "dimension/" + dimensionId;
        Pages.nameSection(Pages.DIMENSIONS, section, dimensionId);
        Map<Aspect, BoolOption> switches = new EnumMap<>(Aspect.class);
        for (Aspect aspect : Aspect.values()) {
            String name = EnumOption.storedName(aspect);
            String labelKey = "litelights.option.dimensions.modded." + name;
            switches.put(aspect, BoolOption.builder("dimensions." + dimensionId.replace(':', '.') + "." + name, true)
                    .at(Pages.DIMENSIONS, section).text(labelKey, labelKey + ".description")
                    .feature("modded_dimensions").register());
        }
        KNOWN.put(dimensionId, switches);
    }
}
