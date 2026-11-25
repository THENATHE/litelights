package com.thenathe.litelights.overlays;

import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.ui.OverlaysPage;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The overlays module (Dynamic Overlays, merged into LiteLights): generated connected overlay
 * textures, drawn by Continuity.
 *
 * <p>Nothing here is a hard dependency. Whether the module runs is decided once, from the installed
 * mods, the first time anything asks; the mixins call in early (the client pack repository is
 * built during the game's start-up), so the answer can't wait for {@link #initialize()}.
 */
public final class OverlaysModule {
    public static final Logger LOG = LoggerFactory.getLogger("LiteLights/Overlays");

    private static OverlayRenderer.Choice choice;

    private OverlaysModule() {
    }

    /** Called from the LiteLights client entrypoint. */
    public static void initialize() {
        OverlayRenderer.Choice current = choice();
        OverlaysPage.register();
        if (current.active()) {
            OverlayConfig.get();
            LOG.info("Overlays drawn by Continuity");
        } else {
            LOG.info("Overlays module is off: {}", switch (current.reason()) {
                case STANDALONE_INSTALLED -> "the standalone Dynamic Overlays mod is installed and draws them instead";
                case NO_CONTINUITY -> "Continuity isn't installed";
                case NONE -> "unknown";
            });
        }
    }

    public static synchronized OverlayRenderer.Choice choice() {
        if (choice == null) {
            FabricLoader loader = FabricLoader.getInstance();
            choice = OverlayRenderer.choose(loader.isModLoaded(OverlayRenderer.CONTINUITY_MOD_ID),
                loader.isModLoaded(OverlayRenderer.STANDALONE_MOD_ID));
        }
        return choice;
    }

    public static boolean active() {
        return choice().active();
    }

    public static OverlayRenderer renderer() {
        return choice().renderer();
    }
}
