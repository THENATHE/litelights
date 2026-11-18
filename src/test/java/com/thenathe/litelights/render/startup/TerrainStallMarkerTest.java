package com.thenathe.litelights.render.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainStallMarkerTest {
    /** The render thread's stack in the friend's 0.9.7 log (vanilla renderer, Apple's OpenGL 4.1). */
    private static final String VANILLA_STALL = """
        org.lwjgl.opengl.GL32C.nglDrawElementsInstancedBaseVertex(Native Method)
        org.lwjgl.opengl.GL32C.glDrawElementsInstancedBaseVertex(GL32C.java:231)
        com.mojang.renderpearl.backend.opengl.GlCommandEncoder.executeDraw(GlCommandEncoder.java:397)
        com.mojang.renderpearl.backend.opengl.GlRenderPass.drawIndexed(GlRenderPass.java:155)
        com.mojang.renderpearl.frontend.FrontendRenderPass.drawMultipleIndexed(FrontendRenderPass.java:343)
        net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawSeparate.render(ChunkSectionsToRender.java:81)
        net.minecraft.client.renderer.chunk.ChunkSectionsToRender.renderLayers(ChunkSectionsToRender.java:58)
        net.minecraft.client.renderer.chunk.ChunkSectionsToRender.renderGroup(ChunkSectionsToRender.java:39)
        net.minecraft.client.renderer.LevelRenderer.mixinextras$bridge$renderGroup$123(LevelRenderer.java)
        net.minecraft.client.renderer.LevelRenderer.wrapOperation$bgl000$fabric-rendering-v1$wrapRenderOpaqueTerrain(LevelRenderer.java:2400)
        net.minecraft.client.renderer.LevelRenderer.executeSolid(LevelRenderer.java:516)
        net.minecraft.client.renderer.LevelRenderer.lambda$addMainPass$0(LevelRenderer.java:457)
        com.mojang.blaze3d.framegraph.FrameGraphBuilder.execute(FrameGraphBuilder.java:67)
        net.minecraft.client.renderer.LevelRenderer.render(LevelRenderer.java:287)
        net.minecraft.client.renderer.GameRenderer.renderLevel(GameRenderer.java:693)
        net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:496)
        net.minecraft.client.Minecraft.renderFrame(Minecraft.java:1376)
        """;

    /** The same freeze with Sodium 0.9.2 (the friend's later logs). */
    private static final String SODIUM_STALL = """
        org.lwjgl.opengl.GL32C.nglMultiDrawElementsBaseVertex(Native Method)
        com.mojang.renderpearl.backend.opengl.GlCommandEncoder.executeDraws(GlCommandEncoder.java:415)
        com.mojang.renderpearl.backend.opengl.GlRenderPass.multiDrawIndexed(GlRenderPass.java:165)
        com.mojang.renderpearl.frontend.FrontendRenderPass.multiDrawIndexed(FrontendRenderPass.java:264)
        net.caffeinemc.mods.sodium.client.gpu.device.batch.GLDrawBatch.draw(GLDrawBatch.java:35)
        net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer.render(DefaultChunkRenderer.java:181)
        net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.renderLayer(SodiumWorldRenderer.java:303)
        net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.drawChunkLayer(SodiumWorldRenderer.java:294)
        net.caffeinemc.mods.sodium.client.util.SodiumChunkSection.renderGroup(SodiumChunkSection.java:40)
        net.minecraft.client.renderer.LevelRenderer.executeSolid(LevelRenderer.java:516)
        net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:496)
        """;

    /** mac096's test of the watch: held in LiteLights' frame set-up, not in a terrain draw. */
    private static final String SET_UP_STALL = """
        java.lang.Thread.sleep(Thread.java:509)
        com.thenathe.litelights.render.startup.StartupTrace.holdForDebugging(StartupTrace.java:248)
        com.thenathe.litelights.render.startup.StartupTrace.frameSetUpStarting(StartupTrace.java:240)
        com.thenathe.litelights.render.LiteLightsRenderer.beforeLevel(LiteLightsRenderer.java:228)
        net.minecraft.client.renderer.GameRenderer.renderLevel(GameRenderer.java:660)
        net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:496)
        """;

    /** Inside the mob shadow map's replay of the entity draws, in the same level render. */
    private static final String ENTITY_STALL = """
        org.lwjgl.opengl.GL32C.nglDrawElementsBaseVertex(Native Method)
        com.mojang.renderpearl.backend.opengl.GlRenderPass.drawIndexed(GlRenderPass.java:155)
        com.thenathe.litelights.render.shadow.MobShadowMap.draw(MobShadowMap.java:140)
        com.thenathe.litelights.render.LiteLightsRenderer.onFeaturesPrepared(LiteLightsRenderer.java:428)
        net.minecraft.client.renderer.LevelRenderer.render(LevelRenderer.java:270)
        net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:496)
        """;

    private static StackTraceElement[] stack(String lines) {
        return lines.lines().map(String::strip).filter(line -> !line.isEmpty()).map(line -> {
            int call = line.indexOf('(');
            String qualified = line.substring(0, call);
            int dot = qualified.lastIndexOf('.');
            return new StackTraceElement(qualified.substring(0, dot), qualified.substring(dot + 1), null, -1);
        }).toArray(StackTraceElement[]::new);
    }

    @Test
    void theMacFreezesAreInTerrainDraws() {
        assertTrue(TerrainStallMarker.inTerrainDraw(stack(VANILLA_STALL)));
        assertTrue(TerrainStallMarker.inTerrainDraw(stack(SODIUM_STALL)));
    }

    @Test
    void otherStallsAreNot() {
        assertFalse(TerrainStallMarker.inTerrainDraw(stack(SET_UP_STALL)));
        assertFalse(TerrainStallMarker.inTerrainDraw(stack(ENTITY_STALL)));
        assertFalse(TerrainStallMarker.inTerrainDraw(new StackTraceElement[0]), "a thread with no stack (it ended)");
    }

    @Test
    void theMarkerLastsUntilALaunchGoesWell(@TempDir Path config) throws IOException {
        Path file = config.resolve(TerrainStallMarker.FILE_NAME);
        TerrainStallMarker marker = new TerrainStallMarker(file);
        assertFalse(marker.present());

        marker.stalledInTerrainDraw("the first frames after the world appeared");
        assertTrue(marker.present());
        assertTrue(Files.readString(file).contains("the first frames after the world appeared"));
        // A second freeze replaces the note; nothing half-written is left beside it.
        marker.stalledInTerrainDraw("first world frame: drawing the level, before the mob shadow map");
        assertTrue(Files.readString(file).contains("before the mob shadow map"));
        try (var files = Files.list(config)) {
            assertEquals(1, files.count());
        }

        marker.launchWentWell();
        assertFalse(marker.present());
        marker.launchWentWell();
        assertFalse(marker.present(), "removing it again is harmless");
    }

    @Test
    void aMissingConfigFolderIsMade(@TempDir Path root) {
        TerrainStallMarker marker = new TerrainStallMarker(root.resolve("config").resolve(TerrainStallMarker.FILE_NAME));
        marker.stalledInTerrainDraw("the first frames after the world appeared");
        assertTrue(marker.present());
    }
}
