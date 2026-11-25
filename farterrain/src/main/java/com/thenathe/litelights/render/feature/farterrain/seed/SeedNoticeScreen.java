package com.thenathe.litelights.render.feature.farterrain.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The notice before seed generation on a server whose terrain may not match what this game would
 * generate (the user's rule: never block, say it may not be right and ask). Three answers:
 * Generate (this session), Not now (this session), Don't ask again for this server (saved).
 * Escape is Not now. It says what is known: whether the seed is this world's, which mods and packs
 * differ when the server's helper said, or that the server's mods can't be checked when it didn't.
 */
public final class SeedNoticeScreen extends Screen {
    /** The player's answer. */
    public enum Answer { GENERATE, NOT_NOW, ALWAYS }

    private static final int PAD = 12;
    private static final int LINE = 10;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;

    private final @Nullable Screen parent;
    private final List<Component> paragraphs;
    private final Consumer<Answer> answered;
    private List<FormattedCharSequence> lines = List.of();
    private boolean done;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public SeedNoticeScreen(@Nullable Screen parent, List<Component> paragraphs, Consumer<Answer> answered) {
        super(Component.translatable("litelights.seed_notice.title"));
        this.parent = parent;
        this.paragraphs = paragraphs;
        this.answered = answered;
    }

    @Override
    protected void init() {
        panelW = Math.min(420, width - 16);
        int inner = panelW - 2 * PAD;
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component paragraph : paragraphs) {
            if (!wrapped.isEmpty()) {
                wrapped.add(FormattedCharSequence.EMPTY);
            }
            wrapped.addAll(font.split(paragraph, inner));
        }
        lines = wrapped;
        panelH = PAD + LINE + 8 + lines.size() * LINE + 12 + 3 * BUTTON_H + 2 * GAP + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(4, (height - panelH) / 2);
        int y = panelY + panelH - PAD - 3 * BUTTON_H - 2 * GAP;
        int x = panelX + PAD;
        addRenderableWidget(Button.builder(Component.translatable("litelights.seed_notice.generate"), button -> answer(Answer.GENERATE))
            .bounds(x, y, inner, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("litelights.seed_notice.not_now"), button -> answer(Answer.NOT_NOW))
            .bounds(x, y + BUTTON_H + GAP, inner, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("litelights.seed_notice.always"), button -> answer(Answer.ALWAYS))
            .bounds(x, y + 2 * (BUTTON_H + GAP), inner, BUTTON_H).build());
    }

    private void answer(Answer answer) {
        if (!done) {
            done = true;
            answered.accept(answer);
        }
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        answer(Answer.NOT_NOW);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE0101418);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF6A7A8A);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = panelX + PAD;
        int y = panelY + PAD;
        graphics.text(font, title, x, y, 0xFFFFFFFF, true);
        y += LINE + 8;
        for (FormattedCharSequence line : lines) {
            graphics.text(font, line, x, y, 0xFFD8DEE4, false);
            y += LINE;
        }
    }

    /** For QA: the middle of an answer's button. */
    public int[] buttonPoint(Answer answer) {
        int inner = panelW - 2 * PAD;
        int y = panelY + panelH - PAD - 3 * BUTTON_H - 2 * GAP + answer.ordinal() * (BUTTON_H + GAP);
        return new int[] {panelX + PAD + inner / 2, y + BUTTON_H / 2};
    }
}
