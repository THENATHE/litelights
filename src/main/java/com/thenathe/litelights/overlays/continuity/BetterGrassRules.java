package com.thenathe.litelights.overlays.continuity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.pack.BetterGrass;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * What LambdaBetterGrass draws on a side face of one of its blocks, worked out the way it decides
 * it, so the overlays can follow a neighbour's side they never see. Its rules (2.9.0,
 * {@code LBGBakedModel} and {@code LayeredBlockUtils}, read in its open source):
 *
 * <ul>
 *   <li>A side is <b>full</b>, drawn with the block's top texture, when the block in front of it and
 *       one down is the same block state with nothing sturdy on it (a carpet doesn't count). In
 *       fastest mode every side is full.</li>
 *   <li>In fancy mode a side that isn't full is <b>blended</b> (its "blend_up" or "arch" masks)
 *       when a side neighbour steps down, or is the same state and its own side is full.</li>
 *   <li>With better layers on, a block with a snowy variant that has a plant on it, with two or
 *       more snow layers next to the plant, is drawn as its snowy variant.</li>
 * </ul>
 *
 * <p>The blocks come from its {@code bettergrass} resources, read from the same resource manager it
 * reads them from: {@code states} files of type {@code grass} are its blocks (a {@code snowy=true}
 * variant gives one a snowy look), {@code states} files of type {@code layer} are the plants that
 * can carry a snow layer, unless their data file switches the snow layer off, and
 * {@code layer_types/snow} says what counts as snow.
 *
 * <p>Only states that aren't snowy are asked about here: a source is always pinned to its own
 * default state, which for these blocks is the one without snow.
 */
final class BetterGrassRules {
    /** What LambdaBetterGrass did with a side face. */
    enum Side {
        /** Left alone: the block's own side. */
        VANILLA,
        /** Drawn with the block's top texture. */
        FULL,
        /** Fancy mode's partial blend of the top down the side. */
        BLEND
    }

    private static final String STATES = "bettergrass/states";
    private static final Identifier SNOW_LAYER_TYPE = Identifier.fromNamespaceAndPath(BetterGrass.MOD_ID,
        "bettergrass/layer_types/snow.json");

    private final Set<Block> blocks;
    private final Set<Block> snowyVariants;
    private final Map<Block, Predicate<BlockState>> snowLayerPlants;
    private final Predicate<BlockState> snow;

    BetterGrassRules(Set<Block> blocks, Set<Block> snowyVariants, Map<Block, Predicate<BlockState>> snowLayerPlants,
                     Predicate<BlockState> snow) {
        this.blocks = Set.copyOf(blocks);
        this.snowyVariants = Set.copyOf(snowyVariants);
        this.snowLayerPlants = Map.copyOf(snowLayerPlants);
        this.snow = snow;
    }

    boolean redraws(Block block) {
        return blocks.contains(block);
    }

    /**
     * What LambdaBetterGrass draws on this side of a block that isn't snowy and that it doesn't
     * draw snowy ({@link #drawnSnowy}); any other block's side is {@link Side#VANILLA}.
     */
    Side side(BlockAndTintGetter level, BlockPos pos, BlockState state, Direction face, BetterGrass.Settings settings) {
        if (settings.mode() == BetterGrass.Mode.OFF || !blocks.contains(state.getBlock())) {
            return Side.VANILLA;
        }
        if (settings.mode() == BetterGrass.Mode.FASTEST || fullyConnects(level, state, pos, face)) {
            return Side.FULL;
        }
        if (settings.mode() != BetterGrass.Mode.FANCY) {
            return Side.VANILLA;
        }
        return blendsToward(level, state, pos, face, face.getClockWise())
            || blendsToward(level, state, pos, face, face.getCounterClockWise()) ? Side.BLEND : Side.VANILLA;
    }

    /**
     * Whether LambdaBetterGrass draws this block as its snowy variant although its state isn't
     * snowy: better layers on, a plant on it that carries a snow layer, and more than one snow
     * layer next to the plant (a neighbouring plant of the same kind counts when it has more than
     * one snow layer next to it).
     */
    boolean drawnSnowy(BlockAndTintGetter level, BlockPos pos, BlockState state, BetterGrass.Settings settings) {
        if (settings.mode() == BetterGrass.Mode.OFF || !settings.betterLayers() || snow == null
            || !snowyVariants.contains(state.getBlock())
            || !state.hasProperty(BlockStateProperties.SNOWY) || state.getValue(BlockStateProperties.SNOWY)) {
            return false;
        }
        BlockPos plantPos = pos.above();
        BlockState plant = level.getBlockState(plantPos);
        if (plant.isAir() || plant.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        Predicate<BlockState> carriesSnow = snowLayerPlants.get(plant.getBlock());
        if (carriesSnow == null || !carriesSnow.test(plant)) {
            return false;
        }
        int layered = 0;
        BlockPos.MutableBlockPos around = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            around.setWithOffset(plantPos, direction);
            BlockState beside = level.getBlockState(around);
            if (beside.is(plant.getBlock())) {
                if (snowAround(level, around) > 1) {
                    layered++;
                }
            } else if (snow.test(beside)) {
                layered++;
            }
        }
        return layered > 1;
    }

    private int snowAround(BlockAndTintGetter level, BlockPos pos) {
        int count = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (snow.test(level.getBlockState(pos.relative(direction)))) {
                count++;
            }
        }
        return count;
    }

    private static boolean fullyConnects(BlockAndTintGetter level, BlockState self, BlockPos pos, Direction face) {
        return connects(level, self, pos.relative(face).below());
    }

    /** A side neighbour that steps down, or that is the same state with its own side full. */
    private static boolean blendsToward(BlockAndTintGetter level, BlockState self, BlockPos pos, Direction face,
                                        Direction toward) {
        BlockPos beside = pos.relative(toward);
        return connects(level, self, beside.below())
            || (connects(level, self, beside) && fullyConnects(level, self, beside, face));
    }

    private static boolean connects(BlockAndTintGetter level, BlockState self, BlockPos adjacentPos) {
        if (level.getBlockState(adjacentPos) != self) {
            return false;
        }
        BlockPos upPos = adjacentPos.above();
        BlockState up = level.getBlockState(upPos);
        return up.isAir() || !up.isFaceSturdy(level, upPos, Direction.DOWN)
            || up.getBlock() instanceof CarpetBlock || up.getBlock() instanceof MossyCarpetBlock;
    }

    // --- Reading its resources ---

    static BetterGrassRules load(ResourceManager resources) {
        Set<Block> blocks = new HashSet<>();
        Set<Block> snowyVariants = new HashSet<>();
        Map<Block, Predicate<BlockState>> snowLayerPlants = new HashMap<>();
        resources.listResources(STATES, id -> id.getPath().endsWith(".json")).forEach((id, resource) -> {
            try {
                readStates(resources, id, resource, blocks, snowyVariants, snowLayerPlants);
            } catch (RuntimeException unusable) {
                // One file LambdaBetterGrass can't use (a wrong JSON type, an upper-case id) is
                // skipped, as it skips it itself; the rest of its blocks still count.
                skipped(id, unusable);
            }
        });
        Predicate<BlockState> snow = null;
        try {
            snow = resources.getResource(SNOW_LAYER_TYPE)
                .map(BetterGrassRules::readObject)
                .map(BetterGrassRules::snowMatcher)
                .orElse(null);
        } catch (RuntimeException unusable) {
            skipped(SNOW_LAYER_TYPE, unusable);
        }
        return new BetterGrassRules(blocks, snowyVariants, snowLayerPlants, snow);
    }

    /** One {@code states} file: a block of its own (type {@code grass}), or a plant that can carry snow (type {@code layer}). */
    private static void readStates(ResourceManager resources, Identifier id, Resource resource, Set<Block> blocks,
                                   Set<Block> snowyVariants, Map<Block, Predicate<BlockState>> snowLayerPlants) {
        String path = id.getPath();
        Identifier blockId = Identifier.fromNamespaceAndPath(id.getNamespace(),
            path.substring(STATES.length() + 1, path.length() - ".json".length()));
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
        JsonObject state = readObject(resource);
        if (block.isEmpty() || state == null || !state.has("type")) {
            return;
        }
        switch (state.get("type").getAsString()) {
            case "grass" -> {
                blocks.add(block.get());
                if (hasSnowyVariant(state)) {
                    snowyVariants.add(block.get());
                }
            }
            case "layer" -> {
                Predicate<BlockState> carriesSnow = snowLayerStates(resources, block.get(), state);
                if (carriesSnow != null) {
                    snowLayerPlants.put(block.get(), carriesSnow);
                }
            }
            default -> {
            }
        }
    }

    private static void skipped(Identifier file, RuntimeException reason) {
        OverlaysModule.LOG.warn("Skipped LambdaBetterGrass's {}, which can't be read: {}", file, reason.toString());
    }

    private static boolean hasSnowyVariant(JsonObject state) {
        if (!state.has("variants")) {
            return false;
        }
        for (Map.Entry<String, JsonElement> variant : state.getAsJsonObject("variants").entrySet()) {
            if (variant.getValue().isJsonObject() && variant.getValue().getAsJsonObject().has("data")
                && List.of(variant.getKey().split(",")).contains("snowy=true")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The states of a plant that carry a snow layer: those its {@code data} (or a variant's) covers,
     * unless that data file, in any pack, sets the snow layer's {@code layer} to false.
     */
    private static Predicate<BlockState> snowLayerStates(ResourceManager resources, Block block, JsonObject state) {
        List<Predicate<BlockState>> carrying = new ArrayList<>();
        if (state.has("variants")) {
            for (Map.Entry<String, JsonElement> variant : state.getAsJsonObject("variants").entrySet()) {
                if (variant.getValue().isJsonObject() && variant.getValue().getAsJsonObject().has("data")
                    && carriesSnow(resources, variant.getValue().getAsJsonObject().get("data").getAsString())) {
                    carrying.add(properties(block, variant.getKey()));
                }
            }
        } else if (state.has("data") && carriesSnow(resources, state.get("data").getAsString())) {
            carrying.add(any -> true);
        }
        if (carrying.isEmpty()) {
            return null;
        }
        return plant -> carrying.stream().anyMatch(test -> test.test(plant));
    }

    private static boolean carriesSnow(ResourceManager resources, String dataId) {
        Identifier id = Identifier.tryParse(dataId);
        if (id == null) {
            return false;
        }
        boolean layer = true;
        for (Resource resource : resources.getResourceStack(id.withSuffix(".json"))) {
            JsonObject data = readObject(resource);
            if (data == null) {
                continue;
            }
            for (String key : new String[] {"snow", BetterGrass.MOD_ID + ":snow"}) {
                if (data.has(key) && data.get(key).isJsonObject()) {
                    JsonObject snowLayer = data.getAsJsonObject(key);
                    layer = !snowLayer.has("layer") || snowLayer.get("layer").getAsBoolean();
                }
            }
        }
        return layer;
    }

    /** The snow layer type's {@code match} (a block, a {@code #tag}, a block with properties, or a list), else its block. */
    private static Predicate<BlockState> snowMatcher(JsonObject type) {
        if (type == null) {
            return null;
        }
        if (type.has("match")) {
            JsonElement match = type.get("match");
            List<Predicate<BlockState>> tests = new ArrayList<>();
            if (match.isJsonArray()) {
                match.getAsJsonArray().forEach(entry -> tests.add(matcher(entry)));
            } else {
                tests.add(matcher(match));
            }
            if (!tests.isEmpty()) {
                return state -> tests.stream().anyMatch(test -> test.test(state));
            }
        }
        if (type.has("block") && type.get("block").isJsonObject() && type.getAsJsonObject("block").has("id")) {
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(
                Identifier.tryParse(type.getAsJsonObject("block").get("id").getAsString()));
            return block.<Predicate<BlockState>>map(found -> state -> state.is(found)).orElse(null);
        }
        return null;
    }

    private static Predicate<BlockState> matcher(JsonElement entry) {
        if (entry.isJsonPrimitive()) {
            String text = entry.getAsString();
            if (text.startsWith("#")) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(text.substring(1)));
                return state -> state.is(tag);
            }
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(text));
            return block.<Predicate<BlockState>>map(found -> state -> state.is(found)).orElse(state -> false);
        }
        if (entry.isJsonObject() && entry.getAsJsonObject().has("block")) {
            JsonObject object = entry.getAsJsonObject();
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(object.get("block").getAsString()));
            if (block.isEmpty()) {
                return state -> false;
            }
            StringBuilder values = new StringBuilder();
            if (object.has("properties") && object.get("properties").isJsonObject()) {
                object.getAsJsonObject("properties").entrySet().forEach(value ->
                    values.append(values.isEmpty() ? "" : ",").append(value.getKey()).append('=').append(value.getValue().getAsString()));
            }
            Predicate<BlockState> withValues = properties(block.get(), values.toString());
            return state -> state.is(block.get()) && withValues.test(state);
        }
        return state -> false;
    }

    /** LambdaBetterGrass's variant keys: {@code key=value} pairs joined by commas; unknown ones are ignored. */
    private static Predicate<BlockState> properties(Block block, String variant) {
        Map<Property<?>, Comparable<?>> values = new HashMap<>();
        for (String pair : variant.split(",")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            Property<?> property = block.getStateDefinition().getProperty(pair.substring(0, separator));
            if (property != null) {
                property.getValue(pair.substring(separator + 1)).ifPresent(value -> values.put(property, value));
            }
        }
        return state -> values.entrySet().stream()
            .allMatch(value -> state.getValue(value.getKey()).equals(value.getValue()));
    }

    private static JsonObject readObject(Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            JsonElement json = JsonParser.parseReader(reader);
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (IOException | RuntimeException unreadable) {
            // LambdaBetterGrass skips a file it can't read, so it draws nothing from it either.
            return null;
        }
    }
}
