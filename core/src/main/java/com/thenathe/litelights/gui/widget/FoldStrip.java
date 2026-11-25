package com.thenathe.litelights.gui.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * The clickable strip over a section caption. It draws nothing; the screen paints the caption
 * and its chevron, so this only gives the caption mouse and keyboard focus.
 */
public final class FoldStrip extends AbstractButton {
    private final Runnable flip;

    public FoldStrip(int w, int h, Component caption, Runnable flip) {
        super(0, 0, w, h, caption);
        this.flip = flip;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        flip.run();
    }

    public boolean lit() {
        return Ink.hot(this);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
