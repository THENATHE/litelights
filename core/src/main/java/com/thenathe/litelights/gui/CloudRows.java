package com.thenathe.litelights.gui;

import com.thenathe.litelights.LiteLightsClient;
import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.config.LiteLightsOptions;
import com.thenathe.litelights.config.PageContent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

/**
 * The Sky page's cloud height and cloud distance rows. Neither is a setting of LiteLights' alone:
 * <ul>
 *   <li><b>Cloud distance</b> is vanilla's own option (Sodium's Video Settings shows the same one), so
 *   the row reads and writes it directly.</li>
 *   <li><b>Cloud height</b> is Sodium Extra's when that mod is installed: the row then reads and writes
 *   its setting (through reflection, so nothing of Sodium Extra's is needed to build or run), and both
 *   screens always agree. Without Sodium Extra it is LiteLights' own option,
 *   {@link LiteLightsOptions#CLOUD_HEIGHT}, applied in {@code mixin.skyfog.CloudHooksMixin}.</li>
 * </ul>
 */
public final class CloudRows {
    private static final boolean SODIUM_EXTRA = FabricLoader.getInstance().isModLoaded("sodium-extra");
    private static final int VANILLA_HEIGHT = 192;

    private static boolean sodiumExtraBroken;

    private CloudRows() {
    }

    /** Whether Sodium Extra owns the cloud height (LiteLights' own option is hidden then). */
    public static boolean sodiumExtraOwnsHeight() {
        return SODIUM_EXTRA && !sodiumExtraBroken;
    }

    static void contribute(PageContent page) {
        page.section("clouds");
        if (sodiumExtraOwnsHeight()) {
            page.row(CustomRow.slider(Component.translatable("litelights.option.sky.cloud_height"),
                    Component.translatable("litelights.option.sky.cloud_height.sodium_extra"), VANILLA_HEIGHT,
                    LiteLightsOptions.CLOUD_HEIGHT.min(), LiteLightsOptions.CLOUD_HEIGHT.max(), LiteLightsOptions.CLOUD_HEIGHT.step(),
                    value -> Component.translatable("litelights.value.blocks", value),
                    CloudRows::sodiumExtraHeight, CloudRows::setSodiumExtraHeight)
                .shownWhen(CloudRows::sodiumExtraOwnsHeight));
        }
        OptionInstance<Integer> range = Minecraft.getInstance().options.cloudRange();
        if (range.values() instanceof OptionInstance.IntRange(int min, int max, boolean immediately)) {
            page.row(CustomRow.slider(Component.translatable("litelights.option.sky.cloud_distance"),
                Component.translatable("litelights.option.sky.cloud_distance.description"), Math.min(max, Math.max(min, 128)), min, max, 1,
                value -> Component.translatable("litelights.value.chunks", value),
                range::get, value -> {
                    range.set(value);
                    Minecraft.getInstance().options.save();
                }));
        }
    }

    private static Object sodiumExtraSettings() throws ReflectiveOperationException {
        Method options = Class.forName("me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod").getMethod("options");
        Object all = options.invoke(null);
        return all.getClass().getField("extraSettings").get(all);
    }

    private static int sodiumExtraHeight() {
        try {
            Object extra = sodiumExtraSettings();
            boolean override = extra.getClass().getField("cloudHeightOverride").getBoolean(extra);
            return override ? extra.getClass().getField("cloudHeight").getInt(extra) : VANILLA_HEIGHT;
        } catch (ReflectiveOperationException | RuntimeException error) {
            sodiumExtraChanged(error);
            return VANILLA_HEIGHT;
        }
    }

    private static void setSodiumExtraHeight(int height) {
        try {
            Object extra = sodiumExtraSettings();
            Field override = extra.getClass().getField("cloudHeightOverride");
            override.setBoolean(extra, height != VANILLA_HEIGHT || override.getBoolean(extra));
            extra.getClass().getField("cloudHeight").setInt(extra, height);
            Object all = Class.forName("me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod").getMethod("options").invoke(null);
            all.getClass().getMethod("writeChanges").invoke(all);
        } catch (ReflectiveOperationException | RuntimeException error) {
            sodiumExtraChanged(error);
        }
    }

    /** A Sodium Extra whose settings moved: LiteLights' own cloud height takes over from the next screen. */
    private static void sodiumExtraChanged(Exception error) {
        if (!sodiumExtraBroken) {
            sodiumExtraBroken = true;
            LiteLightsClient.LOG.warn("Couldn't reach Sodium Extra's cloud height; LiteLights' own setting is used instead", error);
        }
    }
}
