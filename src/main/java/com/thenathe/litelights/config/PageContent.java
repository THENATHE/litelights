package com.thenathe.litelights.config;

import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * What a {@link PageContributor} can add to a page. Rows go into the current section: rows added
 * before any {@link #section} call go at the top of the page, above the first caption.
 * Choosing a section that already holds options appends after them.
 */
public interface PageContent {
    /** The id of the page being built. */
    String page();

    /** Continues an existing section, or starts a new one at the end with caption {@code litelights.section.<section>}. */
    PageContent section(String section);

    /** Starts or continues a section with a caption of your own. */
    PageContent section(String section, Component caption, boolean startsFolded);

    /** A short wrapped paragraph. */
    PageContent note(Component text);

    /**
     * A greyed-out line recommending another mod. It isn't a setting; when {@code modId} is
     * loaded it shows "Installed" instead of asking.
     */
    PageContent recommendation(String modId, Component text);

    /** A row with a button that does something, such as "Rebuild now". */
    PageContent action(Component label, Component description, Component button, Runnable action);

    /** Like {@link #action}, but the button arms on the first click and acts on a second, for things that can't be undone. */
    PageContent confirmedAction(Component label, Component description, Component button, Runnable action);

    /**
     * A row whose button opens another screen. {@code opener} gets the settings screen as the
     * parent to return to and gives back the screen to open.
     */
    PageContent subScreen(Component label, Component description, Component button, Function<Screen, Screen> opener);

    /** A row bound to the module's own data. */
    PageContent row(CustomRow row);

    /**
     * A boxed entry: a heading, a wrapped text under it, and optionally a button beside them. The
     * button opens {@code url} through Minecraft's "open this link?" confirmation when one is given,
     * and otherwise runs {@code action}. The box grows to fit its text; on narrow lists the button
     * moves under it.
     */
    PageContent card(Component heading, Component text, Component button, String url, Runnable action);
}
