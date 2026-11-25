package com.thenathe.litelights.gui;

import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.config.PageContent;
import com.thenathe.litelights.config.RowState;
import com.thenathe.litelights.overlays.sodium.QuadFacingFix;
import com.thenathe.litelights.render.compat.NoOitRenderTypes;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/**
 * General's Compatibility section: one notice per temporary fix for a bug between other mods.
 * The fixes have no switch. Each runs wherever it applies, and its notice says whether it is
 * active, or why not.
 */
final class CompatibilityNotices {
    static final String WAKES = "wakes";

    /** What a notice shows at its right. */
    enum Shown {
        ACTIVE("litelights.compat.active"),
        NOT_NEEDED("litelights.compat.not_needed"),
        SWITCHED_OFF("litelights.compat.switched_off");

        private final String key;

        Shown(String key) {
            this.key = key;
        }
    }

    /** A fix's status, and the translation key of the reason under the notice (null while active). */
    record Status(Shown shown, String reasonKey) {
        static final Status ACTIVE = new Status(Shown.ACTIVE, null);

        RowState rowState() {
            return reasonKey == null ? RowState.READY : new RowState.Unavailable(reasonKey);
        }
    }

    private CompatibilityNotices() {
    }

    static void contribute(PageContent page) {
        page.section("compatibility")
                .row(notice("facing_fix", () -> facingFix(QuadFacingFix.status())))
                .row(notice("wakes_fix", () -> wakesFix(BuiltInPages.installed(WAKES), NoOitRenderTypes.switchedOff())));
    }

    private static CustomRow notice(String fix, Supplier<Status> status) {
        String key = "litelights.compat." + fix;
        return CustomRow.notice(Component.translatable(key), Component.translatable(key + ".description"),
                        () -> Component.translatable(status.get().shown().key))
                .tag(key + ".tag")
                .state(() -> status.get().rowState());
    }

    /**
     * The face culling fix for Continuity's overlays under Sodium 0.9.2 (see QuadFacingFix): active
     * once the mixin is in. A plugin that never decided counts as an unknown Sodium.
     */
    static Status facingFix(QuadFacingFix.Status fix) {
        return switch (fix) {
            case APPLIED -> Status.ACTIVE;
            case NO_SODIUM, NO_CONTINUITY, FIXED_IN_SODIUM -> facingReason(Shown.NOT_NEEDED, fix);
            case UNKNOWN_SODIUM, NOT_CHECKED -> facingReason(Shown.SWITCHED_OFF, QuadFacingFix.Status.UNKNOWN_SODIUM);
            case FAILED -> facingReason(Shown.SWITCHED_OFF, fix);
        };
    }

    private static Status facingReason(Shown shown, QuadFacingFix.Status fix) {
        return new Status(shown, "litelights.compat.facing_fix." + fix.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The draw of render types without Improved Transparency pipelines after the OIT composite (see
     * NoOitRenderTypes). It is always armed; it matters while Wakes is installed, and a failure
     * switches it off for the session whichever mod's render type caused it.
     */
    static Status wakesFix(boolean wakesInstalled, boolean switchedOff) {
        if (switchedOff) {
            return new Status(Shown.SWITCHED_OFF, "litelights.compat.wakes_fix.failed");
        }
        return wakesInstalled ? Status.ACTIVE : new Status(Shown.NOT_NEEDED, "litelights.compat.wakes_fix.no_wakes");
    }
}
