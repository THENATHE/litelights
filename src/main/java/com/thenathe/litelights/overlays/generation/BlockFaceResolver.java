package com.thenathe.litelights.overlays.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.overlays.pack.PackStack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * Finds which textures a block shows on top and on its sides by reading its blockstate and model
 * files from the current packs, the same files the game bakes. Reading them directly (rather than
 * asking the baked models) lets this run on a worker thread while the game is still reloading.
 *
 * <p>Only models made of whole-block cuboids count. Anything else, such as a model with a custom
 * loader or a partial element, isn't a full cube and is skipped with a reason.
 */
public final class BlockFaceResolver {
    private static final int MAX_PARENT_DEPTH = 32;

    private final PackStack packs;
    private final Map<Identifier, JsonObject> models = new ConcurrentHashMap<>();

    public BlockFaceResolver(PackStack packs) {
        this.packs = packs;
    }

    /** Thrown when a block's model can't give overlay layers; the message says why. */
    public static final class UnusableModelException extends Exception {
        public UnusableModelException(String reason) {
            super(reason);
        }
    }

    /**
     * @param blockId       the block, such as {@code minecraft:grass_block}
     * @param defaultValues property values of the block's default state, used to pick the variant
     */
    public FaceLayers resolve(String blockId, Map<String, String> defaultValues) throws UnusableModelException {
        Identifier block = Identifier.parse(blockId);
        JsonObject blockstate = readJson(block.withPath(path -> "blockstates/" + path + ".json"));
        if (blockstate == null) {
            throw new UnusableModelException("no blockstate file");
        }
        JsonObject variant = pickVariant(blockstate, defaultValues);
        if (variant == null || !variant.has("model")) {
            throw new UnusableModelException("no variant for the default state");
        }
        Identifier modelId = Identifier.parse(variant.get("model").getAsString());
        int rotationX = variant.has("x") ? Math.floorMod(variant.get("x").getAsInt(), 360) : 0;

        Map<String, String> textures = new HashMap<>();
        JsonArray elements = collectModel(modelId, textures);
        if (elements == null) {
            throw new UnusableModelException("model " + modelId + " has no elements");
        }
        String topFace = switch (rotationX) {
            case 90 -> "south";
            case 180 -> "down";
            case 270 -> "north";
            default -> "up";
        };
        String sideFace = switch (rotationX) {
            case 90 -> "up";
            case 180 -> "south";
            case 270 -> "down";
            default -> "north";
        };
        List<FaceLayers.Layer> top = new ArrayList<>();
        List<FaceLayers.Layer> side = new ArrayList<>();
        for (JsonElement element : elements) {
            JsonObject cuboid = element.getAsJsonObject();
            if (!isWholeBlock(cuboid)) {
                throw new UnusableModelException("model " + modelId + " isn't a full cube");
            }
            JsonObject faces = cuboid.getAsJsonObject("faces");
            addLayer(faces, topFace, textures, top);
            addLayer(faces, sideFace, textures, side);
        }
        if (top.isEmpty() && side.isEmpty()) {
            throw new UnusableModelException("model " + modelId + " draws no top or side");
        }
        return new FaceLayers(List.copyOf(top), List.copyOf(side));
    }

    private static JsonObject pickVariant(JsonObject blockstate, Map<String, String> defaultValues) {
        if (!blockstate.has("variants")) {
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : blockstate.getAsJsonObject("variants").entrySet()) {
            if (matchesDefault(entry.getKey(), defaultValues)) {
                JsonElement value = entry.getValue();
                // A list means random variants; they share textures and differ only in rotation.
                return value.isJsonArray() ? value.getAsJsonArray().get(0).getAsJsonObject() : value.getAsJsonObject();
            }
        }
        return null;
    }

    private static boolean matchesDefault(String variantKey, Map<String, String> defaultValues) {
        if (variantKey.isEmpty() || variantKey.equals("normal")) {
            return true;
        }
        for (String condition : variantKey.split(",")) {
            String[] parts = condition.split("=", 2);
            if (parts.length != 2 || !parts[1].equals(defaultValues.get(parts[0]))) {
                return false;
            }
        }
        return true;
    }

    /** Walks the parent chain, merging texture variables, and returns the nearest elements list. */
    private JsonArray collectModel(Identifier modelId, Map<String, String> textures) throws UnusableModelException {
        JsonArray elements = null;
        Identifier current = modelId;
        for (int depth = 0; current != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (current.getPath().startsWith("builtin/")) {
                throw new UnusableModelException("model " + modelId + " is built in");
            }
            JsonObject model = model(current);
            if (model == null) {
                throw new UnusableModelException("missing model " + current);
            }
            if (model.has("loader")) {
                throw new UnusableModelException("model " + current + " uses a custom loader");
            }
            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> texture : model.getAsJsonObject("textures").entrySet()) {
                    if (texture.getValue().isJsonPrimitive()) {
                        textures.putIfAbsent(texture.getKey(), texture.getValue().getAsString());
                    }
                }
            }
            if (elements == null && model.has("elements")) {
                elements = model.getAsJsonArray("elements");
            }
            current = model.has("parent") ? Identifier.parse(model.get("parent").getAsString()) : null;
        }
        return elements;
    }

    private JsonObject model(Identifier modelId) throws UnusableModelException {
        JsonObject cached = models.get(modelId);
        if (cached != null) {
            return cached;
        }
        JsonObject read = readJson(modelId.withPath(path -> "models/" + path + ".json"));
        if (read != null) {
            models.put(modelId, read);
        }
        return read;
    }

    private static boolean isWholeBlock(JsonObject cuboid) {
        JsonArray from = cuboid.getAsJsonArray("from");
        JsonArray to = cuboid.getAsJsonArray("to");
        if (from == null || to == null || cuboid.has("rotation")) {
            return false;
        }
        for (int axis = 0; axis < 3; axis++) {
            if (from.get(axis).getAsFloat() != 0 || to.get(axis).getAsFloat() != 16) {
                return false;
            }
        }
        return true;
    }

    private static void addLayer(JsonObject faces, String faceName, Map<String, String> textures, List<FaceLayers.Layer> into)
            throws UnusableModelException {
        if (faces == null || !faces.has(faceName)) {
            return;
        }
        JsonObject face = faces.getAsJsonObject(faceName);
        String texture = resolveTexture(face.get("texture").getAsString(), textures);
        int tintIndex = face.has("tintindex") ? face.get("tintindex").getAsInt() : -1;
        into.add(new FaceLayers.Layer(texture, tintIndex));
    }

    private static String resolveTexture(String reference, Map<String, String> textures) throws UnusableModelException {
        String value = reference;
        for (int depth = 0; value.startsWith("#") && depth < MAX_PARENT_DEPTH; depth++) {
            value = textures.get(value.substring(1));
            if (value == null) {
                throw new UnusableModelException("unresolved texture " + reference);
            }
        }
        if (value.startsWith("#")) {
            throw new UnusableModelException("texture variables loop at " + reference);
        }
        return Identifier.parse(value).toString();
    }

    private JsonObject readJson(Identifier location) throws UnusableModelException {
        try {
            byte[] bytes = packs.read(location);
            if (bytes == null) {
                return null;
            }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException failure) {
            throw new UnusableModelException("couldn't read " + location + ": " + failure.getMessage());
        }
    }
}
