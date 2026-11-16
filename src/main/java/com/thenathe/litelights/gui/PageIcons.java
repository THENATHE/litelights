package com.thenathe.litelights.gui;

import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The 11x11 pixel pictures in the navigation, one per page. Letters pick colours from
 * {@link #COLOURS}; '.' is empty. A page whose icon name isn't here gets the "module" icon.
 */
public final class PageIcons {
    public static final int SIZE = 11;

    private static final Map<Character, Integer> COLOURS = Map.ofEntries(
            Map.entry('a', 0xFFE8A33D), // amber
            Map.entry('y', 0xFFFFE08A), // pale yellow
            Map.entry('w', 0xFFFFF8EA), // warm white
            Map.entry('l', 0xFFD8CCB8), // light
            Map.entry('d', 0xFF8C7F6C), // dim
            Map.entry('k', 0xFF4A4038), // dark
            Map.entry('b', 0xFF6FA8DC), // water blue
            Map.entry('B', 0xFF3F6E9E), // deep blue
            Map.entry('g', 0xFF7DBA5A), // leaf green
            Map.entry('G', 0xFF4E8A36), // deep green
            Map.entry('r', 0xFFE2694E), // red
            Map.entry('p', 0xFFB98AE8), // purple
            Map.entry('P', 0xFF7650B0), // deep purple
            Map.entry('s', 0xFFE3CF8E)  // sand
    );

    private static final Map<String, String[]> ICONS = Map.ofEntries(
            Map.entry("general", new String[] {
                    "..l....l...",
                    "..l....l...",
                    ".aaa...l...",
                    ".aaa...l..l",
                    "..l...aaa.l",
                    "..l...aaa.l",
                    "..l....l..l",
                    "..l....l.aa",
                    "..l....l.aa",
                    "..l....l..l",
                    "..l....l..l"}),
            Map.entry("sky", new String[] {
                    "...a.......",
                    "a..a..a....",
                    ".aaaaa.....",
                    "aaayyaa....",
                    ".ayyya.....",
                    "aaaaa.www..",
                    ".a..wwwwww.",
                    "...wwwwwwww",
                    "..wwwwwwwww",
                    "..lllllllll",
                    "..........."}),
            Map.entry("fog", new String[] {
                    "...........",
                    "..dddddd...",
                    "...........",
                    "ddddd.dddd.",
                    "...........",
                    ".lllllllll.",
                    "...........",
                    "llll.llllll",
                    "...........",
                    "..wwwwwww..",
                    "..........."}),
            Map.entry("lighting", new String[] {
                    "...aaaaa...",
                    "..ayyyyya..",
                    ".ayywwyyya.",
                    ".ayywyyyya.",
                    ".ayyyyyyya.",
                    "..ayyyyya..",
                    "...ayyya...",
                    "....aaa....",
                    "...ddddd...",
                    "...ddddd...",
                    "....ddd...."}),
            Map.entry("colour_grading", new String[] {
                    "...rrr.....",
                    "..rrrrr....",
                    ".rrrrrrr...",
                    ".rrraaaabb.",
                    ".rraaaabbbb",
                    "..raaabbbbb",
                    "...ggaabbbb",
                    "..gggggbbb.",
                    "..ggggggg..",
                    "...ggggg...",
                    "..........."}),
            Map.entry("shadows", new String[] {
                    "....ll.....",
                    "..llllll...",
                    "llllllllll.",
                    "lddllllaal.",
                    "ldddddaaal.",
                    "ldddddaaal.",
                    "ldddddaaakk",
                    "lddddaaakkk",
                    ".ldddaakkkk",
                    "...ldakkkk.",
                    ".....kkk..."}),
            Map.entry("water", new String[] {
                    ".....b.....",
                    ".....b.....",
                    "....bbb....",
                    "...bbbbb...",
                    "..bbwbbbb..",
                    ".bbwbbbbbb.",
                    ".bbwbbbbbB.",
                    ".bbbbbbbbB.",
                    "..bbbbbBB..",
                    "...BBBBB...",
                    "..........."}),
            Map.entry("vegetation", new String[] {
                    "......GGG..",
                    "....GGgggG.",
                    "...GgggggG.",
                    "..GggggggG.",
                    "..Ggggggg..",
                    "...GgggG...",
                    ".gg..GG....",
                    "gggg.G.....",
                    ".ggg.G.....",
                    "...gGG.....",
                    "....G......"}),
            Map.entry("particles", new String[] {
                    "..a........",
                    ".aya....w..",
                    "..a........",
                    "......a....",
                    ".....aya...",
                    "...aayyyaa.",
                    ".....aya...",
                    "..w...a....",
                    "........a..",
                    ".......aya.",
                    "........a.."}),
            Map.entry("weather", new String[] {
                    "...lll.....",
                    ".lllllll...",
                    "lllllllllll",
                    "lllllllllll",
                    ".ddddddddd.",
                    "...........",
                    ".b..b..b...",
                    "b..b..b..b.",
                    "..b..b..b..",
                    ".b..b..b...",
                    "..........."}),
            Map.entry("outlines", new String[] {
                    "........ppp",
                    ".......pwlp",
                    "......pwlp.",
                    ".....pwlp..",
                    "..p.pwlp...",
                    "..ppwlp....",
                    "...pdp.....",
                    "..pdppp....",
                    ".pdp..p....",
                    "pdp........",
                    "pp........."}),
            Map.entry("dimensions", new String[] {
                    "kkkkkkkkkkk",
                    "kPPPPPPPPPk",
                    "kPpppppppPk",
                    "kPpPpppppPk",
                    "kPppppPppPk",
                    "kPpppppppPk",
                    "kPppPppppPk",
                    "kPpppppPpPk",
                    "kPpppppppPk",
                    "kPPPPPPPPPk",
                    "kkkkkkkkkkk"}),
            Map.entry("overlays", new String[] {
                    "ggggggg....",
                    "gGgggGgg...",
                    "ggggggggs..",
                    "dgGgdgggss.",
                    "ddgddgdsss.",
                    "dddddgsssss",
                    "ddddddsssss",
                    "..sssssssss",
                    "..sssssssss",
                    "..sssssssss",
                    "..sssssssss"}),
            Map.entry("about", new String[] {
                    "...lllll...",
                    "..l.....l..",
                    ".l...a...l.",
                    "l.........l",
                    "l...aa....l",
                    "l....a....l",
                    "l....a....l",
                    ".l...a...l.",
                    "..l.aaa.l..",
                    "...lllll...",
                    "..........."}),
            Map.entry("credits", new String[] {
                    "...........",
                    ".rrr...rrr.",
                    "rrrrr.rrrrr",
                    "rwrrrrrrrrr",
                    "rwrrrrrrrrr",
                    "rrrrrrrrrrr",
                    ".rrrrrrrrr.",
                    "..rrrrrrr..",
                    "...rrrrr...",
                    "....rrr....",
                    ".....r....."}),
            Map.entry("module", new String[] {
                    "...........",
                    ".aaaa.llll.",
                    ".aaaa.llll.",
                    ".aaaa.llll.",
                    ".aaaa.llll.",
                    "...........",
                    ".llll.llll.",
                    ".llll.llll.",
                    ".llll.llll.",
                    ".llll.llll.",
                    "..........."}));

    private PageIcons() {
    }

    /** Draws the icon; {@code faded} draws it at half strength, for pages that aren't chosen. */
    public static void draw(GuiGraphicsExtractor graphics, String name, int x, int y, boolean faded) {
        String[] rows = ICONS.getOrDefault(name, ICONS.get("module"));
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int column = 0;
            while (column < line.length()) {
                char key = line.charAt(column);
                Integer colour = COLOURS.get(key);
                int runEnd = column + 1;
                while (runEnd < line.length() && line.charAt(runEnd) == key) {
                    runEnd++;
                }
                if (colour != null) {
                    int drawn = faded ? Theme.withAlpha(colour, 0xB0) : colour;
                    graphics.fill(x + column, y + row, x + runEnd, y + row + 1, drawn);
                }
                column = runEnd;
            }
        }
    }
}
