package com.thenathe.litelights.gui;

import com.thenathe.litelights.config.FirstSetup;
import com.thenathe.litelights.config.FirstSetup.Tier;
import com.thenathe.litelights.gui.widget.Ink;
import com.thenathe.litelights.gui.widget.LiteButton;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The first-time setup: a panel in the settings screen's style that asks how powerful the
 * computer is (FirstSetup). Four answers on the left, the creator's recommended under them, and a
 * box on the right saying who the pointed-at answer is for. Escape or closing it leaves every
 * setting as it is, which on a fresh install is the creator's recommended.
 */
public final class FirstSetupScreen extends Screen {
    private static final int PAD = 10;
    private static final int LINE = 10;
    private static final int BUTTON_H = 20;
    private static final int RECOMMENDED_H = 26;
    private static final int GAP = 4;
    private static final int BOX_PAD = 6;

    /** One answer: its button's text and what the box says about it. */
    private record Answer(String key, Runnable choose) {
        Component name() {
            return Component.translatable("litelights.setup." + key);
        }

        List<Component> lines() {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("litelights.setup." + key + ".description"));
            if (!key.equals("recommended")) {
                lines.add(Component.translatable("litelights.setup." + key + ".cpu"));
                lines.add(Component.translatable("litelights.setup." + key + ".gpu"));
            }
            return lines;
        }
    }

    private final Screen parent;
    private final List<Answer> answers = new ArrayList<>();
    private final List<LiteButton> buttons = new ArrayList<>();
    private Answer described;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int boxX;
    private int boxY;
    private int boxW;
    private int boxH;
    private int hintY;
    private List<FormattedCharSequence> subtitle = List.of();

    public FirstSetupScreen(Screen parent) {
        super(Component.translatable("litelights.setup.title"));
        this.parent = parent;
        for (Tier tier : Tier.values()) {
            answers.add(new Answer("tier." + tier.name().toLowerCase(Locale.ROOT), () -> FirstSetup.choose(tier)));
        }
        answers.add(new Answer("recommended", FirstSetup::useRecommended));
    }

    @Override
    protected void init() {
        buttons.clear();
        panelW = Math.min(440, width - 16);
        int innerW = panelW - 2 * PAD;
        int leftW = Math.min(190, (innerW - PAD) / 2);
        boxW = innerW - PAD - leftW;
        subtitle = font.split(Component.translatable("litelights.setup.subtitle"), innerW);

        int stackH = (Tier.values().length) * (BUTTON_H + GAP) + GAP + RECOMMENDED_H;
        boxH = Math.max(stackH, tallestDescription());
        panelH = PAD + LINE + 4 + subtitle.size() * LINE + 8 + boxH + 8 + LINE + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(4, (height - panelH) / 2);

        int left = panelX + PAD;
        int y = panelY + PAD + LINE + 4 + subtitle.size() * LINE + 8;
        boxX = left + leftW + PAD;
        boxY = y;
        for (Answer answer : answers) {
            boolean recommended = answer.key().equals("recommended");
            if (recommended) {
                y += GAP;
            }
            int h = recommended ? RECOMMENDED_H : BUTTON_H;
            LiteButton button = new LiteButton(left, y, leftW, h, answer.name(), () -> pick(answer));
            if (recommended) {
                // The answer most players should take stands out in the accent colour.
                button.chosenWhen(() -> true);
            }
            buttons.add(addRenderableWidget(button));
            y += h + GAP;
        }
        hintY = boxY + boxH + 8;
    }

    /** The box's height for the longest of the five descriptions at this width. */
    private int tallestDescription() {
        int tallest = 0;
        for (Answer answer : answers) {
            tallest = Math.max(tallest, descriptionLines(answer).size());
        }
        return BOX_PAD + LINE + 2 + tallest * LINE + BOX_PAD;
    }

    private List<FormattedCharSequence> descriptionLines(Answer answer) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Component> parts = answer.lines();
        for (int i = 0; i < parts.size(); i++) {
            if (i == 1) {
                lines.add(FormattedCharSequence.EMPTY);
            }
            lines.addAll(font.split(parts.get(i), boxW - 2 * BOX_PAD));
        }
        return lines;
    }

    private void pick(Answer answer) {
        answer.choose().run();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        FirstSetup.dismiss();
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
        y += LINE + 4;
        for (FormattedCharSequence line : subtitle) {
            graphics.text(font, line, x, y, Theme.TEXT_DIM, false);
            y += LINE;
        }
        for (int i = 0; i < buttons.size(); i++) {
            if (Ink.hot(buttons.get(i))) {
                described = answers.get(i);
            }
        }
        drawDescription(graphics);
        graphics.text(font, Component.translatable("litelights.setup.hint"), x, hintY, Theme.TEXT_DIM, false);
    }

    private void drawDescription(GuiGraphicsExtractor graphics) {
        Surfaces.framed(graphics, boxX, boxY, boxW, boxH, Theme.WELL, Theme.CARD_EDGE);
        int x = boxX + BOX_PAD;
        int y = boxY + BOX_PAD;
        if (described == null) {
            for (FormattedCharSequence line : font.split(Component.translatable("litelights.setup.point"), boxW - 2 * BOX_PAD)) {
                graphics.text(font, line, x, y, Theme.TEXT_DIM, false);
                y += LINE;
            }
            return;
        }
        graphics.text(font, described.name(), x, y, Theme.TEXT_STRONG, true);
        y += LINE + 2;
        for (FormattedCharSequence line : descriptionLines(described)) {
            graphics.text(font, line, x, y, Theme.TEXT, false);
            y += LINE;
        }
    }

    /** For QA: the middle of an answer's button, by its translation key's last part ("tier.top", "recommended"). */
    public int[] answerPoint(String key) {
        for (int i = 0; i < answers.size(); i++) {
            if (answers.get(i).key().equals(key)) {
                LiteButton button = buttons.get(i);
                return new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
            }
        }
        return null;
    }

    /** For QA: the answer the box describes, or null while it shows the prompt. */
    public String describedKey() {
        return described == null ? null : described.key();
    }

    /** For QA: whether the panel lies wholly inside the window. */
    public boolean fitsWindow() {
        return panelX >= 0 && panelY >= 0 && panelX + panelW <= width && panelY + panelH <= height;
    }
}
