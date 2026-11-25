package com.thenathe.litelights.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.thenathe.litelights.gui.Glyphs;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The list a dropdown opens. The screen owns one and gives it every click, scroll and key first
 * while it is open. It opens under its button, or above when there is more room there, and
 * scrolls when it is taller than the room it has. Disabled options can't be picked.
 */
public final class PopupList {
    private static final int ROW_H = 14;
    private static final int INSET = 3;
    private static final int CHECK_COLUMN = 12;

    /** One row: its label, an optional dim tag after it, and whether it can be picked. */
    public record Option(Component label, Component tag, boolean enabled) {
    }

    private AbstractWidget anchor;
    private List<Option> options = List.of();
    private int chosen;
    private IntConsumer onPick;
    private int x;
    private int y;
    private int w;
    private int h;
    private int scroll;
    private int keyboardRow = -1;

    public boolean isOpen() {
        return anchor != null;
    }

    public boolean isOpenFor(AbstractWidget widget) {
        return anchor == widget;
    }

    public void close() {
        anchor = null;
    }

    public void open(AbstractWidget anchor, List<Option> options, int chosen, IntConsumer onPick) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int screenW = minecraft.getWindow().getGuiScaledWidth();
        int screenH = minecraft.getWindow().getGuiScaledHeight();
        this.anchor = anchor;
        this.options = options;
        this.chosen = chosen;
        this.onPick = onPick;
        this.keyboardRow = -1;

        int widest = 0;
        for (Option option : options) {
            int tagW = option.tag() == null ? 0 : font.width(option.tag()) + 6;
            widest = Math.max(widest, CHECK_COLUMN + font.width(option.label()) + tagW);
        }
        w = Math.min(screenW - 8, Math.max(anchor.getWidth(), widest + 12));
        x = Math.clamp(anchor.getX() + anchor.getWidth() - w, 4, Math.max(4, screenW - 4 - w));

        int below = screenH - (anchor.getY() + anchor.getHeight()) - 4;
        int above = anchor.getY() - 4;
        int contentH = options.size() * ROW_H;
        int room = Math.max(below, above) - 2 * INSET;
        h = Math.max(ROW_H, Math.min(contentH, room)) + 2 * INSET;
        boolean opensUp = below < h && above > below;
        y = opensUp ? anchor.getY() - h : anchor.getY() + anchor.getHeight();

        int visible = h - 2 * INSET;
        scroll = Math.clamp(chosen * ROW_H - (visible - ROW_H) / 2, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, options.size() * ROW_H - (h - 2 * INSET));
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseX >= x + w || mouseY < y + INSET || mouseY >= y + h - INSET) {
            return -1;
        }
        int row = (int) ((mouseY - y - INSET + scroll) / ROW_H);
        return row < options.size() ? row : -1;
    }

    public void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!isOpen()) {
            return;
        }
        graphics.nextStratum();
        Font font = Minecraft.getInstance().font;
        Surfaces.framed(graphics, x, y, w, h, Theme.CARD, Theme.ACCENT_DEEP);
        int hovered = rowAt(mouseX, mouseY);
        int lit = hovered >= 0 ? hovered : keyboardRow;
        graphics.enableScissor(x + 1, y + INSET, x + w - 1, y + h - INSET);
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            int rowY = y + INSET + i * ROW_H - scroll;
            boolean isLit = i == lit && option.enabled();
            if (isLit) {
                Surfaces.rounded(graphics, x + 2, rowY, w - 4, ROW_H, Theme.ACCENT);
            }
            int colour = !option.enabled() ? Theme.TEXT_OFF : isLit ? Theme.ACCENT_TEXT : i == chosen ? Theme.ACCENT : Theme.TEXT;
            int textX = x + INSET + 3;
            int textY = rowY + (ROW_H - 8) / 2;
            if (i == chosen) {
                Glyphs.CHECK.draw(graphics, textX - 1, textY + 1, colour);
            }
            textX += CHECK_COLUMN;
            graphics.text(font, option.label(), textX, textY, colour, !isLit);
            if (option.tag() != null) {
                graphics.text(font, option.tag(), textX + font.width(option.label()) + 6, textY,
                        isLit ? Theme.ACCENT_TEXT : Theme.TEXT_DIM, false);
            }
        }
        graphics.disableScissor();
    }

    /** True when the popup used the click; a click outside closes it and is used too. */
    public boolean mouseClicked(MouseButtonEvent event) {
        if (!isOpen()) {
            return false;
        }
        int row = rowAt(event.x(), event.y());
        if (row >= 0) {
            if (options.get(row).enabled()) {
                pick(row);
            }
        } else {
            close();
        }
        return true;
    }

    private void pick(int row) {
        IntConsumer callback = onPick;
        close();
        callback.accept(row);
    }

    public boolean mouseScrolled(double vertical) {
        if (!isOpen()) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) Math.round(vertical * ROW_H), 0, maxScroll());
        return true;
    }

    /** Up, Down, Enter and Escape work in the list; other keys are held back while it is open. */
    public boolean keyPressed(KeyEvent event) {
        if (!isOpen()) {
            return false;
        }
        switch (event.key()) {
            case InputConstants.KEY_ESCAPE -> close();
            case InputConstants.KEY_DOWN -> moveKeyboardRow(1);
            case InputConstants.KEY_UP -> moveKeyboardRow(-1);
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_SPACE -> {
                int row = keyboardRow >= 0 ? keyboardRow : chosen;
                if (options.get(row).enabled()) {
                    pick(row);
                }
            }
            default -> {
            }
        }
        return true;
    }

    private void moveKeyboardRow(int step) {
        int from = keyboardRow >= 0 ? keyboardRow : chosen;
        keyboardRow = Math.floorMod(from + step, options.size());
        int top = keyboardRow * ROW_H;
        int visible = h - 2 * INSET;
        if (top < scroll) {
            scroll = top;
        } else if (top + ROW_H > scroll + visible) {
            scroll = top + ROW_H - visible;
        }
    }
}
