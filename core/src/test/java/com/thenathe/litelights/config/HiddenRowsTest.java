package com.thenathe.litelights.config;

import com.thenathe.litelights.gui.PageModel;
import com.thenathe.litelights.gui.RowValue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rows locked by another setting are left off their page (hidden-rows-117): the cloud rows follow
 * the Clouds choice, search still finds a locked row, a section left empty goes with its heading,
 * and a hidden row keeps its value, its changed state and its reset.
 */
class HiddenRowsTest {
    private static final String TEST_PAGE = "hidden_rows_test";
    private static final boolean[] UNLOCKED = {false};

    @TempDir
    static Path folder;

    @BeforeAll
    static void loadOptions() {
        if (!LiteLightsConfig.loaded()) {
            LiteLightsConfig.useFile(folder.resolve("litelights.json"));
        }
        LiteLightsConfig.init();
        Pages.contribute(TEST_PAGE, page -> page
                .section("main", Component.literal("Main"), false)
                .row(CustomRow.toggle(Component.literal("Master"), Component.literal(""), true, () -> 1, value -> { }))
                .section("locked_only", Component.literal("Locked only"), false)
                .row(CustomRow.toggle(Component.literal("Needs the other setting"), Component.literal(""), true, () -> 1, value -> { })
                        .enabledWhen(() -> UNLOCKED[0]).lockedTag("litelights.tag.clouds_off")));
    }

    @AfterEach
    void putCloudsBack() {
        LiteLightsOptions.CLOUDS.reset();
        LiteLightsOptions.CLOUD_REACH.reset();
        UNLOCKED[0] = false;
    }

    @Test
    void theCloudRowsFollowTheCloudsChoice() {
        LiteLightsOptions.CLOUDS.set(LiteLightsOptions.Clouds.FANCY);
        assertTrue(onPage(LiteLightsOptions.CLOUD_LAYERS), "Flat and Fancy keep Cloud layers");
        for (Option<?> dynamicOnly : List.of(LiteLightsOptions.CLOUD_REACH, LiteLightsOptions.CLOUD_SHADOWS, LiteLightsOptions.CLOUD_QUALITY)) {
            assertFalse(onPage(dynamicOnly), dynamicOnly.id() + " is off the page under Fancy");
        }
        LiteLightsOptions.CLOUDS.set(LiteLightsOptions.Clouds.DYNAMIC);
        assertTrue(onPage(LiteLightsOptions.CLOUD_REACH), "Cloud reach comes back with the Dynamic clouds");
        assertFalse(onPage(LiteLightsOptions.CLOUD_LAYERS), "Dynamic clouds have no layers");
        assertTrue(onPage(LiteLightsOptions.CLOUD_HEIGHT));
        LiteLightsOptions.CLOUDS.set(LiteLightsOptions.Clouds.OFF);
        assertFalse(onPage(LiteLightsOptions.CLOUD_HEIGHT), "Cloud height goes with the clouds off");
        assertTrue(onPage(LiteLightsOptions.CLOUDS), "the Clouds choice itself always stays");
    }

    @Test
    void aLockedRowIsOffThePageButSearchFindsItWithItsReason() {
        PageModel page = PageModel.build(TEST_PAGE);
        assertFalse(labels(page).contains("Needs the other setting"), "off the page while locked");
        List<PageModel.Entry> found = page.matches("needs the other");
        assertTrue(found.stream().anyMatch(entry -> entry instanceof PageModel.Setting setting
                && setting.value().label().getString().equals("Needs the other setting")
                && setting.value().lockedTag() != null), "search finds it, with its reason");
        UNLOCKED[0] = true;
        assertTrue(labels(page).contains("Needs the other setting"), "back on the page once unlocked");
    }

    @Test
    void aSectionLeftEmptyGoesWithItsHeading() {
        PageModel page = PageModel.build(TEST_PAGE);
        assertEquals(List.of("main"), sectionKeys(page), "the locked row's section is gone");
        assertEquals(2, page.sections().size(), "the model itself keeps both");
        UNLOCKED[0] = true;
        assertEquals(List.of("main", "locked_only"), sectionKeys(page), "it comes back with its row");
    }

    @Test
    void aHiddenRowKeepsItsValueAndStaysChangedAndResettable() {
        LiteLightsOptions.CLOUDS.set(LiteLightsOptions.Clouds.DYNAMIC);
        LiteLightsOptions.CLOUD_REACH.set(4096);
        LiteLightsOptions.CLOUDS.set(LiteLightsOptions.Clouds.FANCY);
        assertFalse(PageModel.onPage(RowValue.of(LiteLightsOptions.CLOUD_REACH)));
        assertEquals(4096, LiteLightsOptions.CLOUD_REACH.value(), "hidden, its value is kept");
        assertTrue(LiteLightsOptions.CLOUD_REACH.isChanged(), "and it still counts as changed (the title's count adds these up)");
        LiteLightsOptions.CLOUDS.set(LiteLightsOptions.Clouds.DYNAMIC);
        assertTrue(PageModel.onPage(RowValue.of(LiteLightsOptions.CLOUD_REACH)));
        assertEquals(4096, LiteLightsOptions.CLOUD_REACH.value(), "and is the same when it shows again");

        LiteLightsOptions.CLOUDS.set(LiteLightsOptions.Clouds.FANCY);
        LiteLightsConfig.registry().resetAll();
        assertEquals(LiteLightsOptions.CLOUD_REACH.defaultValue(), LiteLightsOptions.CLOUD_REACH.get(), "Reset all resets a hidden row too");
    }

    private static List<String> labels(PageModel page) {
        List<String> labels = new ArrayList<>();
        for (PageModel.Section section : page.shownSections()) {
            for (PageModel.Entry entry : section.entries()) {
                if (entry instanceof PageModel.Setting setting) {
                    labels.add(setting.value().label().getString());
                }
            }
        }
        return labels;
    }

    private static boolean onPage(Option<?> option) {
        return PageModel.onPage(RowValue.of(option));
    }

    private static List<String> sectionKeys(PageModel page) {
        return page.shownSections().stream().map(PageModel.Section::key).toList();
    }

}
