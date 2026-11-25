package com.thenathe.litelights.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The settings pages, in the order the navigation shows them, and what modules add to them.
 *
 * <p>A page lists its options by section, in the order the options were registered. Sections can
 * start folded. A section id with a slash, such as {@code storms/rain}, is a sub-section: its
 * options show in the {@code storms} section under a "Rain" sub-heading
 * ({@code litelights.section.storms.rain}), and Sodium's screen gives it a group of its own.
 * Modules can add pages of their own and add rows (notes, buttons, sub-screens,
 * rows bound to their own data) to any page through a {@link PageContributor}.
 */
public final class Pages {
    public static final String GENERAL = "general";
    public static final String SKY = "sky";
    public static final String FOG = "fog";
    public static final String LIGHTING = "lighting";
    public static final String COLOUR_GRADING = "colour_grading";
    public static final String SHADOWS = "shadows";
    public static final String WATER = "water";
    public static final String VEGETATION = "vegetation";
    public static final String PARTICLES = "particles";
    public static final String WEATHER = "weather";
    public static final String OUTLINES = "outlines";
    public static final String DIMENSIONS = "dimensions";
    public static final String OVERLAYS = "overlays";
    public static final String ABOUT = "about";

    /**
     * A page in the navigation. {@code order} sorts pages; the built-in ones use 100, 200 and so
     * on, so a module page can go between them. {@code icon} names one of the screen's icons
     * (a page id such as "water", or "module" for a generic one).
     */
    public record PageInfo(String id, String labelKey, String icon, int order) {
    }

    private static final List<PageInfo> PAGES = new CopyOnWriteArrayList<>();
    private static final Map<String, List<PageContributor>> CONTRIBUTORS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FOLDED_SECTIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> SECTION_NAMES = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> LEADING_SECTIONS = new ConcurrentHashMap<>();

    static {
        String[] builtIn = {GENERAL, SKY, FOG, LIGHTING, COLOUR_GRADING, SHADOWS, WATER, VEGETATION, PARTICLES,
                WEATHER, OUTLINES, DIMENSIONS, OVERLAYS, ABOUT};
        for (int i = 0; i < builtIn.length; i++) {
            PAGES.add(new PageInfo(builtIn[i], "litelights.page." + builtIn[i], builtIn[i], (i + 1) * 100));
        }
    }

    private Pages() {
    }

    /** Adds a page. Its label is the translation of {@code labelKey}. */
    public static void register(PageInfo page) {
        if (find(page.id()) != null) {
            throw new IllegalArgumentException("Page " + page.id() + " already exists");
        }
        PAGES.add(page);
    }

    public static List<PageInfo> all() {
        List<PageInfo> sorted = new ArrayList<>(PAGES);
        sorted.sort(Comparator.comparingInt(PageInfo::order));
        return sorted;
    }

    public static PageInfo find(String id) {
        return PAGES.stream().filter(page -> page.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * Adds rows to a page. The contributor runs every time the screen builds the page, so rows
     * can come from live data (one row per block, the current build status).
     */
    public static void contribute(String page, PageContributor contributor) {
        CONTRIBUTORS.computeIfAbsent(page, key -> new CopyOnWriteArrayList<>()).add(contributor);
    }

    public static List<PageContributor> contributors(String page) {
        return CONTRIBUTORS.getOrDefault(page, List.of());
    }

    /**
     * Makes a section start folded (for settings most players never touch). A folded section
     * still opens by itself when something in it differs from its default.
     */
    public static void foldSectionByDefault(String page, String section) {
        FOLDED_SECTIONS.put(page + "/" + section, true);
    }

    public static boolean startsFolded(String page, String section) {
        return FOLDED_SECTIONS.getOrDefault(page + "/" + section, false);
    }

    /**
     * Gives a section a caption that isn't a translation key, for sections made from data (one
     * per modded dimension). Other sections use {@code litelights.section.<section>}.
     */
    public static void nameSection(String page, String section, String caption) {
        SECTION_NAMES.put(page + "/" + section, caption);
    }

    /** The literal caption given with {@link #nameSection}, or null. */
    public static String sectionName(String page, String section) {
        return SECTION_NAMES.get(page + "/" + section);
    }

    /**
     * Puts a section above the page's option sections, for rows a contributor adds (General's
     * Getting started). Sections placed first keep the order they were placed in.
     */
    public static void placeSectionFirst(String page, String section) {
        List<String> leading = LEADING_SECTIONS.computeIfAbsent(page, key -> new CopyOnWriteArrayList<>());
        if (!leading.contains(section)) {
            leading.add(section);
        }
    }

    /** The section a section id shows in: {@code storms} for {@code storms/rain}, the id itself otherwise. */
    public static String parentSection(String section) {
        int slash = section.indexOf('/');
        return slash < 0 ? section : section.substring(0, slash);
    }

    /** Whether the section id is a sub-section, shown under a sub-heading inside its parent. */
    public static boolean isSubsection(String section) {
        return section.indexOf('/') >= 0;
    }

    /** The caption's translation key: {@code litelights.section.storms.rain} for {@code storms/rain}. */
    public static String captionKey(String section) {
        return "litelights.section." + section.replace('/', '.');
    }

    public static List<String> leadingSections(String page) {
        return LEADING_SECTIONS.getOrDefault(page, List.of());
    }
}
