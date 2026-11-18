package com.thenathe.litelights.gui;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.startup.TerrainStallMarker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Opens {@link TerrainStallOfferScreen} once, on the first title screen after the loading screen,
 * when the last launch left {@link TerrainStallMarker}. Without the marker it does nothing at all.
 */
public final class TerrainStallOffer {
    private static boolean pending;

    private TerrainStallOffer() {
    }

    /** Called once from the client entrypoint. */
    public static void register() {
        if (!TerrainStallMarker.get().present()) {
            return;
        }
        pending = true;
        LiteLightsClient.LOG.info("LiteLights: the last launch froze while drawing the world's blocks; "
            + "the title screen offers vanilla terrain shading for this session");
        ClientTickEvents.END_CLIENT_TICK.register(TerrainStallOffer::onTick);
    }

    private static void onTick(Minecraft client) {
        if (pending && client.gui.screen() instanceof TitleScreen title && client.gui.overlay() == null) {
            pending = false;
            client.gui.setScreen(new TerrainStallOfferScreen(title));
        }
    }
}
