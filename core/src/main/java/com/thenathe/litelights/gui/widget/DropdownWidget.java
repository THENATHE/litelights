package com.thenathe.litelights.gui.widget;

import com.thenathe.litelights.gui.Glyphs;
import com.thenathe.litelights.gui.RowValue;
import com.thenathe.litelights.gui.Surfaces;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Shows the current choice with a down chevron; pressing it opens the screen's popup list. */
public final class DropdownWidget extends LiteButton {
    private static final int CHEVRON_ROOM = 12;

    private final RowValue row;
    private final PopupList popup;

    public DropdownWidget(int w, int h, RowValue row, PopupList popup) {
        super(0, 0, w, h, Component.empty(), () -> { });
        this.row = row;
        this.popup = popup;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (popup.isOpenFor(this)) {
            popup.close();
            return;
        }
        List<PopupList.Option> options = new ArrayList<>();
        List<Component> choices = row.choices();
        for (int i = 0; i < choices.size(); i++) {
            options.add(new PopupList.Option(choices.get(i), row.choiceTag(i), row.choiceEnabled(i)));
        }
        popup.open(this, options, row.value(), row::set);
    }

    @Override
    public Component getMessage() {
        return row.format(row.value());
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean hot = lit() || popup.isOpenFor(this);
        Surfaces.plate(graphics, getX(), getY(), width, height, face(hot, false));
        int colour = textColour(hot, false);
        drawLabel(graphics, colour, CHEVRON_ROOM);
        Glyphs.CHEVRON_DOWN.draw(graphics, getX() + width - CHEVRON_ROOM + 1, getY() + (height - Glyphs.CHEVRON_DOWN.height()) / 2, colour);
    }
}
