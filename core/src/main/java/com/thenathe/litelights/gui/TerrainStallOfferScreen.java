package com.thenathe.litelights.gui;

import com.thenathe.litelights.gui.widget.LiteButton;
import com.thenathe.litelights.render.pipeline.TerrainParts;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The Mac safety net's offer, over the title screen after a launch that froze while drawing the
 * world's blocks (TerrainStallMarker): vanilla terrain shading for this session, or carry on as
 * usual. Nothing it does is saved. Escape keeps LiteLights' shading.
 */
public final class TerrainStallOfferScreen extends Screen {
    private static final int PAD = 10;
    private static final int LINE = 10;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;
    private static final String[] PARAGRAPHS = {"what_happened", "what_it_does", "to_keep"};

    private final Screen parent;
    private LiteButton useVanilla;
    private LiteButton keep;
    private List<FormattedCharSequence> text = List.of();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public TerrainStallOfferScreen(Screen parent) {
        super(Component.translatable("litelights.stall_offer.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(400, width - 16);
        int innerW = panelW - 2 * PAD;
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph : PARAGRAPHS) {
            if (!lines.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
            }
            lines.addAll(font.split(Component.translatable("litelights.stall_offer." + paragraph), innerW));
        }
        text = lines;
        panelH = PAD + LINE + 6 + text.size() * LINE + 10 + 2 * BUTTON_H + GAP + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(4, (height - panelH) / 2);

        int left = panelX + PAD;
        int y = panelY + panelH - PAD - 2 * BUTTON_H - GAP;
        useVanilla = addRenderableWidget(new LiteButton(left, y, innerW, BUTTON_H,
            Component.translatable("litelights.stall_offer.use"), this::useVanillaShading));
        // The answer the offer is for stands out in the accent colour.
        useVanilla.chosenWhen(() -> true);
        keep = addRenderableWidget(new LiteButton(left, y + BUTTON_H + GAP, innerW, BUTTON_H,
            Component.translatable("litelights.stall_offer.keep"), this::onClose));
    }

    private void useVanillaShading() {
        TerrainParts.useVanillaShadingThisSession();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, Theme.WORLD_DIM_DOCKED);
        Surfaces.panel(graphics, panelX, panelY, panelW, panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = panelX + PAD;
        int y = panelY + PAD;
        graphics.text(font, title, x, y, Theme.TITLE, true);
        y += LINE + 6;
        for (FormattedCharSequence line : text) {
            graphics.text(font, line, x, y, Theme.TEXT, false);
            y += LINE;
        }
    }

    /** For QA: the middle of the "use vanilla" (true) or "keep" (false) button. */
    public int[] buttonPoint(boolean useVanillaButton) {
        LiteButton button = useVanillaButton ? useVanilla : keep;
        return new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
    }

    /** For QA: whether the panel lies wholly inside the window. */
    public boolean fitsWindow() {
        return panelX >= 0 && panelY >= 0 && panelX + panelW <= width && panelY + panelH <= height;
    }
}
