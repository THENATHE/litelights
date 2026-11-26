package com.thenathe.litelights.render.feature.farterrain;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.thenathe.litelights.api.CameraView;
import com.thenathe.litelights.api.LiteLightsApi;
import com.thenathe.litelights.api.VisualSnowSettings;
import com.thenathe.litelights.render.feature.farterrain.land.FarLand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The visual snow's Snow on far terrain: far land shows the season's snow as Almanac has it now,
 * rather than the snow it had when it was last seen, in layers by the same shares as the near snow
 * and at the same columns' points, and thaws with it (include/farterrain/far_snow.glsl, in the far
 * land's snow pipelines). It reuses far snow's data side ({@link FarSnow}): Almanac's far snow lines
 * and bare lines around the camera, now with each column's climate, filled in the background. Under
 * Almanac's regional weather the shares are each place's (FarSnowPlaces), since the snow lies where
 * its storms brought it, and the near land that replaces far land has the same; without regional
 * weather, those where the player stands, as the near snow's.
 *
 * <p>Only with Almanac 1.0.0 or later, in the overworld, while far land is drawn and the row is on
 * (far terrain's own since far-snow-115, whatever the Visual snow switch);
 * otherwise nothing here is made or bound, and the far land draws its plain pipelines. Without
 * Almanac far land shows the snow it was seen with, which is vanilla's own.
 */
public final class FarLandSnow implements AutoCloseable {
    private @Nullable FarSnow snow;
    private boolean active;

    /** Once a frame, after the far land's own frame: whether the snow pipelines draw this frame. */
    public void frame(ClientLevel level, CameraView camera, boolean farLandDrawn) {
        VisualSnowSettings settings = LiteLightsApi.settings().visualSnow();
        active = false;
        if (!farLandDrawn || !FarLand.get().settings().farSnow.on() || level.dimension() != Level.OVERWORLD) {
            if (snow != null) {
                snow.leaveLevel();
            }
            return;
        }
        if (snow == null) {
            snow = FarSnow.create();
        }
        if (!snow.available()) {
            return;
        }
        snow.setLook(settings.leafTops(), settings.grass());
        Minecraft client = Minecraft.getInstance();
        snow.setReach(FarLand.get().farDistance());
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        active = snow.prepare(level, camera.x(), camera.z(), level.getGameTime(), partialTick);
    }

    /** Whether the far land's snow pipelines are used this frame. */
    public boolean active() {
        return active && snow != null;
    }

    /** Binds the snow's lines, colours and uniforms on a far land pass using a snow pipeline. */
    public void bind(RenderPass pass) {
        if (snow != null) {
            snow.bind(pass);
        }
    }

    /** For QA: what the far snow knows. */
    public String describe() {
        return snow == null ? "not made" : snow.describe();
    }

    @Override
    public void close() {
        active = false;
        if (snow != null) {
            snow.close();
            snow = null;
        }
    }
}
