package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.RowValue;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.MutableComponent;

/** A 26x14 on/off switch: a rounded track, amber when on, with a light knob that slides across. */
public final class ToggleWidget extends AbstractButton {
    public static final int WIDTH = 26;
    public static final int HEIGHT = 14;
    private static final int KNOB = 10;

    private final RowValue row;

    public ToggleWidget(RowValue row) {
        super(0, 0, WIDTH, HEIGHT, row.label());
        this.row = row;
    }

    private boolean on() {
        return row.value() != 0;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        row.set(on() ? 0 : 1);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean hot = active && Ink.hot(this);
        int x = getX();
        int y = getY();
        int track;
        if (on()) {
            track = active ? (hot ? Theme.ACCENT_HOT : Theme.ACCENT) : Theme.shade(Theme.ACCENT, -0.55F);
        } else {
            track = Theme.WELL;
        }
        Surfaces.framed(graphics, x, y, WIDTH, HEIGHT, track, hot ? Theme.ACCENT_DEEP : Theme.WELL_EDGE);
        int knobX = on() ? x + WIDTH - KNOB - 2 : x + 2;
        int knob = active ? (hot ? Theme.TEXT_STRONG : Theme.TEXT) : Theme.TEXT_OFF;
        Surfaces.rounded(graphics, knobX, y + 2, KNOB, HEIGHT - 4, knob);
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return CommonComponents.optionStatus(getMessage(), on());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
