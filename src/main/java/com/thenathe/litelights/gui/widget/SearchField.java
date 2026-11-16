package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.Glyphs;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The search box: a sunken well with a magnifier, and a small cross that empties it. Vanilla's
 * own frame is turned off; the editable text sits inside the well.
 */
public final class SearchField extends EditBox {
    private static final int TEXT_LEFT = 16;
    private static final int CLEAR_ROOM = 14;

    private int frameX;
    private int frameY;
    private int frameW;
    private int frameH;

    public SearchField(int x, int y, int w, int h, Component hint) {
        super(Minecraft.getInstance().font, 0, 0, 0, 0, hint);
        setBordered(false);
        setMaxLength(64);
        setHint(hint.copy().withColor(Theme.TEXT_OFF));
        setTextColor(Theme.TEXT);
        setFrame(x, y, w, h);
    }

    public void setFrame(int x, int y, int w, int h) {
        frameX = x;
        frameY = y;
        frameW = w;
        frameH = h;
        setX(x + TEXT_LEFT);
        setY(y + (h - 8) / 2);
        setWidth(Math.max(10, w - TEXT_LEFT - CLEAR_ROOM));
        setHeight(10);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= frameX && mouseX < frameX + frameW && mouseY >= frameY && mouseY < frameY + frameH;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean focused = isFocused();
        int edge = focused ? Theme.ACCENT : isMouseOver(mouseX, mouseY) ? Theme.ACCENT_DEEP : Theme.WELL_EDGE;
        Surfaces.well(graphics, frameX, frameY, frameW, frameH, edge);
        Glyphs.MAGNIFIER.draw(graphics, frameX + 5, frameY + (frameH - Glyphs.MAGNIFIER.height()) / 2,
                focused ? Theme.ACCENT : Theme.TEXT_DIM);
        super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
        if (!getValue().isEmpty()) {
            boolean hot = overClear(mouseX, mouseY);
            Glyphs.CROSS.draw(graphics, clearX(), frameY + (frameH - Glyphs.CROSS.height()) / 2, hot ? Theme.ACCENT_HOT : Theme.TEXT_DIM);
        }
    }

    private int clearX() {
        return frameX + frameW - 11;
    }

    private boolean overClear(double mouseX, double mouseY) {
        return mouseX >= clearX() - 3 && mouseX < frameX + frameW && mouseY >= frameY && mouseY < frameY + frameH;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!getValue().isEmpty() && overClear(event.x(), event.y())) {
            setValue("");
            setFocused(true);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}
