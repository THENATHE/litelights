package com.thenathe.litelights.overlays.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.thenathe.litelights.gui.Glyphs;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import com.thenathe.litelights.gui.widget.Ink;
import com.thenathe.litelights.gui.widget.LiteButton;
import com.thenathe.litelights.gui.widget.SearchField;
import com.thenathe.litelights.overlays.config.BlockEntry;
import com.thenathe.litelights.overlays.config.HardnessGroup;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.pack.OverlayBuilds;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Picks blocks to add to a group of the hierarchy: every eligible full block, searchable, several
 * at once. Blocks already in the hierarchy are listed with their group and can't be picked again.
 */
final class BlockPickerScreen extends Screen {
    private static final int ROW_H = 20;
    private static final int COLUMNS = 2;

    private final HierarchyEditorScreen editor;
    private final int group;
    private final List<String> all;
    private final Set<String> chosen = new LinkedHashSet<>();
    private List<String> shown = List.of();

    private SearchField search;
    private LiteButton addButton;
    private int contentX;
    private int contentW;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int scroll;

    BlockPickerScreen(HierarchyEditorScreen editor, int group) {
        super(OverlaysPage.text("picker.title"));
        this.editor = editor;
        this.group = group;
        this.all = OverlayBuilds.eligible().all();
    }

    @Override
    protected void init() {
        contentW = Math.min(640, width - 20);
        contentX = (width - contentW) / 2;
        int panelY = 26;
        int panelH = height - 34 - panelY;
        search = new SearchField(contentX + 8, panelY + 8, contentW - 16, 18, OverlaysPage.text("picker.search"));
        search.setResponder(text -> filter());
        addRenderableWidget(search);
        setInitialFocus(search);
        listX = contentX + 8;
        listY = panelY + 32;
        listW = contentW - 16;
        listH = panelH - 40;

        int footerY = height - 26;
        int cancelW = Math.max(70, font.width(CommonComponents.GUI_CANCEL) + 16);
        int addW = 120;
        addRenderableWidget(new LiteButton(contentX + contentW - addW - cancelW - 4, footerY, cancelW, 20,
            CommonComponents.GUI_CANCEL, this::onClose));
        addButton = new LiteButton(contentX + contentW - addW, footerY, addW, 20, Component.empty(), this::addChosen);
        addRenderableWidget(addButton);
        filter();
    }

    private void filter() {
        String needle = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String id : all) {
            String name = OverlaysPage.blockName(id).getString().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || id.contains(needle) || name.contains(needle)) {
                matches.add(id);
            }
        }
        shown = matches;
        scroll = Math.clamp(scroll, 0, maxScroll());
        updateAddButton();
    }

    private void updateAddButton() {
        addButton.setMessage(Component.translatable("litelights.overlays.picker.add", chosen.size()));
        addButton.active = !chosen.isEmpty();
    }

    private int rowsCount() {
        return (shown.size() + COLUMNS - 1) / COLUMNS;
    }

    private int maxScroll() {
        return Math.max(0, rowsCount() * ROW_H - (listH - 4));
    }

    private static String groupOf(String id) {
        for (HardnessGroup candidate : OverlayConfig.get().groups) {
            for (BlockEntry entry : candidate.blocks) {
                if (entry.id.equals(id)) {
                    return candidate.name;
                }
            }
        }
        return null;
    }

    private void addChosen() {
        editor.addBlocks(group, List.copyOf(chosen));
        minecraft.gui.setScreen(editor);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (minecraft.level != null) {
            graphics.fill(0, 0, width, height, Theme.WORLD_DIM_FULL);
        } else {
            super.extractBackground(graphics, mouseX, mouseY, delta);
        }
        List<HardnessGroup> groups = OverlayConfig.get().groups;
        String target = groups.isEmpty() ? "" : groups.get(Math.clamp(group, 0, groups.size() - 1)).name;
        graphics.text(font, title, contentX, 8, Theme.TITLE, true);
        graphics.text(font, Component.translatable("litelights.overlays.picker.into", target), contentX + font.width(title) + 10,
            8, Theme.TEXT_DIM, false);
        Surfaces.panel(graphics, contentX, 26, contentW, height - 60);
        Surfaces.well(graphics, listX, listY, listW, listH, Theme.WELL_EDGE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int top = listY + 2;
        int bottom = listY + listH - 2;
        int columnW = (listW - 8) / COLUMNS;
        graphics.enableScissor(listX + 1, top, listX + listW - 1, bottom);
        for (int i = 0; i < shown.size(); i++) {
            int x = listX + 2 + (i % COLUMNS) * columnW;
            int y = top + (i / COLUMNS) * ROW_H - scroll;
            if (y + ROW_H < top || y > bottom) {
                continue;
            }
            drawEntry(graphics, shown.get(i), x, y, columnW, mouseX, mouseY);
        }
        if (shown.isEmpty()) {
            Component empty = Component.translatable("litelights.overlays.picker.empty", search.getValue());
            graphics.text(font, empty, listX + (listW - font.width(empty)) / 2, listY + listH / 2 - 4, Theme.TEXT_DIM, false);
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int h = bottom - top;
            int thumbH = Math.max(16, h * h / Math.max(1, rowsCount() * ROW_H));
            int thumbY = top + (h - thumbH) * scroll / maxScroll();
            Surfaces.rounded(graphics, listX + listW - 5, thumbY, 3, thumbH, Theme.RAISED_HOT);
        }
    }

    private void drawEntry(GuiGraphicsExtractor graphics, String id, int x, int y, int w, int mouseX, int mouseY) {
        String inGroup = groupOf(id);
        boolean picked = chosen.contains(id);
        boolean hovered = inGroup == null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H
            && mouseY >= listY && mouseY < listY + listH;
        if (picked) {
            Surfaces.rounded(graphics, x, y, w - 2, ROW_H, Theme.ACCENT_WASH);
        } else if (hovered) {
            graphics.fill(x, y, x + w - 2, y + ROW_H, Theme.HOVER);
        }
        graphics.item(HierarchyEditorScreen.itemFor(id), x + 4, y + 2);
        String tag = inGroup != null
            ? Component.translatable("litelights.overlays.picker.in_group", inGroup).getString()
            : OverlayBuilds.eligible().canSpill(id) ? "" : OverlaysPage.text("block.receive_only").getString();
        int tagW = font.width(tag);
        int nameColour = picked ? Theme.TEXT_STRONG : inGroup == null ? Theme.TEXT : Theme.TEXT_OFF;
        String name = Ink.trim(font, OverlaysPage.blockName(id).getString(), w - 40 - tagW);
        graphics.text(font, name, x + 24, y + 6, nameColour, true);
        if (!tag.isEmpty()) {
            graphics.text(font, tag, x + w - 8 - tagW, y + 6, Theme.TEXT_DIM, false);
        }
        if (picked) {
            Glyphs.CHECK.draw(graphics, x + w - 8 - Glyphs.CHECK.width() - (tag.isEmpty() ? 0 : tagW + 4), y + 6, Theme.ACCENT);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || event.x() < listX || event.x() >= listX + listW
                || event.y() < listY + 2 || event.y() >= listY + listH - 2) {
            return false;
        }
        int columnW = (listW - 8) / COLUMNS;
        int column = (int) ((event.x() - listX - 2) / columnW);
        int row = (int) ((event.y() - listY - 2 + scroll) / ROW_H);
        int index = row * COLUMNS + column;
        if (column < COLUMNS && index >= 0 && index < shown.size()) {
            String id = shown.get(index);
            if (groupOf(id) == null && !chosen.remove(id)) {
                chosen.add(id);
            }
            updateAddButton();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - (int) (vertical * 20), 0, maxScroll());
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(editor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
