package com.thenathe.litelights.overlays.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.gui.RowValue;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import com.thenathe.litelights.gui.widget.ArmedButton;
import com.thenathe.litelights.gui.widget.DropdownWidget;
import com.thenathe.litelights.gui.widget.Ink;
import com.thenathe.litelights.gui.widget.LiteButton;
import com.thenathe.litelights.gui.widget.PopupList;
import com.thenathe.litelights.gui.widget.ToggleWidget;
import com.thenathe.litelights.overlays.config.BlockEntry;
import com.thenathe.litelights.overlays.config.HardnessGroup;
import com.thenathe.litelights.overlays.config.OverlayConfig;
import com.thenathe.litelights.overlays.generation.EdgeStyle;
import com.thenathe.litelights.overlays.hierarchy.OverlayPlan;
import com.thenathe.litelights.overlays.pack.OverlayBuilds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Edits the hardness hierarchy: the order of groups, the order of blocks inside them, which
 * blocks take part, and each block's switch and edge style. A sub-screen of the LiteLights
 * settings, drawn in their style; it started as Dynamic Overlays' editor.
 *
 * <p>The list on the left reads softest first; every block spills onto every block below it.
 * Blocks can be dragged to a new place, or moved with the buttons on the right (or Shift with the
 * arrow keys). Like the settings screen, every change applies at once and there is no Cancel;
 * "Undo all" puts back the hierarchy as it was when this screen opened, and the overlays rebuild
 * once when the player leaves the settings.
 */
final class HierarchyEditorScreen extends Screen {
    private static final int ROW_H = 18;
    private static final int GROUP_ROW_H = 20;
    // The detail column's rows, shared by the widgets (rebuildDetail) and their labels and help text
    // (drawDetailText), so a label can't drift from its widget.
    private static final int DETAIL_TOP = 46;
    private static final int TOGGLE_ROW_H = 24;
    private static final int FIELD_ROW_H = 30;
    private static final int DRAG_THRESHOLD = 4;
    private static final int BUTTON_H = 18;
    private static final int GAP = 4;

    private final Screen parent;
    private final OverlayConfig config = OverlayConfig.get();
    /** The selected block's overlay flow, and what it was worked out for. */
    private String flowKey;
    private int[] flow;
    private final List<HardnessGroup> openedWith;
    private final PopupList popup = new PopupList();
    private final List<AbstractWidget> detailWidgets = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private int contentX;
    private int contentW;
    private int panelY;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int detailX;
    private int detailW;
    private int scroll;
    private int contentHeight;

    private int selectedGroup;
    private int selectedBlock = -1;

    private Row pressed;
    private double pressX;
    private double pressY;
    private boolean dragging;
    private double dragMouseY;

    /** A line of the list: a group header ({@code block} is -1) or a block. */
    private record Row(int group, int block, int y, int h) {
        boolean isGroup() {
            return block < 0;
        }
    }

    HierarchyEditorScreen(Screen parent) {
        super(OverlaysPage.text("editor.title"));
        this.parent = parent;
        this.openedWith = config.copyOfGroups();
        if (config.groups.isEmpty()) {
            config.groups.add(new HardnessGroup(newGroupName(), List.of()));
        }
    }

    @Override
    protected void init() {
        popup.close();
        contentW = Math.min(640, width - 20);
        contentX = (width - contentW) / 2;
        panelY = 26;
        panelH = height - 34 - panelY;
        listX = contentX + 8;
        listY = panelY + 8;
        listW = Math.max(150, contentW * 55 / 100);
        listH = panelH - 16;
        detailX = listX + listW + 10;
        detailW = contentX + contentW - 8 - detailX;

        int footerY = height - 26;
        Component undoLabel = OverlaysPage.text("editor.undo_all");
        addRenderableWidget(new ArmedButton(contentX, footerY, Math.max(90, font.width(undoLabel) + 16), 20, undoLabel,
            this::undoAll));
        int doneW = Math.max(70, font.width(CommonComponents.GUI_DONE) + 16);
        addRenderableWidget(new LiteButton(contentX + contentW - doneW, footerY, doneW, 20, CommonComponents.GUI_DONE,
            this::onClose));
        rebuildRows();
        rebuildDetail();
    }

    // The list

    private void rebuildRows() {
        rows.clear();
        int y = 0;
        for (int group = 0; group < config.groups.size(); group++) {
            rows.add(new Row(group, -1, y, GROUP_ROW_H));
            y += GROUP_ROW_H;
            for (int block = 0; block < config.groups.get(group).blocks.size(); block++) {
                rows.add(new Row(group, block, y, ROW_H));
                y += ROW_H;
            }
        }
        contentHeight = y;
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (listH - 4));
    }

    private boolean isSelected(Row row) {
        return row.group() == selectedGroup && row.block() == selectedBlock;
    }

    private void select(int group, int block) {
        selectedGroup = Math.clamp(group, 0, config.groups.size() - 1);
        int blocks = config.groups.get(selectedGroup).blocks.size();
        selectedBlock = block < 0 || blocks == 0 ? -1 : Math.min(block, blocks - 1);
        rebuildRows();
        scrollToSelection();
        rebuildDetail();
    }

    private void scrollToSelection() {
        for (Row row : rows) {
            if (isSelected(row)) {
                if (row.y() < scroll) {
                    scroll = row.y();
                } else if (row.y() + row.h() > scroll + listH - 4) {
                    scroll = row.y() + row.h() - (listH - 4);
                }
                scroll = Math.clamp(scroll, 0, maxScroll());
                return;
            }
        }
    }

    private Row rowAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY + 2 || mouseY >= listY + listH - 2) {
            return null;
        }
        double contentY = mouseY - listY - 2 + scroll;
        for (Row row : rows) {
            if (contentY >= row.y() && contentY < row.y() + row.h()) {
                return row;
            }
        }
        return null;
    }

    // The detail column

    private void rebuildDetail() {
        detailWidgets.forEach(this::removeWidget);
        detailWidgets.clear();
        int x = detailX + 6;
        int w = detailW - 12;
        int half = (w - GAP) / 2;
        int y = panelY + DETAIL_TOP;

        if (selectedBlock >= 0) {
            BlockEntry entry = config.groups.get(selectedGroup).blocks.get(selectedBlock);
            CustomRow enabled = CustomRow.toggle(OverlaysPage.text("editor.enabled"), Component.empty(), true,
                () -> entry.enabled ? 1 : 0, value -> entry.enabled = value != 0);
            ToggleWidget toggle = new ToggleWidget(RowValue.of(enabled));
            toggle.setPosition(x + w - ToggleWidget.WIDTH, y);
            add(toggle);
            y += TOGGLE_ROW_H;
            DropdownWidget dropdown = new DropdownWidget(w - 70, BUTTON_H, RowValue.of(styleRow(entry)), popup);
            dropdown.setPosition(x + 70, y);
            add(dropdown);
            y += FIELD_ROW_H;

            add(button(x, y, half, "move_up", () -> moveBlock(-1), canMoveBlock(-1)));
            add(button(x + half + GAP, y, half, "move_down", () -> moveBlock(1), canMoveBlock(1)));
            y += BUTTON_H + GAP;
            add(button(x, y, half, "to_softer_group", () -> moveBlockToGroup(-1), selectedGroup > 0));
            add(button(x + half + GAP, y, half, "to_harder_group", () -> moveBlockToGroup(1),
                selectedGroup < config.groups.size() - 1));
            y += BUTTON_H + GAP;
            add(new ArmedButton(x, y, w, BUTTON_H, OverlaysPage.text("editor.remove_block"), this::removeBlock));
        } else {
            HardnessGroup group = config.groups.get(selectedGroup);
            EditBox name = new EditBox(font, x + 4, y + 5, w - 8, 10, OverlaysPage.text("editor.group_name"));
            name.setBordered(false);
            name.setTextColor(Theme.TEXT);
            name.setMaxLength(48);
            name.setValue(group.name);
            name.setResponder(text -> group.name = text);
            add(name);
            y += FIELD_ROW_H;
            add(button(x, y, half, "move_group_up", () -> moveGroup(-1), selectedGroup > 0));
            add(button(x + half + GAP, y, half, "move_group_down", () -> moveGroup(1), selectedGroup < config.groups.size() - 1));
            y += BUTTON_H + GAP;
            ArmedButton merge = new ArmedButton(x, y, half, BUTTON_H, OverlaysPage.text("editor.merge_down"), this::mergeDown);
            merge.active = selectedGroup < config.groups.size() - 1;
            add(merge);
            ArmedButton delete = new ArmedButton(x + half + GAP, y, half, BUTTON_H, OverlaysPage.text("editor.delete_group"),
                this::deleteGroup);
            delete.active = config.groups.size() > 1;
            add(delete);
        }

        int bottom = panelY + panelH - 8 - BUTTON_H;
        add(button(x, bottom, half, "new_group", this::newGroup, true));
        add(button(x + half + GAP, bottom, half, "add_blocks",
            () -> minecraft.gui.setScreen(new BlockPickerScreen(this, selectedGroup)), true));
    }

    /** Default (the fallback style), each style, or receive only. */
    private CustomRow styleRow(BlockEntry entry) {
        List<Component> choices = new ArrayList<>();
        choices.add(Component.translatable("litelights.overlays.block.default", config.fallbackStyle().displayName()));
        for (EdgeStyle style : EdgeStyle.values()) {
            choices.add(style.displayName());
        }
        choices.add(OverlaysPage.text("block.receive_only"));
        int receiveOnly = choices.size() - 1;
        return CustomRow.choice(OverlaysPage.text("editor.style"), Component.empty(), 0, choices, () -> {
            if (entry.receivesOnly()) {
                return receiveOnly;
            }
            return entry.usesDefaultStyle() ? 0 : 1 + EdgeStyle.byId(entry.style, EdgeStyle.ORGANIC).ordinal();
        }, value -> {
            if (value == 0) {
                entry.style = BlockEntry.USE_DEFAULT;
            } else if (value == receiveOnly) {
                entry.style = BlockEntry.RECEIVE_ONLY;
            } else {
                entry.style = EdgeStyle.values()[value - 1].id();
            }
        });
    }

    private LiteButton button(int x, int y, int w, String key, Runnable action, boolean active) {
        LiteButton button = new LiteButton(x, y, w, BUTTON_H, OverlaysPage.text("editor." + key), action);
        button.active = active;
        return button;
    }

    private void add(AbstractWidget widget) {
        detailWidgets.add(widget);
        addRenderableWidget(widget);
    }

    // Changes

    private boolean canMoveBlock(int direction) {
        if (selectedBlock < 0) {
            return false;
        }
        if (direction < 0) {
            return selectedBlock > 0 || selectedGroup > 0;
        }
        return selectedBlock < config.groups.get(selectedGroup).blocks.size() - 1 || selectedGroup < config.groups.size() - 1;
    }

    /** Moves the selected block one place; past the end of its group it joins the neighbouring group. */
    private void moveBlock(int direction) {
        if (!canMoveBlock(direction)) {
            return;
        }
        List<BlockEntry> blocks = config.groups.get(selectedGroup).blocks;
        int target = selectedBlock + direction;
        if (target >= 0 && target < blocks.size()) {
            blocks.add(target, blocks.remove(selectedBlock));
            select(selectedGroup, target);
            return;
        }
        BlockEntry entry = blocks.remove(selectedBlock);
        int group = selectedGroup + direction;
        List<BlockEntry> next = config.groups.get(group).blocks;
        int position = direction < 0 ? next.size() : 0;
        next.add(position, entry);
        select(group, position);
    }

    /** Moves the selected block to the end of the softer group above, or the start of the harder group below. */
    private void moveBlockToGroup(int direction) {
        int group = selectedGroup + direction;
        if (selectedBlock < 0 || group < 0 || group >= config.groups.size()) {
            return;
        }
        BlockEntry entry = config.groups.get(selectedGroup).blocks.remove(selectedBlock);
        List<BlockEntry> next = config.groups.get(group).blocks;
        int position = direction < 0 ? next.size() : 0;
        next.add(position, entry);
        select(group, position);
    }

    private void moveGroup(int direction) {
        int target = selectedGroup + direction;
        if (target < 0 || target >= config.groups.size()) {
            return;
        }
        config.groups.add(target, config.groups.remove(selectedGroup));
        select(target, -1);
    }

    private void removeBlock() {
        if (selectedBlock >= 0) {
            config.groups.get(selectedGroup).blocks.remove(selectedBlock);
            select(selectedGroup, selectedBlock);
        }
    }

    /** Joins the next group onto the end of this one, keeping every block. */
    private void mergeDown() {
        if (selectedGroup < config.groups.size() - 1) {
            HardnessGroup next = config.groups.remove(selectedGroup + 1);
            config.groups.get(selectedGroup).blocks.addAll(next.blocks);
            select(selectedGroup, -1);
        }
    }

    /** Deletes the group and its blocks. The last group can't go, so there is always somewhere to add to. */
    private void deleteGroup() {
        if (config.groups.size() > 1) {
            config.groups.remove(selectedGroup);
            select(Math.min(selectedGroup, config.groups.size() - 1), -1);
        }
    }

    private void newGroup() {
        int position = selectedGroup + 1;
        config.groups.add(position, new HardnessGroup(newGroupName(), List.of()));
        select(position, -1);
    }

    private String newGroupName() {
        String base = OverlaysPage.text("editor.new_group_name").getString();
        String name = base;
        for (int n = 2; nameTaken(name); n++) {
            name = base + " " + n;
        }
        return name;
    }

    private boolean nameTaken(String name) {
        return config.groups.stream().anyMatch(group -> name.equals(group.name));
    }

    /** Adds blocks from the picker to the end of a group. */
    void addBlocks(int group, List<String> ids) {
        int target = Math.clamp(group, 0, config.groups.size() - 1);
        for (String id : ids) {
            if (config.find(id) == null) {
                config.groups.get(target).blocks.add(new BlockEntry(id));
            }
        }
        select(target, config.groups.get(target).blocks.size() - 1);
    }

    private void undoAll() {
        config.groups.clear();
        for (HardnessGroup group : openedWith) {
            config.groups.add(group.copy());
        }
        select(0, -1);
    }

    /** Where a dragged block would land: a group and a position in it, and the y of the insertion line. */
    private int[] dropTarget(double mouseY) {
        double contentY = mouseY - listY - 2 + scroll;
        for (Row row : rows) {
            if (row.isGroup() && contentY < row.y() + row.h()) {
                return new int[] {row.group(), 0, row.y() + row.h()};
            }
            if (!row.isGroup() && contentY < row.y() + row.h() / 2.0) {
                return new int[] {row.group(), row.block(), row.y()};
            }
        }
        int last = config.groups.size() - 1;
        return new int[] {last, config.groups.get(last).blocks.size(), contentHeight};
    }

    private void drop(double mouseY) {
        int[] target = dropTarget(mouseY);
        int group = target[0];
        int position = target[1];
        BlockEntry entry = config.groups.get(pressed.group()).blocks.get(pressed.block());
        config.groups.get(pressed.group()).blocks.remove(pressed.block());
        if (group == pressed.group() && position > pressed.block()) {
            position--;
        }
        List<BlockEntry> blocks = config.groups.get(group).blocks;
        position = Math.clamp(position, 0, blocks.size());
        blocks.add(position, entry);
        select(group, position);
    }

    // Drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (minecraft.level != null) {
            graphics.fill(0, 0, width, height, Theme.WORLD_DIM_FULL);
        } else {
            super.extractBackground(graphics, mouseX, mouseY, delta);
        }
        graphics.text(font, title, contentX, 8, Theme.TITLE, true);
        Component subtitle = OverlaysPage.text("editor.subtitle");
        graphics.text(font, Ink.trim(font, subtitle.getString(), contentW - font.width(title) - 12),
            contentX + font.width(title) + 10, 8, Theme.TEXT_DIM, false);
        Surfaces.panel(graphics, contentX, panelY, contentW, panelH);
        Surfaces.well(graphics, listX, listY, listW, listH, Theme.WELL_EDGE);
        Surfaces.card(graphics, detailX, listY, detailW, listH);
        if (selectedBlock < 0) {
            Surfaces.well(graphics, detailX + 6, panelY + DETAIL_TOP, detailW - 12, 20, Theme.CARD_EDGE);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int pointerX = popup.isOpen() ? -1 : mouseX;
        int pointerY = popup.isOpen() ? -1 : mouseY;
        super.extractRenderState(graphics, pointerX, pointerY, delta);
        drawList(graphics, pointerX, pointerY);
        drawDetailText(graphics);
        if (dragging && pressed != null) {
            BlockEntry entry = config.groups.get(pressed.group()).blocks.get(pressed.block());
            graphics.item(itemFor(entry.id), mouseX + 6, mouseY - 8);
            graphics.text(font, OverlaysPage.blockName(entry.id), mouseX + 24, mouseY - 4, Theme.TEXT_STRONG, true);
        }
        popup.extract(graphics, mouseX, mouseY);
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int top = listY + 2;
        int bottom = listY + listH - 2;
        graphics.enableScissor(listX + 1, top, listX + listW - 1, bottom);
        Row hovered = dragging ? null : rowAt(mouseX, mouseY);
        int rowW = listW - 8;
        int groupNumber = 0;
        for (Row row : rows) {
            int y = top + row.y() - scroll;
            if (row.isGroup()) {
                groupNumber++;
            }
            if (y + row.h() < top || y > bottom) {
                continue;
            }
            boolean selected = isSelected(row);
            if (selected) {
                Surfaces.rounded(graphics, listX + 2, y, rowW, row.h(), Theme.ACCENT_WASH);
                graphics.fill(listX + 2, y + 1, listX + 4, y + row.h() - 1, Theme.ACCENT);
            } else if (row.equals(hovered)) {
                graphics.fill(listX + 2, y, listX + 2 + rowW, y + row.h(), Theme.HOVER);
            }
            if (row.isGroup()) {
                drawGroupRow(graphics, row, groupNumber, y, rowW, selected);
            } else {
                drawBlockRow(graphics, row, y, rowW, selected);
            }
        }
        if (dragging) {
            int lineY = top + dropTarget(mouseY)[2] - scroll;
            graphics.fill(listX + 4, lineY - 1, listX + rowW, lineY + 1, Theme.ACCENT);
        }
        graphics.disableScissor();
        drawScrollbar(graphics, listX + listW - 5, top, bottom - top);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, int x, int y, int h) {
        if (maxScroll() <= 0) {
            return;
        }
        int thumbH = Math.max(16, h * h / Math.max(1, contentHeight));
        int thumbY = y + (h - thumbH) * scroll / maxScroll();
        Surfaces.rounded(graphics, x, thumbY, 3, thumbH, Theme.RAISED_HOT);
    }

    private void drawGroupRow(GuiGraphicsExtractor graphics, Row row, int number, int y, int rowW, boolean selected) {
        HardnessGroup group = config.groups.get(row.group());
        String name = group.name.isBlank() ? OverlaysPage.text("editor.unnamed").getString() : group.name;
        String text = number + "  " + name.toUpperCase(Locale.ROOT);
        String count = blockCount(group.blocks.size()).getString();
        int countX = listX + 2 + rowW - 6 - font.width(count);
        graphics.text(font, trim(text, countX - listX - 14), listX + 8, y + 6, selected ? Theme.ACCENT_HOT : Theme.CAPTION, true);
        graphics.text(font, count, countX, y + 6, Theme.TEXT_DIM, false);
        graphics.fill(listX + 8, y + row.h() - 2, listX + 2 + rowW - 6, y + row.h() - 1, Theme.RULE);
    }

    private void drawBlockRow(GuiGraphicsExtractor graphics, Row row, int y, int rowW, boolean selected) {
        BlockEntry entry = config.groups.get(row.group()).blocks.get(row.block());
        graphics.item(itemFor(entry.id), listX + 14, y + 1);
        boolean known = OverlayBuilds.eligible().isEligible(entry.id);
        String tag = tagFor(entry, known);
        int tagX = listX + 2 + rowW - 6 - font.width(tag);
        int nameColour = selected ? Theme.TEXT_STRONG : entry.enabled && known ? Theme.TEXT : Theme.TEXT_OFF;
        graphics.text(font, trim(OverlaysPage.blockName(entry.id).getString(), tagX - listX - 40), listX + 34, y + 5,
            nameColour, true);
        graphics.text(font, tag, tagX, y + 5, Theme.TEXT_DIM, false);
    }

    private static Component blockCount(int count) {
        return count == 1 ? OverlaysPage.text("editor.count.one") : Component.translatable("litelights.overlays.editor.count", count);
    }

    private String tagFor(BlockEntry entry, boolean known) {
        if (!known) {
            return OverlaysPage.text("editor.tag.missing").getString();
        }
        if (!entry.enabled) {
            return OverlaysPage.text("block.off").getString();
        }
        if (entry.receivesOnly() || !OverlayBuilds.eligible().canSpill(entry.id)) {
            return OverlaysPage.text("block.receive_only").getString();
        }
        if (entry.usesDefaultStyle()) {
            return OverlaysPage.text("editor.tag.default").getString();
        }
        return EdgeStyle.byId(entry.style, EdgeStyle.ORGANIC).displayName().getString();
    }

    private void drawDetailText(GuiGraphicsExtractor graphics) {
        int x = detailX + 6;
        int w = detailW - 12;
        int y = listY + 6;
        if (selectedBlock >= 0) {
            BlockEntry entry = config.groups.get(selectedGroup).blocks.get(selectedBlock);
            graphics.item(itemFor(entry.id), x, y + 2);
            graphics.text(font, trim(OverlaysPage.blockName(entry.id).getString(), w - 20), x + 20, y + 2, Theme.TEXT_STRONG, true);
            graphics.text(font, trim(entry.id, w - 20), x + 20, y + 13, Theme.TEXT_DIM, false);
            graphics.text(font, OverlaysPage.text("editor.enabled"), x, panelY + DETAIL_TOP + 5, Theme.TEXT, true);
            graphics.text(font, OverlaysPage.text("editor.style"), x, panelY + DETAIL_TOP + TOGGLE_ROW_H + 5, Theme.TEXT, true);
            int textY = panelY + DETAIL_TOP + TOGGLE_ROW_H + FIELD_ROW_H + (BUTTON_H + GAP) * 3 + 6;
            int[] flow = flowOf(entry.id);
            Component flowText = Component.translatable("litelights.overlays.editor.flow", flow[0], flow[1]);
            textY = paragraph(graphics, flowText, x, textY, w, Theme.TEXT_DIM);
            if (!OverlayBuilds.eligible().canSpill(entry.id) && OverlayBuilds.eligible().isEligible(entry.id)) {
                paragraph(graphics, OverlaysPage.text("editor.cannot_spill"), x, textY, w, Theme.NOTICE);
            }
        } else {
            HardnessGroup group = config.groups.get(selectedGroup);
            graphics.text(font, Component.translatable("litelights.overlays.editor.group_heading", selectedGroup + 1), x, y + 2,
                Theme.TEXT_STRONG, true);
            graphics.text(font, blockCount(group.blocks.size()), x, y + 13, Theme.TEXT_DIM, false);
            int textY = panelY + DETAIL_TOP + FIELD_ROW_H + (BUTTON_H + GAP) * 2 + 6;
            paragraph(graphics, OverlaysPage.text("editor.group_help"), x, textY, w, Theme.TEXT_DIM);
        }
        int hintY = panelY + panelH - 8 - BUTTON_H - 6 - 20;
        paragraph(graphics, OverlaysPage.text("editor.keys"), x, hintY, w, Theme.TEXT_DIM);
    }

    private int paragraph(GuiGraphicsExtractor graphics, Component text, int x, int y, int w, int colour) {
        for (FormattedCharSequence line : font.split(text, w)) {
            graphics.text(font, line, x, y, colour, false);
            y += 10;
        }
        return y;
    }

    /**
     * How many blocks a block takes overlays from and gives its own to, as the next build will
     * have it (blending counts both ways; masonry's own rules and receive-only included).
     */
    private int[] flowOf(String blockId) {
        String key = blockId + '|' + config.generationFingerprint();
        if (key.equals(flowKey)) {
            return flow;
        }
        OverlayPlan plan = OverlayPlan.ofHierarchy(config, OverlayBuilds.eligible());
        int from = 0;
        int onto = 0;
        for (OverlayPlan.Source source : plan.sources()) {
            if (source.blockId().equals(blockId)) {
                onto = source.spillOnto().size() + source.blendWith().size();
            } else if (source.spillOnto().contains(blockId) || source.blendWith().contains(blockId)) {
                from++;
            }
        }
        flowKey = key;
        flow = new int[] {from, onto};
        return flow;
    }

    private String trim(String text, int width) {
        return Ink.trim(font, text, Math.max(10, width));
    }

    static ItemStack itemFor(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return new ItemStack(Items.BARRIER);
        }
        Item item = BuiltInRegistries.BLOCK.getValue(id).asItem();
        return item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
    }

    // Input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (popup.isOpen()) {
            return popup.mouseClicked(event);
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        Row row = rowAt(event.x(), event.y());
        if (row != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            setFocused(null);
            select(row.group(), row.block());
            pressed = row.isGroup() ? null : row;
            pressX = event.x();
            pressY = event.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressed != null) {
            if (!dragging && Math.hypot(event.x() - pressX, event.y() - pressY) > DRAG_THRESHOLD) {
                dragging = true;
            }
            if (dragging) {
                dragMouseY = event.y();
                if (event.y() < listY + 10) {
                    scroll = Math.max(0, scroll - 4);
                } else if (event.y() > listY + listH - 10) {
                    scroll = Math.min(maxScroll(), scroll + 4);
                }
                return true;
            }
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging && pressed != null) {
            drop(dragMouseY);
        }
        dragging = false;
        pressed = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (popup.isOpen()) {
            return popup.mouseScrolled(vertical);
        }
        if (mouseX >= listX && mouseX < listX + listW) {
            scroll = Math.clamp(scroll - (int) (vertical * 20), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (popup.isOpen()) {
            return popup.keyPressed(event);
        }
        boolean typing = getFocused() instanceof EditBox;
        int key = event.key();
        if (!typing && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
            int direction = key == InputConstants.KEY_UP ? -1 : 1;
            if (event.hasShiftDown()) {
                if (selectedBlock >= 0) {
                    moveBlock(direction);
                } else {
                    moveGroup(direction);
                }
            } else {
                stepSelection(direction);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private void stepSelection(int direction) {
        for (int i = 0; i < rows.size(); i++) {
            if (isSelected(rows.get(i))) {
                Row next = rows.get(Math.clamp(i + direction, 0, rows.size() - 1));
                select(next.group(), next.block());
                return;
            }
        }
    }

    @Override
    public void onClose() {
        OverlayConfig.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
