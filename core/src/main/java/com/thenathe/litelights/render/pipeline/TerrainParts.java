package com.thenathe.litelights.render.pipeline;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.BoolOption;
import com.thenathe.litelights.config.LiteLightsOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Mac troubleshooting (General > Compatibility): switches that compile parts of LiteLights' terrain
 * and block-model shaders out, so a player whose Mac freezes in the first terrain draw can find the
 * part Metal can't build by turning them off one at a time (DECISIONS, diag097). Each part is a
 * {@link ReloadDefines} switch in shaders/include/terrain_parts.glsl, fixed when the shaders compile;
 * "Use vanilla terrain shading" leaves out the master define, and the shaders then compile their
 * vanilla main and none of LiteLights' includes.
 *
 * <p>The Mac safety net ({@code TerrainStallOffer}) can turn vanilla terrain shading on for one
 * session without touching the saved setting.
 */
public final class TerrainParts {
    public static final Identifier INCLUDE = LiteLightsClient.id("terrain_parts.glsl");
    /** Defined unless "Use vanilla terrain shading" is on. */
    public static final String LITELIGHTS_SHADING = "LITE_TERRAIN_LITELIGHTS";

    /** The parts, in the order of their rows; {@code name} is the word the log uses. */
    public enum Part {
        WATER("LITE_TERRAIN_WATER", "water", LiteLightsOptions.TERRAIN_WATER),
        CAUSTICS("LITE_TERRAIN_CAUSTICS", "caustics", LiteLightsOptions.TERRAIN_CAUSTICS),
        GLASS("LITE_TERRAIN_GLASS", "glass weather", LiteLightsOptions.TERRAIN_GLASS),
        SHADOWS("LITE_TERRAIN_SHADOWS", "shadows", LiteLightsOptions.TERRAIN_SHADOWS),
        LIGHT("LITE_TERRAIN_LIGHT", "light extras", LiteLightsOptions.TERRAIN_LIGHT),
        FOG("LITE_TERRAIN_FOG", "fog", LiteLightsOptions.TERRAIN_FOG),
        SWAY("LITE_TERRAIN_SWAY", "sway", LiteLightsOptions.TERRAIN_SWAY);

        private final String define;
        private final String logName;
        private final BoolOption option;

        Part(String define, String logName, BoolOption option) {
            this.define = define;
            this.logName = logName;
            this.option = option;
        }

        public String define() {
            return define;
        }

        public String logName() {
            return logName;
        }

        public BoolOption option() {
            return option;
        }
    }

    private static volatile boolean vanillaShadingThisSession;

    private TerrainParts() {
    }

    public static void register() {
        // The Mac troubleshooting rows are hidden and no longer read (1.0.4): every part is always in,
        // and vanilla terrain shading only comes from the safety net's offer, for one session.
        ReloadDefines.register(INCLUDE, LITELIGHTS_SHADING, () -> !vanillaShading());
        for (Part part : Part.values()) {
            ReloadDefines.register(INCLUDE, part.define(), () -> true);
        }
    }

    /** Vanilla terrain shading: only for this session, when the safety net's offer was taken. */
    public static boolean vanillaShading() {
        return vanillaShadingThisSession;
    }

    public static boolean vanillaShadingThisSession() {
        return vanillaShadingThisSession;
    }

    /**
     * The safety net's offer was taken: vanilla terrain shading until the game closes, with the
     * saved setting left as it is. Called from the offer's screen, so the shaders reload once it closes.
     */
    public static void useVanillaShadingThisSession() {
        vanillaShadingThisSession = true;
        ReloadDefines.changed();
    }

    /** For the "shaders ready" log line: what the shaders in use left out, or null before the include was read. */
    public static @Nullable String builtState() {
        String state = describe(ReloadDefines.built(LITELIGHTS_SHADING), part -> ReloadDefines.built(part.define()));
        if (Boolean.FALSE.equals(ReloadDefines.built(LITELIGHTS_SHADING)) && vanillaShadingThisSession) {
            state += ", for this session only (offered after a launch froze in a terrain draw)";
        }
        return state;
    }

    /**
     * "all terrain parts in", "terrain parts compiled out: water, sway", or "vanilla terrain shading
     * (every terrain part compiled out)". A part whose state isn't known counts as in, as the include
     * reads with it in.
     */
    static @Nullable String describe(@Nullable Boolean liteLightsShading, Function<Part, @Nullable Boolean> built) {
        if (liteLightsShading == null) {
            return null;
        }
        if (!liteLightsShading) {
            return "vanilla terrain shading (every terrain part compiled out)";
        }
        List<String> out = new ArrayList<>();
        for (Part part : Part.values()) {
            if (Boolean.FALSE.equals(built.apply(part))) {
                out.add(part.logName());
            }
        }
        return out.isEmpty() ? "all terrain parts in" : "terrain parts compiled out: " + String.join(", ", out);
    }
}
