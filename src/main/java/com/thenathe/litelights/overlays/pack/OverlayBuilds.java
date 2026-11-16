package com.thenathe.litelights.overlays.pack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.config.BlockEntry;
import com.thenathe.litelights.overlays.config.HardnessGroup;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.continuity.VarietyMethod;
import com.thenathe.litelights.overlays.generation.BuildCost;
import com.thenathe.litelights.overlays.generation.GeneratedFiles;
import com.thenathe.litelights.overlays.generation.GeneratedFolders;
import com.thenathe.litelights.overlays.generation.GenerationReport;
import com.thenathe.litelights.overlays.generation.OverlayFormat;
import com.thenathe.litelights.overlays.generation.OverlayGenerator;
import com.thenathe.litelights.overlays.generation.Workers;
import com.thenathe.litelights.overlays.hierarchy.EligibleBlocks;
import com.thenathe.litelights.overlays.ui.OverlaysPage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Starts a build for every resource reload and keeps the latest report for the settings screen. */
public final class OverlayBuilds {
    private static final int FALLBACK_MAX_TEXTURE_SIZE = 16384;
    /** Dynamic Overlays' pack icon, shown on the generated pack in the Resource Packs screen and in exports. */
    private static final String PACK_ICON = "/assets/litelights/overlays/pack.png";
    private static EligibleBlocks eligible;
    private static volatile GenerationReport lastReport = GenerationReport.NONE;
    private static volatile GeneratedFiles lastFiles = GeneratedFiles.EMPTY;
    private static volatile boolean building;
    /** Time the client thread spent starting the last build (the settings snapshot), in microseconds. */
    private static volatile long lastStartMicros;

    private OverlayBuilds() {
    }

    /** Called on the client thread as a resource reload starts, with every pack of that reload. */
    public static void onReloadStarting(List<PackResources> packs) {
        try {
            startBuild(packs);
        } catch (RuntimeException | LinkageError failure) {
            // Thrown into the reload, this would crash the game at start-up or on F3+T. The reload
            // goes on without the generated pack instead, and the page offers a retry.
            OverlaysModule.LOG.error("Couldn't start the overlay build", failure);
            OverlaysPage.FEATURE.markFailed(failure.toString(), () -> Minecraft.getInstance().reloadResourcePacks());
        }
    }

    private static void startBuild(List<PackResources> packs) {
        if (!OverlaysModule.active()) {
            return;
        }
        GeneratedPack target = null;
        for (PackResources pack : packs) {
            if (pack instanceof GeneratedPack generated) {
                target = generated;
            }
        }
        if (target == null) {
            return;
        }
        long started = System.nanoTime();
        OverlayFormat format = liveFormat();
        OverlayGenerator.Input input = snapshot(GeneratedPack.NAMESPACE, format);
        PackStack stack = new PackStack(GeneratedPack.withoutGenerated(packs));
        lastStartMicros = (System.nanoTime() - started) / 1000;
        building = true;
        CompletableFuture<GeneratedFiles> build = CompletableFuture
            .supplyAsync(() -> timed(stack, input), Workers.coordinator())
            .whenComplete((files, failure) -> {
                building = false;
                if (failure != null) {
                    OverlaysModule.LOG.error("Overlay build failed", failure);
                    // The page shows why, with a Retry button; the next resource reload tries again by itself too.
                    OverlaysPage.FEATURE.markFailed(failure.toString(), () -> Minecraft.getInstance().reloadResourcePacks());
                    return;
                }
                OverlaysPage.FEATURE.markReady();
                lastReport = files.report();
                lastFiles = files;
                OverlaysModule.LOG.info("Built overlays: {} ({})", files.report().summary(), BuildCost.last().summary());
                for (String reason : files.report().skipped()) {
                    OverlaysModule.LOG.info("Skipped {}", reason);
                }
            });
        target.attachBuild(build, GeneratedFolders.CONTINUITY);
    }

    /**
     * The format the live pack is written in: the module's own Continuity method, or Continuity's
     * plain overlay files if the player chose them or the method can't be set up (as Dynamic
     * Overlays 0.1.0 always did).
     */
    public static OverlayFormat liveFormat() {
        boolean plain = OverlayConfig.get().plainOverlays || !VarietyMethod.ensureRegistered();
        return plain ? OverlayFormat.PLAIN : OverlayFormat.VARIETY;
    }

    /** Runs a build and records its cost: wall time, and the heap it allocated on this thread and the workers. */
    private static GeneratedFiles timed(PackStack stack, OverlayGenerator.Input input) {
        return BuildCost.measure(() -> OverlayGenerator.generate(stack, input));
    }

    /** Copies everything a build needs off the client thread's live state. */
    public static OverlayGenerator.Input snapshot(String namespace, OverlayFormat format) {
        OverlayConfig config = OverlayConfig.get().snapshot();
        Map<String, Map<String, String>> defaultStates = new LinkedHashMap<>();
        for (HardnessGroup group : config.groups) {
            for (BlockEntry entry : group.blocks) {
                Identifier id = Identifier.tryParse(entry.id);
                if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                    defaultStates.put(entry.id, stateValues(BuiltInRegistries.BLOCK.getValue(id)));
                }
            }
        }
        return new OverlayGenerator.Input(config, eligible(), defaultStates, namespace, format, maxTextureSize(),
            BetterGrass.installed());
    }

    /** The largest texture side the graphics card allows, or a common value if the device isn't up yet. */
    private static int maxTextureSize() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return FALLBACK_MAX_TEXTURE_SIZE;
        }
        return device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
    }

    public static EligibleBlocks eligible() {
        if (eligible == null) {
            eligible = EligibleBlocks.scan();
        }
        return eligible;
    }

    public static GenerationReport lastReport() {
        return lastReport;
    }

    /** The files of the latest build. The live pack holds the same map, so this costs no extra memory. */
    public static GeneratedFiles lastFiles() {
        return lastFiles;
    }

    public static long lastStartMicros() {
        return lastStartMicros;
    }

    public static boolean isBuilding() {
        return building;
    }

    public static InputStream packIcon() throws IOException {
        InputStream icon = OverlayBuilds.class.getResourceAsStream(PACK_ICON);
        if (icon == null) {
            throw new IOException("pack.png is missing from the mod jar");
        }
        return icon;
    }

    private static Map<String, String> stateValues(Block block) {
        BlockState state = block.defaultBlockState();
        Map<String, String> values = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            values.put(property.getName(), valueName(state, property));
        }
        return values;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
