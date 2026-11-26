package com.thenathe.litelights.gui;

import com.thenathe.litelights.gui.widget.ResetWidget;

/**
 * Where everything goes, worked out on every resize. Docked, the panel sits at the left over the
 * live world; full screen, it is centred and wider. On short windows the page icons run in a
 * row above the list instead of down the side, and the search box moves onto the title line.
 *
 * @param navLabels   whether the navigation shows page names next to the icons
 * @param navAcross   whether the navigation is a row of icons above the list
 * @param describeH   height of the description box under the list; 0 when there is no room
 * @param describeMaxH the most the description box may grow to; 0 when there is no box
 * @param costW       width of the cost column at the right edge of the setting rows
 */
public record ScreenLayout(
        boolean docked,
        int panelX, int panelY, int panelW, int panelH,
        int titleY, int searchX, int searchY, int searchW,
        int navX, int navY, int navW, int navStep, int navItemH, boolean navLabels, boolean navAcross,
        int creditsY,
        int listX, int listY, int listW, int listBottom,
        int describeY, int describeH, int describeMaxH,
        int footerY, int costW) {

    public static final int PAD = 6;
    public static final int SEARCH_H = 16;
    public static final int FOOTER_H = 18;
    public static final int CREDITS_H = 16;
    private static final int TITLE_H = 14;
    private static final int MIN_NAV_STEP = 13;
    /** The description box is only shown when the list and the box together have this much height. */
    private static final int DESCRIBE_MIN_BODY = 150;
    /** The smallest box: a name and one line of description. */
    public static final int DESCRIBE_MIN_H = 30;
    /** What the credit heart takes after a row's name: a gap and the 7-pixel heart. */
    public static final int HEART_ROOM = 11;
    /**
     * How much of the 6-pixel margin a row's name keeps before its control the heart may use,
     * leaving it 3 pixels clear of the control.
     */
    public static final int HEART_MARGIN = 3;
    /** The gap a row's name (and the markers after it) keeps before the row's control. */
    public static final int NAME_GAP = 6;
    /** The cost column's width when the list is too narrow for the cost's words: a coloured dot. */
    public static final int COST_DOT_W = 9;
    /** The cost's words show when the list is at least this wide; dots below. */
    public static final int COST_WORDS_MIN_LIST = 230;
    /** The cost column before the screen has measured its words ("Medium" in the default font). */
    private static final int DEFAULT_COST_W = 38;
    /** Between the reset and the cost column. */
    private static final int COST_GAP = 4;
    /** What the changed marker takes after the name (or after the heart). */
    public static final int CHANGED_ROOM = 5;

    public static ScreenLayout of(int width, int height, boolean fullScreenWanted, int pages) {
        int dockedW = Math.clamp(Math.round(width * 0.46F), 300, 460);
        boolean docked = !fullScreenWanted && width >= 420 && width - dockedW >= 160;
        int margin = 4;
        int panelW = docked ? dockedW : Math.min(width - 2 * margin, 760);
        int panelX = docked ? margin : (width - panelW) / 2;
        int panelY = margin;
        int panelH = height - 2 * margin;
        int inner = panelW - 2 * PAD;
        int left = panelX + PAD;

        boolean compact = height < 260;
        int titleY = panelY + PAD;
        int searchX;
        int searchY;
        int searchW;
        int bodyY;
        if (compact) {
            // Search shares the title line; the title and count take what they need first.
            searchX = left + Math.min(inner / 2, 150);
            searchY = titleY - 1;
            searchW = left + inner - 18 - searchX;
            bodyY = titleY + SEARCH_H + 4;
        } else {
            searchX = left;
            searchY = titleY + TITLE_H + 2;
            searchW = inner;
            bodyY = searchY + SEARCH_H + 6;
        }
        int footerY = panelY + panelH - PAD - FOOTER_H;
        int bodyBottom = footerY - 5;

        boolean navLabels = inner >= 340;
        int navW = navLabels ? Math.clamp(Math.round(inner * 0.28F), 92, 140) : 22;
        int navRoom = bodyBottom - bodyY - CREDITS_H - 4;
        int navStep = Math.min(18, navRoom / Math.max(1, pages));
        boolean navAcross = navStep < MIN_NAV_STEP;

        int listX;
        int listY;
        int navX = left;
        int navY = bodyY;
        int creditsY;
        int navItemH;
        if (navAcross) {
            navStep = Math.max(14, Math.min(18, inner / (pages + 1)));
            navItemH = 15;
            creditsY = navY;
            listX = left + 2;
            listY = bodyY + navItemH + 5;
            navLabels = false;
            navW = inner;
        } else {
            navItemH = Math.min(16, navStep - 1);
            creditsY = bodyBottom - CREDITS_H;
            listX = left + navW + 8;
            listY = bodyY;
        }
        int listRight = left + inner - 5;
        int listW = listRight - listX;

        // The box grows to fit the description it shows, up to two fifths of the height, so the
        // list above always keeps the larger share.
        int body = bodyBottom - listY;
        int describeMaxH = body >= DESCRIBE_MIN_BODY ? Math.max(DESCRIBE_MIN_H, body * 2 / 5) : 0;
        int describeH = describeMaxH > 0 ? DESCRIBE_MIN_H : 0;
        int listBottom = describeH > 0 ? bodyBottom - describeH - 4 : bodyBottom;
        int describeY = listBottom + 4;

        return new ScreenLayout(docked, panelX, panelY, panelW, panelH, titleY, searchX, searchY, searchW,
                navX, navY, navW, navStep, navItemH, navLabels, navAcross, creditsY,
                listX, listY, listW, listBottom, describeY, describeH, describeMaxH, footerY, DEFAULT_COST_W);
    }

    /**
     * The same layout with the cost column as wide as the widest cost badge ({@code wordsW}), or
     * as wide as a dot when the list is too narrow for words.
     */
    public ScreenLayout withCostColumn(int wordsW) {
        int w = listW >= COST_WORDS_MIN_LIST ? wordsW : COST_DOT_W;
        return new ScreenLayout(docked, panelX, panelY, panelW, panelH, titleY, searchX, searchY, searchW,
                navX, navY, navW, navStep, navItemH, navLabels, navAcross, creditsY,
                listX, listY, listW, listBottom, describeY, describeH, describeMaxH, footerY, w);
    }

    /** Whether the cost column shows the cost's words rather than dots. */
    public boolean costWords() {
        return costW > COST_DOT_W;
    }

    /**
     * The same layout with the description box at {@code wantedH} (kept between its smallest
     * size and {@link #describeMaxH}); the list ends where the box begins. Unchanged without a box.
     */
    public ScreenLayout withDescribeH(int wantedH) {
        if (describeMaxH == 0) {
            return this;
        }
        int h = Math.clamp(wantedH, DESCRIBE_MIN_H, describeMaxH);
        if (h == describeH) {
            return this;
        }
        int newListBottom = bodyBottom() - h - 4;
        return new ScreenLayout(docked, panelX, panelY, panelW, panelH, titleY, searchX, searchY, searchW,
                navX, navY, navW, navStep, navItemH, navLabels, navAcross, creditsY,
                listX, listY, listW, newListBottom, newListBottom + 4, h, describeMaxH, footerY, costW);
    }

    /**
     * The same layout with the box sized for a description {@code wantedH} high, but never reaching
     * up over a row that ends at {@code rowBottom}: the box stops below that row (or stays at its
     * smallest size) and its text scrolls instead.
     */
    public ScreenLayout withDescribeBelow(int wantedH, int rowBottom) {
        return withDescribeH(Math.min(wantedH, bodyBottom() - 4 - rowBottom));
    }

    /**
     * The list's height with the description box at its largest. The list scrolls against this
     * height, so its last row can always be brought up clear of the biggest box.
     */
    public int shortestListH() {
        return describeMaxH == 0 ? listH() : bodyBottom() - describeMaxH - 4 - listY;
    }

    /** Where the list (and the description box under it) must end, above the footer's rule. */
    public int bodyBottom() {
        return footerY - 5;
    }

    public int listH() {
        return listBottom - listY;
    }

    public int navItemX(int index) {
        return navAcross ? navX + index * navStep : navX;
    }

    public int navItemY(int index) {
        return navAcross ? navY : navY + index * navStep;
    }

    /** Where the Credits button goes: the end of the icon row, or the bottom of the column. */
    public int creditsX() {
        return navAcross ? navX + navW - navStep : navX;
    }

    public int controlW() {
        return Math.clamp(Math.round(listW * 0.4F), 70, 140);
    }

    /** Offsets from listX: the cost column is flush right, the reset before it, the control before that. */
    public int costDx() {
        return listW - costW;
    }

    public int resetDx() {
        return costDx() - COST_GAP - ResetWidget.SIZE;
    }

    public int controlDx() {
        return resetDx() - 4 - controlW();
    }

    /**
     * An action row's button: as wide as a control and its reset together, flush with the list's
     * right edge, since an action has no reset and no cost; its name runs up to it.
     */
    public int actionDx() {
        return listW - controlW() - 4 - ResetWidget.SIZE;
    }

    /** Where every control ends: a switch, as the narrowest, is flush with this edge. */
    public int controlEndDx() {
        return controlDx() + controlW();
    }

    /**
     * A row's name runs up to its own control: the room from the list's left edge to a control
     * starting at {@code controlLeftDx}, less the gap before it. A switch starts further right
     * than a slider or a choice, so its name gets more room.
     */
    public static int nameRoom(int controlLeftDx) {
        return controlLeftDx - NAME_GAP;
    }

    /**
     * The room left for a row's name out of {@code room}, once a credited row has kept space for
     * its heart and a changed row for its marker; the name is shortened to fit, so neither reaches
     * the row's control.
     */
    public static int labelRoom(int room, boolean credited, boolean changed) {
        return room - (credited ? HEART_ROOM - HEART_MARGIN : 0) - (changed ? CHANGED_ROOM : 0);
    }

    public int panelRight() {
        return panelX + panelW;
    }
}
