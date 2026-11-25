package com.thenathe.litelights.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.DimensionOptions;
import com.thenathe.litelights.config.FirstSetup;
import com.thenathe.litelights.config.LiteLightsConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Loads the settings at client start, adds the (unbound) key that opens the screen, asks the
 * first-time setup question on a fresh install's title screen, and notices modded dimensions as
 * the player enters them.
 */
public final class SettingsClient {
    private static KeyMapping openKey;
    private static ResourceKey<Level> lastDimension;
    private static boolean setupAsked;

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
        if (!setupAsked && client.gui.screen() instanceof TitleScreen title) {
            setupAsked = true;
            if (firstSetupDue()) {
                client.gui.setScreen(new FirstSetupScreen(title));
            }
        }
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

    /**
     * A fresh install asks once, on the title screen. Fabric's client gametests start without a
     * settings file too, so under them it only asks when a QA run says so.
     */
    private static boolean firstSetupDue() {
        boolean gametest = System.getProperty("fabric.client.gametest") != null;
        return FirstSetup.due() && (!gametest || Boolean.getBoolean("litelights.qa.firstSetup"));
    }
}
