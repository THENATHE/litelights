package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.Glyphs;
import com.thenathe.litelights.gui.RowValue;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** The small reset at the end of a row; it only works while the row differs from its default. */
public final class ResetWidget extends AbstractButton {
    public static final int SIZE = 16;

    private final RowValue row;
    private final Runnable afterReset;

    public ResetWidget(RowValue row, Runnable afterReset) {
        super(0, 0, SIZE, SIZE, Component.translatable("litelights.reset"));
        this.row = row;
        this.afterReset = afterReset;
        setTooltip(Tooltip.create(Component.translatable("litelights.reset.tooltip")));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        row.reset();
        afterReset.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        active = row.changed() && row.state().editable();
        if (!active) {
            Glyphs.UNDO.drawCentred(graphics, getX(), getY(), width, height, Theme.withAlpha(Theme.TEXT_OFF, 0x70));
            return;
        }
        boolean hot = Ink.hot(this);
        Surfaces.plate(graphics, getX(), getY(), width, height, hot ? Theme.RAISED_HOT : Theme.RAISED);
        Glyphs.UNDO.drawCentred(graphics, getX(), getY(), width, height, hot ? Theme.ACCENT_HOT : Theme.CHANGED);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
