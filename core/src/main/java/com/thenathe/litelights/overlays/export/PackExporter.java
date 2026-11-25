package com.thenathe.litelights.overlays.export;

import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.generation.GeneratedFiles;
import com.thenathe.litelights.overlays.generation.OverlayFormat;
import com.thenathe.litelights.overlays.generation.OverlayGenerator;
import com.thenathe.litelights.overlays.generation.Workers;
import com.thenathe.litelights.overlays.pack.GeneratedPack;
import com.thenathe.litelights.overlays.pack.OverlayBuilds;
import com.thenathe.litelights.overlays.pack.PackStack;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

/**
 * Writes the overlays as an ordinary resource pack zip, for sharing or for playing without
 * LiteLights (Continuity still has to be installed to draw them).
 *
 * <p>Exports use Continuity's plain {@code method=overlay}, which needs nothing but Continuity: no
 * edge variety and no spilling down steps, since those are drawn by the module's own method.
 * Exports made by the standalone Dynamic Overlays mod
 * ({@code dynamic_overlays_export}) still work as they are and are never read as a source.
 *
 * <p>The export uses its own namespace, so it never collides with the live pack. If both are on,
 * the live build sees the export's overlays as a pack's own and leaves those pairs to it, so
 * nothing is drawn twice; the export just won't follow later setting changes.
 */
public final class PackExporter {
    public static final String NAMESPACE = "litelights_export";
    /** Exports made by the standalone Dynamic Overlays mod. Left out of new exports like LiteLights' own. */
    private static final String DYNAMIC_OVERLAYS_NAMESPACE = "dynamic_overlays_export";

    private PackExporter() {
    }

    /**
     * Builds from the packs currently loaded and writes the zip into the resource pack folder. A
     * failure is logged with its stack, and no half-written file is left in the folder.
     */
    public static CompletableFuture<Path> exportToResourcePacks() {
        Minecraft minecraft = Minecraft.getInstance();
        // Earlier exports are left out too, or their overlays would count as a pack's own and be skipped.
        List<PackResources> sources = GeneratedPack.withoutGenerated(minecraft.getResourceManager().listPacks().toList()).stream()
            .filter(pack -> !isExport(pack))
            .toList();
        PackStack packs = new PackStack(sources);
        // Other players won't have the module's drawing method, so exports are always plain overlays.
        OverlayGenerator.Input input = OverlayBuilds.snapshot(NAMESPACE, OverlayFormat.PLAIN);
        Path folder = minecraft.getResourcePackDirectory();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"));
        Path target = folder.resolve("LiteLights overlays export " + stamp + ".zip");
        CompletableFuture<Path> export = CompletableFuture.supplyAsync(() -> {
            GeneratedFiles files = OverlayGenerator.generateExport(packs, input);
            try {
                write(files, target);
            } catch (IOException failure) {
                throw new IllegalStateException("Couldn't write " + target, failure);
            }
            OverlaysModule.LOG.info("Exported overlays to {}: {}", target, files.report().summary());
            return target;
        }, Workers.coordinator());
        export.whenComplete((path, failure) -> {
            if (failure != null) {
                OverlaysModule.LOG.error("Couldn't export the overlays to {}", target, failure);
            }
        });
        return export;
    }

    private static boolean isExport(PackResources pack) {
        Set<String> namespaces = pack.getNamespaces(PackType.CLIENT_RESOURCES);
        return namespaces.contains(NAMESPACE) || namespaces.contains(DYNAMIC_OVERLAYS_NAMESPACE);
    }

    static void write(GeneratedFiles files, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                put(zip, "pack.mcmeta", packMetadata().getBytes(StandardCharsets.UTF_8));
                try (InputStream icon = OverlayBuilds.packIcon()) {
                    put(zip, "pack.png", icon.readAllBytes());
                }
                for (Map.Entry<String, byte[]> file : files.files().entrySet()) {
                    put(zip, file.getKey(), file.getValue());
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException leftOver) {
                failure.addSuppressed(leftOver);
            }
            throw failure;
        }
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String packMetadata() {
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        return """
            {
              "pack": {
                "description": "Overlays generated by LiteLights. Needs Continuity.",
                "min_format": %d,
                "max_format": %d
              }
            }
            """.formatted(format, format);
    }
}
