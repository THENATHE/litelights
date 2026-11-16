package com.thenathe.litelights.overlays.pack;

import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.generation.GeneratedFiles;
import com.thenathe.litelights.overlays.generation.GeneratedFolders;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * The in-memory resource pack the overlays live in. The game opens a fresh one for every resource
 * reload; {@link OverlayBuilds} hands it the other packs of that reload as soon as the reload
 * starts, and the build runs on the worker pool from then on.
 *
 * <p>Anything that asks this pack for files in its own folders (Continuity's properties scan, the
 * block atlas loading the tiles) waits for the build to finish.
 * Those callers are reload worker threads, never the render thread, so the loading screen keeps
 * drawing meanwhile.
 *
 * <p>The pack shares the {@code litelights} namespace with the mod's own assets, so it answers
 * only inside the folders the build writes ({@link GeneratedFolders}) and returns at once
 * for everything else. A shader or language file of LiteLights never waits on an overlay build.
 */
public final class GeneratedPack implements PackResources {
    /**
     * Not the mod id: Fabric already lists the mod's own assets as a pack under that id, and a
     * second pack with the same id would hide them.
     */
    public static final String ID = "litelights/overlays";
    public static final String NAMESPACE = "litelights";
    private static final long BUILD_TIMEOUT_SECONDS = 120;

    private final PackLocationInfo location;
    private final AtomicReference<CompletableFuture<GeneratedFiles>> build = new AtomicReference<>();
    private volatile GeneratedFolders folders = GeneratedFolders.NONE;

    public GeneratedPack(PackLocationInfo location) {
        this.location = location;
    }

    public void attachBuild(CompletableFuture<GeneratedFiles> build, GeneratedFolders folders) {
        this.folders = folders;
        this.build.set(build);
    }

    /**
     * The build's files, waiting for it if needed. A build that timed out or failed is swapped for an
     * empty result, so the rest of the reload neither waits again nor logs again.
     */
    private GeneratedFiles files() {
        CompletableFuture<GeneratedFiles> current = build.get();
        if (current == null) {
            return GeneratedFiles.EMPTY;
        }
        try {
            return current.get(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            if (giveUpOn(current)) {
                OverlaysModule.LOG.error("Overlay build took over {} s; this reload goes without overlays", BUILD_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (giveUpOn(current)) {
                OverlaysModule.LOG.error("Overlay build failed; this reload goes without overlays", failure);
            }
        }
        return GeneratedFiles.EMPTY;
    }

    /** Replaces a build with an empty result; true for the one caller that made the swap. */
    private boolean giveUpOn(CompletableFuture<GeneratedFiles> failed) {
        return build.compareAndSet(failed, CompletableFuture.completedFuture(GeneratedFiles.EMPTY));
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        if (path.length == 1 && path[0].equals("pack.png")) {
            return OverlayBuilds::packIcon;
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (type != PackType.CLIENT_RESOURCES || !NAMESPACE.equals(location.getNamespace())
                || !folders.holds(location.getPath())) {
            return null;
        }
        byte[] bytes = files().get("assets/" + NAMESPACE + "/" + location.getPath());
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !NAMESPACE.equals(namespace) || !folders.overlaps(directory)) {
            return;
        }
        String prefix = "assets/" + NAMESPACE + "/" + directory + "/";
        for (Map.Entry<String, byte[]> file : files().files().entrySet()) {
            if (file.getKey().startsWith(prefix)) {
                byte[] bytes = file.getValue();
                String path = file.getKey().substring(("assets/" + NAMESPACE + "/").length());
                output.accept(Identifier.fromNamespaceAndPath(NAMESPACE, path), () -> new ByteArrayInputStream(bytes));
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(NAMESPACE) : Set.of();
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> type) {
        // The pack's description and format are given directly when it's listed, not read from here.
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return location;
    }

    @Override
    public void close() {
    }

    /** Pulls this reload's generated packs out of a pack list, leaving the ones to read from. */
    public static List<PackResources> withoutGenerated(List<PackResources> packs) {
        return packs.stream().filter(pack -> !(pack instanceof GeneratedPack)).toList();
    }
}
