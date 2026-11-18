package com.thenathe.litelights.render.startup;

import com.thenathe.litelights.LiteLightsClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Mac safety net (review B4): a file left in the config folder when the start-up watch finds
 * the render thread stuck for over 10 s inside a terrain draw, which is where some Macs freeze
 * (DECISIONS mac096, caps097, diag097). The next launch sees it and offers vanilla terrain shading
 * for that session. A launch whose first world frames run normally removes it.
 *
 * <p>A frozen game is usually force-quit, so the file is written while the stall is still going
 * on, from the watch thread. It only ever holds a short note for whoever opens it.
 */
public final class TerrainStallMarker {
    static final String FILE_NAME = "litelights-terrain-stall.txt";
    /**
     * Where a terrain draw happens: vanilla's section drawing (Sodium-free) and Sodium's chunk
     * renderer, as in the stacks of the Mac logs that froze.
     */
    private static final String[] TERRAIN_DRAW_CLASSES = {
        "net.minecraft.client.renderer.chunk.ChunkSectionsToRender",
        "net.caffeinemc.mods.sodium.client.render.chunk.",
        "net.caffeinemc.mods.sodium.client.util.SodiumChunkSection",
    };
    private static final String[] TERRAIN_DRAW_METHODS = {
        "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.drawChunkLayer",
        "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.renderLayer",
    };

    private static TerrainStallMarker inConfigFolder;

    private final Path file;

    TerrainStallMarker(Path file) {
        this.file = file;
    }

    /** The marker in the game's config folder. */
    public static synchronized TerrainStallMarker get() {
        if (inConfigFolder == null) {
            inConfigFolder = new TerrainStallMarker(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
        }
        return inConfigFolder;
    }

    /** Whether the stack is inside a terrain draw (the render thread's, as the watch saw it). */
    static boolean inTerrainDraw(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            for (String terrain : TERRAIN_DRAW_CLASSES) {
                if (className.startsWith(terrain)) {
                    return true;
                }
            }
            String method = className + "." + frame.getMethodName();
            for (String terrain : TERRAIN_DRAW_METHODS) {
                if (method.equals(terrain)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the last launch (or an earlier one) left the marker. */
    public boolean present() {
        return Files.exists(file);
    }

    /**
     * Leaves the marker for the next launch. Written to a temporary file first and moved into place,
     * so a game killed half-way leaves either no marker or a whole one.
     */
    void stalledInTerrainDraw(String step) {
        String note = "LiteLights left this file because the game stopped responding for over 10 s while it drew\n"
            + "the world's blocks (" + step + ", " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ").\n"
            + "The next launch offers vanilla terrain shading for that session. It is removed after a launch\n"
            + "whose first world frames run normally. Deleting it does no harm.\n";
        Path temporary = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, note, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            LiteLightsClient.LOG.warn("LiteLights: couldn't leave the terrain stall marker {}: {}", file, failure.toString());
        }
    }

    /** The first world frames ran without a stall: the next launch needn't offer anything. */
    void launchWentWell() {
        try {
            if (Files.deleteIfExists(file)) {
                LiteLightsClient.LOG.info("LiteLights: this launch's first world frames ran normally; the terrain stall marker is removed");
            }
        } catch (IOException | RuntimeException failure) {
            LiteLightsClient.LOG.warn("LiteLights: couldn't remove the terrain stall marker {}: {}", file, failure.toString());
        }
    }
}
