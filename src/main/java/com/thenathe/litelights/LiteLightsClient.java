package com.thenathe.litelights;

import com.thenathe.litelights.gui.SettingsClient;
import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import com.thenathe.litelights.render.feature.particles.ParticleSystem;
import com.thenathe.litelights.render.feature.farterrain.FarTerrain;
import com.thenathe.litelights.render.feature.skyfog.SkyFogFeature;
import com.thenathe.litelights.render.feature.vegetation.VegetationModule;
import com.thenathe.litelights.render.feature.water.WaterRenderer;
import com.thenathe.litelights.render.feature.weather.GlassWeather;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.post.LitePostChain;
import com.thenathe.litelights.render.pipeline.ReloadDefines;
import com.thenathe.litelights.render.pipeline.ShaderLoopCaps;
import com.thenathe.litelights.render.pipeline.TerrainParts;
import com.thenathe.litelights.render.startup.StartupTrace;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LiteLightsClient implements ClientModInitializer {
    public static final String MOD_ID = "litelights";
    public static final Logger LOG = LoggerFactory.getLogger("LiteLights");

    private static boolean wasInWorld;

    /** An id in LiteLights' namespace. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        LOG.info("Starting");
        // The order the streams' own entrypoints used to run in, so listeners keep their order.
        LiteLightsRenderer.get().registerEvents();
        OverlaysModule.initialize();
        ParticleSystem.get().register();
        VegetationModule.initialize();
        SettingsClient.initialize();
        ShaderLoopCaps.register();
        TerrainParts.register();
        ReloadDefines.registerEvents();
        LitePostChain.install();
        LightPost.get().register();
        WaterRenderer.get().register();
        GlassWeather.get().register();
        SkyFogFeature.register();
        FarTerrain.register();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> StartupTrace.stopWatching());
        // Not Fabric's DISCONNECT event: leaving a singleplayer world fires it on the network thread,
        // and a task handed to the render thread from there is dropped by Minecraft.disconnect.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean inWorld = client.level != null;
            if (wasInWorld && !inWorld) {
                onWorldLeft();
            }
            wasInWorld = inWorld;
        });
    }

    /** Lets go of the world the player left, and of the GPU memory that only mattered there. */
    private static void onWorldLeft() {
        StartupTrace.stopWatching();
        try {
            DimensionGate.onWorldLeft();
            SkyFogFeature.get().onWorldLeft();
            WaterRenderer.get().onWorldLeft();
            FarTerrain.get().onWorldLeft();
            ParticleSystem.get().onWorldLeft();
            LiteLightsRenderer.get().onWorldLeft();
        } catch (RuntimeException failure) {
            LOG.error("Couldn't release the world that was left", failure);
        }
    }
}
