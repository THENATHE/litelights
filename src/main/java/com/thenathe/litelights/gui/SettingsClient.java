package com.thenathe.litelights.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.DimensionOptions;
import com.thenathe.litelights.config.LiteLightsConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Loads the settings at client start, adds the (unbound) key that opens the screen, and notices
 * modded dimensions as the player enters them.
 */
public final class SettingsClient {
    private static KeyMapping openKey;
    private static ResourceKey<Level> lastDimension;

    private SettingsClient() {
    }

    /** Called once from the client entrypoint. */
    public static void initialize() {
        LiteLightsConfig.init();
        BuiltInPages.register();
        KeyMapping.Category category = KeyMapping.Category.register(LiteLightsClient.id("litelights"));
        openKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.litelights.open_settings", InputConstants.UNKNOWN.getValue(), category));
        ClientTickEvents.END_CLIENT_TICK.register(SettingsClient::onTick);
    }

    private static void onTick(Minecraft client) {
        while (openKey.consumeClick()) {
            if (client.gui.screen() == null) {
                client.gui.setScreen(SettingsScreen.create(null));
            }
        }
        if (client.level != null) {
            // Resource keys are interned, so comparing them is cheap; the id string is only made on a change.
            ResourceKey<Level> dimension = client.level.dimension();
            if (dimension != lastDimension) {
                lastDimension = dimension;
                DimensionOptions.notice(dimension.identifier().toString());
            }
        } else {
            lastDimension = null;
        }
    }
}
