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
 */
public record ScreenLayout(
        boolean docked,
        int panelX, int panelY, int panelW, int panelH,
        int titleY, int searchX, int searchY, int searchW,
        int navX, int navY, int navW, int navStep, int navItemH, boolean navLabels, boolean navAcross,
        int creditsY,
        int listX, int listY, int listW, int listBottom,
        int describeY, int describeH, int describeMaxH,
        int footerY) {

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
                listX, listY, listW, listBottom, describeY, describeH, describeMaxH, footerY);
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
                listX, listY, listW, newListBottom, newListBottom + 4, h, describeMaxH, footerY);
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

    /** Offsets from listX: the reset is flush right, the control just before it. */
    public int resetDx() {
        return listW - ResetWidget.SIZE;
    }

    public int controlDx() {
        return resetDx() - 4 - controlW();
    }

    public int panelRight() {
        return panelX + panelW;
    }
}
