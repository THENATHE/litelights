package com.thenathe.litelights;

import com.thenathe.litelights.api.LiteLightsMod;
import com.thenathe.litelights.api.ModuleEvents;
import com.thenathe.litelights.gui.SettingsClient;
import com.thenathe.litelights.gui.TerrainStallOffer;
import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.render.LiteLightsRenderer;
import com.thenathe.litelights.render.feature.clouds.DynamicClouds;
import com.thenathe.litelights.render.api.LiteRenderEvents;
import com.thenathe.litelights.render.feature.lightning.CloudLightning;
import com.thenathe.litelights.render.feature.lightpost.LightPost;
import com.thenathe.litelights.render.feature.particles.ParticleSystem;
import com.thenathe.litelights.render.feature.climate.LocalClimate;
import com.thenathe.litelights.render.feature.skyfog.SkyFogFeature;
import com.thenathe.litelights.render.feature.vegetation.VegetationModule;
import com.thenathe.litelights.render.feature.water.WaterRenderer;
import com.thenathe.litelights.render.feature.weather.GlassWeather;
import com.thenathe.litelights.render.feature.weather.Puddles;
import com.thenathe.litelights.render.feature.weather.SnowyLeaves;
import com.thenathe.litelights.render.feature.weather.snow.VisualSnow;
import com.thenathe.litelights.render.frame.DimensionGate;
import com.thenathe.litelights.render.frame.FrameClock;
import com.thenathe.litelights.render.modules.ModuleHost;
import com.thenathe.litelights.render.post.LitePostChain;
import com.thenathe.litelights.render.pipeline.ReloadDefines;
import com.thenathe.litelights.render.pipeline.ShaderLoopCaps;
import com.thenathe.litelights.render.pipeline.TerrainParts;
import com.thenathe.litelights.render.startup.StartupTrace;
import com.thenathe.litelights.render.timing.AbTiming;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class LiteLightsClient implements ClientModInitializer {
    public static final String MOD_ID = LiteLightsMod.MOD_ID;
    public static final Logger LOG = LiteLightsMod.LOG;

    private static boolean wasInWorld;
    private static @Nullable ClientLevel lastLevel;

    /** An id in LiteLights' namespace. */
    public static Identifier id(String path) {
        return LiteLightsMod.id(path);
    }

    @Override
    public void onInitializeClient() {
        LOG.info("Starting");
        ModuleHost.installApi();
        // The order the streams' own entrypoints used to run in, so listeners keep their order.
        LiteLightsRenderer.get().registerEvents();
        LocalClimate.register();
        OverlaysModule.initialize();
        ParticleSystem.get().register();
        VegetationModule.initialize();
        SettingsClient.initialize();
        ShaderLoopCaps.register();
        TerrainParts.register();
        TerrainStallOffer.register();
        ReloadDefines.registerEvents();
        LitePostChain.install();
        LightPost.get().register();
        WaterRenderer.get().register();
        GlassWeather.get().register();
        Puddles.get().register();
        SnowyLeaves.get().register();
        VisualSnow.register();
        SkyFogFeature.register();
        // After sky-fog: the in-cloud fog joins the storm fog sky-fog has just worked out.
        DynamicClouds.register();
        // The modules (far terrain) start last, where far terrain always registered.
        ModuleHost.startModules();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> StartupTrace.stopWatching());
        // A developer's A/B timing run puts its option back, so nothing saved later keeps side B.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AbTiming.stop());
        // Not Fabric's DISCONNECT event: leaving a singleplayer world fires it on the network thread,
        // and a task handed to the render thread from there is dropped by Minecraft.disconnect.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean inWorld = client.level != null;
            if (wasInWorld && !inWorld) {
                onWorldLeft();
            }
            wasInWorld = inWorld;
        });
        // Joining a world and changing dimension both bring a new level object. Checked at the start
        // of the tick and before each frame, so a module hears of the new level before it draws.
        ClientTickEvents.START_CLIENT_TICK.register(client -> noticeLevel(client.level));
        LiteRenderEvents.FRAME_UPDATE.register((frame, settings) -> noticeLevel(Minecraft.getInstance().level));
    }

    private static void noticeLevel(@Nullable ClientLevel level) {
        if (level == lastLevel) {
            return;
        }
        lastLevel = level;
        if (level != null) {
            try {
                ModuleEvents.LEVEL_CHANGED.invoker().onLevelChanged(level);
            } catch (RuntimeException failure) {
                LOG.error("A module couldn't follow the change of level", failure);
            }
        }
    }

    /** Lets go of the world the player left, and of the GPU memory that only mattered there. */
    private static void onWorldLeft() {
        StartupTrace.stopWatching();
        try {
            DimensionGate.onWorldLeft();
            FrameClock.onWorldLeft();
            SkyFogFeature.get().onWorldLeft();
            CloudLightning.get().onWorldLeft();
            WaterRenderer.get().onWorldLeft();
            GlassWeather.get().onWorldLeft();
            Puddles.get().onWorldLeft();
            SnowyLeaves.get().onWorldLeft();
            VisualSnow.onWorldLeft();
            LightPost.get().onWorldLeft();
            ModuleEvents.WORLD_LEFT.invoker().onWorldLeft();
            ParticleSystem.get().onWorldLeft();
            LiteLightsRenderer.get().onWorldLeft();
        } catch (RuntimeException failure) {
            LOG.error("Couldn't release the world that was left", failure);
        }
    }
}
