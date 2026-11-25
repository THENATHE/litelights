package com.thenathe.litelights.overlays.continuity;

import com.thenathe.litelights.overlays.OverlaysModule;
import com.thenathe.litelights.overlays.generation.OverlayGenerator;
import net.minecraft.client.Minecraft;

/**
 * Whether the overlays module's own Continuity method can be used, and the switch back to plain
 * overlays if it can't.
 *
 * <p>The method is registered through Continuity's public API the first time a resource reload
 * starts, when every mod has finished starting up. Before that, the API is checked against what
 * the overlays module was built for; if anything is missing (a newer Continuity changed it, say),
 * the method isn't registered and builds write Continuity's plain {@code method=overlay} files
 * instead, as Dynamic Overlays 0.1.0 did. If the method fails later, while reading files or drawing, it's switched off for the
 * rest of the session and the overlays are rebuilt once in the plain format.
 *
 * <p>This class never touches Continuity's classes itself, so it loads even when they've changed.
 */
public final class VarietyMethod {
    private static volatile Boolean registered;
    private static volatile boolean failed;
    private static volatile String problem = "";

    private VarietyMethod() {
    }

    /** Registers the method if that hasn't been tried yet. Called on the client thread as a reload starts. */
    public static synchronized boolean ensureRegistered() {
        if (registered == null) {
            String reason;
            try {
                reason = ContinuityHooks.register();
            } catch (LinkageError | RuntimeException failure) {
                reason = "Continuity's API doesn't match: " + failure;
            }
            registered = reason == null;
            if (reason != null) {
                problem = reason;
                OverlaysModule.LOG.warn("Edge variety is off for this session, overlays use Continuity's plain "
                    + "overlay method instead: {}", reason);
            } else {
                OverlaysModule.LOG.info("Registered the '{}' Continuity method", OverlayGenerator.VARIETY_METHOD);
            }
        }
        return isAvailable();
    }

    public static boolean isAvailable() {
        return Boolean.TRUE.equals(registered) && !failed;
    }

    /** Why the method isn't in use, or an empty string while it is. */
    public static String problem() {
        return problem;
    }

    /**
     * Switches the method off after it went wrong, logs why, and rebuilds the overlays in the plain
     * format. Safe to call from any thread, any number of times; only the first call acts.
     */
    public static void failed(String what, Throwable cause) {
        synchronized (VarietyMethod.class) {
            if (failed) {
                return;
            }
            failed = true;
            problem = what + ": " + cause;
        }
        OverlaysModule.LOG.error("Edge variety failed ({}); rebuilding with Continuity's plain overlay method", what, cause);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(minecraft::reloadResourcePacks);
    }
}
