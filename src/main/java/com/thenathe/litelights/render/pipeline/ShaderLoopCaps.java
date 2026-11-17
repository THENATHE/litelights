package com.thenathe.litelights.render.pipeline;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.LiteLightsOptions;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Shader loop safety caps (General > Compatibility). Loops in LiteLights' shaders whose count comes
 * from uniform data stop at a fixed number of passes while {@code LITE_LOOP_CAPS} is defined
 * (shaders/include/loop_caps.glsl), so data that arrives corrupted can't keep the GPU looping until
 * the driver gives up. The define is a {@link ReloadDefines} switch: fixed when the shaders compile,
 * so no uniform can turn it off, and a change in the settings reloads resources once they close.
 */
public final class ShaderLoopCaps {
    public static final Identifier INCLUDE = LiteLightsClient.id("loop_caps.glsl");
    public static final String DEFINE = "LITE_LOOP_CAPS";

    private ShaderLoopCaps() {
    }

    public static void register() {
        ReloadDefines.register(INCLUDE, DEFINE, LiteLightsOptions.SHADER_LOOP_CAPS::on);
        LiteLightsOptions.SHADER_LOOP_CAPS.addListener((was, now) -> ReloadDefines.changed());
    }

    /** For the start-up log and QA: "on", "off", or null before the include was read. */
    public static @Nullable String builtState() {
        Boolean built = ReloadDefines.built(DEFINE);
        return built == null ? null : built ? "on" : "off";
    }
}
