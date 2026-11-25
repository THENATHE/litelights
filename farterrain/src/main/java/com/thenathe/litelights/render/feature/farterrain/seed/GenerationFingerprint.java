package com.thenathe.litelights.render.feature.farterrain.seed;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

/**
 * What a world's generation is made of, in a form two sides can compare entry by entry: each
 * world-generation registry hashed per namespace (its entries encoded through their own codecs,
 * key order fixed, tags included), the data packs in force, the version of every mod whose
 * namespace takes part, and the hash of those mods' configuration files. The client works one out
 * for what it would generate with; the server's helper sends one for what the server generates
 * with; where they differ the notice can say which mod or pack it is.
 *
 * <p>Anything that fails to encode makes its entry {@link #UNKNOWN}, never a match: a mod's
 * mixins into the generator or settings read at run time are not in registries at all, which is
 * why the mods' versions and configurations are listed too. The seed is not part of it.
 */
public final class GenerationFingerprint {
    /** Raised whenever what goes into an entry changes, so old and new fingerprints never pass for equal. */
    public static final int FORMAT = 1;
    public static final String UNKNOWN = "unknown";
    private static final String REGISTRY = "registry:";
    private static final String PACK = "pack:";
    private static final String MOD = "mod:";
    private static final String CONFIG = "config:";

    private final SortedMap<String, String> entries;

    private GenerationFingerprint(SortedMap<String, String> entries) {
        this.entries = entries;
    }

    /** A fingerprint read back from its entries (the helper's reply, a stored identity). */
    public static GenerationFingerprint of(Map<String, String> entries) {
        return new GenerationFingerprint(new TreeMap<>(entries));
    }

    /**
     * Works out the fingerprint of world generation with these registries and packs. Slow for a
     * large modpack (every biome and feature is encoded): never on the client or server thread.
     */
    public static GenerationFingerprint of(HolderLookup.Provider registries, Collection<String> packs) {
        SortedMap<String, String> entries = new TreeMap<>();
        entries.put("format", Integer.toString(FORMAT));
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        TreeSet<String> namespaces = new TreeSet<>();
        for (RegistryDataLoader.RegistryData<?> data : worldgenRegistries()) {
            hashRegistry(entries, namespaces, registries, ops, data);
        }
        FabricLoader loader = FabricLoader.getInstance();
        for (String pack : packs) {
            // A mod's own data pack is the mod: it is listed by its namespace and version below,
            // and client-only mods with a data folder would otherwise always differ.
            if (!loader.isModLoaded(pack) && !pack.startsWith("fabric")) {
                entries.put(PACK + pack, "on");
            }
        }
        namespaces.remove("minecraft");
        for (String namespace : namespaces) {
            Optional<ModContainer> mod = loader.getModContainer(namespace);
            if (mod.isPresent()) {
                entries.put(MOD + namespace, mod.get().getMetadata().getVersion().getFriendlyString());
                entries.put(CONFIG + namespace, configHash(loader.getConfigDir(), namespace));
            }
        }
        return new GenerationFingerprint(entries);
    }

    /** The registries that shape the land: everything under {@code worldgen/}, and dimension types. */
    private static List<RegistryDataLoader.RegistryData<?>> worldgenRegistries() {
        List<RegistryDataLoader.RegistryData<?>> chosen = new ArrayList<>();
        for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLD_REGISTRIES) {
            String path = data.key().identifier().getPath();
            if (path.startsWith("worldgen/") || path.equals("dimension_type")) {
                chosen.add(data);
            }
        }
        chosen.sort(Comparator.comparing(data -> data.key().identifier().toString()));
        return chosen;
    }

    private static <T> void hashRegistry(SortedMap<String, String> entries, TreeSet<String> namespaces, HolderLookup.Provider registries,
                                         DynamicOps<JsonElement> ops, RegistryDataLoader.RegistryData<T> data) {
        String name = data.key().identifier().toString();
        Optional<? extends HolderLookup.RegistryLookup<T>> lookup = registries.lookup(data.key());
        if (lookup.isEmpty()) {
            entries.put(REGISTRY + name, "absent");
            return;
        }
        SortedMap<String, MessageDigest> byNamespace = new TreeMap<>();
        List<Holder.Reference<T>> elements = new ArrayList<>(lookup.get().listElements().toList());
        elements.sort(Comparator.comparing(element -> element.key().identifier().toString()));
        boolean failed = false;
        for (Holder.Reference<T> element : elements) {
            String namespace = element.key().identifier().getNamespace();
            namespaces.add(namespace);
            MessageDigest digest = byNamespace.computeIfAbsent(namespace, ignored -> sha256());
            part(digest, element.key().identifier().toString());
            String encoded = encode(data.elementCodec(), ops, element.value());
            if (encoded == null) {
                failed = true;
                entries.put(REGISTRY + name + ":" + namespace, UNKNOWN);
            } else {
                part(digest, encoded);
            }
        }
        lookup.get().listTags().sorted(Comparator.comparing(tag -> tag.key().location().toString())).forEach(tag -> {
            String namespace = tag.key().location().getNamespace();
            MessageDigest digest = byNamespace.computeIfAbsent(namespace, ignored -> sha256());
            part(digest, "#" + tag.key().location());
            tag.stream().forEach(member -> part(digest, member.unwrapKey().map(key -> key.identifier().toString()).orElse("direct")));
        });
        for (Map.Entry<String, MessageDigest> entry : byNamespace.entrySet()) {
            entries.putIfAbsent(REGISTRY + name + ":" + entry.getKey(), HexFormat.of().formatHex(entry.getValue().digest()));
        }
        if (failed) {
            entries.put(REGISTRY + name, UNKNOWN);
        }
    }

    private static <T> String encode(Codec<T> codec, DynamicOps<JsonElement> ops, T value) {
        try {
            var result = codec.encodeStart(ops, value);
            return result.result().map(json -> canonical(json).toString()).orElse(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Keys sorted, so the same values always hash the same. */
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject ordered = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, child) -> ordered.add(key, canonical(child)));
            return ordered;
        }
        if (value.isJsonArray()) {
            JsonArray ordered = new JsonArray();
            value.getAsJsonArray().forEach(child -> ordered.add(canonical(child)));
            return ordered;
        }
        return value;
    }

    /** The hash of a mod's configuration files ({@code <id>.json}, {@code .json5}, {@code .toml}, {@code <id>/}), or "none". */
    static String configHash(Path configFolder, String modId) {
        if (!modId.matches("[a-z0-9_.-]+")) {
            return UNKNOWN;
        }
        MessageDigest digest = sha256();
        boolean any = false;
        try {
            for (String suffix : List.of(".json", ".json5", ".toml")) {
                Path file = configFolder.resolve(modId + suffix);
                if (Files.isRegularFile(file)) {
                    part(digest, modId + suffix);
                    hashFile(digest, file);
                    any = true;
                }
            }
            Path folder = configFolder.resolve(modId);
            if (Files.isDirectory(folder)) {
                try (Stream<Path> files = Files.walk(folder)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        part(digest, folder.relativize(file).toString().replace('\\', '/'));
                        hashFile(digest, file);
                        any = true;
                    }
                }
            }
        } catch (IOException unreadable) {
            return UNKNOWN;
        }
        return any ? HexFormat.of().formatHex(digest.digest()) : "none";
    }

    private static void hashFile(MessageDigest digest, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    public SortedMap<String, String> entries() {
        return entries;
    }

    /** Whether every entry could be worked out. */
    public boolean complete() {
        return !entries.containsValue(UNKNOWN);
    }

    /** One hash of every entry, for naming what predictions were made with. */
    public String hash() {
        MessageDigest digest = sha256();
        entries.forEach((key, value) -> {
            part(digest, key);
            part(digest, value);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * What differs from another side's fingerprint, by what a player can act on: the mods and
     * data packs one side has and the other hasn't or has at another version, the mods whose
     * configuration differs, and the namespaces whose world generation differs although the mod
     * lists agree. Empty when they match; an entry unknown on either side is a difference.
     */
    public List<Difference> differences(GenerationFingerprint other) {
        List<Difference> found = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(entries.keySet());
        keys.addAll(other.entries.keySet());
        TreeSet<String> namespacesNamed = new TreeSet<>();
        for (String key : keys) {
            String mine = entries.get(key);
            String theirs = other.entries.get(key);
            if (mine != null && mine.equals(theirs) && !UNKNOWN.equals(mine)) {
                continue;
            }
            if (key.startsWith(MOD)) {
                String mod = key.substring(MOD.length());
                namespacesNamed.add(mod);
                found.add(new Difference(Difference.Kind.MOD, mod, mine, theirs));
            } else if (key.startsWith(PACK)) {
                found.add(new Difference(Difference.Kind.PACK, key.substring(PACK.length()), mine, theirs));
            } else if (key.startsWith(CONFIG)) {
                String mod = key.substring(CONFIG.length());
                if (mine != null && theirs != null) {
                    namespacesNamed.add(mod);
                    found.add(new Difference(Difference.Kind.CONFIG, mod, mine, theirs));
                }
            }
        }
        // Registries that differ in a namespace no mod or pack difference explains: a data pack's
        // edits under minecraft:, or a mod's data changed at the same version.
        TreeSet<String> registryNamespaces = new TreeSet<>();
        for (String key : keys) {
            if (!key.startsWith(REGISTRY) || key.indexOf(':', REGISTRY.length()) < 0) {
                continue;
            }
            String mine = entries.get(key);
            String theirs = other.entries.get(key);
            if (mine != null && mine.equals(theirs) && !UNKNOWN.equals(mine)) {
                continue;
            }
            String namespace = key.substring(key.lastIndexOf(':') + 1);
            if (!namespacesNamed.contains(namespace)) {
                registryNamespaces.add(namespace);
            }
        }
        for (String namespace : registryNamespaces) {
            found.add(new Difference(Difference.Kind.DATA, namespace, null, null));
        }
        if (!String.valueOf(FORMAT).equals(other.entries.get("format"))) {
            found.add(new Difference(Difference.Kind.FORMAT, other.entries.getOrDefault("format", "?"), null, null));
        }
        return found;
    }

    /**
     * One thing that differs. {@code here} and {@code there} are this side's and the other's
     * version or hash, null where the side lacks it.
     */
    public record Difference(Kind kind, String name, String here, String there) {
        public enum Kind { MOD, PACK, CONFIG, DATA, FORMAT }

        /** Plain words for the notice. */
        public String describe() {
            return switch (kind) {
                case MOD -> here == null ? name + " (only on the server)" : there == null ? name + " (only here)"
                    : name + " (" + there + " on the server, " + here + " here)";
                case PACK -> "data pack " + name + (here == null ? " (only on the server)" : " (only here)");
                case CONFIG -> name + "'s settings";
                case DATA -> "the " + name + " world-generation data";
                case FORMAT -> "the server's helper is another version";
            };
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void part(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    /** Registry key names, for tests. */
    static String registryEntry(ResourceKey<? extends Registry<?>> registry, String namespace) {
        return REGISTRY + registry.identifier() + ":" + namespace;
    }
}
