package com.thenathe.litelights.render.feature.farterrain.seed;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Where a player types the seed of the server they are on (Far Terrain page, Server seed). The
 * field never shows a seed already given (it is not shown anywhere, as the seed helper's rule
 * asks), only whether there is one; Save takes a whole number, Forget deletes this server's seed.
 */
public final class SeedEntryScreen extends Screen {
    private static final int PAD = 12;
    private static final int LINE = 10;

    private final @Nullable Screen parent;
    private final boolean hasSeed;
    private final LongConsumer saved;
    private final Runnable forgotten;
    private EditBox field;
    private Component message = Component.empty();
    private List<FormattedCharSequence> help = List.of();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public SeedEntryScreen(@Nullable Screen parent, boolean hasSeed, LongConsumer saved, Runnable forgotten) {
        super(Component.translatable("litelights.seed_entry.title"));
        this.parent = parent;
        this.hasSeed = hasSeed;
        this.saved = saved;
        this.forgotten = forgotten;
    }

    @Override
    protected void init() {
        panelW = Math.min(360, width - 16);
        int inner = panelW - 2 * PAD;
        help = font.split(Component.translatable(hasSeed ? "litelights.seed_entry.help_saved" : "litelights.seed_entry.help"), inner);
        panelH = PAD + LINE + 8 + help.size() * LINE + 8 + 20 + 6 + LINE + 8 + 20 + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(4, (height - panelH) / 2);
        int y = panelY + PAD + LINE + 8 + help.size() * LINE + 8;
        field = new EditBox(font, panelX + PAD, y, inner, 20, Component.translatable("litelights.seed_entry.field"));
        field.setMaxLength(21);
        field.setHint(Component.translatable("litelights.seed_entry.hint"));
        addRenderableWidget(field);
        setInitialFocus(field);
        int buttons = panelY + panelH - PAD - 20;
        int third = (inner - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("litelights.seed_entry.save"), button -> save())
            .bounds(panelX + PAD, buttons, third, 20).build());
        Button forget = Button.builder(Component.translatable("litelights.seed_entry.forget"), button -> {
            forgotten.run();
            minecraft.gui.setScreen(parent);
        }).bounds(panelX + PAD + third + 4, buttons, third, 20).build();
        forget.active = hasSeed;
        addRenderableWidget(forget);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
            .bounds(panelX + PAD + 2 * (third + 4), buttons, third, 20).build());
    }

    private void save() {
        OptionalLong seed = ServerSeeds.parse(field.getValue());
        if (seed.isEmpty()) {
            message = Component.translatable("litelights.seed_entry.not_a_number");
            return;
        }
        saved.accept(seed.getAsLong());
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
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
        for (FormattedCharSequence line : help) {
            graphics.text(font, line, x, y, 0xFFD8DEE4, false);
            y += LINE;
        }
        graphics.text(font, message, x, field.getY() + 26, 0xFFFF8A80, false);
    }

    /** For QA: types a seed and saves it. */
    public void enterForTest(String text) {
        field.setValue(text);
        save();
    }
}
