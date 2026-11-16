package com.thenathe.litelights.overlays.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.overlays.continuity.BetterGrassRules.Side;
import com.thenathe.litelights.overlays.continuity.VarietySettings.BetterGrassRole;
import com.thenathe.litelights.overlays.pack.BetterGrass;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BetterGrassRules} decides as LambdaBetterGrass 2.9.0 does, and the variety method spills
 * by the user's rules beside what it decided.
 */
class BetterGrassRulesTest {
    private static final BetterGrass.Settings FANCY = new BetterGrass.Settings(BetterGrass.Mode.FANCY, true);
    private static final BetterGrass.Settings FAST = new BetterGrass.Settings(BetterGrass.Mode.FAST, true);
    private static final BetterGrass.Settings FASTEST = new BetterGrass.Settings(BetterGrass.Mode.FASTEST, true);
    private static final BetterGrass.Settings OFF = new BetterGrass.Settings(BetterGrass.Mode.OFF, true);
    private static final BlockPos AT = new BlockPos(0, 1, 0);

    private static BetterGrassRules rules;
    private static BlockState grass;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        grass = Blocks.GRASS_BLOCK.defaultBlockState();
        rules = new BetterGrassRules(Set.of(Blocks.GRASS_BLOCK, Blocks.PODZOL), Set.of(Blocks.GRASS_BLOCK),
            Map.of(Blocks.SHORT_GRASS, state -> true), state -> state.is(Blocks.SNOW));
    }

    @Test
    void aSideIsFullWhenTheSameBlockIsInFrontAndOneDown() {
        Level level = new Level().put(AT, grass).put(AT.south().below(), grass);
        assertEquals(Side.FULL, rules.side(level.view(), AT, grass, Direction.SOUTH, FANCY));
        assertEquals(Side.FULL, rules.side(level.view(), AT, grass, Direction.SOUTH, FAST));
        assertEquals(Side.VANILLA, rules.side(level.view(), AT, grass, Direction.SOUTH, OFF));
        assertEquals(Side.VANILLA, rules.side(level.view(), AT, grass, Direction.NORTH, FANCY));
        assertEquals(Side.FULL, rules.side(level.view(), AT, grass, Direction.NORTH, FASTEST), "fastest makes every side full");
    }

    @Test
    void somethingSturdyOnTheBlockInFrontStopsIt() {
        Level level = new Level().put(AT, grass).put(AT.south().below(), grass).put(AT.south(), Blocks.STONE.defaultBlockState());
        assertEquals(Side.VANILLA, rules.side(level.view(), AT, grass, Direction.SOUTH, FANCY));
        level.put(AT.south(), Blocks.MOSS_CARPET.defaultBlockState());
        assertEquals(Side.FULL, rules.side(level.view(), AT, grass, Direction.SOUTH, FANCY), "a carpet doesn't count");
    }

    @Test
    void aSnowyBlockInFrontIsNotTheSameState() {
        Level level = new Level().put(AT, grass)
            .put(AT.south().below(), grass.setValue(BlockStateProperties.SNOWY, true));
        assertEquals(Side.VANILLA, rules.side(level.view(), AT, grass, Direction.SOUTH, FANCY));
    }

    @Test
    void fancyBlendsWhenASideNeighbourStepsDownOrIsFull() {
        // Case 105's west grass: grass one step down to its west.
        Level stepped = new Level().put(AT, grass).put(AT.west().below(), grass);
        assertEquals(Side.BLEND, rules.side(stepped.view(), AT, grass, Direction.SOUTH, FANCY));
        assertEquals(Side.VANILLA, rules.side(stepped.view(), AT, grass, Direction.SOUTH, FAST), "fast mode only connects");

        // Case 121's grass over the dirt: the grass beside it is full.
        Level besideFull = new Level().put(AT, grass).put(AT.east(), grass).put(AT.east().south().below(), grass);
        assertEquals(Side.BLEND, rules.side(besideFull.view(), AT, grass, Direction.SOUTH, FANCY));
        assertEquals(Side.FULL, rules.side(besideFull.view(), AT.east(), grass, Direction.SOUTH, FANCY));
    }

    @Test
    void onlyItsOwnBlocksAreRedrawn() {
        BlockState sand = Blocks.SAND.defaultBlockState();
        Level level = new Level().put(AT, sand).put(AT.south().below(), sand);
        assertEquals(Side.VANILLA, rules.side(level.view(), AT, sand, Direction.SOUTH, FASTEST));
    }

    @Test
    void grassUnderAPlantAmongSnowIsDrawnSnowy() {
        Level level = new Level().put(AT, grass).put(AT.above(), Blocks.SHORT_GRASS.defaultBlockState())
            .put(AT.above().east(), Blocks.SNOW.defaultBlockState());
        assertFalse(rules.drawnSnowy(level.view(), AT, grass, FANCY), "one snow layer isn't enough");
        level.put(AT.above().west(), Blocks.SNOW.defaultBlockState());
        assertTrue(rules.drawnSnowy(level.view(), AT, grass, FANCY));
        assertFalse(rules.drawnSnowy(level.view(), AT, grass, new BetterGrass.Settings(BetterGrass.Mode.FANCY, false)),
            "better layers off");
        assertFalse(rules.drawnSnowy(level.view(), AT, grass, OFF));
        level.put(AT.above(), Blocks.FERN.defaultBlockState());
        assertFalse(rules.drawnSnowy(level.view(), AT, grass, FANCY), "a plant without a snow layer");
    }

    @Test
    void aNeighbouringPlantCountsWhenItHasSnowAroundIt() {
        BlockPos plant = AT.above();
        Level level = new Level().put(AT, grass).put(plant, Blocks.SHORT_GRASS.defaultBlockState())
            .put(plant.east(), Blocks.SNOW.defaultBlockState())
            .put(plant.west(), Blocks.SHORT_GRASS.defaultBlockState())
            .put(plant.west().north(), Blocks.SNOW.defaultBlockState())
            .put(plant.west().south(), Blocks.SNOW.defaultBlockState());
        assertTrue(rules.drawnSnowy(level.view(), AT, grass, FANCY));
    }

    @Test
    void spillsFollowTheUsersRules() {
        Side full = Side.FULL;
        Side blend = Side.BLEND;
        Side alone = Side.VANILLA;
        // A full side spills the top layer onto a side that isn't blended, except soil left alone.
        assertTrue(BetterGrassRole.TOP.spillsBeside(full, alone, false));
        assertTrue(BetterGrassRole.TOP.spillsBeside(full, full, false));
        assertTrue(BetterGrassRole.TOP.spillsBeside(full, full, true), "a full podzol side shows its top");
        assertFalse(BetterGrassRole.TOP.spillsBeside(full, blend, false));
        assertFalse(BetterGrassRole.TOP.spillsBeside(full, alone, true));
        assertFalse(BetterGrassRole.TOP.spillsBeside(alone, alone, false));
        // Soil left alone takes the fringe from a full side, and never the dirt.
        assertTrue(BetterGrassRole.FRINGE.spillsBeside(full, alone, true));
        assertFalse(BetterGrassRole.SIDE.spillsBeside(full, alone, true));
        assertFalse(BetterGrassRole.FRINGE.spillsBeside(full, alone, false));
        // A blended side spills nothing; a side it changed takes none of the vanilla side.
        for (BetterGrassRole role : BetterGrassRole.values()) {
            assertFalse(role.spillsBeside(blend, alone, false), role + " from a blend");
            assertFalse(role.spillsBeside(alone, full, false), role + " onto a full side");
            assertFalse(role.spillsBeside(alone, blend, true), role + " onto a blend");
        }
        // Sides left alone on both sides: as before.
        assertTrue(BetterGrassRole.SIDE.spillsBeside(alone, alone, false));
        assertTrue(BetterGrassRole.FRINGE.spillsBeside(alone, alone, true));
        assertTrue(BetterGrassRole.BOTH.spillsBeside(alone, alone, false));
    }

    @Test
    void readsItsBlocksFromItsResources(@TempDir Path pack) throws IOException {
        write(pack, "assets/minecraft/bettergrass/states/grass_block.json",
            "{\"type\":\"grass\",\"variants\":{\"snowy=false\":{\"data\":\"minecraft:bettergrass/data/grass_block\"},"
                + "\"snowy=true\":{\"data\":\"minecraft:bettergrass/data/snowy_grass_block\"}}}");
        write(pack, "assets/minecraft/bettergrass/states/podzol.json", "{\"type\":\"grass\",\"data\":\"minecraft:bettergrass/data/podzol\"}");
        write(pack, "assets/minecraft/bettergrass/states/short_grass.json", "{\"type\":\"layer\",\"data\":\"minecraft:bettergrass/data/short_grass\"}");
        write(pack, "assets/minecraft/bettergrass/data/short_grass.json", "{}");
        write(pack, "assets/minecraft/bettergrass/states/fern.json", "{\"type\":\"layer\",\"data\":\"minecraft:bettergrass/data/fern\"}");
        write(pack, "assets/minecraft/bettergrass/data/fern.json", "{\"snow\":{\"layer\":false}}");
        write(pack, "assets/lambdabettergrass/bettergrass/layer_types/snow.json",
            "{\"block\":{\"id\":\"minecraft:snow\"},\"match\":[\"minecraft:snow\",{\"block\":\"minecraft:snow_block\"}]}");
        PackLocationInfo info = new PackLocationInfo("test", Component.literal("test"), PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
            List.of(new PathPackResources(info, pack)))) {
            BetterGrassRules loaded = BetterGrassRules.load(resources);
            assertTrue(loaded.redraws(Blocks.GRASS_BLOCK));
            assertTrue(loaded.redraws(Blocks.PODZOL));
            assertFalse(loaded.redraws(Blocks.SHORT_GRASS));

            Level level = new Level().put(AT, grass).put(AT.above(), Blocks.SHORT_GRASS.defaultBlockState())
                .put(AT.above().east(), Blocks.SNOW.defaultBlockState()).put(AT.above().west(), Blocks.SNOW_BLOCK.defaultBlockState());
            assertTrue(loaded.drawnSnowy(level.view(), AT, grass, FANCY));
            level.put(AT.above(), Blocks.FERN.defaultBlockState());
            assertFalse(loaded.drawnSnowy(level.view(), AT, grass, FANCY), "its data switched the fern's snow layer off");
            BlockState podzol = Blocks.PODZOL.defaultBlockState();
            level.put(AT, podzol).put(AT.above(), Blocks.SHORT_GRASS.defaultBlockState());
            assertFalse(loaded.drawnSnowy(level.view(), AT, podzol, FANCY), "podzol has no snowy variant in its states file");
        }
    }

    private static void write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Blocks by position, air everywhere else. */
    private static final class Level {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final BlockAndTintGetter view;

        Level() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getBlockState" -> blocks.getOrDefault((BlockPos) args[0], Blocks.AIR.defaultBlockState());
                case "getFluidState" -> blocks.getOrDefault((BlockPos) args[0], Blocks.AIR.defaultBlockState()).getFluidState();
                case "getBlockEntity" -> null;
                case "getHeight" -> 384;
                case "getMinY" -> -64;
                default -> method.isDefault()
                    ? InvocationHandler.invokeDefault(proxy, method, args)
                    : unsupported(method.getName());
            };
            view = (BlockAndTintGetter) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {BlockAndTintGetter.class}, handler);
        }

        BlockAndTintGetter view() {
            return view;
        }

        Level put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            return this;
        }

        private static Object unsupported(String name) {
            throw new UnsupportedOperationException(name);
        }
    }
}
