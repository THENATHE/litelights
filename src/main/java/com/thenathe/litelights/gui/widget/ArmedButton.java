package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** For actions that can't be undone: the first click arms it, a second within four seconds acts. */
public final class ArmedButton extends LiteButton {
    private static final long ARMED_MS = 4000;

    private final Component idleLabel;
    private final Runnable action;
    private long armedUntil;

    public ArmedButton(int x, int y, int w, int h, Component label, Runnable action) {
        super(x, y, w, h, label, () -> { });
        this.idleLabel = label;
        this.action = action;
    }

    private boolean armed() {
        return Util.getMillis() < armedUntil;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (armed()) {
            armedUntil = 0;
            action.run();
        } else {
            armedUntil = Util.getMillis() + ARMED_MS;
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean armed = armed();
        setMessage(armed ? Component.translatable("litelights.confirm") : idleLabel);
        if (!armed) {
            super.extractContents(graphics, mouseX, mouseY, delta);
            return;
        }
        Surfaces.plate(graphics, getX(), getY(), width, height, lit() ? Theme.shade(Theme.ERROR_PLATE, 0.15F) : Theme.ERROR_PLATE);
        drawLabel(graphics, Theme.TEXT_STRONG, 0);
    }
}
