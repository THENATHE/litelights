package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.RowValue;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** A stepped slider: a sunken well filled in amber up to a light handle, with the value in the middle. */
public final class SliderWidget extends AbstractSliderButton {
    private static final int HANDLE_W = 4;

    private final RowValue row;

    public SliderWidget(int w, int h, RowValue row) {
        super(0, 0, w, h, Component.empty(), fraction(row, row.value()));
        this.row = row;
        updateMessage();
    }

    private static double fraction(RowValue row, int value) {
        int span = row.max() - row.min();
        return span == 0 ? 0 : (double) (value - row.min()) / span;
    }

    private int steppedValue() {
        double raw = row.min() + value * (row.max() - row.min());
        int steps = (int) Math.round((raw - row.min()) / row.step());
        return Math.min(row.max(), row.min() + steps * row.step());
    }

    @Override
    protected void updateMessage() {
        setMessage(row.format(steppedValue()));
    }

    @Override
    protected void applyValue() {
        row.set(steppedValue());
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // A reset or a change from another screen moves the value; follow it so the handle is never stale.
        double current = fraction(row, row.value());
        if (Math.abs(current - fraction(row, steppedValue())) > 1.0E-9) {
            value = current;
            updateMessage();
        }
        int x = getX();
        int y = getY();
        boolean hot = active && Ink.hot(this);
        Surfaces.well(graphics, x, y, width, height, hot ? Theme.ACCENT_DEEP : Theme.WELL_EDGE);
        int handleX = x + 1 + (int) Math.round(value * (width - 2 - HANDLE_W));
        int fill = active ? Theme.withAlpha(Theme.ACCENT_DEEP, 0xC8) : Theme.shade(Theme.ACCENT_DEEP, -0.5F);
        if (handleX > x + 2) {
            graphics.fill(x + 2, y + 2, handleX, y + height - 2, fill);
        }
        int handle = active ? (hot ? Theme.TEXT_STRONG : Theme.ACCENT_HOT) : Theme.TEXT_OFF;
        Surfaces.rounded(graphics, handleX, y + 1, HANDLE_W, height - 2, handle);
        Font font = Minecraft.getInstance().font;
        String text = Ink.trim(font, getMessage().getString(), width - 6);
        int colour = active ? Theme.TEXT_STRONG : Theme.TEXT_OFF;
        graphics.text(font, text, x + (width - font.width(text)) / 2, y + (height - 8) / 2 + 1, colour, true);
    }
}
