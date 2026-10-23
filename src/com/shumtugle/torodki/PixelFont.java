package com.shumtugle.torodki;

/** A 5x7 bitmap font drawn into a Px: uppercase Latin, digits and a few signs. Plain Java, no platform classes. */
final class PixelFont {
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:.'-/!?,%+";
    private static final String[] GLYPHS = {
        ".###.#...##...#######...##...##...#", // A
        "####.#...##...#####.#...##...#####.", // B
        ".###.#...##....#....#....#...#.###.", // C
        "####.#...##...##...##...##...#####.", // D
        "######....#....####.#....#....#####", // E
        "######....#....####.#....#....#....", // F
        ".###.#...##....#.####...##...#.####", // G
        "#...##...##...#######...##...##...#", // H
        ".###...#....#....#....#....#...###.", // I
        "..###...#....#....#....#.#..#..##..", // J
        "#...##..#.#.#..##...#.#..#..#.#...#", // K
        "#....#....#....#....#....#....#####", // L
        "#...###.###.#.##.#.##...##...##...#", // M
        "#...##...###..##.#.##..###...##...#", // N
        ".###.#...##...##...##...##...#.###.", // O
        "####.#...##...#####.#....#....#....", // P
        ".###.#...##...##...##.#.##..#..##.#", // Q
        "####.#...##...#####.#.#..#..#.#...#", // R
        ".#####....#.....###.....#....#####.", // S
        "#####..#....#....#....#....#....#..", // T
        "#...##...##...##...##...##...#.###.", // U
        "#...##...##...##...##...#.#.#...#..", // V
        "#...##...##...##.#.##.#.##.#.#.#.#.", // W
        "#...##...#.#.#...#...#.#.#...##...#", // X
        "#...##...#.#.#...#....#....#....#..", // Y
        "#####....#...#...#...#...#....#####", // Z
        ".###.#...##..###.#.###..##...#.###.", // 0
        "..#...##....#....#....#....#...###.", // 1
        ".###.#...#....#...#...#...#...#####", // 2
        "#####...#...#.....#.....##...#.###.", // 3
        "...#...##..#.#.#..#.#####...#....#.", // 4
        "######....####.....#....##...#.###.", // 5
        "..##..#...#....####.#...##...#.###.", // 6
        "#####....#...#...#...#....#....#...", // 7
        ".###.#...##...#.###.#...##...#.###.", // 8
        ".###.#...##...#.####....#...#..##..", // 9
        "......#....#.........#....#........", // :
        ".........................##...##...", // .
        "..#....#...#.......................", // '
        "...............###.................", // -
        "....#....#...#...#...#...#....#....", // /
        "..#....#....#....#....#.........#..", // !
        ".###.#...#....#...#...#.........#..", // ?
        "....................##....#...#....", // ,
        "##..###..#...#...#...#...#..###..##", // %
        ".......#....#..#####..#....#.......", // +
    };
    private static final long[] MASK = new long[128];
    private static final int[] LEFT = new int[128], RIGHT = new int[128];

    static {
        for (int i = 0; i < CHARS.length(); i++) {
            String g = GLYPHS[i];
            if (g.length() < 35) g = g + "...................................".substring(g.length());
            long m = 0;
            int lo = 5, hi = -1;
            for (int b = 0; b < 35; b++)
                if (g.charAt(b) == '#') {
                    m |= 1L << b;
                    lo = Math.min(lo, b % 5);
                    hi = Math.max(hi, b % 5);
                }
            char ch = CHARS.charAt(i);
            MASK[ch] = m;
            LEFT[ch] = lo;
            RIGHT[ch] = hi;
        }
    }

    private PixelFont() {}

    private static char norm(char c) {
        if (c >= 'a' && c <= 'z') return (char) (c - 32);
        if (c == '\u2019') return '\'';
        return c < 128 ? c : '?';
    }

    private static boolean narrow(char c) {
        return c == ':' || c == '.' || c == '\'' || c == '!' || c == ',';
    }

    private static int advance(char c) {
        if (c == ' ') return 4;
        if (narrow(c) && MASK[c] != 0) return RIGHT[c] - LEFT[c] + 2;
        return 6;
    }

    static int width(String s, int scale) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += advance(norm(s.charAt(i)));
        return Math.max(0, w - 1) * scale;
    }

    static void draw(Px px, String s, int x, int y, int scale, int color) {
        draw(px, s, x, y, scale, color, null);
    }

    /** Draws text with its top-left at (x, y). rowColors, when given, colors each of the seven glyph rows. */
    static void draw(Px px, String s, int x, int y, int scale, int color, int[] rowColors) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            char ch = norm(s.charAt(i));
            long m = MASK[ch];
            int shift = narrow(ch) && m != 0 ? LEFT[ch] : 0;
            for (int b = 0; b < 35; b++) {
                if ((m & (1L << b)) == 0) continue;
                int gx = b % 5 - shift, gy = b / 5;
                px.rect(cx + gx * scale, y + gy * scale, cx + (gx + 1) * scale, y + (gy + 1) * scale,
                        rowColors != null ? rowColors[gy] : color);
            }
            cx += advance(ch) * scale;
        }
    }
}
