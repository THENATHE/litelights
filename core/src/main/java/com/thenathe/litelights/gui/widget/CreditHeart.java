package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.PageIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The small heart after a credited row's name (or a page's first caption). Its tooltip is the
 * credit, word for word, shown under the pointer or while the heart has keyboard focus, and the
 * narrator reads it. It does nothing when clicked; it only takes the click, so a caption under it
 * doesn't fold.
 */
public final class CreditHeart extends AbstractWidget {
    public static final int WIDTH = 9;
    public static final int HEIGHT = 10;

    private final Component credit;

    public CreditHeart(Component credit) {
        super(0, 0, WIDTH, HEIGHT, Component.translatable("litelights.credit.heart"));
        this.credit = credit;
        setTooltip(Tooltip.create(credit));
    }

    public Component credit() {
        return credit;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        PageIcons.draw(graphics, "credit_heart", getX() + 1, getY() + 2, !Ink.hot(this));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return active && visible && isMouseOver(event.x(), event.y());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
