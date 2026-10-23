package com.shumtugle.torodki;

/**
 * A tiny integer pixel canvas, ARGB. The whole frame and all game art are painted
 * here, pixel by pixel; nothing is loaded from disk. Plain Java, no platform classes,
 * so the desktop stand can run the same code.
 */
final class Px {
    final int w, h;
    final int[] a;
    boolean wrap; // horizontal wrap-around for seamless background strips

    Px(int w, int h) {
        this.w = w;
        this.h = h;
        this.a = new int[w * h];
    }

    void set(int x, int y, int c) {
        if (wrap) x = ((x % w) + w) % w;
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        a[y * w + x] = c;
    }

    int get(int x, int y) {
        if (wrap) x = ((x % w) + w) % w;
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        return a[y * w + x];
    }

    /** Fills [x0, x1) x [y0, y1). */
    void rect(int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) set(x, y, c);
    }

    void disc(float cx, float cy, float r, int c) {
        int r0 = (int) Math.ceil(r);
        float rr = r * r + r * 0.6f;
        for (int y = -r0; y <= r0; y++)
            for (int x = -r0; x <= r0; x++)
                if (x * x + y * y <= rr) set(Math.round(cx) + x, Math.round(cy) + y, c);
    }

    void ellipse(float cx, float cy, float rx, float ry, int c) {
        int x0 = (int) Math.floor(cx - rx), x1 = (int) Math.ceil(cx + rx);
        int y0 = (int) Math.floor(cy - ry), y1 = (int) Math.ceil(cy + ry);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                float dx = (x - cx) / rx, dy = (y - cy) / ry;
                if (dx * dx + dy * dy <= 1.05f) set(x, y, c);
            }
    }

    /** Recolors existing pixels of one color with a sparse random pattern. */
    void speckle(java.util.Random rnd, int from, int to, float density) {
        for (int i = 0; i < a.length; i++)
            if (a[i] == from && rnd.nextFloat() < density) a[i] = to;
    }

    /** One-pixel dark outline around every fully opaque shape. */
    void outline(int c) {
        int[] src = a.clone();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if ((src[y * w + x] >>> 24) != 0) continue;
                if (opaque(src, x - 1, y) || opaque(src, x + 1, y)
                        || opaque(src, x, y - 1) || opaque(src, x, y + 1)) a[y * w + x] = c;
            }
    }

    private boolean opaque(int[] src, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return false;
        return (src[y * w + x] >>> 24) == 0xFF;
    }

    void clear(int c) {
        java.util.Arrays.fill(a, c);
    }

    /** Copies a sprite in, skipping fully transparent pixels. */
    void blit(Px s, int x, int y) {
        for (int j = 0; j < s.h; j++)
            for (int i = 0; i < s.w; i++) {
                int c = s.a[j * s.w + i];
                if ((c >>> 24) != 0) set(x + i, y + j, c);
            }
    }
}
