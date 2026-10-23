package com.shumtugle.torodki;

/**
 * Between buildings the view tips up into the evening sky. Towering clouds stand over
 * the street, lit gold from below by the low sun, and in them, half dissolved, the
 * carved faces of the old gods of the place watch the street. They judge each fall
 * with their mouths: laughter, and lightning from the thunderer, for a building felled
 * at one throw; a smile for a few throws; nothing for many; a yawn when the stars run
 * out. The next building then falls out of the sky as the view comes back down.
 *
 * The clouds are noise, not geometry: a field of billows shaped by a few towers and
 * worked over with fractal noise; light comes from how the density changes toward the
 * sun, and a fine grain does the rest. The faces are painted pictures blended into the
 * clouds once; only their mouths move. The tilt is a scroll: the street slides down and
 * a strip of sky, painted once above the frame, slides in from the top.
 */
final class Sky {
    static final int OVATION = 0, CHEER = 1, POLITE = 2, YAWN = 3;

    private int w, sh;
    private int[] strip;
    private int verdict = POLITE;
    float time;
    int bolts;                    // how many strokes of lightning so far: the world thunders for each
    private int boltIdx = -1;
    private final java.util.Random rnd = new java.util.Random(5);

    // the faces: where they sit in the strip (center), their size, and where the mouth is
    private static final int FACES = 4;
    private static final int THUNDERER = 0, GODDESS = 1, THINKER = 2, ELDER = 3;
    private static final float[][] AT = {{0.6f, 0.19f}, {0.24f, 0.36f}, {0.76f, 0.5f}, {0.28f, 0.66f}};
    private static final float[][] MOUTH = {{0.4f, 0.57f}, {0.64f, 0.72f}, {0.46f, 0.72f}, {0.5f, 0.6f}};
    private final int[] fw = new int[FACES], fh = new int[FACES], fx = new int[FACES], fy = new int[FACES];

    // ---- noise ----

    private static int hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0xFFFF;
    }

    private static float vnoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float tx = x - xi, ty = y - yi;
        tx = tx * tx * (3f - 2f * tx);
        ty = ty * ty * (3f - 2f * ty);
        float a = hash(xi, yi, seed) / 65535f, b = hash(xi + 1, yi, seed) / 65535f;
        float c = hash(xi, yi + 1, seed) / 65535f, d = hash(xi + 1, yi + 1, seed) / 65535f;
        return a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty;
    }

    private static float fbm(float x, float y, int seed) {
        float s = 0f, amp = 0.5f, f = 1f;
        for (int o = 0; o < 5; o++) {
            s += vnoise(x * f, y * f, seed + o * 17) * amp;
            f *= 2.03f;
            amp *= 0.5f;
        }
        return s;
    }

    // ---- the strip ----

    private float[] towerX, towerY, towerRx, towerRy;

    /** Paints the sky above the frame, its clouds and the faces in them. */
    void build(Raster r, byte[] faces) {
        w = r.w;
        sh = r.h;
        strip = new int[w * sh];
        float[] d = new float[3];
        float shx = Raster.sunX(), shz = Raster.sunZ();
        float sl = (float) Math.sqrt(shx * shx + shz * shz);
        shx /= sl;
        shz /= sl;
        for (int j = 0; j < sh; j++)
            for (int x = 0; x < w; x++) {
                r.ray(x + 0.5f, j - sh + 0.5f, d);
                float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                strip[j * w + x] = Backdrop.sky(d[0] / l, d[1] / l, d[2] / l, shx, shz);
            }
        readFaces(faces);
        planTowers();
        clouds();
        stampFaces(faces);
    }

    /**
     * The masses the clouds billow from: a heaped tower round every face, a few more
     * between. Each tower is a stack of billows, widest at the bottom.
     */
    private void planTowers() {
        java.util.Random tr = new java.util.Random(31);
        int extra = 5, per = 5, n = (FACES + extra) * per;
        towerX = new float[n];
        towerY = new float[n];
        towerRx = new float[n];
        towerRy = new float[n];
        int k = 0;
        for (int t = 0; t < FACES + extra; t++) {
            float cx, cy, size;
            if (t < FACES) {
                cx = AT[t][0] * w;
                cy = AT[t][1] * sh + 8f;
                size = w * (t == THUNDERER ? 0.3f : 0.24f);
            } else {
                cx = tr.nextFloat() * w;
                cy = sh * (0.06f + tr.nextFloat() * 0.7f);
                size = w * (0.12f + tr.nextFloat() * 0.12f);
            }
            for (int b = 0; b < per; b++) {
                float up = b / (per - 1f);
                towerX[k] = cx + (tr.nextFloat() - 0.5f) * size * 0.9f;
                towerY[k] = cy + size * 0.5f - up * size * 1.1f;
                towerRx[k] = size * (1.15f - up * 0.55f) * (0.85f + tr.nextFloat() * 0.3f);
                towerRy[k] = size * (0.55f - up * 0.15f) * (0.85f + tr.nextFloat() * 0.3f);
                k++;
            }
        }
    }

    /** Cloud density at a point of the strip: towers, broken up by warped noise. */
    private float density(float x, float y) {
        float wx = x + (fbm(x * 0.02f, y * 0.02f, 7) - 0.5f) * 40f;
        float wy = y + (fbm(x * 0.02f + 9f, y * 0.02f, 8) - 0.5f) * 30f;
        float base = 0f;
        for (int i = 0; i < towerX.length; i++) {
            float u = (wx - towerX[i]) / towerRx[i], v = (wy - towerY[i]) / towerRy[i];
            // flatter undersides, billowing tops
            if (v > 0f) v *= 1.5f;
            float g = 1f - (u * u + v * v);
            if (g > base) base = g;
        }
        float detail = fbm(wx * 0.05f, wy * 0.05f, 3);
        float fine = fbm(wx * 0.14f, wy * 0.14f, 5);
        float dd = base * 0.95f + (detail - 0.5f) * 0.6f + (fine - 0.5f) * 0.1f;
        // no clouds where the strip meets the street's own sky
        float low = (y - sh * 0.84f) / (sh * 0.12f);
        if (low > 0f) dd -= low * 0.6f;
        return dd;
    }

    private void clouds() {
        // the light comes from low on the left: look a few pixels that way
        final float lx = -5f, ly = 4f;
        float[] dens = new float[w * sh];
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < w; x++) dens[y * w + x] = density(x + 0.5f, y + 0.5f);
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < w; x++) {
                int k = y * w + x;
                float dv = dens[k];
                float a = smooth(0.16f, 0.28f, dv);
                if (a <= 0f) continue;
                int sx = Math.min(w - 1, Math.max(0, Math.round(x + lx))), sy = Math.min(sh - 1, Math.max(0, Math.round(y + ly)));
                int sx2 = Math.min(w - 1, Math.max(0, Math.round(x + lx * 3f))), sy2 = Math.min(sh - 1, Math.max(0, Math.round(y + ly * 3f)));
                // thinner toward the sun means this side faces it
                float lit = 0.62f + (dv - dens[sy * w + sx]) * 1.7f + (dv - dens[sy2 * w + sx2]) * 0.8f;
                // deep inside a mass the light is lost
                lit -= smooth(0.6f, 1.15f, dv) * 0.3f;
                lit = Math.max(0f, Math.min(1f, lit));
                int c = lit < 0.5f ? Backdrop.mix(0xFF8A82B6, 0xFFEDCFCB, lit * 2f)
                        : Backdrop.mix(0xFFEDCFCB, 0xFFFFF0C9, (lit - 0.5f) * 2f);
                // the thin sunlit edge burns gold
                float rim = (1f - smooth(0.2f, 0.42f, dv)) * Math.max(0f, lit - 0.45f) * 2f;
                c = Backdrop.mix(c, 0xFFFFC66E, Math.min(1f, rim));
                // grain
                int g = hash(x, y, 77) & 0xFF;
                c = Backdrop.scale(c, 0.97f + g / 4250f);
                strip[k] = Backdrop.mix(strip[k], c, a);
            }
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    // ---- the faces ----

    private void readFaces(byte[] b) {
        if (b == null || b.length < 2) return;
        int n = Math.min(FACES, ((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
        int p = 2;
        for (int i = 0; i < n; i++) {
            fw[i] = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            fh[i] = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            p += 4 + fw[i] * fh[i] * 4;
            fx[i] = Math.round(AT[i][0] * w - fw[i] * 0.5f);
            fy[i] = Math.round(AT[i][1] * sh - fh[i] * 0.5f);
        }
    }

    /** Blends each face into its cloud, toned a little toward the cloud around it. */
    private void stampFaces(byte[] b) {
        if (b == null || b.length < 2) return;
        int n = Math.min(FACES, ((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
        int p = 2;
        for (int i = 0; i < n; i++) {
            p += 4;
            for (int y = 0; y < fh[i]; y++)
                for (int x = 0; x < fw[i]; x++) {
                    int q = p + (y * fw[i] + x) * 4;
                    int a = b[q + 3] & 0xFF;
                    int sx = fx[i] + x, sy = fy[i] + y;
                    if (a == 0 || sx < 0 || sy < 0 || sx >= w || sy >= sh) continue;
                    int c = 0xFF000000 | ((b[q] & 0xFF) << 16) | ((b[q + 1] & 0xFF) << 8) | (b[q + 2] & 0xFF);
                    int k = sy * w + sx;
                    c = Backdrop.mix(c, strip[k], 0.12f);
                    strip[k] = Backdrop.mix(strip[k], c, a / 255f);
                }
            p += fw[i] * fh[i] * 4;
        }
    }

    // ---- the verdict ----

    void begin(int verdict) {
        this.verdict = verdict;
        time = 0f;
        boltIdx = -1;
    }

    void update(float dt) {
        time += dt;
    }

    void compose(int[] scene, int[] out, int h, int off) {
        for (int y = 0; y < h; y++) {
            int src = y - off;
            if (src >= 0) System.arraycopy(scene, src * w, out, y * w, w);
            else {
                int j = sh + src;
                if (j < 0) j = 0;
                System.arraycopy(strip, j * w, out, y * w, w);
            }
        }
    }

    /** How far each face's mouth is open for this verdict, at this moment. */
    private float mouthOf(int face, float t) {
        switch (verdict) {
            case OVATION:
                return 0.45f + 0.35f * Math.abs((float) Math.sin(t * 8f + face * 1.7f));
            case CHEER:
                return face == GODDESS ? 0.12f : 0.22f + 0.08f * Math.abs((float) Math.sin(t * 5f + face));
            case YAWN:
                return face == ELDER ? Math.min(1f, Math.max(0f, (t - 0.3f) / 0.7f)) * (t < 2.4f ? 1f : 0.6f) : 0f;
            default:
                return 0f;
        }
    }

    /** Mouths and lightning, drawn where the strip now is on screen (shift = off - sh). */
    void drawClouds(Px f, float shift) {
        float t = time;
        for (int i = 0; i < FACES; i++) {
            if (fw[i] == 0) continue;
            float m = mouthOf(i, t);
            if (m < 0.04f) continue;
            float mx = fx[i] + MOUTH[i][0] * fw[i], my = fy[i] + MOUTH[i][1] * fh[i] + shift;
            float rx = fw[i] * (0.05f + 0.04f * m), ry = fh[i] * 0.06f * m + 0.5f;
            // the lips in the light, the mouth's own shadow inside
            // the open mouth: a soft shadow sunk into the stone, lit lip above it
            softOval(f, mx, my - ry * 0.55f, rx * 1.15f, Math.max(1f, ry * 0.35f), 0xFFFFE6C8, 0.45f);
            softOval(f, mx, my + ry * 0.3f, rx, ry, 0xFF5A4052, 0.8f);
        }
        // lightning on a great fall: three strokes from beside the thunderer
        if (verdict == OVATION && fw[THUNDERER] > 0) {
            float bt = t - 0.35f;
            if (bt > 0f) {
                int idx = (int) (bt / 0.85f);
                float since = bt - idx * 0.85f;
                if (idx < 3) {
                    if (idx != boltIdx) {
                        boltIdx = idx;
                        bolts++;
                        makeBolt(fx[THUNDERER] - 4f, fy[THUNDERER] + fh[THUNDERER] * 0.35f);
                    }
                    if (since < 0.2f || (since > 0.3f && since < 0.4f)) drawBolt(f, shift);
                }
            }
        }
    }

    /** A blended oval with a soft rim. */
    private static void softOval(Px f, float cx, float cy, float rx, float ry, int color, float strength) {
        int x0 = (int) Math.floor(cx - rx - 1), x1 = (int) Math.ceil(cx + rx + 1);
        int y0 = (int) Math.floor(cy - ry - 1), y1 = (int) Math.ceil(cy + ry + 1);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                if (x < 0 || y < 0 || x >= f.w || y >= f.h) continue;
                float u = (x + 0.5f - cx) / rx, v = (y + 0.5f - cy) / ry;
                float d = (float) Math.sqrt(u * u + v * v);
                if (d >= 1f) continue;
                float a = strength * (1f - smooth(0.45f, 1f, d));
                int k = y * f.w + x;
                f.a[k] = Backdrop.mix(f.a[k], color, a);
            }
    }

    // ---- lightning ----

    private final float[] boltX = new float[30], boltY = new float[30];
    private int boltN;

    private void makeBolt(float x0, float y0) {
        boltN = 0;
        float x = x0, y = y0;
        boltX[boltN] = x;
        boltY[boltN++] = y;
        while (boltN < boltX.length && y < sh * 1.2f) {
            x += -7f + (rnd.nextFloat() - 0.5f) * 30f;
            y += 16f + rnd.nextFloat() * 22f;
            boltX[boltN] = x;
            boltY[boltN++] = y;
        }
    }

    private void drawBolt(Px f, float shift) {
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < boltN - 1; i++) {
                float x0 = boltX[i], y0 = boltY[i] + shift, x1 = boltX[i + 1], y1 = boltY[i + 1] + shift;
                int n = (int) (Math.abs(x1 - x0) + Math.abs(y1 - y0)) + 1;
                for (int k = 0; k <= n; k++) {
                    float q = k / (float) n;
                    float x = x0 + (x1 - x0) * q, y = y0 + (y1 - y0) * q;
                    if (pass == 0) glow(f, x, y);
                    else {
                        f.set(Math.round(x), Math.round(y), 0xFFFFFFF4);
                        f.set(Math.round(x) + 1, Math.round(y), 0xFFFFF2C0);
                    }
                }
            }
    }

    private void glow(Px f, float x, float y) {
        for (int dy = -3; dy <= 3; dy++)
            for (int dx = -3; dx <= 3; dx++) {
                int px = Math.round(x) + dx, py = Math.round(y) + dy;
                if (px < 0 || py < 0 || px >= f.w || py >= f.h) continue;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 3.2f) continue;
                int k = py * f.w + px;
                f.a[k] = Backdrop.mix(f.a[k], 0xFFFFE6A0, 0.22f * (1f - d / 3.3f));
            }
    }
}
