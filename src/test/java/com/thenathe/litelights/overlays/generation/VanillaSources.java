package com.thenathe.litelights.overlays.generation;

import com.thenathe.litelights.overlays.VanillaAssets;
import com.thenathe.litelights.overlays.config.DefaultHierarchy;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.hierarchy.EligibleBlocks;
import com.thenathe.litelights.overlays.hierarchy.OverlayPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Vanilla's blockstates, models and textures as a build's sources, blown up {@code scale} times
 * (nearest texel) to stand in for a high-resolution pack. {@code round} 0 gives the textures as
 * they are; any other round changes every texel by one bit, so a build sees new textures and
 * reuses nothing from round 0.
 */
record VanillaSources(int scale, int round, boolean betterGrass) implements OverlayGenerator.Sources {
    /** LambdaBetterGrass's blocks in vanilla, for builds as if it were installed. */
    private static final Set<String> BETTER_GRASS = Set.of("minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium",
        "minecraft:crimson_nylium", "minecraft:warped_nylium");

    VanillaSources(int scale, int round) {
        this(scale, round, false);
    }

    /** Blocks you sink into: they receive overlays but never spill. */
    private static final Set<String> NOT_SPILLABLE = Set.of("minecraft:mud", "minecraft:soul_sand");
    private static final Map<String, Map<String, String>> DEFAULT_STATES = Map.of(
        "minecraft:grass_block", Map.of("snowy", "false"),
        "minecraft:mycelium", Map.of("snowy", "false"),
        "minecraft:podzol", Map.of("snowy", "false"),
        "minecraft:deepslate", Map.of("axis", "y"));

    /** The default hierarchy's blocks, as the game's registry would list them. */
    static EligibleBlocks eligible() {
        List<String> ids = DefaultHierarchy.blockIds();
        List<String> spillable = new ArrayList<>(ids);
        spillable.removeAll(NOT_SPILLABLE);
        return EligibleBlocks.of(ids, spillable);
    }

    static OverlayGenerator.Input input(OverlayConfig config, OverlayFormat format) {
        return new OverlayGenerator.Input(config, eligible(), DEFAULT_STATES, "litelights", format, 16384, false);
    }

    /** A whole build of {@code config}'s hierarchy. */
    static GeneratedFiles build(OverlayConfig config, OverlayFormat format, int scale, int round) {
        return build(config, format, scale, round, false);
    }

    /** A whole build of {@code config}'s hierarchy, as if LambdaBetterGrass were installed or not. */
    static GeneratedFiles build(OverlayConfig config, OverlayFormat format, int scale, int round, boolean betterGrass) {
        OverlayGenerator.Input input = input(config, format);
        return OverlayGenerator.generate(OverlayPlan.from(config, input.eligible()), new VanillaSources(scale, round, betterGrass),
            input);
    }

    @Override
    public Map<String, FaceLayers> layers(OverlayPlan plan, OverlayGenerator.Input input, List<String> skipped,
                                          ExecutorService pool) {
        Set<String> blocks = new LinkedHashSet<>();
        for (OverlayPlan.Source source : plan.sources()) {
            blocks.add(source.blockId());
            blocks.addAll(source.spillOnto());
            blocks.addAll(source.blendWith());
        }
        BlockFaceResolver resolver = new BlockFaceResolver(VanillaAssets.packs());
        Map<String, FaceLayers> layers = new HashMap<>();
        for (String block : blocks) {
            try {
                layers.put(block, resolver.resolve(block, input.defaultStates().getOrDefault(block, Map.of())));
            } catch (BlockFaceResolver.UnusableModelException reason) {
                skipped.add(block + ": " + reason.getMessage());
            }
        }
        return layers;
    }

    @Override
    public ExistingOverlays existingOverlays() {
        return ExistingOverlays.NONE;
    }

    @Override
    public OverlayGenerator.BetterGrassSides betterGrassSides(Map<String, FaceLayers> layers, OverlayGenerator.Input input) {
        if (!betterGrass) {
            return OverlayGenerator.BetterGrassSides.NONE;
        }
        Map<String, Set<String>> plain = new HashMap<>();
        for (String block : BETTER_GRASS) {
            FaceLayers faces = layers.get(block);
            if (faces != null) {
                Set<String> sides = new java.util.TreeSet<>();
                faces.side().forEach(layer -> sides.add(layer.texture()));
                plain.put(block, sides);
            }
        }
        return new OverlayGenerator.BetterGrassSides(plain, Map.of());
    }

    @Override
    public Map<String, SourceTexture> textures(Set<String> ids, List<String> skipped, ExecutorService pool) {
        Map<String, SourceTexture> textures = new HashMap<>();
        for (String id : ids) {
            SourceTexture texture = VanillaAssets.texture(id);
            textures.put(id, scaled(texture, scale, round == 0 ? 0 : 1));
        }
        return textures;
    }

    private static SourceTexture scaled(SourceTexture texture, int scale, int shift) {
        int size = texture.size() * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int texel = texture.pixels()[(y / scale) * texture.size() + x / scale];
                pixels[y * size + x] = texel == 0 ? 0 : texel ^ shift;
            }
        }
        return new SourceTexture(texture.id(), size, pixels, texture.animated());
    }
}
