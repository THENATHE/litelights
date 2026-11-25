package com.thenathe.litelights.render.feature.farterrain.land;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.thenathe.litelights.api.FarTerrainState;
import com.thenathe.litelights.api.FeatureStates;
import com.thenathe.litelights.api.FrameAccess;
import com.thenathe.litelights.api.FrameBits;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.ModuleEvents;
import com.thenathe.litelights.api.ModuleFeature;
import com.thenathe.litelights.api.ModuleSettings;
import com.thenathe.litelights.api.NearTerrain;
import com.thenathe.litelights.api.RenderPasses;
import com.thenathe.litelights.api.WorldIdentity;
import com.thenathe.litelights.render.feature.farterrain.FarLandSnow;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * LiteLights' own far terrain (docs/FAR-TERRAIN.md): the land a player has visited, drawn far past
 * the render distance. Chunks the client receives are stored per world and per server as tiles at
 * eight levels of detail, meshed on worker threads with the game's own block textures, and drawn
 * inside the level just before translucent terrain, lit and hazed as the near land is.
 *
 * <p>Off by default. While off nothing runs: no thread, no file, no video memory, no draw, no
 * frame bit; the chunk and block listeners return at once. Turning it off lets go of everything.
 */
public final class FarLand implements FarTerrainState {
    private static final FarLand INSTANCE = new FarLand();
    /** The far plane reaches a little past the far land, so its last tiles are never clipped. */
    private static final float FAR_PLANE_MARGIN = 512.0f;

    private @Nullable FarLandSettings settings;
    private @Nullable SeedGeneration seedGeneration;
    private final FarLandPass pass = new FarLandPass();
    /** The visual snow's Snow on far terrain. */
    private final FarLandSnow snow = new FarLandSnow();
    private @Nullable FarLandSession session;
    private boolean drawn;
    private boolean clearRequested;
    private long sessionsOpened;
    private long passes;
    private @Nullable String lastProblem;

    private FarLand() {
    }

    public static FarLand get() {
        return INSTANCE;
    }

    /** Declares the rows and listens to the world. Called once, as the far terrain module starts. */
    public static void register() {
        FarLand land = INSTANCE;
        land.settings = FarLandSettings.declare();
        land.seedGeneration = new SeedGeneration(land.settings);
        FeatureStates features = LiteLightsApi.features();
        features.claimPipelines(ModuleFeature.FAR_TERRAIN, FarLandPass.OPEN, FarLandPass.SEAM, FarLandPass.CARDS, FarLandPass.WATER, FarLandPass.WATER_PLAIN,
            FarLandPass.LIGHTS, FarLandPass.OPEN_SNOW, FarLandPass.SEAM_SNOW, FarLandPass.CARDS_SNOW);
        LiteLightsApi.provideFarTerrain(land);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            FarLandSession open = land.session;
            if (open != null) {
                open.chunkLoaded(chunk.getPos().x(), chunk.getPos().z());
            }
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            FarLandSession open = land.session;
            if (open != null) {
                open.chunkUnloading(level, chunk);
            }
        });
        ModuleEvents.BLOCK_CHANGED.register((level, pos, oldState, newState) -> {
            FarLandSession open = land.session;
            if (open != null) {
                open.blockChanged(pos.getX() >> 4, pos.getZ() >> 4);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FarLandSession open = land.session;
            if (open != null && client.level != null) {
                open.tick(client.level);
            }
        });
        ModuleEvents.LEVEL_CHANGED.register(level -> land.closeSession());
        ModuleEvents.WORLD_LEFT.register(land::closeSession);
        ModuleEvents.BLOCK_ATLAS_STITCHED.register(atlas -> {
            FarLandSession open = land.session;
            if (open != null) {
                open.atlasStitched();
            }
        });
        ModuleEvents.AFTER_SCENE_COPY.register(new ModuleEvents.AfterSceneCopy() {
            @Override
            public boolean wantsPassAfterSceneCopy() {
                return land.wantsPassOverScene();
            }

            @Override
            public boolean wantsSceneCopy() {
                // The Plain look needs no copy; where near water takes one anyway, its hand-over uses it.
                return land.settings.waterLook.get() == FarLandSettings.WaterLook.FULL;
            }

            @Override
            public void afterSceneCopy(RenderTarget mainTarget) {
                land.drawOverScene(mainTarget);
            }
        });
        // LiteLights' seed helper on a server: its reply, asked for by seed generation.
        SeedHelperClient.register(reply -> {
            if (land.seedGeneration != null) {
                land.seedGeneration.helperReplied(reply);
            }
        });
        // The game's own /seed reply on a server, read only while the player is waiting for one.
        ClientSendMessageEvents.COMMAND.register(command -> {
            if (land.session != null && land.seedGeneration != null) {
                land.seedGeneration.commandSent(command);
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && land.session != null && land.seedGeneration != null) {
                land.seedGeneration.messageReceived(message);
            }
        });
        LiteLightsApi.rows().contribute(ModuleSettings.FAR_TERRAIN_PAGE, land::statusRows);
    }

    public FarLandSettings settings() {
        return settings;
    }

    /**
     * Once a frame, before the LiteFrame block is uploaded: opens or closes the world's far terrain
     * as the switch and the level say, and picks the frame's draws.
     */
    public void frameUpdate(FrameAccess frame) {
        drawn = false;
        if (LiteLightsApi.passes().abFlipped("farland.absent")) {
            // A developer's A/B run timing the module's off path against no path at all.
            return;
        }
        FeatureStates features = LiteLightsApi.features();
        ClientLevel level = Minecraft.getInstance().level;
        if (settings == null || !settings.enabled.on() || level == null || !features.mayRun(ModuleFeature.FAR_TERRAIN)) {
            closeSession();
            return;
        }
        try {
            WorldIdentity world = LiteLightsApi.world().current();
            if (world == null) {
                closeSession();
                return;
            }
            if (clearRequested) {
                clearRequested = false;
                if (session != null) {
                    session.deleteOnClose();
                }
                closeSession();
            }
            if (session != null && (!session.dimension().equals(world.dimension()) || !session.world().key().equals(world.key()))) {
                closeSession();
            }
            NearTerrain near = LiteLightsApi.nearTerrain();
            if (session == null) {
                session = new FarLandSession(world, level, settings.meshThreads());
                sessionsOpened++;
                var camera = frame.camera();
                session.noticeLoadedChunks(level, (int) Math.floor(camera.x()) >> 4, (int) Math.floor(camera.z()) >> 4,
                    near.renderDistanceChunks() + 2);
            }
            long started = System.nanoTime();
            drawn = session.frame(frame.camera(), near, settings, level);
            try {
                seedGeneration.frame(session, level, frame.camera());
            } catch (RuntimeException failure) {
                // Seed generation going wrong never takes the visited land with it.
                LiteLightsMod.LOG.error("Seed generation failed; far terrain carries on with visited land", failure);
                seedGeneration.off();
            }
            LiteLightsApi.passes().addCpuTiming("farland.cpu", (System.nanoTime() - started) / 1.0e6);
            if (LiteLightsApi.passes().abFlipped("farland.hide")) {
                drawn = false;
            }
            if (drawn) {
                frame.addFeatureFlags(FrameBits.FAR_TERRAIN);
            }
            snow.frame(level, frame.camera(), drawn);
            String problem = session.problem();
            if (problem != null && !problem.equals(lastProblem)) {
                lastProblem = problem;
                features.failed(ModuleFeature.FAR_TERRAIN, new IllegalStateException(problem));
            }
        } catch (RuntimeException failure) {
            features.failed(ModuleFeature.FAR_TERRAIN, failure);
            closeSession();
        }
    }

    /** Whether far land is drawn this frame. */
    public boolean wantsPass() {
        return drawn && session != null;
    }

    /** Draws the far land into the main target, just before translucent terrain. */
    public void draw(RenderTarget mainTarget) {
        FarLandSession open = session;
        if (!drawn || open == null) {
            return;
        }
        RenderPasses timings = LiteLightsApi.passes();
        try {
            timings.beginTiming(RenderPasses.TimedSection.FAR_LAND);
            pass.useSnow(snow);
            open.draw(mainTarget, pass, LiteLightsApi.frame().camera(), LiteLightsApi.nearTerrain());
            timings.endTiming(RenderPasses.TimedSection.FAR_LAND);
            passes++;
            LiteLightsApi.features().succeeded(ModuleFeature.FAR_TERRAIN);
        } catch (RuntimeException failure) {
            LiteLightsApi.features().failed(ModuleFeature.FAR_TERRAIN, failure);
            drawn = false;
        }
    }

    /** Whether far water or lights are drawn this frame, over water's scene copy. */
    public boolean wantsPassOverScene() {
        FarLandSession open = session;
        return drawn && open != null && open.drawsOverScene();
    }

    /** Draws the far water over the copied scene, then the lights, just before translucent terrain. */
    public void drawOverScene(RenderTarget mainTarget) {
        FarLandSession open = session;
        if (!drawn || open == null) {
            return;
        }
        // Not timed as a section of its own: FAR_LAND's timestamps belong to the opaque draw, and
        // the A/B tool's GPU frame time covers both.
        try {
            boolean plain = settings != null && settings.waterLook.get() == FarLandSettings.WaterLook.PLAIN;
            open.drawOverScene(mainTarget, pass, LiteLightsApi.frame().camera(), LiteLightsApi.nearTerrain(), plain);
        } catch (RuntimeException failure) {
            LiteLightsApi.features().failed(ModuleFeature.FAR_TERRAIN, failure);
        }
    }

    /** The level's far plane: out past the far land while it is on. */
    public float farPlane(float vanillaFarPlane) {
        if (session == null || settings == null || !settings.enabled.on()) {
            return vanillaFarPlane;
        }
        return Math.max(vanillaFarPlane, settings.distance.get().blocks + FAR_PLANE_MARGIN);
    }

    @Override
    public boolean farTerrainDrawn() {
        return drawn;
    }

    @Override
    public float farDistance() {
        return drawn && settings != null ? settings.distance.get().blocks : 0.0f;
    }

    /** Writes everything to disk, stops the threads and frees the video memory. */
    public void closeSession() {
        FarLandSession open = session;
        if (open == null) {
            return;
        }
        session = null;
        drawn = false;
        if (seedGeneration != null) {
            seedGeneration.sessionClosed();
        }
        snow.close();
        try {
            open.close();
        } catch (RuntimeException failure) {
            LiteLightsMod.LOG.error("Couldn't close far terrain cleanly", failure);
        }
        pass.close();
    }

    /**
     * The status rows and Clear button on the Far Terrain page. The status is asked for each time
     * the page is drawn, so it follows the switch, the land coming in and a Clear while the page
     * is open.
     */
    private void statusRows(ModuleSettings.PageRows rows) {
        rows.section("far_land");
        rows.status(Component.translatable("litelights.option.far_terrain.stored"),
            Component.translatable("litelights.option.far_terrain.stored.description"), this::storedStatus);
        rows.status(Component.translatable("litelights.option.far_terrain.video_in_use"),
            Component.translatable("litelights.option.far_terrain.video_in_use.description"), this::videoStatus);
        rows.confirmedAction(Component.translatable("litelights.option.far_terrain.clear"),
            Component.translatable("litelights.option.far_terrain.clear.description"),
            Component.translatable("litelights.option.far_terrain.clear.button"),
            () -> clearRequested = session != null);
        rows.section(FarLandSettings.SEED_SECTION);
        rows.status(Component.translatable("litelights.option.far_terrain.predicted"),
            Component.translatable("litelights.option.far_terrain.predicted.description"), this::predictedStatus);
        rows.status(Component.translatable("litelights.option.far_terrain.seed"),
            Component.translatable("litelights.option.far_terrain.seed.description"), this::seedStatus);
        rows.action(Component.translatable("litelights.option.far_terrain.seed_entry"),
            Component.translatable("litelights.option.far_terrain.seed_entry.description"),
            Component.translatable("litelights.option.far_terrain.seed_entry.button"),
            this::openSeedEntry);
        rows.confirmedAction(Component.translatable("litelights.option.far_terrain.clear_predicted"),
            Component.translatable("litelights.option.far_terrain.clear_predicted.description"),
            Component.translatable("litelights.option.far_terrain.clear.button"),
            () -> {
                if (seedGeneration != null && session != null) {
                    seedGeneration.clear();
                }
            });
    }

    /** The Far Terrain page's Server seed button: the screen to type this server's seed in. */
    public void openSeedEntry() {
        if (seedGeneration != null) {
            seedGeneration.openSeedEntry();
        }
    }

    private Component predictedStatus() {
        return seedGeneration == null || session == null ? Component.translatable("litelights.option.far_terrain.predicted.off")
            : seedGeneration.predictedStatus();
    }

    private Component seedStatus() {
        return seedGeneration == null ? Component.empty() : seedGeneration.seedStatus();
    }

    private Component storedStatus() {
        FarLandSession open = session;
        if (open == null) {
            return Component.translatable(settings != null && settings.enabled.on() ? "litelights.option.far_terrain.stored.waiting"
                : "litelights.option.far_terrain.stored.off");
        }
        return Component.translatable("litelights.option.far_terrain.stored.value", open.tiles(), megabytes(open.diskBytes()));
    }

    private Component videoStatus() {
        FarLandSession open = session;
        return Component.translatable("litelights.option.far_terrain.video_in_use.value", megabytes(open == null ? 0 : open.videoMemoryBytes()));
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    /** For QA: what far terrain is doing. */
    public Map<String, Object> describe() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", settings != null && settings.enabled.on());
        state.put("session", session != null);
        state.put("sessionsOpened", sessionsOpened);
        state.put("drawn", drawn);
        state.put("farSnow", snow.active() + ": " + snow.describe());
        state.put("passes", passes);
        state.put("threads", Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().startsWith("LiteLights far terrain")).count());
        if (session != null) {
            state.putAll(session.describe());
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                state.put("seamPicture", session.seamPicture(LiteLightsApi.nearTerrain(), level, LiteLightsApi.frame().camera()));
            }
        }
        if (seedGeneration != null) {
            state.put("seedGeneration", seedGeneration.describe());
        }
        return state;
    }
}
