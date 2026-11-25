package com.thenathe.litelights.render.pipeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Shader switches that are fixed when the shaders compile: {@code #define} lines in LiteLights'
 * includes that are kept or left out as each resource reload reads the include
 * ({@code ShaderManagerMixin} hands every include's source to {@link #loaded}).
 *
 * <p>A pipeline define would be fixed once the pipeline is built, and vanilla builds its pipelines
 * once per game; an include is read again by every reload, and reaches every pipeline that
 * includes it, vanilla's and Sodium's terrain among them. A uniform could do the same at run time,
 * but its data is exactly what a switch like the loop caps must not depend on.
 *
 * <p>The include, as written, has each switch's line, {@code #define NAME} alone on its line, so
 * the shaders read as with the switch on when nothing here runs. A switch changed in a menu reloads
 * resources once the player is back in the game or at the title or pause screen; changes made in
 * code (QA resetting options) wait for the next reload.
 */
public final class ReloadDefines {
    private record Switch(Identifier include, String define, BooleanSupplier on) {
    }

    private static final List<Switch> SWITCHES = new CopyOnWriteArrayList<>();
    /** Each define as the shaders in use were built: on or off, absent before its include was first read. */
    private static final Map<String, Boolean> BUILT = new ConcurrentHashMap<>();
    private static boolean changedInMenus;

    private ReloadDefines() {
    }

    /** A switch: the line {@code #define <define>} of the include, kept while {@code on} says so. */
    public static void register(Identifier include, String define, BooleanSupplier on) {
        SWITCHES.add(new Switch(include, define, on));
    }

    public static void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(ReloadDefines::onTick);
    }

    /** A switch was changed by the player: reload once every menu has closed, if the shaders differ by then. */
    public static void changed() {
        changedInMenus |= Minecraft.getInstance().gui.screen() != null;
    }

    /** An include's source as a reload reads it (on the reload's worker thread), with its switches set. */
    public static String loaded(Identifier include, String source) {
        String result = source;
        for (Switch entry : SWITCHES) {
            if (entry.include().equals(include)) {
                boolean on = entry.on().getAsBoolean();
                BUILT.put(entry.define(), on);
                result = withDefine(result, entry.define(), on);
            }
        }
        return result;
    }

    /** Whether the shaders in use were built with the define, or null before its include was read. */
    public static @Nullable Boolean built(String define) {
        return BUILT.get(define);
    }

    /** The source with the define's line kept (on) or turned into a comment (off). */
    static String withDefine(String source, String define, boolean on) {
        if (on) {
            return source;
        }
        Pattern line = Pattern.compile("(?m)^#define " + Pattern.quote(define) + "[ \\t]*$");
        return line.matcher(source).replaceAll(Matcher.quoteReplacement("// " + define + " left out by LiteLights (ReloadDefines)"));
    }

    private static boolean stale() {
        for (Switch entry : SWITCHES) {
            Boolean built = BUILT.get(entry.define());
            if (built != null && built != entry.on().getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static void onTick(Minecraft minecraft) {
        if (!changedInMenus || !resting(minecraft.gui.screen()) || minecraft.gui.overlay() != null) {
            return;
        }
        changedInMenus = false;
        if (stale()) {
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("litelights.toast.reloading_shaders"), null);
            minecraft.reloadResourcePacks();
        }
    }

    /** Back in the game or at the title or pause screen: every settings screen has closed. */
    private static boolean resting(@Nullable Screen screen) {
        return screen == null || screen instanceof PauseScreen || screen instanceof TitleScreen;
    }
}
