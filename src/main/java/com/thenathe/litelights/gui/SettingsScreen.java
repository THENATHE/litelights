package com.thenathe.litelights.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.thenathe.litelights.config.LiteLightsConfig;
import com.thenathe.litelights.config.Pages;
import com.thenathe.litelights.config.RowState;
import com.thenathe.litelights.gui.widget.ArmedButton;
import com.thenathe.litelights.gui.widget.DropdownWidget;
import com.thenathe.litelights.gui.widget.FoldStrip;
import com.thenathe.litelights.gui.widget.Ink;
import com.thenathe.litelights.gui.widget.LiteButton;
import com.thenathe.litelights.gui.widget.PopupList;
import com.thenathe.litelights.gui.widget.ResetWidget;
import com.thenathe.litelights.gui.widget.SearchField;
import com.thenathe.litelights.gui.widget.SliderWidget;
import com.thenathe.litelights.gui.widget.ToggleWidget;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

/**
 * The LiteLights settings. Docked at the left over the live world by default, so every change
 * shows at once, or full screen. Pages down the left, the rows of the chosen page on the right,
 * a description strip under them and the time-of-day preview, frame time and Done at the bottom.
 *
 * <p>Changes apply as they are made. The file is written once, when the screen goes away.
 */
public final class SettingsScreen extends Screen {
    private static final String STATE_MODULE = "settings_screen";
    private static final int SCROLL_STEP = 20;
    private static final int ROW_H = 22;
    private static final int CONTROL_H = 16;
    private static final int LINE = 10;
    private static final int FIRST_CAPTION_H = 14;
    private static final int CAPTION_H = 22;
    private static final int SUBHEADING_H = 16;

    /** The page last open in this session, so reopening lands where the player left off. */

    private final Screen parent;
    private final PopupList popup = new PopupList();
    private final List<Pages.PageInfo> pages = Pages.all();
    private final Map<String, PageModel> models = new LinkedHashMap<>();
    private final Map<String, Boolean> folded = new HashMap<>();
    private final List<ListRow> rows = new ArrayList<>();
    private final List<AbstractWidget> listWidgets = new ArrayList<>();
    private final FrameTimer frameTimer = new FrameTimer();

    private ScreenLayout layout;
    private SearchField search;
    private String page;
    private String query = "";
    private int scroll;
    private int contentHeight;
    private boolean draggingScrollbar;
    private Object described;
    private int describeScroll;
    private boolean previewLabelShown;
    private List<Object> builtLayout = List.of();
    /** The pointer while the list draws (-1 outside it), and the compact row tag under it, if any. */
    private int listPointerX = -1;
    private int listPointerY = -1;
    private Component hoveredTag;

    private enum RowKind { CAPTION, HEADER, SUBHEADING, NOTE, SETTING, RECOMMENDATION, ACTION, CARD }

    /** A laid-out line of the list; {@code source} is the section, entry or page name it shows. */
    private record ListRow(RowKind kind, Object source, int y, int h, boolean grouped, List<Placed> widgets) {
    }

    /** A widget and its offset from the row's top-left corner. */
    private record Placed(AbstractWidget widget, int dx, int dy) {
    }

    private SettingsScreen(Screen parent, String page) {
        super(Component.translatable("litelights.title"));
        this.parent = parent;
        BuiltInPages.register();
        String wanted = page != null ? page : stringOr(screenState(), "page", null);
        this.page = wanted != null && (Pages.find(wanted) != null || Credits.PAGE.equals(wanted)) ? wanted : Pages.GENERAL;
    }

    public static Screen create(Screen parent) {
        return new SettingsScreen(parent, null);
    }

    public static Screen create(Screen parent, String page) {
        return new SettingsScreen(parent, page);
    }

    /** Opens the Credits page if this screen is the one showing; used by the About page's button. */
    static void showCreditsOnOpenScreen() {
        if (Minecraft.getInstance().gui.screen() instanceof SettingsScreen screen) {
            screen.choosePage(Credits.PAGE);
        }
    }

    private static JsonObject screenState() {
        return LiteLightsConfig.module(STATE_MODULE);
    }

    private static boolean fullScreenWanted() {
        return booleanOr(screenState(), "fullScreen", false);
    }

    // litelights.json can be edited by hand: a value of the wrong kind falls back instead of
    // stopping the screen from opening.
    private static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static boolean booleanOr(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
    }

    private boolean searching() {
        return !query.isBlank();
    }

    @Override
    protected void init() {
        popup.close();
        buildModels();
        layout = ScreenLayout.of(width, height, fullScreenWanted(), pages.size());

        search = new SearchField(layout.searchX(), layout.searchY(), layout.searchW(), ScreenLayout.SEARCH_H,
                Component.translatable("litelights.search"));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            scroll = 0;
            rebuildList();
        });
        addRenderableWidget(search);

        if (layout.docked() || ScreenLayout.of(width, height, false, pages.size()).docked()) {
            addFullScreenToggle();
        }

        addNavigation();
        addFooter();
        rebuildList();
    }

    private void addFullScreenToggle() {
        LiteButton fullScreen = new LiteButton(layout.panelRight() - ScreenLayout.PAD - 14, layout.titleY() - 2, 14, 14,
                Component.translatable(layout.docked() ? "litelights.full_screen" : "litelights.dock"), this::toggleFullScreen)
                .glyphOnly(layout.docked() ? Glyphs.EXPAND : Glyphs.DOCK);
        fullScreen.setTooltip(Tooltip.create(fullScreen.getMessage()));
        addRenderableWidget(fullScreen);
    }

    private void buildModels() {
        models.clear();
        for (Pages.PageInfo info : pages) {
            models.put(info.id(), PageModel.build(info.id()));
        }
        models.put(Credits.PAGE, PageModel.credits());
    }

    private void toggleFullScreen() {
        screenState().addProperty("fullScreen", layout.docked());
        LiteLightsConfig.save();
        rebuildWidgets();
    }

    private void addNavigation() {
        for (int i = 0; i < pages.size(); i++) {
            Pages.PageInfo info = pages.get(i);
            int w = layout.navAcross() ? layout.navStep() - 1 : layout.navW();
            addRenderableWidget(new NavButton(layout.navItemX(i), layout.navItemY(i), w, layout.navItemH(), info.id(), info.icon(),
                    Component.translatable(info.labelKey()), layout.navLabels()));
        }
        int creditsW = layout.navAcross() ? layout.navStep() - 1 : layout.navW();
        addRenderableWidget(new NavButton(layout.creditsX(), layout.creditsY(), creditsW,
                layout.navAcross() ? layout.navItemH() : ScreenLayout.CREDITS_H, Credits.PAGE, "credits",
                Component.translatable("litelights.page.credits"), layout.navLabels() || !layout.navAcross()));
    }

    /** A page in the navigation: icon, name when there's room, an amber bar when it's open. */
    private final class NavButton extends LiteButton {
        private final String pageId;
        private final String icon;
        private final boolean withLabel;

        NavButton(int x, int y, int w, int h, String pageId, String icon, Component label, boolean withLabel) {
            super(x, y, w, h, label, () -> choosePage(pageId));
            this.pageId = pageId;
            this.icon = icon;
            this.withLabel = withLabel;
            if (!withLabel || w < 60) {
                setTooltip(Tooltip.create(label));
            }
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            boolean open = !searching() && page.equals(pageId);
            boolean hot = lit();
            int x = getX();
            int y = getY();
            if (open) {
                Surfaces.rounded(graphics, x, y, width, height, Theme.ACCENT_WASH);
                graphics.fill(x, y + 1, x + 2, y + height - 1, Theme.ACCENT);
            } else if (hot) {
                Surfaces.rounded(graphics, x, y, width, height, Theme.HOVER);
            }
            int iconX = withLabel && width >= 60 ? x + 5 : x + (width - PageIcons.SIZE) / 2;
            PageIcons.draw(graphics, icon, iconX, y + (height - PageIcons.SIZE) / 2, !open && !hot);
            if (withLabel && width >= 60) {
                int colour = open ? Theme.ACCENT_HOT : hot ? Theme.TEXT_STRONG : Theme.TEXT;
                String text = Ink.trim(font, getMessage().getString(), width - 26);
                graphics.text(font, text, x + 20, y + (height - 8) / 2, colour, true);
            }
            PageModel model = models.get(pageId);
            if (model != null && model.changedCount() > 0) {
                graphics.fill(x + width - 5, y + 2, x + width - 2, y + 5, Theme.CHANGED);
            }
        }
    }

    private void addFooter() {
        int y = layout.footerY();
        int left = layout.panelX() + ScreenLayout.PAD;
        int right = layout.panelRight() - ScreenLayout.PAD;
        int doneW = Math.max(46, font.width(CommonComponents.GUI_DONE) + 14);
        addRenderableWidget(new LiteButton(right - doneW, y, doneW, ScreenLayout.FOOTER_H, CommonComponents.GUI_DONE, this::onClose));

        // The three preview buttons get their names when there's room, otherwise only their pictures.
        // Each is as wide as its longest name, so clicking through its choices never moves anything.
        PreviewControls[] controls = PreviewControls.values();
        int room = right - doneW - 60 - left;
        Component previewLabel = Component.translatable("litelights.preview");
        int labelW = font.width(previewLabel) + 6;
        int[] namedW = new int[controls.length];
        int namedTotal = 0;
        for (int i = 0; i < controls.length; i++) {
            for (int choice = 0; choice < controls[i].count(); choice++) {
                int glyphW = controls[i].glyph(choice).width() + 4;
                namedW[i] = Math.max(namedW[i], font.width(controls[i].name(choice)) + glyphW + 12);
            }
            namedTotal += namedW[i] + 2;
        }
        boolean named = labelW + namedTotal <= room;
        boolean labelled = labelW + controls.length * (PREVIEW_GLYPH_W + 2) <= room;
        int x = left + (labelled ? labelW : 0);
        for (int i = 0; i < controls.length; i++) {
            int w = named ? namedW[i] : PREVIEW_GLYPH_W;
            addRenderableWidget(new PreviewButton(x, y, w, controls[i], named));
            x += w + 2;
        }
        previewLabelShown = labelled;
    }

    private static final int PREVIEW_GLYPH_W = 22;

    /** A preview button: shows its current choice and steps to the next one on each click. */
    private static final class PreviewButton extends LiteButton {
        private final PreviewControls control;
        private final boolean named;
        private int shown = -1;

        PreviewButton(int x, int y, int w, PreviewControls control, boolean named) {
            super(x, y, w, ScreenLayout.FOOTER_H, control.name(control.current()), control::advance);
            this.control = control;
            this.named = named;
            chosenWhen(() -> control.current() != 0);
            follow();
        }

        /**
         * Takes on the current choice's name, picture and tooltip whenever it changed, or the switch
         * the choice needs was turned on or off (the tooltip says when it is off).
         */
        private void follow() {
            int current = control.current();
            int state = current * 2 + (control.switchedOff(current) ? 1 : 0);
            if (state == shown) {
                return;
            }
            shown = state;
            setMessage(control.name(current));
            if (named) {
                glyph(control.glyph(current));
            } else {
                glyphOnly(control.glyph(current));
            }
            setTooltip(Tooltip.create(control.tooltip()));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            super.onPress(input);
            follow();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            follow();
            super.extractContents(graphics, mouseX, mouseY, delta);
        }
    }

    private void choosePage(String pageId) {
        popup.close();
        page = pageId;
        scroll = 0;
        if (search != null) {
            search.setValue("");
        }
        query = "";
        // Contributors can show live data, so a page is built fresh each time it opens.
        models.put(pageId, Credits.PAGE.equals(pageId) ? PageModel.credits() : PageModel.build(pageId));
        rebuildList();
    }

    // Building the list

    private void rebuildList() {
        for (AbstractWidget widget : listWidgets) {
            removeWidget(widget);
        }
        listWidgets.clear();
        rows.clear();

        int y = 0;
        if (searching()) {
            y = buildSearchResults(y);
        } else {
            y = buildPage(models.get(page), y);
        }
        contentHeight = rows.isEmpty() ? 0 : y + 4;
        scroll = Math.clamp(scroll, 0, maxScroll());
        placeWidgets();
        builtLayout = layoutSignature();
    }

    private int buildPage(PageModel model, int y) {
        for (PageModel.Section section : model.sections()) {
            boolean grouped = false;
            if (section.caption() != null) {
                y = addCaption(model.page(), section, y);
                grouped = true;
                if (isFolded(model.page(), section)) {
                    continue;
                }
            }
            for (PageModel.Entry entry : section.entries()) {
                y = addEntry(entry, y, grouped);
            }
        }
        return y;
    }

    private int buildSearchResults(int y) {
        String needle = query.toLowerCase(Locale.ROOT).strip();
        for (Pages.PageInfo info : pages) {
            PageModel model = models.get(info.id());
            List<PageModel.Entry> matches = new ArrayList<>();
            for (PageModel.Section section : model.sections()) {
                String caption = section.caption() == null ? "" : section.caption().getString().toLowerCase(Locale.ROOT);
                PageModel.Subheading heading = null;
                for (PageModel.Entry entry : section.entries()) {
                    if (entry instanceof PageModel.Subheading subheading) {
                        heading = subheading;
                        continue;
                    }
                    String label = searchableLabel(entry);
                    String headingText = heading == null ? "" : heading.caption().getString().toLowerCase(Locale.ROOT);
                    if (label != null && (label.contains(needle) || caption.contains(needle) || headingText.contains(needle))) {
                        // Rows under a sub-heading share their labels ("Fog density"): keep the heading above them.
                        if (heading != null && !matches.contains(heading)) {
                            matches.add(heading);
                        }
                        matches.add(entry);
                    }
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            int headerH = rows.isEmpty() ? FIRST_CAPTION_H : CAPTION_H;
            rows.add(new ListRow(RowKind.HEADER, Component.translatable(info.labelKey()), y, headerH, false, List.of()));
            y += headerH;
            for (PageModel.Entry entry : matches) {
                y = addEntry(entry, y, true);
            }
        }
        return y;
    }

    private static String searchableLabel(PageModel.Entry entry) {
        return switch (entry) {
            case PageModel.Setting setting -> setting.value().shown() ? setting.value().label().getString().toLowerCase(Locale.ROOT) : null;
            case PageModel.Action action -> action.label().getString().toLowerCase(Locale.ROOT);
            default -> null;
        };
    }

    private String foldKey(String pageId, PageModel.Section section) {
        return pageId + "/" + section.key();
    }

    /** A section that starts folded stays open while something in it is changed, until the player folds it. */
    private boolean isFolded(String pageId, PageModel.Section section) {
        return folded.computeIfAbsent(foldKey(pageId, section),
                key -> section.startsFolded() && !sectionChanged(section));
    }

    private static boolean sectionChanged(PageModel.Section section) {
        return section.entries().stream().anyMatch(entry -> entry instanceof PageModel.Setting setting && setting.value().changed());
    }

    private int addCaption(String pageId, PageModel.Section section, int y) {
        int h = rows.isEmpty() ? FIRST_CAPTION_H : CAPTION_H;
        FoldStrip strip = new FoldStrip(layout.listW(), 12, section.caption(), () -> {
            String key = foldKey(pageId, section);
            folded.put(key, !folded.getOrDefault(key, false));
            rebuildList();
        });
        List<Placed> placed = List.of(new Placed(strip, 0, h - 13));
        register(placed);
        rows.add(new ListRow(RowKind.CAPTION, new CaptionState(pageId, section), y, h, false, placed));
        return y + h;
    }

    private record CaptionState(String pageId, PageModel.Section section) {
    }

    private int addEntry(PageModel.Entry entry, int y, boolean grouped) {
        return switch (entry) {
            case PageModel.Setting setting -> setting.value().shown() ? addSetting(setting.value(), y, grouped) : y;
            case PageModel.Subheading heading -> addSubheading(heading, y, grouped);
            case PageModel.Note note -> addNote(note, y, grouped);
            case PageModel.Recommendation recommendation -> addRecommendation(recommendation, y, grouped);
            case PageModel.Action action -> addAction(action, y, grouped);
            case PageModel.Card card -> addCard(card, y);
        };
    }

    /** The line under a row: its feature's reason if it leads the feature, then its credit. */
    private List<Detail> details(RowValue value) {
        List<Detail> lines = new ArrayList<>();
        RowState state = value.state();
        if (value.leadsFeature()) {
            switch (state) {
                case RowState.Failed failed -> lines.add(new Detail(reason(failed.reason()), Theme.ERROR));
                case RowState.Unavailable unavailable -> lines.add(new Detail(reason(unavailable.reason()), Theme.NOTICE));
                case RowState.HandledBy handled -> lines.add(new Detail(
                        Component.translatable("litelights.state.handled_by", handled.modName()), Theme.NOTICE));
                default -> {
                }
            }
        }
        if (value.credit() != null) {
            lines.add(new Detail(value.credit().copy().withStyle(ChatFormatting.ITALIC), Theme.TEXT_DIM));
        }
        return lines;
    }

    private record Detail(Component text, int colour) {
    }

    /** The detail lines wrapped to the list's width, each with its colour. */
    private List<DetailLine> detailLines(RowValue value) {
        List<DetailLine> lines = new ArrayList<>();
        for (Detail detail : details(value)) {
            for (FormattedCharSequence line : font.split(detail.text(), layout.listW() - 4)) {
                lines.add(new DetailLine(line, detail.colour()));
            }
        }
        return lines;
    }

    private record DetailLine(FormattedCharSequence text, int colour) {
    }

    /** A reason is a translation key or plain text; a key without a translation shows as written. */
    private static Component reason(String text) {
        return Component.translatableWithFallback(text, text);
    }

    private int addSetting(RowValue value, int y, boolean grouped) {
        List<Placed> placed = new ArrayList<>();
        int controlDx = layout.controlDx();
        int controlW = layout.controlW();
        int controlEnd = controlDx + controlW;
        int controlY = (ROW_H - CONTROL_H) / 2;

        if (value.state() instanceof RowState.ComingSoon) {
            // Nothing to change yet: the "Coming soon" tag takes the control's place (drawBadge).
        } else if (value.state() instanceof RowState.Failed && value.leadsFeature()) {
            LiteButton retry = new LiteButton(0, 0, controlW, CONTROL_H, Component.translatable("litelights.retry"),
                    () -> retry(value));
            placed.add(new Placed(retry, controlDx, controlY));
        } else {
            switch (value.kind()) {
                case TOGGLE -> placed.add(new Placed(new ToggleWidget(value), controlEnd - ToggleWidget.WIDTH,
                        (ROW_H - ToggleWidget.HEIGHT) / 2));
                case SLIDER -> placed.add(new Placed(new SliderWidget(controlW, CONTROL_H, value), controlDx, controlY));
                case CHOICE -> placed.addAll(choiceWidgets(value, controlDx, controlW, controlY));
                case NOTICE -> {
                    // Nothing to set: drawSetting writes the status where the control would be.
                }
            }
        }
        if (value.tracked()) {
            placed.add(new Placed(new ResetWidget(value, this::rebuildList), layout.resetDx(), (ROW_H - ResetWidget.SIZE) / 2));
        }
        register(placed);
        int detailLines = detailLines(value).size();
        int h = ROW_H + detailLines * LINE + (detailLines == 0 ? 0 : 2);
        rows.add(new ListRow(RowKind.SETTING, value, y, h, grouped, placed));
        return y + h;
    }

    private void retry(RowValue value) {
        if (value instanceof RowValue.OptionValue option) {
            option.option().feature().retry();
        } else if (value.state() instanceof RowState.Failed failed) {
            failed.retry().run();
        }
        rebuildList();
    }

    /** Up to three short choices become buttons; anything else a dropdown. */
    private List<Placed> choiceWidgets(RowValue value, int controlDx, int controlW, int controlY) {
        List<Component> choices = value.choices();
        int gap = 2;
        int segmentW = (controlW - gap * (choices.size() - 1)) / choices.size();
        boolean fits = choices.size() <= 3 && choices.stream().allMatch(choice -> font.width(choice) + 6 <= segmentW);
        boolean anyTagged = false;
        for (int i = 0; i < choices.size(); i++) {
            anyTagged |= value.choiceTag(i) != null;
        }
        if (!fits || anyTagged) {
            return List.of(new Placed(new DropdownWidget(controlW, CONTROL_H, value, popup), controlDx, controlY));
        }
        List<Placed> segments = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            int choice = i;
            boolean last = i == choices.size() - 1;
            int w = last ? controlW - (segmentW + gap) * i : segmentW;
            LiteButton segment = new LiteButton(0, 0, w, CONTROL_H, choices.get(i), () -> value.set(choice))
                    .chosenWhen(() -> value.value() == choice);
            segments.add(new Placed(segment, controlDx + (segmentW + gap) * i, controlY));
        }
        return segments;
    }

    private int addSubheading(PageModel.Subheading heading, int y, boolean grouped) {
        rows.add(new ListRow(RowKind.SUBHEADING, heading, y, SUBHEADING_H, grouped, List.of()));
        return y + SUBHEADING_H;
    }

    private int addNote(PageModel.Note note, int y, boolean grouped) {
        int lines = font.split(note.text(), layout.listW() - 4).size();
        int h = lines * LINE + 8;
        rows.add(new ListRow(RowKind.NOTE, note, y, h, grouped, List.of()));
        return y + h;
    }

    private int addRecommendation(PageModel.Recommendation recommendation, int y, boolean grouped) {
        int lines = font.split(recommendation.text(), recommendationTextW()).size();
        int h = Math.max(ROW_H, lines * LINE + 8);
        rows.add(new ListRow(RowKind.RECOMMENDATION, recommendation, y, h, grouped, List.of()));
        return y + h;
    }

    private int recommendationTextW() {
        return layout.listW() - 16 - font.width(Component.translatable("litelights.installed")) - 8;
    }

    private int addAction(PageModel.Action action, int y, boolean grouped) {
        int w = layout.resetDx() + ResetWidget.SIZE - layout.controlDx();
        Runnable run = action.opener() != null
                ? () -> minecraft.gui.setScreen(action.opener().apply(this))
                : () -> {
                    action.run().run();
                    if (minecraft.gui.screen() == this) {
                        buildModels();
                        rebuildList();
                    }
                };
        AbstractWidget button = action.confirm()
                ? new ArmedButton(0, 0, w, CONTROL_H, action.button(), run)
                : new LiteButton(0, 0, w, CONTROL_H, action.button(), run);
        List<Placed> placed = List.of(new Placed(button, layout.controlDx(), (ROW_H - CONTROL_H) / 2));
        register(placed);
        rows.add(new ListRow(RowKind.ACTION, action, y, ROW_H, grouped, placed));
        return y + ROW_H;
    }

    private int addCard(PageModel.Card card, int y) {
        CardLayout shape = cardLayout(card);
        List<Placed> placed = List.of();
        if (card.hasButton()) {
            Runnable press = card.url() != null
                    ? () -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(card.url()))
                    : card.action();
            LiteButton button = new LiteButton(0, 0, shape.buttonW(), CONTROL_H, card.button(), press);
            if (card.url() != null) {
                button.glyph(Glyphs.LINK);
                button.setTooltip(Tooltip.create(Component.literal(card.url())));
            }
            placed = List.of(new Placed(button, shape.buttonDx(), shape.buttonDy()));
            register(placed);
        }
        rows.add(new ListRow(RowKind.CARD, card, y, shape.h() + 4, false, placed));
        return y + shape.h() + 4;
    }

    /**
     * A card's wrapped lines and its height. The heading and text wrap in the room left of the
     * button; when the list is too narrow for text beside it, the button goes under the text and
     * the text takes the whole width. The card always grows to fit its text.
     */
    private record CardLayout(List<FormattedCharSequence> heading, List<FormattedCharSequence> text,
                              int textW, int textY, int buttonW, int buttonDx, int buttonDy, int h) {
    }

    private static final int CARD_TOP = 6;
    private static final int CARD_MIN_TEXT_W = 110;

    private CardLayout cardLayout(PageModel.Card card) {
        int buttonW = card.hasButton() ? Math.min(cardButtonW(card), layout.listW() - 4) : 0;
        int besideW = card.hasButton() ? layout.listW() - buttonW - 12 : layout.listW() - 4;
        boolean buttonBelow = card.hasButton() && besideW < CARD_MIN_TEXT_W;
        int textW = buttonBelow ? layout.listW() - 4 : besideW;
        List<FormattedCharSequence> headingLines = font.split(card.heading(), textW);
        List<FormattedCharSequence> textLines = font.split(card.text(), textW);
        int textY = CARD_TOP + headingLines.size() * LINE + 2;
        int textBottom = textY + textLines.size() * LINE;
        if (buttonBelow) {
            int buttonDy = textBottom + 3;
            return new CardLayout(headingLines, textLines, textW, textY, buttonW, 0, buttonDy, buttonDy + CONTROL_H + 6);
        }
        int h = Math.max(textBottom + 5, card.hasButton() ? CARD_TOP + CONTROL_H + 6 : 0);
        return new CardLayout(headingLines, textLines, textW, textY, buttonW, layout.listW() - buttonW - 4, CARD_TOP, h);
    }

    /** Link buttons share one width, wide enough for the longest site name with its glyph; others fit their label. */
    private int cardButtonW(PageModel.Card card) {
        if (card.url() == null) {
            return font.width(card.button()) + 16;
        }
        int widest = font.width(card.button());
        for (String site : LINK_SITES) {
            widest = Math.max(widest, font.width(site));
        }
        return widest + Glyphs.LINK.width() + 16;
    }

    private static final List<String> LINK_SITES = List.of("CurseForge", "minecraft.net", "Modrinth");

    private static TextColor rgb(int argb) {
        return TextColor.fromRgb(argb & 0xFFFFFF);
    }

    private void register(List<Placed> placed) {
        for (Placed widget : placed) {
            listWidgets.add(widget.widget());
            addWidget(widget.widget());
        }
    }

    private void placeWidgets() {
        for (ListRow row : rows) {
            int top = layout.listY() + row.y() - scroll;
            for (Placed placed : row.widgets()) {
                AbstractWidget widget = placed.widget();
                widget.setX(layout.listX() + placed.dx());
                widget.setY(top + placed.dy());
                widget.visible = widget.getBottom() > layout.listY() && widget.getY() < layout.listBottom();
            }
        }
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - layout.listH());
    }

    private void scrollTo(int next) {
        int clamped = Math.clamp(next, 0, maxScroll());
        if (clamped != scroll) {
            scroll = clamped;
            placeWidgets();
        }
    }

    /**
     * The list is laid out again as soon as anything that shapes it changes: a row showing or
     * hiding (the "Edit time of day" row once the grade goes by time of day), a feature state (a
     * feature becoming ready gets its control, a failure gets Retry, another mod taking over), or
     * the lines under a row. Whatever changed it (a control here, a reset, Sodium's screen, a
     * feature's owner), the page follows at once; the scroll position is kept.
     */
    private void rebuildIfLayoutChanged() {
        if (!layoutSignature().equals(builtLayout)) {
            rebuildList();
        }
    }

    /** Per setting row of what the list is built from: whether it shows, its state, its extra lines. */
    private List<Object> layoutSignature() {
        List<Object> signature = new ArrayList<>();
        if (searching()) {
            for (Pages.PageInfo info : pages) {
                addSignature(models.get(info.id()), signature);
            }
        } else {
            addSignature(models.get(page), signature);
        }
        return signature;
    }

    private void addSignature(PageModel model, List<Object> signature) {
        if (model == null) {
            return;
        }
        for (PageModel.Section section : model.sections()) {
            for (PageModel.Entry entry : section.entries()) {
                if (entry instanceof PageModel.Setting setting) {
                    RowValue value = setting.value();
                    boolean shown = value.shown();
                    signature.add(shown);
                    if (shown) {
                        signature.add(value.state());
                        signature.add(detailLines(value).size());
                    }
                }
            }
        }
    }


    /** Greys controls out live: a feature that isn't ready, a master switch turned off. */
    private void refreshActive() {
        for (ListRow row : rows) {
            if (row.kind() != RowKind.SETTING) {
                continue;
            }
            RowValue value = (RowValue) row.source();
            boolean usable = value.state().editable() && value.active();
            for (Placed placed : row.widgets()) {
                AbstractWidget widget = placed.widget();
                if (widget instanceof ResetWidget) {
                    continue;
                }
                if (value.state() instanceof RowState.Failed && value.leadsFeature()) {
                    widget.active = true;
                } else if (widget instanceof LiteButton && !(widget instanceof DropdownWidget) && value.kind() == RowValue.Kind.CHOICE) {
                    int index = row.widgets().indexOf(placed);
                    widget.active = usable && value.choiceEnabled(index);
                } else {
                    widget.active = usable;
                }
            }
        }
    }

    // Drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // No blur: the world stays sharp behind a light dim, so changes can be judged as they're made.
        if (minecraft.level != null) {
            graphics.fill(0, 0, width, height, layout.docked() ? Theme.WORLD_DIM_DOCKED : Theme.WORLD_DIM_FULL);
        } else {
            super.extractBackground(graphics, mouseX, mouseY, delta);
        }
        Surfaces.panel(graphics, layout.panelX(), layout.panelY(), layout.panelW(), layout.panelH());
        drawTitle(graphics);
        if (!layout.navAcross()) {
            int dividerX = layout.navX() + layout.navW() + 3;
            graphics.fill(dividerX, layout.navY(), dividerX + 1, layout.footerY() - 5, Theme.RULE);
        }
        int footerRuleY = layout.footerY() - 4;
        graphics.fill(layout.panelX() + 4, footerRuleY, layout.panelRight() - 4, footerRuleY + 1, Theme.RULE);
        if (previewLabelShown) {
            graphics.text(font, Component.translatable("litelights.preview"), layout.panelX() + ScreenLayout.PAD,
                    layout.footerY() + 5, Theme.TEXT_DIM, false);
        }
        drawFrameTime(graphics);
    }

    private void drawTitle(GuiGraphicsExtractor graphics) {
        int x = layout.panelX() + ScreenLayout.PAD;
        int y = layout.titleY() + 2;
        graphics.text(font, title, x, y, Theme.TITLE, true);
        int changedX = x + font.width(title) + 8;
        int room = (layout.searchY() < layout.titleY() + 4 ? layout.searchX() - 4 : layout.panelRight() - ScreenLayout.PAD - 18) - changedX;
        int count = changedCount();
        Component changed = count == 0 ? Component.translatable("litelights.changed.none")
                : count == 1 ? Component.translatable("litelights.changed.one")
                : Component.translatable("litelights.changed.many", count);
        String text = Ink.trim(font, changed.getString(), room);
        graphics.text(font, text, changedX, y, count == 0 ? Theme.TEXT_DIM : Theme.CHANGED, false);
    }

    private int changedCount() {
        int count = 0;
        for (Pages.PageInfo info : pages) {
            count += models.get(info.id()).changedCount();
        }
        return count;
    }

    private void drawFrameTime(GuiGraphicsExtractor graphics) {
        double millis = frameTimer.millis();
        Component text = Component.translatable("litelights.frame_time", String.format(Locale.ROOT, "%.1f", millis));
        int doneW = Math.max(46, font.width(CommonComponents.GUI_DONE) + 14);
        int right = layout.panelRight() - ScreenLayout.PAD - doneW - 6;
        graphics.text(font, text, right - font.width(text), layout.footerY() + 5, Theme.frameTimeColour(millis), false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        frameTimer.tick();
        rebuildIfLayoutChanged();
        refreshActive();
        // While the popup is open, nothing under it reacts to the pointer.
        int pointerX = popup.isOpen() ? -1 : mouseX;
        int pointerY = popup.isOpen() ? -1 : mouseY;
        super.extractRenderState(graphics, pointerX, pointerY, delta);

        // The box keeps what it shows while the pointer is over it, so a long description can be
        // read and scrolled; anywhere else the hovered row, or the focused one, is described.
        Object hovered = inList(pointerX, pointerY) ? rowSourceAt(pointerY) : null;
        Object pointed = hovered;
        if (pointed == null && !overDescription(pointerX, pointerY)) {
            pointed = focusedSource();
        }
        if (pointed != null && pointed != described) {
            described = pointed;
            describeScroll = 0;
        }
        fitDescriptionBox(hovered);

        boolean inList = inList(pointerX, pointerY);
        int listMouseX = inList ? pointerX : -1;
        int listMouseY = inList ? pointerY : -1;
        listPointerX = listMouseX;
        listPointerY = listMouseY;
        hoveredTag = null;

        graphics.enableScissor(layout.listX() - 6, layout.listY(), layout.panelRight() - 4, layout.listBottom());
        if (rows.isEmpty()) {
            drawEmptyState(graphics);
        }
        drawGroupCards(graphics);
        for (ListRow row : rows) {
            int top = layout.listY() + row.y() - scroll;
            if (top + row.h() < layout.listY() || top > layout.listBottom()) {
                continue;
            }
            if (listMouseY >= top && listMouseY < top + row.h() && describable(row)) {
                if (row.grouped() || row.kind() == RowKind.CARD) {
                    graphics.fill(layout.listX() - 3, top, layout.listX() + layout.listW() + 3, top + row.h() - 1, Theme.HOVER);
                }
            }
            drawRow(graphics, row, top);
            for (Placed placed : row.widgets()) {
                if (placed.widget().visible) {
                    placed.widget().extractRenderState(graphics, listMouseX, listMouseY, delta);
                }
            }
        }
        graphics.disableScissor();
        drawScrollbar(graphics, pointerX, pointerY);
        if (hoveredTag != null) {
            graphics.setTooltipForNextFrame(font.split(hoveredTag, 200), pointerX, pointerY);
        }

        if (layout.describeH() > 0) {
            drawDescription(graphics, pointerX, pointerY);
        } else if (pointed != null && pointerX >= layout.listX() && pointerX < layout.listX() + layout.controlDx()) {
            Component description = descriptionOf(pointed);
            if (description != null) {
                graphics.setTooltipForNextFrame(tooltipLines(description), pointerX, pointerY);
            }
        }
        popup.extract(graphics, mouseX, mouseY);
    }

    /** Rows that light up under the pointer and fill the description box; headings don't. */
    private static boolean describable(ListRow row) {
        return row.kind() != RowKind.CAPTION && row.kind() != RowKind.HEADER && row.kind() != RowKind.SUBHEADING;
    }

    private boolean inList(double x, double y) {
        return y >= layout.listY() && y < layout.listBottom() && x >= layout.listX() - 6 && x < layout.panelRight() - 4;
    }

    /** The setting, action or credit under the pointer's height in the list, if any. */
    private Object rowSourceAt(int pointerY) {
        for (ListRow row : rows) {
            int top = layout.listY() + row.y() - scroll;
            if (pointerY >= top && pointerY < top + row.h() && describable(row)) {
                return row.source();
            }
        }
        return null;
    }

    /**
     * Without a description box (short windows) descriptions are tooltips. A tooltip that would be
     * taller than the window is made wider instead, so none of it falls off the screen.
     */
    private List<FormattedCharSequence> tooltipLines(Component description) {
        int widest = Math.max(200, width - 24);
        int w = Math.min(200, widest);
        List<FormattedCharSequence> lines = font.split(description, w);
        while (lines.size() * LINE + 8 > height - 8 && w < widest) {
            w = Math.min(widest, w + 40);
            lines = font.split(description, w);
        }
        return lines;
    }

    private void drawEmptyState(GuiGraphicsExtractor graphics) {
        Component text = Component.translatable("litelights.search.empty", query);
        String line = Ink.trim(font, text.getString(), layout.listW());
        int y = layout.listY() + layout.listH() / 2 - 4;
        graphics.text(font, line, layout.listX() + (layout.listW() - font.width(line)) / 2, y, Theme.TEXT_DIM, false);
    }

    /** One card behind each run of rows under a caption. */
    private void drawGroupCards(GuiGraphicsExtractor graphics) {
        int start = -1;
        int end = -1;
        for (ListRow row : rows) {
            if (row.grouped()) {
                if (start < 0) {
                    start = row.y();
                }
                end = row.y() + row.h();
            } else if (start >= 0) {
                drawGroupCard(graphics, start, end);
                start = -1;
            }
        }
        if (start >= 0) {
            drawGroupCard(graphics, start, end);
        }
    }

    private void drawGroupCard(GuiGraphicsExtractor graphics, int startY, int endY) {
        int y = layout.listY() + startY - scroll - 1;
        Surfaces.card(graphics, layout.listX() - 5, y, layout.listW() + 10, endY - startY + 2);
    }

    private void drawRow(GuiGraphicsExtractor graphics, ListRow row, int top) {
        switch (row.kind()) {
            case CAPTION -> drawCaption(graphics, (CaptionState) row.source(), row, top + row.h() - 11);
            case HEADER -> drawPageHeader(graphics, (Component) row.source(), top + row.h() - 11);
            case SUBHEADING -> drawSubheading(graphics, (PageModel.Subheading) row.source(), top);
            case NOTE -> drawNote(graphics, (PageModel.Note) row.source(), top);
            case SETTING -> drawSetting(graphics, (RowValue) row.source(), row, top);
            case RECOMMENDATION -> drawRecommendation(graphics, (PageModel.Recommendation) row.source(), top);
            case ACTION -> drawLabel(graphics, ((PageModel.Action) row.source()).label(), top, Theme.TEXT, layout.controlDx() - 6);
            case CARD -> drawCard(graphics, (PageModel.Card) row.source(), top, row.h() - 4);
        }
        // A sub-heading has no rule under it, so it reads as the title of the rows that follow.
        if (row.grouped() && row.kind() != RowKind.CAPTION && row.kind() != RowKind.SUBHEADING) {
            int index = rows.indexOf(row);
            boolean nextInGroup = index + 1 < rows.size() && rows.get(index + 1).grouped();
            if (nextInGroup) {
                graphics.fill(layout.listX(), top + row.h() - 1, layout.listX() + layout.listW(), top + row.h(), Theme.RULE);
            }
        }
    }

    private void drawLabel(GuiGraphicsExtractor graphics, Component label, int top, int colour, int room) {
        String text = Ink.trim(font, label.getString(), room);
        graphics.text(font, text, layout.listX(), top + (ROW_H - 8) / 2, colour, true);
    }

    private void drawSetting(GuiGraphicsExtractor graphics, RowValue value, ListRow row, int top) {
        RowState state = value.state();
        boolean usable = state.editable() && value.active();
        int badgeW = drawBadge(graphics, value, top);
        int room = layout.controlDx() - 6 - badgeW;
        String label = Ink.trim(font, value.label().getString(), room - (value.changed() ? 5 : 0));
        int labelY = top + (ROW_H - 8) / 2;
        graphics.text(font, label, layout.listX(), labelY, usable ? Theme.TEXT : Theme.TEXT_OFF, usable);
        if (value.changed()) {
            int markX = layout.listX() + font.width(label) + 3;
            graphics.fill(markX, labelY + 2, markX + 3, labelY + 5, Theme.CHANGED);
        }
        if (value.kind() == RowValue.Kind.NOTICE) {
            drawNoticeStatus(graphics, value, top);
        }
        int lineY = top + ROW_H - 1;
        for (DetailLine detail : detailLines(value)) {
            graphics.text(font, detail.text(), layout.listX() + 2, lineY, detail.colour(), false);
            lineY += LINE;
        }
    }

    /**
     * A notice's status, right-aligned where a switch would be: green with a tick while what it
     * stands for is running ("Active"), dimmed otherwise, with the reason on the line under it.
     */
    private void drawNoticeStatus(GuiGraphicsExtractor graphics, RowValue value, int top) {
        boolean good = value.state() instanceof RowState.Ready;
        String text = noticeStatusText(value);
        int x = layout.listX() + layout.controlDx() + layout.controlW() - font.width(text);
        if (good) {
            Glyphs.CHECK.draw(graphics, x - 11, top + (ROW_H - 6) / 2, Theme.GOOD);
        }
        graphics.text(font, text, x, top + (ROW_H - 8) / 2, good ? Theme.GOOD : Theme.TEXT_DIM, false);
    }

    private String noticeStatusText(RowValue value) {
        int room = layout.controlW() - (value.state() instanceof RowState.Ready ? 11 : 0);
        return Ink.trim(font, value.format(value.value()).getString(), room);
    }

    /** Where a notice's status starts, tick included, so its tag can sit just before it. */
    private int noticeStatusLeft(RowValue value) {
        int tick = value.state() instanceof RowState.Ready ? 11 : 0;
        return layout.listX() + layout.controlDx() + layout.controlW() - font.width(noticeStatusText(value)) - tick;
    }

    /**
     * The cost badge just before the control, or "Coming soon" while the feature isn't built.
     * On narrow lists it shrinks to a coloured dot. Returns the room it took.
     */
    private int drawBadge(GuiGraphicsExtractor graphics, RowValue value, int top) {
        boolean comingSoon = value.state() instanceof RowState.ComingSoon;
        int right = layout.listX() + (comingSoon ? layout.controlDx() + layout.controlW() : layout.controlDx() - 4);
        if (value.kind() == RowValue.Kind.NOTICE) {
            // A notice costs nothing and has no control: its tag (such as Temporary) sits just before
            // the short status, leaving the label the room a control would have taken.
            int tagRight = noticeStatusLeft(value) - 6;
            int taken = drawRowTag(graphics, value, tagRight, top + (ROW_H - 11) / 2, 0);
            return layout.listX() + layout.controlDx() - (tagRight - taken);
        }
        if (!value.tracked()) {
            return 0;
        }
        if (!comingSoon && !(value.state() instanceof RowState.Ready || value.state() instanceof RowState.Failed)) {
            // No cost badge while the row can't be used, but an Experimental tag still says what it is.
            return drawRowTag(graphics, value, right, top + (ROW_H - 11) / 2, 0);
        }
        Component text = comingSoon ? Component.translatable("litelights.state.coming_soon")
                : Component.translatable(value.cost().translationKey());
        int colour = comingSoon ? Theme.TEXT_DIM : Theme.costColour(value.cost());
        int textW = font.width(text);
        boolean roomy = comingSoon || layout.controlDx() - textW - 10 >= 70;
        if (!roomy) {
            int dotY = top + ROW_H / 2 - 2;
            Surfaces.rounded(graphics, right - 5, dotY, 5, 5, colour);
            return 9;
        }
        int w = textW + 6;
        int y = top + (ROW_H - 11) / 2;
        Surfaces.chip(graphics, right - w, y, w, 11, colour);
        graphics.text(font, text, right - w + 3, y + 2, colour, false);
        if (comingSoon) {
            return 0;
        }
        return drawRowTag(graphics, value, right, y, w + 4);
    }

    /**
     * The row's tag (such as Experimental) left of what already sits at the right; returns the room
     * taken in all. Where the word would squeeze the label, a small flask chip stands in for it and
     * the word shows as a tooltip over the chip. A tag with a tooltip of its own (such as Temporary)
     * shows that over the chip instead, in either form.
     */
    private int drawRowTag(GuiGraphicsExtractor graphics, RowValue value, int right, int y, int taken) {
        Component tag = value.rowTag();
        if (tag == null) {
            return taken;
        }
        Component tooltip = value.rowTagTooltip();
        int tagRight = right - taken;
        int tagW = font.width(tag) + 6;
        if (layout.controlDx() - taken - tagW - 10 >= 70) {
            Surfaces.chip(graphics, tagRight - tagW, y, tagW, 11, Theme.NOTICE);
            graphics.text(font, tag, tagRight - tagW + 3, y + 2, Theme.NOTICE, false);
            if (tooltip != null && pointerOverChip(tagRight - tagW, tagW, y)) {
                hoveredTag = tooltip;
            }
            return taken + tagW + 4;
        }
        int chipW = Glyphs.FLASK.width() + 6;
        int chipX = tagRight - chipW;
        Surfaces.chip(graphics, chipX, y, chipW, 11, Theme.NOTICE);
        Glyphs.FLASK.drawCentred(graphics, chipX, y, chipW, 11, Theme.NOTICE);
        if (pointerOverChip(chipX, chipW, y)) {
            hoveredTag = tooltip != null ? tooltip : tag;
        }
        return taken + chipW + 4;
    }

    private boolean pointerOverChip(int chipX, int chipW, int y) {
        return listPointerX >= chipX && listPointerX < chipX + chipW && listPointerY >= y && listPointerY < y + 11;
    }

    private void drawCaption(GuiGraphicsExtractor graphics, CaptionState state, ListRow row, int y) {
        int x = layout.listX();
        FoldStrip strip = (FoldStrip) row.widgets().getFirst().widget();
        boolean hot = strip.lit();
        boolean isFolded = isFolded(state.pageId(), state.section());
        int colour = hot ? Theme.ACCENT_HOT : Theme.CAPTION;
        Glyphs chevron = isFolded ? Glyphs.CHEVRON_RIGHT : Glyphs.CHEVRON_DOWN;
        chevron.draw(graphics, x + (7 - chevron.width()) / 2, y + (8 - chevron.height()) / 2, colour);
        String text = Ink.trim(font, state.section().caption().getString().toUpperCase(Locale.ROOT), layout.listW() - 20);
        graphics.text(font, text, x + 10, y, colour, false);
        int textEnd = x + 10 + font.width(text);
        if (isFolded && sectionChanged(state.section())) {
            graphics.fill(textEnd + 3, y + 2, textEnd + 6, y + 5, Theme.CHANGED);
        }
        if (textEnd + 10 < x + layout.listW()) {
            graphics.fill(textEnd + 10, y + 4, x + layout.listW(), y + 5, hot ? Theme.ACCENT_DEEP : Theme.RULE);
        }
    }

    /** Search results are grouped under each page's name. */
    private void drawPageHeader(GuiGraphicsExtractor graphics, Component name, int y) {
        Component text = Component.literal(name.getString().toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD);
        graphics.text(font, text, layout.listX(), y, Theme.ACCENT, false);
        graphics.fill(layout.listX() + font.width(text) + 6, y + 3, layout.listX() + layout.listW(), y + 5, Theme.ACCENT_DEEP);
    }

    /**
     * A sub-heading: its icon and name in the caption colour, in mixed case so it reads as part of
     * the section above. Its hover text, if it has one, shows over the name.
     */
    private void drawSubheading(GuiGraphicsExtractor graphics, PageModel.Subheading heading, int top) {
        int x = layout.listX();
        int textY = top + SUBHEADING_H - 10;
        if (heading.icon() != null) {
            Glyphs icon = heading.icon();
            icon.draw(graphics, x, textY + (8 - icon.height()) / 2, Theme.CAPTION);
            x += icon.width() + 4;
        }
        String text = Ink.trim(font, heading.caption().getString(), layout.listX() + layout.listW() - x);
        graphics.text(font, text, x, textY, Theme.CAPTION, false);
        int textEnd = x + font.width(text);
        if (heading.tooltip() != null && listPointerX >= layout.listX() && listPointerX < textEnd
                && listPointerY >= textY - 2 && listPointerY < textY + 10) {
            hoveredTag = heading.tooltip();
        }
    }

    private void drawNote(GuiGraphicsExtractor graphics, PageModel.Note note, int top) {
        int y = top + 4;
        for (FormattedCharSequence line : font.split(note.text(), layout.listW() - 4)) {
            graphics.text(font, line, layout.listX() + 2, y, Theme.TEXT, true);
            y += LINE;
        }
    }

    private void drawRecommendation(GuiGraphicsExtractor graphics, PageModel.Recommendation recommendation, int top) {
        boolean installed = BuiltInPages.installed(recommendation.modId());
        PageIcons.draw(graphics, "credits", layout.listX(), top + 5, true);
        int y = top + 5;
        List<FormattedCharSequence> lines = font.split(recommendation.text(), recommendationTextW());
        if (lines.size() == 1) {
            y = top + (Math.max(ROW_H, LINE + 8) - 8) / 2;
        }
        for (FormattedCharSequence line : lines) {
            graphics.text(font, line, layout.listX() + 16, y, Theme.TEXT_OFF, false);
            y += LINE;
        }
        if (installed) {
            Component tag = Component.translatable("litelights.installed");
            int x = layout.listX() + layout.listW() - font.width(tag) - 2;
            Glyphs.CHECK.draw(graphics, x - 11, top + (ROW_H - 6) / 2, Theme.GOOD);
            graphics.text(font, tag, x, top + (ROW_H - 8) / 2, Theme.GOOD, false);
        }
    }

    private void drawCard(GuiGraphicsExtractor graphics, PageModel.Card card, int top, int h) {
        Surfaces.card(graphics, layout.listX() - 5, top, layout.listW() + 10, h);
        CardLayout shape = cardLayout(card);
        int x = layout.listX();
        int y = top + CARD_TOP;
        for (FormattedCharSequence line : shape.heading()) {
            graphics.text(font, line, x, y, Theme.TEXT_STRONG, true);
            y += LINE;
        }
        y = top + shape.textY();
        for (FormattedCharSequence line : shape.text()) {
            graphics.text(font, line, x, y, Theme.TEXT, false);
            y += LINE;
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int max = maxScroll();
        if (max == 0) {
            return;
        }
        int x = layout.panelRight() - ScreenLayout.PAD - 2;
        int thumbH = Math.max(12, layout.listH() * layout.listH() / contentHeight);
        int thumbY = layout.listY() + Math.round((layout.listH() - thumbH) * ((float) scroll / max));
        boolean hot = draggingScrollbar || onScrollbar(mouseX, mouseY);
        graphics.fill(x, layout.listY(), x + 3, layout.listBottom(), 0x22FFFFFF);
        graphics.fill(x, thumbY, x + 3, thumbY + thumbH, hot ? Theme.ACCENT_HOT : Theme.withAlpha(Theme.ACCENT, 0xA0));
    }

    private boolean onScrollbar(double mouseX, double mouseY) {
        int x = layout.panelRight() - ScreenLayout.PAD - 2;
        return maxScroll() > 0 && mouseY >= layout.listY() && mouseY < layout.listBottom() && mouseX >= x - 3 && mouseX < x + 5;
    }

    private void scrollToPointer(double mouseY) {
        float position = (float) ((mouseY - layout.listY()) / layout.listH());
        scrollTo(Math.round(position * maxScroll()));
    }

    private Component descriptionOf(Object source) {
        return switch (source) {
            case RowValue value -> value.description();
            case PageModel.Action action -> action.description();
            default -> null;
        };
    }

    // The description box

    private static final int DESCRIBE_PAD_TOP = 5;
    private static final int DESCRIBE_PAD_BOTTOM = 4;
    private static final int DESCRIBE_BAR_W = 6;

    /**
     * What the description box shows, wrapped to a width: a heading (the name, its value, and
     * whether it is changed) and the description, or the hint shown before anything is pointed at.
     */
    private record DescriptionText(List<FormattedCharSequence> heading, List<FormattedCharSequence> body, boolean hint) {
        int height() {
            return DESCRIBE_PAD_TOP + heading.size() * LINE + (heading.isEmpty() ? 0 : 1) + body.size() * LINE + DESCRIBE_PAD_BOTTOM;
        }
    }

    private DescriptionText descriptionText(int textW) {
        Component description = described == null ? null : descriptionOf(described);
        if (description == null) {
            return new DescriptionText(List.of(), font.split(Component.translatable("litelights.description.empty"), textW), true);
        }
        Component label = described instanceof RowValue value ? value.label() : ((PageModel.Action) described).label();
        MutableComponent heading = label.copy().withStyle(style -> style.withBold(true).withColor(rgb(Theme.TEXT_STRONG)));
        if (described instanceof RowValue value) {
            heading.append(Component.literal("  "))
                    .append(value.format(value.value()).copy().withStyle(style -> style.withColor(rgb(Theme.ACCENT_HOT))));
            if (value.tracked()) {
                heading.append(Component.literal("  "))
                        .append(Component.translatable(value.changed() ? "litelights.description.changed" : "litelights.description.default")
                                .withStyle(style -> style.withItalic(true).withColor(rgb(Theme.TEXT_DIM))));
            }
        }
        return new DescriptionText(font.split(heading, textW), font.split(description, textW), false);
    }

    /** The text's width in the box; narrower when a scrollbar has to fit beside it. */
    private int describeTextW(boolean withScrollbar) {
        return layout.listW() - 2 - (withScrollbar ? DESCRIBE_BAR_W : 0);
    }

    /** Whether the description is taller than the box may grow, so the box scrolls instead. */
    private boolean describeScrolls() {
        return layout.describeMaxH() > 0 && descriptionText(describeTextW(false)).height() > layout.describeMaxH();
    }

    private int maxDescribeScroll() {
        if (layout.describeH() == 0 || !describeScrolls()) {
            return 0;
        }
        return Math.max(0, descriptionText(describeTextW(true)).height() - layout.describeH());
    }

    /**
     * Sizes the box to the whole description, up to its largest size, and gives the list the rest.
     * Only a description taller than that scrolls. A hovered row the growing box would cover is
     * scrolled up to stay in view.
     */
    private void fitDescriptionBox(Object hovered) {
        if (layout.describeMaxH() == 0) {
            return;
        }
        int wanted = describeScrolls() ? layout.describeMaxH() : descriptionText(describeTextW(false)).height();
        ScreenLayout fitted = layout.withDescribeH(wanted);
        if (fitted != layout) {
            layout = fitted;
            scroll = Math.clamp(scroll, 0, maxScroll());
            int covered = hovered == null ? 0 : rowBottom(hovered) - layout.listBottom();
            if (covered > 0) {
                scroll = Math.clamp(scroll + covered, 0, maxScroll());
            }
            placeWidgets();
        }
        describeScroll = Math.clamp(describeScroll, 0, maxDescribeScroll());
    }

    /** Where a row ends on screen; the list's top when it isn't in the list. */
    private int rowBottom(Object source) {
        for (ListRow row : rows) {
            if (row.source() == source) {
                return layout.listY() + row.y() + row.h() - scroll;
            }
        }
        return layout.listY();
    }

    private boolean overDescription(double x, double y) {
        return layout.describeH() > 0 && x >= layout.listX() - 5 && x < layout.listX() + layout.listW() + 5
                && y >= layout.describeY() && y < layout.describeY() + layout.describeH();
    }

    private void drawDescription(GuiGraphicsExtractor graphics, int pointerX, int pointerY) {
        int x = layout.listX() - 5;
        int y = layout.describeY();
        int w = layout.listW() + 10;
        int h = layout.describeH();
        Surfaces.framed(graphics, x, y, w, h, Theme.WELL, Theme.CARD_EDGE);
        int maxScroll = maxDescribeScroll();
        DescriptionText text = descriptionText(describeTextW(maxScroll > 0));
        int textX = x + 6;
        int lineY = y + DESCRIBE_PAD_TOP - describeScroll;
        graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (FormattedCharSequence line : text.heading()) {
            graphics.text(font, line, textX, lineY, Theme.TEXT_STRONG, true);
            lineY += LINE;
        }
        if (!text.heading().isEmpty()) {
            lineY += 1;
        }
        for (FormattedCharSequence line : text.body()) {
            graphics.text(font, line, textX, lineY, text.hint() ? Theme.TEXT_DIM : Theme.TEXT, false);
            lineY += LINE;
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int trackX = x + w - 5;
            int trackY = y + 3;
            int trackH = h - 6;
            int thumbH = Math.max(8, trackH * h / text.height());
            int thumbY = trackY + Math.round((trackH - thumbH) * ((float) describeScroll / maxScroll));
            boolean hot = overDescription(pointerX, pointerY);
            graphics.fill(trackX, trackY, trackX + 2, trackY + trackH, 0x22FFFFFF);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, hot ? Theme.ACCENT_HOT : Theme.withAlpha(Theme.ACCENT, 0xA0));
        }
    }

    // Checks for the QA client

    /**
     * Text that would not fit where it is drawn: credit cards shorter than their wrapped lines or
     * with the link outside them, and a description box that is neither tall enough for its text
     * nor scrollable. Empty when everything fits.
     */
    public List<String> layoutProblems() {
        List<String> problems = new ArrayList<>();
        for (ListRow row : rows) {
            if (row.kind() != RowKind.CARD) {
                continue;
            }
            PageModel.Card card = (PageModel.Card) row.source();
            CardLayout shape = cardLayout(card);
            int textBottom = shape.textY() + shape.text().size() * LINE;
            List<FormattedCharSequence> lines = new ArrayList<>(shape.heading());
            lines.addAll(shape.text());
            boolean tooWide = lines.stream().anyMatch(line -> font.width(line) > shape.textW());
            boolean buttonOutside = card.hasButton() && (shape.buttonDy() + CONTROL_H > shape.h() || shape.buttonDx() < 0
                    || shape.buttonDx() + shape.buttonW() > layout.listW()
                    || font.width(card.button()) + (card.url() != null ? Glyphs.LINK.width() + 4 : 0) + 8 > shape.buttonW());
            if (textBottom + 2 > shape.h() || buttonOutside || tooWide) {
                problems.add("card " + card.heading().getString() + " (card " + shape.h() + ", text to " + textBottom + ")");
            }
        }
        if (layout.describeH() > 0) {
            int needed = descriptionText(describeTextW(false)).height();
            if (needed > layout.describeH() && maxDescribeScroll() == 0) {
                problems.add("description box " + layout.describeH() + " high for " + needed + " of text");
            }
        }
        return problems;
    }

    /** The page shown now (a page id, or "credits"). */
    public String currentPage() {
        return page;
    }

    /** How many boxed entries (credits, mod status cards) the shown page lists, and how many of them have a link button. */
    public int[] cardCounts() {
        int cards = 0;
        int links = 0;
        for (ListRow row : rows) {
            if (row.kind() == RowKind.CARD) {
                cards++;
                if (((PageModel.Card) row.source()).url() != null) {
                    links++;
                }
            }
        }
        return new int[] {cards, links};
    }

    /** The name of what the description box shows, or "" before anything is pointed at. */
    public String describedName() {
        return switch (described) {
            case RowValue value -> value.label().getString();
            case PageModel.Action action -> action.label().getString();
            case null, default -> "";
        };
    }

    /** How far the description box can scroll (0 when its text fits) and how far it is scrolled. */
    public int descriptionScrollRange() {
        return maxDescribeScroll();
    }

    public int descriptionScroll() {
        return describeScroll;
    }

    /** The description box's place and size in GUI pixels: x, y, width, height (height 0 without a box). */
    public int[] descriptionBox() {
        return new int[] {layout.listX() - 5, layout.describeY(), layout.listW() + 10, layout.describeH()};
    }

    /** The middle of the label of the setting row with this name, in GUI pixels, or null when it isn't on screen. */
    public int[] rowLabelPoint(String name) {
        for (ListRow row : rows) {
            if (row.source() instanceof RowValue value && value.label().getString().equals(name)) {
                int top = layout.listY() + row.y() - scroll;
                if (top >= layout.listY() && top + ROW_H <= layout.listBottom()) {
                    return new int[] {layout.listX() + 20, top + ROW_H / 2};
                }
            }
        }
        return null;
    }

    /**
     * Where the live world shows beside the docked panel, as a share of the window's width from the
     * left; the middle of the window when the panel fills the screen. Sky event previews go there.
     */
    float liveWorldCentre() {
        if (layout == null || !layout.docked() || width <= 0) {
            return 0.5f;
        }
        return (layout.panelRight() + width) / (2.0f * width);
    }

    /**
     * For QA: presses the preview button for "time", "weather" or "event" as a click would, and
     * returns the name it shows afterwards.
     */
    public String pressPreview(String control) {
        PreviewButton button = previewButton(control);
        button.onPress(null);
        return button.getMessage().getString();
    }

    /** For QA: the name a preview button shows, and where it is (x, y, width, height in GUI pixels). */
    public String previewLabel(String control) {
        PreviewButton button = previewButton(control);
        button.follow();
        return button.getMessage().getString();
    }

    public int[] previewButtonBox(String control) {
        PreviewButton button = previewButton(control);
        return new int[] {button.getX(), button.getY(), button.getWidth(), button.getHeight()};
    }

    /** For QA: the compact row tag under the pointer in the last frame (its tooltip), or "". */
    public String hoveredTagText() {
        return hoveredTag == null ? "" : hoveredTag.getString();
    }

    /**
     * For QA: the middle of the compact tag chip on the row with this name, in GUI pixels, or null
     * when the row isn't on screen. The chip sits just left of the cost badge.
     */
    public int[] compactTagPoint(String name) {
        int[] label = rowLabelPoint(name);
        if (label == null) {
            return null;
        }
        for (ListRow row : rows) {
            if (row.source() instanceof RowValue value && value.label().getString().equals(name)) {
                int badgeW = font.width(Component.translatable(value.cost().translationKey())) + 6;
                boolean roomy = layout.controlDx() - font.width(Component.translatable(value.cost().translationKey())) - 10 >= 70;
                int right = layout.listX() + layout.controlDx() - 4 - (roomy ? badgeW + 4 : 9);
                return new int[] {right - (Glyphs.FLASK.width() + 6) / 2, label[1]};
            }
        }
        return null;
    }

    private PreviewButton previewButton(String control) {
        PreviewControls wanted = PreviewControls.valueOf(control.toUpperCase(Locale.ROOT));
        for (GuiEventListener child : children()) {
            if (child instanceof PreviewButton button && button.control == wanted) {
                return button;
            }
        }
        throw new IllegalStateException("No preview button for " + control);
    }

    /** For QA: whether the row with this name is in the list, and whether its control takes input. */
    public boolean hasRow(String name) {
        return rows.stream().anyMatch(row -> row.source() instanceof RowValue value && value.label().getString().equals(name));
    }

    public boolean rowUsable(String name) {
        for (ListRow row : rows) {
            if (row.source() instanceof RowValue value && value.label().getString().equals(name)) {
                return row.widgets().stream().anyMatch(placed -> !(placed.widget() instanceof ResetWidget) && placed.widget().active);
            }
        }
        return false;
    }

    /**
     * For QA: what the row with this name shows at its right (a notice's status, a switch's On or
     * Off), then the lines under it, or an empty list when there is no such row.
     */
    public List<String> rowStatus(String name) {
        for (ListRow row : rows) {
            if (row.source() instanceof RowValue value && value.label().getString().equals(name)) {
                List<String> shown = new ArrayList<>();
                shown.add(value.format(value.value()).getString());
                for (Detail detail : details(value)) {
                    shown.add(detail.text().getString());
                }
                return shown;
            }
        }
        return List.of();
    }

    /**
     * For QA: the list as built, top to bottom: section captions in capitals, sub-headings after
     * "> ", and setting labels.
     */
    public List<String> rowOutline() {
        List<String> outline = new ArrayList<>();
        for (ListRow row : rows) {
            switch (row.source()) {
                case CaptionState caption -> outline.add(caption.section().caption().getString().toUpperCase(Locale.ROOT));
                case PageModel.Subheading heading -> outline.add("> " + heading.caption().getString());
                case RowValue value -> outline.add(value.label().getString());
                default -> {
                }
            }
        }
        return outline;
    }

    /** For QA: the middle of a sub-heading's name in GUI pixels, or null when it isn't on screen. */
    public int[] subheadingPoint(String caption) {
        for (ListRow row : rows) {
            if (row.source() instanceof PageModel.Subheading heading && heading.caption().getString().equals(caption)) {
                int top = layout.listY() + row.y() - scroll;
                if (top >= layout.listY() && top + row.h() <= layout.listBottom()) {
                    int iconW = heading.icon() == null ? 0 : heading.icon().width() + 4;
                    return new int[] {layout.listX() + iconW + font.width(caption) / 2, top + SUBHEADING_H - 6};
                }
            }
        }
        return null;
    }

    /** For QA: scrolls the list so the sub-heading with this name is at the top. */
    public void scrollToSubheading(String caption) {
        for (ListRow row : rows) {
            if (row.source() instanceof PageModel.Subheading heading && heading.caption().getString().equals(caption)) {
                scrollTo(row.y());
                return;
            }
        }
    }

    /** Scrolls the list so the row with this name is at the top, for pictures of a given row. */
    public void scrollToRow(String name) {
        for (ListRow row : rows) {
            if (row.source() instanceof RowValue value && value.label().getString().equals(name)) {
                scrollTo(row.y());
                return;
            }
        }
    }

    private Object focusedSource() {
        GuiEventListener focused = getFocused();
        for (ListRow row : rows) {
            for (Placed placed : row.widgets()) {
                if (placed.widget() == focused && (row.kind() == RowKind.SETTING || row.kind() == RowKind.ACTION)) {
                    return row.source();
                }
            }
        }
        return null;
    }

    // Input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (popup.mouseClicked(event)) {
            return true;
        }
        if (onScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            scrollToPointer(event.y());
            return true;
        }
        boolean inList = event.y() >= layout.listY() && event.y() < layout.listBottom()
                && event.x() >= layout.listX() - 6 && event.x() < layout.panelRight() - 4;
        if (inList) {
            return super.mouseClicked(event, doubleClick);
        }
        // Rows scrolled under the list's edges are partly drawn; they must not take clicks there.
        for (GuiEventListener child : children()) {
            if (!listWidgets.contains(child) && child.mouseClicked(event, doubleClick)) {
                setFocused(child);
                setDragging(true);
                return true;
            }
        }
        setFocused(null);
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollToPointer(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (popup.mouseScrolled(vertical)) {
            return true;
        }
        // The wheel scrolls a long description only while the pointer is over its box.
        int describeMax = overDescription(mouseX, mouseY) ? maxDescribeScroll() : 0;
        if (describeMax > 0) {
            describeScroll = Math.clamp(describeScroll - (int) Math.round(vertical * 2 * LINE), 0, describeMax);
        } else {
            scrollTo(scroll - (int) Math.round(vertical * SCROLL_STEP));
        }
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (popup.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void setFocused(GuiEventListener focused) {
        super.setFocused(focused);
        // Keyboard focus moving onto a row out of view scrolls it into view.
        if (focused instanceof AbstractWidget widget && listWidgets.contains(widget) && minecraft.getLastInputType().isKeyboard()) {
            int above = widget.getY() - layout.listY();
            int below = widget.getBottom() - layout.listBottom();
            if (above < 0) {
                scrollTo(scroll + above - 4);
            } else if (below > 0) {
                scrollTo(scroll + below + 4);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /**
     * Runs however the screen goes away (Done, Escape, or another screen replacing it): the
     * preview ends and the settings are written, so nothing is ever lost.
     */
    @Override
    public void removed() {
        PreviewControls.clearAll();
        screenState().addProperty("page", page);
        LiteLightsConfig.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A smoothed frame time, measured between this screen's own frames. */
    private static final class FrameTimer {
        private long last;
        private double smoothedMillis = 16.7;

        void tick() {
            long now = System.nanoTime();
            if (last != 0) {
                double millis = (now - last) / 1_000_000.0;
                // Skip hitches over a second (window drags, debugger pauses); they'd distort the average.
                if (millis < 1000) {
                    smoothedMillis += (millis - smoothedMillis) * 0.05;
                }
            }
            last = now;
        }

        double millis() {
            return smoothedMillis;
        }
    }
}
