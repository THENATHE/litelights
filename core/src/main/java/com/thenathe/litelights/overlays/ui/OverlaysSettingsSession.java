package com.thenathe.litelights.overlays.ui;

import com.thenathe.litelights.gui.SettingsScreen;
import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;

/**
 * One visit to the LiteLights settings, which can pass through the hierarchy editor and the block
 * picker and back. When the player leaves all of them, the overlay settings are saved and, if
 * anything that affects the overlays changed, the overlays rebuild once with a resource reload.
 *
 * <p>The settings screen gives no close callback to modules, so the visit is followed from the
 * screen that is open at the end of each client tick. Opening a sub-screen replaces the settings
 * screen for a moment, which is why the check waits for a tick instead of reacting to the screen
 * being removed.
 */
final class OverlaysSettingsSession {
    private static boolean inSettings;
    private static String fingerprint;

    private OverlaysSettingsSession() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(OverlaysSettingsSession::onTick);
    }

    private static void onTick(Minecraft minecraft) {
        boolean open = isSettingsScreen(minecraft.gui.screen());
        if (open && !inSettings) {
            fingerprint = OverlayConfig.get().generationFingerprint();
        } else if (!open && inSettings) {
            finish();
        }
        inSettings = open;
    }

    private static boolean isSettingsScreen(Screen screen) {
        return screen instanceof SettingsScreen || screen instanceof HierarchyEditorScreen || screen instanceof BlockPickerScreen;
    }

    /** Always saves (a renamed group changes no overlay), and rebuilds only if the overlays would change. */
    private static void finish() {
        OverlayConfig.save();
        String now = OverlayConfig.get().generationFingerprint();
        if (!now.equals(fingerprint) && OverlaysModule.active()) {
            rebuild();
        }
        fingerprint = now;
    }

    /** Saves the settings and rebuilds the overlays straight away. */
    static void rebuildNow() {
        OverlayConfig.save();
        fingerprint = OverlayConfig.get().generationFingerprint();
        rebuild();
    }

    private static void rebuild() {
        Minecraft minecraft = Minecraft.getInstance();
        SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            OverlaysPage.text("toast.rebuilding"), null);
        minecraft.reloadResourcePacks();
    }
}
