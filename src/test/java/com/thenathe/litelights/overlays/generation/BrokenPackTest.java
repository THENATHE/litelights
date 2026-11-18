package com.thenathe.litelights.overlays.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.hierarchy.OverlayPlan;
import com.thenathe.litelights.overlays.pack.PackStack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E3: a resource pack's malformed model, or a set that throws while it is drawn, costs that block
 * or that set only. The game draws such a block with the missing texture and goes on; so do the
 * overlays, and the report says what was left out.
 */
class BrokenPackTest {
    private static final String CUBE = """
        {"textures": {"all": "minecraft:block/%s"},
         "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
           "up": {"texture": "#all"}, "north": {"texture": "#all"}}}]}""";

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void malformedModelsAreUnusableNotFatal(@TempDir Path pack) throws Exception {
        // An upper-case model id: the game can't load it either.
        write(pack, "blockstates/sand.json", "{\"variants\": {\"\": {\"model\": \"minecraft:block/SAND\"}}}");
        // A face with no texture.
        write(pack, "blockstates/gravel.json", "{\"variants\": {\"\": {\"model\": \"minecraft:block/gravel\"}}}");
        write(pack, "models/block/gravel.json", """
            {"elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"up": {"uv": [0, 0, 16, 16]}}}]}""");
        // A value of the wrong JSON type.
        write(pack, "blockstates/dirt.json", "{\"variants\": {\"\": {\"model\": \"minecraft:block/dirt\"}}}");
        write(pack, "models/block/dirt.json", "{\"elements\": {\"not\": \"a list\"}}");
        write(pack, "blockstates/stone.json", "{\"variants\": {\"\": {\"model\": \"minecraft:block/stone\"}}}");
        write(pack, "models/block/stone.json", CUBE.formatted("stone"));
        BlockFaceResolver resolver = new BlockFaceResolver(packOf(pack));

        for (String block : List.of("minecraft:sand", "minecraft:gravel", "minecraft:dirt")) {
            BlockFaceResolver.UnusableModelException unusable = assertThrows(BlockFaceResolver.UnusableModelException.class,
                () -> resolver.resolve(block, Map.of()), block);
            assertInstanceOf(RuntimeException.class, unusable.getCause(), "the stack is kept for the log: " + block);
        }
        FaceLayers stone = resolver.resolve("minecraft:stone", Map.of());
        assertEquals("minecraft:block/stone", stone.top().getFirst().texture(), "a good model beside them still reads");
    }

    @Test
    void aSetThatThrowsIsLeftOutAndTheRestBuild() {
        OverlayPlan plan = new OverlayPlan(List.of(
            new OverlayPlan.Source("minecraft:sand", EdgeStyle.ORGANIC, Set.of("minecraft:stone"), Set.of()),
            new OverlayPlan.Source("minecraft:gravel", EdgeStyle.ORGANIC, Set.of("minecraft:stone"), Set.of())));
        OverlayGenerator.Input input = new OverlayGenerator.Input(new OverlayConfig(), null, Map.of(), "litelights",
            OverlayFormat.VARIETY, 16384, false);

        GeneratedFiles files = OverlayGenerator.generate(plan, new OneBrokenTexture("minecraft:block/sand"), input);

        assertTrue(files.files().keySet().stream().anyMatch(path -> path.contains("minecraft_gravel_spill")),
            "gravel's overlays are built");
        assertTrue(files.files().keySet().stream().noneMatch(path -> path.contains("sand")), "sand's set is left out");
        assertTrue(files.report().skipped().stream().anyMatch(line -> line.startsWith("minecraft:sand (spill0")
            && line.contains("couldn't be drawn")), "the report says so: " + files.report().skipped());
    }

    private static PackStack packOf(Path pack) throws IOException {
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {\"description\": \"broken\", \"min_format\": 1, \"max_format\": 999}}");
        PackLocationInfo info = new PackLocationInfo("broken", Component.literal("broken"), PackSource.BUILT_IN, Optional.empty());
        return new PackStack(List.of(new PathPackResources(info, pack)));
    }

    private static void write(Path pack, String path, String text) throws IOException {
        Path file = pack.resolve("assets/minecraft").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Full cubes of one texture each; one texture's pixels are cut short, so drawing its set throws. */
    private record OneBrokenTexture(String broken) implements OverlayGenerator.Sources {
        @Override
        public Map<String, FaceLayers> layers(OverlayPlan plan, OverlayGenerator.Input input, List<String> skipped,
                                              ExecutorService pool) {
            Map<String, FaceLayers> layers = new HashMap<>();
            for (String block : List.of("minecraft:sand", "minecraft:gravel", "minecraft:stone")) {
                FaceLayers.Layer own = new FaceLayers.Layer(block.replace("minecraft:", "minecraft:block/"), -1);
                layers.put(block, new FaceLayers(List.of(own), List.of(own)));
            }
            return layers;
        }

        @Override
        public ExistingOverlays existingOverlays() {
            return ExistingOverlays.NONE;
        }

        @Override
        public OverlayGenerator.BetterGrassSides betterGrassSides(Map<String, FaceLayers> layers, OverlayGenerator.Input input) {
            return OverlayGenerator.BetterGrassSides.NONE;
        }

        @Override
        public Map<String, SourceTexture> textures(Set<String> ids, List<String> skipped, ExecutorService pool) {
            Map<String, SourceTexture> textures = new HashMap<>();
            for (String id : ids) {
                int[] pixels = new int[id.equals(broken) ? 3 : 16 * 16];
                Arrays.fill(pixels, 0xFF808080);
                textures.put(id, new SourceTexture(id, 16, pixels, false));
            }
            return textures;
        }
    }
}
