package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.Glyphs;
import com.thenathe.litelights.gui.Surfaces;
import com.thenathe.litelights.gui.Theme;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * The button used everywhere: footer, choice segments, actions. A chosen button is filled with
 * the amber accent. It can carry a glyph before its label, or only a glyph.
 */
public class LiteButton extends AbstractButton {
    private final Runnable action;
    private BooleanSupplier chosen = () -> false;
    private Glyphs glyph;
    private boolean glyphOnly;
    private Component tag;

    public LiteButton(int x, int y, int w, int h, Component label, Runnable action) {
        super(x, y, w, h, label);
        this.action = action;
    }

    public LiteButton chosenWhen(BooleanSupplier chosen) {
        this.chosen = chosen;
        return this;
    }

    /** Puts a glyph before the label. */
    public LiteButton glyph(Glyphs glyph) {
        this.glyph = glyph;
        return this;
    }

    /** Shows only the glyph; the label is still used for narration. */
    public LiteButton glyphOnly(Glyphs glyph) {
        this.glyph = glyph;
        this.glyphOnly = true;
        return this;
    }

    /** A small dim word after the label, such as "Experimental". */
    public LiteButton tag(Component tag) {
        this.tag = tag;
        return this;
    }

    protected boolean isChosen() {
        return chosen.getAsBoolean();
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    protected boolean lit() {
        return active && Ink.hot(this);
    }

    protected int face(boolean hot, boolean isChosen) {
        if (!active && !isChosen) {
            return Theme.shade(Theme.RAISED, -0.3F);
        }
        if (isChosen) {
            return hot ? Theme.ACCENT_HOT : Theme.ACCENT;
        }
        return hot ? Theme.RAISED_HOT : Theme.RAISED;
    }

    protected int textColour(boolean hot, boolean isChosen) {
        if (isChosen) {
            return Theme.ACCENT_TEXT;
        }
        if (!active) {
            return Theme.TEXT_OFF;
        }
        return hot ? Theme.TEXT_STRONG : Theme.TEXT;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean hot = lit();
        boolean isChosen = isChosen();
        Surfaces.plate(graphics, getX(), getY(), width, height, face(hot, isChosen));
        if (hot && !isChosen) {
            Surfaces.outline(graphics, getX(), getY(), width, height, Theme.ACCENT_DEEP);
        }
        drawLabel(graphics, textColour(hot, isChosen), 0);
    }

    /** Centres the glyph and label, leaving {@code reserved} pixels free at the right. */
    protected void drawLabel(GuiGraphicsExtractor graphics, int colour, int reserved) {
        Font font = Minecraft.getInstance().font;
        int room = width - 8 - reserved;
        if (glyphOnly) {
            glyph.drawCentred(graphics, getX(), getY(), width - reserved, height, colour);
            return;
        }
        int glyphW = glyph == null ? 0 : glyph.width() + 4;
        String text = Ink.trim(font, getMessage().getString(), room - glyphW);
        String tagText = tag == null ? "" : Ink.trim(font, tag.getString(), Math.max(0, room - glyphW - font.width(text) - 4));
        int tagW = tagText.isEmpty() ? 0 : font.width(tagText) + 4;
        int contentW = glyphW + font.width(text) + tagW;
        int x = getX() + (width - reserved - contentW) / 2;
        int textY = getY() + (height - 8) / 2;
        if (glyph != null) {
            glyph.draw(graphics, x, getY() + (height - glyph.height()) / 2, colour);
            x += glyphW;
        }
        boolean shadow = colour != Theme.ACCENT_TEXT;
        graphics.text(font, text, x, textY, colour, shadow);
        if (!tagText.isEmpty()) {
            graphics.text(font, tagText, x + font.width(text) + 4, textY, isChosen() ? Theme.ACCENT_TEXT : Theme.TEXT_DIM, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
