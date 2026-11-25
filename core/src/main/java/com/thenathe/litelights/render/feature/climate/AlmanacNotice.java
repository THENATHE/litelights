package com.thenathe.litelights.render.feature.climate;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.api.AlmanacVersions;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Says that an installed Almanac is too old for LiteLights, and that what the two share is off: in
 * the log as the client starts, and in the chat the first time the player is in a world this
 * session, as Almanac says it of a LiteLights too old for it (user, 2026-11-24). Nothing is said
 * without Almanac or beside a new enough one.
 */
final class AlmanacNotice {
    static final String KEY = "litelights.notice.almanac_too_old";

    private static boolean told;

    private AlmanacNotice() {
    }

    static void logAtStart() {
        AlmanacVersion.tooOld().ifPresent(version -> LiteLightsClient.LOG.warn(
            "Almanac {} is older than {}, the first that works with this LiteLights: the weather doesn't follow its "
                + "seasons, far terrain doesn't take its snow and the Dynamic clouds don't draw its storms until Almanac is updated", version, AlmanacVersions.MINIMUM));
    }

    /** Once a tick in a world; speaks once a session. */
    static void inWorld(Minecraft client) {
        if (told) {
            return;
        }
        told = true;
        AlmanacVersion.tooOld().ifPresent(version -> client.gui.hud.getChat().addClientSystemMessage(
            Component.translatable(KEY, version, AlmanacVersions.MINIMUM)));
    }
}
