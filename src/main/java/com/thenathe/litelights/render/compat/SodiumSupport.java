package com.thenathe.litelights.render.compat;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.render.api.RenderFeature;
import com.thenathe.litelights.render.api.RenderFeatureStatus;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/**
 * Sodium support. LiteLights never touches Sodium's code: it swaps the shader Sodium compiles for
 * its chunks and binds its uniforms on the passes Sodium draws into, both through Minecraft's
 * render pipeline API. That still depends on Sodium's shader contract (its vertex include, uniform
 * names and push constants), so it is enabled only for Sodium versions it was checked against and
 * only when Sodium's chunk pipeline looks as expected.
 */
public final class SodiumSupport {
    /** 0.9.2 (latest stable) and the 0.9.3 pre-releases were checked; see docs/DEPENDENCIES.md. */
    private static final String SUPPORTED_RANGE = ">=0.9.2 <0.10.0-alpha";

    /** Sodium moved the camera offset into its globals block in this pre-release. */
    private static final String CAMERA_OFFSET_IN_GLOBALS = ">=0.9.3-beta.5";

    private static volatile boolean pipelineMismatch;

    private SodiumSupport() {
    }

    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded("sodium");
    }

    public static Optional<Version> version() {
        return FabricLoader.getInstance().getModContainer("sodium").map(ModContainer::getMetadata).map(meta -> meta.getVersion());
    }

    public static boolean isSupportedVersion() {
        return matches(SUPPORTED_RANGE);
    }

    /** Whether Sodium's chunk shader subtracts u_CameraOffset itself (the shader must do the same). */
    public static boolean hasCameraOffsetInGlobals() {
        return matches(CAMERA_OFFSET_IN_GLOBALS);
    }

    private static boolean matches(String range) {
        Optional<Version> version = version();
        if (version.isEmpty()) {
            return false;
        }
        try {
            return VersionPredicate.parse(range).test(version.get());
        } catch (VersionParsingException e) {
            return false;
        }
    }

    /** Whether Sodium's chunk shader should be swapped for LiteLights' version. */
    public static boolean shaderOverrideEnabled() {
        return isLoaded() && isSupportedVersion() && !pipelineMismatch;
    }

    /** Called when Sodium's chunk pipeline doesn't declare what LiteLights' shader needs. */
    public static void reportPipelineMismatch(String detail) {
        if (!pipelineMismatch) {
            pipelineMismatch = true;
            LiteLightsClient.LOG.warn("Sodium's chunk pipeline changed ({}); terrain stays unshaded under Sodium", detail);
            RenderFeatureStatus.markUnsupported(RenderFeature.SODIUM_TERRAIN,
                "Sodium " + version().map(Version::getFriendlyString).orElse("?") + " changed its chunk shader; terrain shading is off");
        }
    }

    /** Logs and reports the Sodium state once at start-up. */
    public static void reportStartupState() {
        if (!isLoaded()) {
            return;
        }
        String friendly = version().map(Version::getFriendlyString).orElse("?");
        if (isSupportedVersion()) {
            LiteLightsClient.LOG.info("Sodium {} found; LiteLights shades Sodium's terrain", friendly);
        } else {
            LiteLightsClient.LOG.warn("Sodium {} is outside the checked range {}; terrain stays unshaded", friendly, SUPPORTED_RANGE);
            RenderFeatureStatus.markUnsupported(RenderFeature.SODIUM_TERRAIN,
                "Sodium " + friendly + " isn't supported yet; terrain shading is off");
        }
    }
}
