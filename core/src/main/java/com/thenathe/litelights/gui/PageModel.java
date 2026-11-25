package com.thenathe.litelights.gui;

import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.config.LiteLightsConfig;
import com.thenathe.litelights.config.Option;
import com.thenathe.litelights.config.PageContent;
import com.thenathe.litelights.config.PageContributor;
import com.thenathe.litelights.config.Pages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * What one page shows, worked out when the screen opens or the page changes: its options grouped
 * by section, then whatever contributors add. Sections keep the order in which they first appear.
 */
public final class PageModel implements PageContent {
    /** The untitled section at the top of a page, for rows added before any caption. */
    private static final String INTRO = "";

    public sealed interface Entry {
    }

    public record Setting(RowValue value) implements Entry {
    }

    /**
     * A sub-heading inside a section, over the options of one sub-section ({@code storms/rain}),
     * with an optional pixel icon and hover text.
     */
    public record Subheading(String key, Component caption, Glyphs icon, Component tooltip) implements Entry {
    }

    public record Note(Component text) implements Entry {
    }

    public record Recommendation(String modId, Component text) implements Entry {
    }

    /** A button row. With an opener it opens another screen; with {@code confirm} it needs two clicks. */
    public record Action(Component label, Component description, Component button, Runnable run, boolean confirm,
                         Function<Screen, Screen> opener) implements Entry {
    }

    /**
     * A boxed entry with a heading, a text and an optional button: a credited project, a mod in the
     * About page's status overview. The button opens {@code url} after Minecraft's confirmation,
     * or else runs {@code action}; with no button label there is no button.
     */
    public record Card(Component heading, Component text, Component button, String url, Runnable action) implements Entry {
        public boolean hasButton() {
            return button != null && (url != null || action != null);
        }
    }

    public static final class Section {
        private final String key;
        private Component caption;
        private boolean startsFolded;
        private final List<Entry> entries = new ArrayList<>();

        Section(String key, Component caption, boolean startsFolded) {
            this.key = key;
            this.caption = caption;
            this.startsFolded = startsFolded;
        }

        public String key() {
            return key;
        }

        /** Null for the untitled section at the top. */
        public Component caption() {
            return caption;
        }

        public boolean startsFolded() {
            return startsFolded;
        }

        public List<Entry> entries() {
            return entries;
        }

        /**
         * A contributor's row: after the section's own options but above its first sub-heading, so
         * a sub-section's rows stay together at the end of the section (Compatibility's notices
         * above Mac troubleshooting).
         */
        private void addContributed(Entry entry) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i) instanceof Subheading) {
                    entries.add(i, entry);
                    return;
                }
            }
            entries.add(entry);
        }
    }

    private static final Map<String, Glyphs> SUBHEADING_ICONS = new ConcurrentHashMap<>();

    private final String page;
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private Section current;
    /** Where in the current section the next contributed row goes, after {@link #below}; -1 for its end. */
    private int insertAt = -1;

    private PageModel(String page) {
        this.page = page;
        current = new Section(INTRO, null, false);
        sections.put(INTRO, current);
    }

    public static PageModel build(String page) {
        PageModel model = new PageModel(page);
        for (String leading : Pages.leadingSections(page)) {
            model.section(leading);
        }
        for (Option<?> option : LiteLightsConfig.registry().onPage(page)) {
            model.section(Pages.parentSection(option.section()));
            if (Pages.isSubsection(option.section()) && option.shown()) {
                // A sub-section whose rows are all hidden has no heading either.
                model.subheading(option.section());
            }
            model.current.entries.add(new Setting(RowValue.of(option)));
        }
        model.current = model.sections.get(INTRO);
        for (PageContributor contributor : Pages.contributors(page)) {
            contributor.contribute(model);
            model.current = model.sections.get(INTRO);
            model.insertAt = -1;
        }
        return model;
    }

    /** The Credits page, which isn't one of the registered pages. */
    public static PageModel credits() {
        PageModel model = new PageModel(Credits.PAGE);
        model.note(Component.translatable("litelights.credits.intro"));
        model.section("credits", Component.translatable("litelights.section.credited_projects"), false);
        for (Credits.Project project : Credits.PROJECTS) {
            model.current.entries.add(new Card(Credits.heading(project), Component.translatable(project.usedKey()),
                    Component.literal(project.site()), project.url(), null));
        }
        return model;
    }

    /** Gives a sub-section's heading a pixel icon (the Storms section's weather icons). */
    static void subheadingIcon(String page, String subsection, Glyphs icon) {
        SUBHEADING_ICONS.put(page + "/" + subsection, icon);
    }

    /**
     * Starts a sub-section's run of rows under its heading, unless the section's last heading is
     * already this one. The hover text comes from {@code <caption key>.tooltip} when it exists.
     */
    private void subheading(String subsection) {
        for (int i = current.entries.size() - 1; i >= 0; i--) {
            if (current.entries.get(i) instanceof Subheading heading) {
                if (heading.key().equals(subsection)) {
                    return;
                }
                break;
            }
        }
        String captionKey = Pages.captionKey(subsection);
        String tooltipKey = captionKey + ".tooltip";
        Component tooltip = Language.getInstance().has(tooltipKey) ? Component.translatable(tooltipKey) : null;
        current.entries.add(new Subheading(subsection, Component.translatable(captionKey),
                SUBHEADING_ICONS.get(page + "/" + subsection), tooltip));
    }

    public List<Section> sections() {
        return sections.values().stream().filter(section -> !section.entries.isEmpty()).toList();
    }

    public List<RowValue> settings() {
        List<RowValue> values = new ArrayList<>();
        for (Section section : sections.values()) {
            for (Entry entry : section.entries) {
                if (entry instanceof Setting setting) {
                    values.add(setting.value());
                }
            }
        }
        return values;
    }

    public int changedCount() {
        int count = 0;
        for (RowValue value : settings()) {
            if (value.changed()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String page() {
        return page;
    }

    @Override
    public PageContent section(String section) {
        insertAt = -1;
        current = sections.computeIfAbsent(section, key -> {
            String literal = Pages.sectionName(page, key);
            Component caption = literal != null ? Component.literal(literal) : Component.translatable(Pages.captionKey(key));
            return new Section(key, caption, Pages.startsFolded(page, key));
        });
        return this;
    }

    @Override
    public PageContent section(String section, Component caption, boolean startsFolded) {
        section(section);
        current.caption = caption;
        current.startsFolded = startsFolded;
        return this;
    }

    @Override
    public PageContent below(Option<?> option) {
        for (Section section : sections.values()) {
            for (int i = 0; i < section.entries.size(); i++) {
                if (section.entries.get(i) instanceof Setting setting && setting.value() instanceof RowValue.OptionValue value
                        && value.option() == option) {
                    current = section;
                    insertAt = i + 1;
                    return this;
                }
            }
        }
        return this;
    }

    /** A contributor's row: under the row {@link #below} named, or at the end of the current section. */
    private void add(Entry entry) {
        if (insertAt >= 0) {
            current.entries.add(insertAt++, entry);
        } else {
            current.addContributed(entry);
        }
    }

    @Override
    public PageContent note(Component text) {
        add(new Note(text));
        return this;
    }

    @Override
    public PageContent recommendation(String modId, Component text) {
        add(new Recommendation(modId, text));
        return this;
    }

    @Override
    public PageContent action(Component label, Component description, Component button, Runnable action) {
        add(new Action(label, description, button, action, false, null));
        return this;
    }

    @Override
    public PageContent confirmedAction(Component label, Component description, Component button, Runnable action) {
        add(new Action(label, description, button, action, true, null));
        return this;
    }

    @Override
    public PageContent subScreen(Component label, Component description, Component button, Function<Screen, Screen> opener) {
        add(new Action(label, description, button, () -> { }, false, opener));
        return this;
    }

    @Override
    public PageContent option(Option<?> option) {
        add(new Setting(RowValue.of(option)));
        return this;
    }

    @Override
    public PageContent row(CustomRow row) {
        add(new Setting(RowValue.of(row)));
        return this;
    }

    @Override
    public PageContent card(Component heading, Component text, Component button, String url, Runnable action) {
        add(new Card(heading, text, button, url, action));
        return this;
    }
}
