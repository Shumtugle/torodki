package com.shumtugle.torodki;

/**
 * Between buildings the view tips up into the evening sky. Rays of the low sun fan up
 * from the left, far clouds hang behind, and the old gods of the place gather, each
 * in a cloud of his own: they sail in from the sides, nearer ones faster, and their faces
 * come out of the vapour as they settle. Then they judge the fall. A wisp of haze passes
 * over each face and, as it clears, the face has changed: laughter, and lightning from
 * the thunderer, for a building felled at one throw; smiles for a few throws; a sceptic
 * look for many; anger, sleep and scepticism when the stars run out. No god ever yawns.
 * The next building then
 * falls out of the sky as the view comes back down.
 *
 * The sky, its rays and the far clouds are painted once into a strip above the frame;
 * the tilt is a scroll that slides the street down and the strip in from the top. The
 * gods are painted pictures, three expressions each, read from an asset and drawn every
 * frame over the strip.
 */
final class Sky {
    static final int OVATION = 0, CHEER = 1, POLITE = 2, GLOOM = 3;

    static final int THUNDERER = 0, GODDESS = 1, THINKER = 2, ELDER = 3, IDLER = 4;
    private static final int GODS = 5;
    private static final int MAX_PICTURES = 24;

    private int w, sh;
    private int[] strip, winterStrip;
    boolean winter;               // over the winter ocean: a cold grey sky, the gods frosted
    private int verdict = POLITE;
    float time;
    int bolts;                    // how many strikes of lightning so far: the world thunders for each
    float boltNear = 1f;          // how near the last strike was: 1 overhead, toward 0 far off
    private final java.util.Random rnd = new java.util.Random(5);

    // where each god settles (center, as a share of the strip), how near he is (parallax),
    // from which side he sails in, when he arrives and when his face changes
    private static final float[][] AT = {{0.56f, 0.2f}, {0.27f, 0.42f}, {0.73f, 0.52f}, {0.72f, 0.75f}, {0.31f, 0.66f}};
    private static final float[] NEAR = {1.35f, 1.2f, 1.16f, 1.08f, 1.1f};
    private static final float[] SIDE = {1f, -1f, 1f, 1f, -1f};
    private static final float[] DELAY = {0.1f, 0.0f, 0.15f, 0.25f, 0.2f};
    private static final float[] CHANGE = {1.25f, 1.5f, 1.75f, 2.2f, 1.95f};
    // the far ones first, the thunderer last
    private static final int[] ORDER = {ELDER, IDLER, THINKER, GODDESS, THUNDERER};
    // which expression each god turns to, by verdict: 0 at rest, then the two the painter gave him
    private static final int[][] FACE_FOR = {
            {1, 1, 1, 1, 1},          // ovation: laughter and smiles
            {0, 1, 1, 1, 1},          // cheer: the thunderer stays stern
            {0, 0, 2, 0, 0},          // polite: only the thinker raises a brow
            {2, 2, 2, 0, 0},          // stars run out: anger, sleep, scepticism; the others look on
    };
    // the faces that yawn: painted, but never shown
    private static final boolean[][] YAWNS = {
            {false, false, false},
            {false, false, false},
            {false, false, false},
            {false, false, true},     // elder
            {false, false, true},     // idler
    };
    // which of those faces open the mouth wide: laughter and yawns. Many open mouths at once
    // read as wailing, so a verdict lets only one god open his; the others keep their rest face
    private static final boolean[][] OPEN = {
            {false, true, false},     // thunderer: laughs
            {false, false, false},    // goddess
            {false, false, false},    // thinker
            {false, true, true},      // elder: laughs, yawns
            {false, false, true},     // idler: yawns
    };
    private final int[] faceTo = new int[GODS];

    // the pictures as read from the asset: premultiplied ARGB, one array per expression. A god
    // may have several pictures; each visit to the sky picks one of his at random
    private int pictures;
    private final int[] role = new int[MAX_PICTURES];
    private final int[][][] face = new int[MAX_PICTURES][][];
    private final int[][] veil = new int[MAX_PICTURES][];       // the rest face blurred to plain cloud
    private final int[][] wisp = new int[MAX_PICTURES][];       // the haze that hides a change
    private final int[] gw = new int[MAX_PICTURES], gh = new int[MAX_PICTURES];
    private final int[] gfx = new int[MAX_PICTURES], gfy = new int[MAX_PICTURES];
    private final int[] wispW = new int[MAX_PICTURES], wispH = new int[MAX_PICTURES];
    private final int[] pick = {-1, -1, -1, -1, -1};             // the picture of each god this visit

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

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : x > 1f ? 1f : x;
    }

    // ---- the strip ----

    /** Paints the sky above the frame with its rays and far clouds, and reads the gods. */
    void build(Raster r, byte[] gods, byte[] far) {
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
        rays();
        farClouds(far, strip, false);
        // the winter sky: low cloud, grey, the same far clouds gone cold
        winterStrip = new int[w * sh];
        for (int j = 0; j < sh; j++)
            for (int x = 0; x < w; x++) {
                r.ray(x + 0.5f, j - sh + 0.5f, d);
                float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                winterStrip[j * w + x] = Ocean.sky(d[0] / l, d[1] / l, d[2] / l);
            }
        farClouds(far, winterStrip, true);
        readGods(gods);
    }

    /** Broad shafts of warm light fanning up from the sun, low on the left below the strip. */
    private void rays() {
        float sx = -0.3f * w, sy = sh * 1.2f;
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < w; x++) {
                float dx = x - sx, dy = sy - y;
                float ang = (float) Math.atan2(dy, dx);
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float band = vnoise(ang * 9f, 0.5f, 41) * 0.65f + vnoise(ang * 23f, 3.5f, 43) * 0.35f;
                float a = smooth(0.48f, 0.8f, band);
                // gone toward the top, and gone at the bottom where the strip meets the street's sky
                a *= 0.2f * clamp01(1.25f - dist / (sh * 1.15f)) * (1f - smooth(0.62f, 0.95f, y / (float) sh));
                if (a > 0f) {
                    int k = y * w + x;
                    strip[k] = Backdrop.mix(strip[k], 0xFFFFE4B4, a);
                }
            }
    }

    // the far clouds, painted pieces laid out over the strip: center (share of the strip)
    private static final float[][] FAR = {
            {0.2f, 0.06f}, {0.86f, 0.15f}, {0.47f, 0.3f}, {0.08f, 0.32f}, {0.98f, 0.37f},
            {0.56f, 0.47f}, {0.95f, 0.6f}, {0.08f, 0.72f}, {0.45f, 0.82f}, {0.32f, 0.57f},
            {0.8f, 0.86f}, {0.2f, 0.92f}, {0.62f, 0.94f}};

    /**
     * Lays the far clouds into the strip, a little veiled by the air between: tinted toward
     * the sky behind them and thinned, more so low down where the strip meets the street's sky.
     */
    private void farClouds(byte[] b, int[] strip, boolean cold) {
        if (b == null || b.length < 2) return;
        int n = Math.min(FAR.length, u16(b, 0)), p = 2;
        for (int i = 0; i < n && p + 4 <= b.length; i++) {
            int ww = u16(b, p), hh = u16(b, p + 2);
            p += 4;
            if (p + ww * hh * 4 > b.length) return;
            int ox = Math.round(FAR[i][0] * w - ww * 0.5f), oy = Math.round(FAR[i][1] * sh - hh * 0.5f);
            for (int y = 0; y < hh; y++)
                for (int x = 0; x < ww; x++, p += 4) {
                    int a = b[p + 3] & 0xFF;
                    int sx = ox + x, sy = oy + y;
                    if (a == 0 || sx < 0 || sy < 0 || sx >= w || sy >= sh) continue;
                    float fade = 0.88f * (1f - smooth(0.78f, 0.97f, sy / (float) sh));
                    if (fade <= 0f) continue;
                    int k = sy * w + sx;
                    float al = a / 255f;
                    // unpremultiply, then let the sky show through a little
                    int c = 0xFF000000 | (Math.min(255, (b[p] & 0xFF) * 255 / a) << 16)
                            | (Math.min(255, (b[p + 1] & 0xFF) * 255 / a) << 8) | Math.min(255, (b[p + 2] & 0xFF) * 255 / a);
                    c = Backdrop.mix(c, strip[k], 0.18f);
                    if (cold) {
                        c = cold(c | 0xFF000000);
                        al *= 0.7f;
                    }
                    strip[k] = Backdrop.mix(strip[k], c, al * fade);
                }
        }
    }

    // ---- the gods ----

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    /** Reads the gods: role, size, face center, then each expression as premultiplied RGBA. */
    private void readGods(byte[] b) {
        if (b == null || b.length < 2) return;
        int n = u16(b, 0), p = 2;
        for (int i = 0; i < n && p + 10 <= b.length; i++) {
            int who = b[p] & 0xFF;
            int ww = u16(b, p + 1), hh = u16(b, p + 3), fx = u16(b, p + 5), fy = u16(b, p + 7);
            int m = b[p + 9] & 0xFF;
            p += 10;
            if (p + m * ww * hh * 4 > b.length) return;
            int[][] frames = new int[m][];
            for (int e = 0; e < m; e++) {
                int[] img = new int[ww * hh];
                boolean any = false;
                for (int k = 0; k < img.length; k++, p += 4) {
                    img[k] = ((b[p + 3] & 0xFF) << 24) | ((b[p] & 0xFF) << 16) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
                    if (img[k] != 0) any = true;
                }
                // an empty face is one the painter has not drawn yet
                frames[e] = any ? img : null;
            }
            if (who >= GODS || m == 0 || pictures == MAX_PICTURES) continue;
            int e = pictures++;
            role[e] = who;
            face[e] = frames;
            gw[e] = ww;
            gh[e] = hh;
            gfx[e] = fx;
            gfy[e] = fy;
            veil[e] = blurred(frames[0], ww, hh);
            makeWisp(e);
            if (pick[who] < 0) pick[who] = e;
        }
    }

    /** A soft blur of a premultiplied picture: three passes of a box, each way. */
    private static int[] blurred(int[] src, int ww, int hh) {
        float[][] ch = new float[4][ww * hh];
        for (int k = 0; k < src.length; k++)
            for (int c = 0; c < 4; c++) ch[c][k] = (src[k] >>> (24 - c * 8)) & 0xFF;
        float[] tmp = new float[ww * hh];
        int r = Math.max(2, ww / 22);
        for (int c = 0; c < 4; c++)
            for (int pass = 0; pass < 3; pass++) {
                box(ch[c], tmp, ww, hh, r, true);
                box(tmp, ch[c], ww, hh, r, false);
            }
        int[] out = new int[ww * hh];
        for (int k = 0; k < out.length; k++) {
            int a = Math.min(255, Math.round(ch[0][k]));
            // lifted toward bright vapour, so no muddy smear of a face shows before the face
            float l = 0.45f;
            int rr = Math.min(a, Math.round(ch[1][k] * (1f - l) + 0xF8 * a / 255f * l));
            int gg = Math.min(a, Math.round(ch[2][k] * (1f - l) + 0xE6 * a / 255f * l));
            int bb = Math.min(a, Math.round(ch[3][k] * (1f - l) + 0xDA * a / 255f * l));
            out[k] = (a << 24) | (rr << 16) | (gg << 8) | bb;
        }
        return out;
    }

    private static void box(float[] in, float[] out, int ww, int hh, int r, boolean rows) {
        int len = rows ? ww : hh, lines = rows ? hh : ww;
        int step = rows ? 1 : ww;
        float inv = 1f / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            int base = rows ? l * ww : l;
            float s = 0f;
            // outside the picture is empty air
            for (int i = 0; i <= r && i < len; i++) s += in[base + i * step];
            for (int i = 0; i < len; i++) {
                out[base + i * step] = s * inv;
                int add = i + r + 1, sub = i - r;
                if (add < len) s += in[base + add * step];
                if (sub >= 0) s -= in[base + sub * step];
            }
        }
    }

    /** The wisp of haze for one god: a ragged puff of cloud, lit from the lower left. */
    private void makeWisp(int g) {
        int ww = Math.round(gw[g] * 0.66f), hh = Math.round(gw[g] * 0.34f);
        int[] img = new int[ww * hh];
        for (int y = 0; y < hh; y++)
            for (int x = 0; x < ww; x++) {
                float u = (x + 0.5f) / ww * 2f - 1f, v = (y + 0.5f) / hh * 2f - 1f;
                float n = fbm(x * 0.13f + g * 7f, y * 0.13f, 61 + g);
                float fine = fbm(x * 0.4f, y * 0.4f, 67 + g);
                float d = 1f - (u * u + v * v * 1.2f) + (n - 0.5f) * 1.2f;
                float a = smooth(0.0f, 0.55f, d) * (0.88f + (fine - 0.5f) * 0.3f);
                if (a <= 0f) continue;
                float lit = clamp01(0.6f - u * 0.3f + v * 0.25f + (n - 0.5f) * 0.9f + (fine - 0.5f) * 0.4f);
                int c = lit < 0.5f ? Backdrop.mix(0xFFB4A2CE, 0xFFF6E0D6, lit * 2f)
                        : Backdrop.mix(0xFFF6E0D6, 0xFFFFEFCB, (lit - 0.5f) * 2f);
                c = Backdrop.mix(c, 0xFFFFC98A, Math.max(0f, lit - 0.75f) * 1.6f);
                int ai = Math.round(a * 255f);
                img[y * ww + x] = (ai << 24) | (((c >> 16) & 0xFF) * ai / 255 << 16)
                        | (((c >> 8) & 0xFF) * ai / 255 << 8) | ((c & 0xFF) * ai / 255);
            }
        wisp[g] = img;
        wispW[g] = ww;
        wispH[g] = hh;
    }

    // ---- the verdict ----

    void begin(int verdict) {
        begin(verdict, false);
    }

    /** A verdict; on a jubilee every god who can laugh laughs aloud, open mouths and all. */
    void begin(int verdict, boolean jubilee) {
        this.verdict = verdict;
        // one open mouth at most: the thunderer's on an ovation (he brings the lightning), else anyone's
        int open = 0;
        for (int g = 0; g < GODS; g++) {
            faceTo[g] = FACE_FOR[verdict][g];
            if (YAWNS[g][faceTo[g]]) faceTo[g] = 0;
            if (OPEN[g][faceTo[g]]) open++;
        }
        int keep = verdict == OVATION && OPEN[THUNDERER][faceTo[THUNDERER]] ? 0 : open > 0 ? rnd.nextInt(open) : -1;
        for (int g = 0, k = 0; g < GODS; g++) {
            if (!OPEN[g][faceTo[g]]) continue;
            boolean mine = jubilee || (verdict == OVATION ? g == THUNDERER : k == keep);
            k++;
            if (!mine) faceTo[g] = 0;
        }
        // each god comes in one of his pictures: one that has the face he is to change to
        for (int g = 0; g < GODS; g++) {
            int n = 0;
            for (int e = 0; e < pictures; e++) if (role[e] == g && has(e, faceTo[g])) n++;
            if (n == 0) continue;
            int k = rnd.nextInt(n);
            for (int e = 0; e < pictures; e++)
                if (role[e] == g && has(e, faceTo[g]) && k-- == 0) pick[g] = e;
        }
        time = 0f;
        planStrikes();
    }

    private boolean has(int e, int f) {
        return face[e].length > f && face[e][f] != null;
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
                System.arraycopy(winter ? winterStrip : strip, j * w, out, y * w, w);
            }
        }
    }

    /** The gods and the lightning, drawn where the strip now is on screen (shift = off - sh). */
    void drawClouds(Px f, float shift) {
        float t = time;
        // lightning on a great fall: three strokes from behind the thunderer once he laughs,
        // drawn first so the clouds of the gods stand in front of it
        int th = pick[THUNDERER];
        if (verdict == OVATION && th >= 0) lightning(f, t, shift * NEAR[THUNDERER], th);
        for (int o = 0; o < ORDER.length; o++) {
            int g = ORDER[o];
            int e = pick[g];
            if (e < 0) continue;
            float since = t - DELAY[g];
            if (since <= 0f) continue;
            // sailing in: fast at first, settling softly
            float arrive = clamp01(since / 1.4f);
            float ease = 1f - (1f - arrive) * (1f - arrive) * (1f - arrive);
            float cx = AT[g][0] * w + SIDE[g] * 95f * (1f - ease);
            float cy = AT[g][1] * sh + 14f * (1f - ease) + shift * NEAR[g]
                    + (float) Math.sin(t * 1.1f + g * 2.1f) * 1.2f;
            float alpha = clamp01(since / 0.5f);
            // the face comes out of the vapour, growing to its size
            float emerge = smooth(0f, 1f, (since - 0.45f) / 0.85f);
            float scale = 0.94f + 0.06f * emerge;
            int[] rest = face[e][0];
            int to = faceTo[g];
            if (!has(e, to)) to = 0;
            float tc = CHANGE[g];
            float swap = to == 0 ? 0f : smooth(tc - 0.12f, tc + 0.12f, t);
            int[] a, b;
            float mix;
            if (emerge < 1f) {
                a = veil[e];
                b = rest;
                mix = emerge;
            } else {
                a = rest;
                b = face[e][to];
                mix = swap;
            }
            drawGod(f, e, a, b, mix, cx, cy, scale, alpha);
            // the haze passes over the face while it changes
            if (to != 0 && t > tc - 0.5f && t < tc + 0.5f) {
                float u = (t - (tc - 0.5f)) / 1f;
                float wa = (float) Math.sin(u * Math.PI);
                float wx = cx + (gfx[e] - gw[e] * 0.5f) * scale + (u - 0.5f) * gw[e] * 0.7f * -SIDE[g];
                float wy = cy + (gfy[e] - gh[e] * 0.5f) * scale + 2f;
                drawWisp(f, e, wx, wy, Math.min(1f, wa * 1.15f));
            }
        }
    }

    /**
     * One god at (cx, cy): picture a blended toward picture b by mix, at a scale and an
     * overall opacity. At full size it is a plain copy; while it grows, bilinear.
     */
    private void drawGod(Px f, int g, int[] a, int[] b, float mix, float cx, float cy, float scale, float alpha) {
        int ww = gw[g], hh = gh[g];
        float hw = ww * scale * 0.5f, hhh = hh * scale * 0.5f;
        int x0 = (int) Math.floor(cx - hw), x1 = (int) Math.ceil(cx + hw);
        int y0 = (int) Math.floor(cy - hhh), y1 = (int) Math.ceil(cy + hhh);
        if (x1 <= 0 || y1 <= 0 || x0 >= f.w || y0 >= f.h) return;
        int ma = Math.round(clamp01(mix) * 256f), al = Math.round(clamp01(alpha) * 256f);
        boolean plain = scale >= 0.999f;
        float inv = 1f / scale;
        int ox = Math.round(cx - ww * 0.5f), oy = Math.round(cy - hh * 0.5f);
        for (int y = Math.max(0, y0); y < Math.min(f.h, y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(f.w, x1); x++) {
                int c;
                if (plain) {
                    int sx = x - ox, sy = y - oy;
                    if (sx < 0 || sy < 0 || sx >= ww || sy >= hh) continue;
                    int k = sy * ww + sx;
                    c = ma == 0 ? a[k] : ma == 256 ? b[k] : lerp(a[k], b[k], ma);
                } else {
                    float sx = (x + 0.5f - cx) * inv + ww * 0.5f - 0.5f, sy = (y + 0.5f - cy) * inv + hh * 0.5f - 0.5f;
                    if (sx < -1f || sy < -1f || sx >= ww || sy >= hh) continue;
                    c = sample(a, ww, hh, sx, sy);
                    if (ma > 0) c = lerp(c, sample(b, ww, hh, sx, sy), ma);
                }
                if (c == 0) continue;
                if (al < 256) c = lerp(0, c, al);
                if (winter) c = cold(c);
                int k = y * f.w + x;
                f.a[k] = over(c, f.a[k]);
            }
    }

    private void drawWisp(Px f, int g, float cx, float cy, float alpha) {
        int[] img = wisp[g];
        int ww = wispW[g], hh = wispH[g];
        int ox = Math.round(cx - ww * 0.5f), oy = Math.round(cy - hh * 0.5f);
        int al = Math.round(clamp01(alpha) * 256f);
        for (int y = 0; y < hh; y++) {
            int py = oy + y;
            if (py < 0 || py >= f.h) continue;
            for (int x = 0; x < ww; x++) {
                int px = ox + x;
                if (px < 0 || px >= f.w) continue;
                int c = img[y * ww + x];
                if (c == 0) continue;
                int k = py * f.w + px;
                c = lerp(0, c, al);
                if (winter) c = cold(c);
                f.a[k] = over(c, f.a[k]);
            }
        }
    }

    /**
     * Frost over a premultiplied color: its light kept, its warmth gone, toward slate in the
     * shadows and the white of snow in the lights, a trace of the old color left.
     */
    static int cold(int c) {
        int a = c >>> 24;
        if (a == 0) return 0;
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        float l = (r * 0.3f + g * 0.59f + b * 0.11f) / a;
        if (l > 1f) l = 1f;
        l = l * l * (3f - 2f * l);
        float dr = 0x4C + (0xEE - 0x4C) * l, dg = 0x58 + (0xF3 - 0x58) * l, db = 0x68 + (0xF8 - 0x68) * l;
        int nr = Math.min(a, (int) (r * 0.28f + dr * a / 255f * 0.72f));
        int ng = Math.min(a, (int) (g * 0.28f + dg * a / 255f * 0.72f));
        int nb = Math.min(a, (int) (b * 0.28f + db * a / 255f * 0.72f));
        return (a << 24) | (nr << 16) | (ng << 8) | nb;
    }

    /** Premultiplied a toward b, t out of 256. */
    private static int lerp(int a, int b, int t) {
        int s = 256 - t;
        int aa = ((a >>> 24) * s + (b >>> 24) * t) >> 8;
        int rr = (((a >> 16) & 0xFF) * s + ((b >> 16) & 0xFF) * t) >> 8;
        int gg = (((a >> 8) & 0xFF) * s + ((b >> 8) & 0xFF) * t) >> 8;
        int bb = ((a & 0xFF) * s + (b & 0xFF) * t) >> 8;
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /** A premultiplied color over an opaque one. */
    private static int over(int c, int d) {
        int k = 255 - (c >>> 24);
        int rr = ((c >> 16) & 0xFF) + ((((d >> 16) & 0xFF) * k) / 255);
        int gg = ((c >> 8) & 0xFF) + ((((d >> 8) & 0xFF) * k) / 255);
        int bb = (c & 0xFF) + (((d & 0xFF) * k) / 255);
        return 0xFF000000 | (Math.min(255, rr) << 16) | (Math.min(255, gg) << 8) | Math.min(255, bb);
    }

    private static int texel(int[] img, int ww, int hh, int x, int y) {
        if (x < 0 || y < 0 || x >= ww || y >= hh) return 0;
        return img[y * ww + x];
    }

    private static int sample(int[] img, int ww, int hh, float sx, float sy) {
        int x = (int) Math.floor(sx), y = (int) Math.floor(sy);
        int tx = Math.round((sx - x) * 256f), ty = Math.round((sy - y) * 256f);
        int top = lerp(texel(img, ww, hh, x, y), texel(img, ww, hh, x + 1, y), tx);
        int bot = lerp(texel(img, ww, hh, x, y + 1), texel(img, ww, hh, x + 1, y + 1), tx);
        return lerp(top, bot, ty);
    }

    // ---- lightning ----
    // Every strike is made fresh: a channel from somewhere behind the thunderer's cloud to
    // somewhere below, or across to another cloud, jagged by repeated midpoint displacement,
    // with a few forks that thin out toward their tips. A strike flashes in one to four
    // pulses down the same channel, a few hundredths of a second apart, and lights the whole
    // sky for an instant; strikes come two to four to an ovation, at uneven intervals, some
    // near and thick, some far and thin.

    private static final int STRIKES = 4, PULSES = 4, SEGS = 320;
    private final float[] strikeAt = new float[STRIKES], strikeNear = new float[STRIKES];
    private final float[][] pulseAt = new float[STRIKES][PULSES], pulseLen = new float[STRIKES][PULSES];
    private final int[] pulses = new int[STRIKES];
    private int strikes, made = -1;
    // the channel of the strike being shown: segments, and how bright each one is (1 the main stroke)
    private final float[] sx0 = new float[SEGS], sy0 = new float[SEGS], sx1 = new float[SEGS], sy1 = new float[SEGS],
            sLit = new float[SEGS];
    private int segs;

    private void planStrikes() {
        strikes = 2 + rnd.nextInt(3);
        float at = CHANGE[THUNDERER] + 0.15f;
        for (int s = 0; s < strikes; s++) {
            strikeAt[s] = at;
            strikeNear[s] = 0.35f + rnd.nextFloat() * 0.65f;
            pulses[s] = 1 + rnd.nextInt(PULSES);
            float p = 0f;
            for (int k = 0; k < pulses[s]; k++) {
                pulseAt[s][k] = p;
                pulseLen[s][k] = 0.04f + rnd.nextFloat() * 0.06f;
                p += pulseLen[s][k] + 0.03f + rnd.nextFloat() * 0.09f;
            }
            at += 0.45f + rnd.nextFloat() * 0.8f;
        }
        made = -1;
    }

    private void lightning(Px f, float t, float shift, int th) {
        for (int s = 0; s < strikes; s++) {
            float since = t - strikeAt[s];
            if (since < 0f || since > 0.6f) continue;
            if (made != s) {
                made = s;
                bolts++;
                boltNear = strikeNear[s];
                makeChannel(th, strikeNear[s]);
            }
            for (int k = 0; k < pulses[s]; k++) {
                float u = since - pulseAt[s][k];
                if (u < 0f || u > pulseLen[s][k]) continue;
                // later pulses are weaker, and light the forks less
                float power = (1f - k * 0.2f) * (1f - u / pulseLen[s][k] * 0.4f);
                skyFlash(f, power * strikeNear[s] * 0.16f);
                drawChannel(f, shift, power, k == 0, strikeNear[s]);
            }
        }
    }

    /** A new channel: the main stroke, then its forks. */
    private void makeChannel(int th, float near) {
        segs = 0;
        float x0 = AT[THUNDERER][0] * w + (rnd.nextFloat() - 0.5f) * gw[th] * 0.8f;
        float y0 = AT[THUNDERER][1] * sh + gh[th] * (0.1f + rnd.nextFloat() * 0.2f);
        float x1, y1;
        if (rnd.nextInt(5) == 0) {
            // across the sky to another cloud
            int g = 1 + rnd.nextInt(GODS - 1);
            x1 = AT[g][0] * w + (rnd.nextFloat() - 0.5f) * 30f;
            y1 = AT[g][1] * sh;
        } else {
            x1 = w * (0.05f + rnd.nextFloat() * 0.9f);
            y1 = sh * (1.15f + rnd.nextFloat() * 0.2f);
        }
        float rough = 0.16f + rnd.nextFloat() * 0.12f;
        int start = segs;
        channel(x0, y0, x1, y1, 7, rough, 1f);
        int main = segs;
        // the forks leave the main stroke at a slant, a third to two thirds of the way down
        int forks = 1 + rnd.nextInt(near > 0.7f ? 5 : 3);
        for (int k = 0; k < forks && main > start + 4; k++) {
            int at = start + (int) ((0.15f + rnd.nextFloat() * 0.6f) * (main - start));
            float bx = sx0[at], by = sy0[at];
            float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
            float ang = (float) Math.atan2(dy, dx) + (rnd.nextBoolean() ? 1f : -1f) * (0.4f + rnd.nextFloat() * 0.6f);
            float bl = len * (0.15f + rnd.nextFloat() * 0.25f);
            channel(bx, by, bx + (float) Math.cos(ang) * bl, by + (float) Math.sin(ang) * bl, 5, rough * 1.2f,
                    0.55f - k * 0.06f);
        }
    }

    /** A jagged line from one point to another: the midpoint pushed aside, again and again. */
    private void channel(float x0, float y0, float x1, float y1, int depth, float rough, float lit) {
        if (segs >= SEGS) return;
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (depth == 0 || len < 3f) {
            sx0[segs] = x0;
            sy0[segs] = y0;
            sx1[segs] = x1;
            sy1[segs] = y1;
            sLit[segs] = lit;
            segs++;
            return;
        }
        float push = (rnd.nextFloat() - 0.5f) * 2f * rough * len;
        float mx = (x0 + x1) * 0.5f - dy / len * push, my = (y0 + y1) * 0.5f + dx / len * push;
        // a fork fades toward its tip
        float tip = lit < 1f ? lit * 0.85f : lit;
        channel(x0, y0, mx, my, depth - 1, rough, lit);
        channel(mx, my, x1, y1, depth - 1, rough, tip);
    }

    /** The whole sky lit for an instant, cold white. */
    private void skyFlash(Px f, float k) {
        if (k <= 0.01f) return;
        for (int i = 0; i < f.a.length; i++) f.a[i] = Backdrop.mix(f.a[i], 0xFFEEF0FF, k);
    }

    private void drawChannel(Px f, float shift, float power, boolean first, float near) {
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < segs; i++) {
                float lit = sLit[i];
                // the forks show at the first pulse, and only faintly after it
                if (lit < 1f && !first) lit *= 0.35f;
                lit *= power;
                if (lit < 0.08f) continue;
                float x0 = sx0[i], y0 = sy0[i] + shift, x1 = sx1[i], y1 = sy1[i] + shift;
                int n = (int) (Math.abs(x1 - x0) + Math.abs(y1 - y0)) + 1;
                for (int k = 0; k <= n; k++) {
                    float q = k / (float) n;
                    float x = x0 + (x1 - x0) * q, y = y0 + (y1 - y0) * q;
                    if (pass == 0) {
                        if (sLit[i] >= 1f || (k & 1) == 0) glow(f, x, y, lit * (0.6f + near * 0.4f));
                    } else {
                        int px = Math.round(x), py = Math.round(y);
                        int core = Backdrop.mix(0xFFB8C8FF, 0xFFFFFFF6, Math.min(1f, lit));
                        f.set(px, py, core);
                        // the main stroke of a near strike is two pixels thick
                        if (sLit[i] >= 1f && near > 0.6f) f.set(px + 1, py, Backdrop.mix(core, 0xFFFFF2C0, 0.4f));
                    }
                }
            }
    }

    private void glow(Px f, float x, float y, float lit) {
        for (int dy = -3; dy <= 3; dy++)
            for (int dx = -3; dx <= 3; dx++) {
                int px = Math.round(x) + dx, py = Math.round(y) + dy;
                if (px < 0 || py < 0 || px >= f.w || py >= f.h) continue;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 3.2f) continue;
                int k = py * f.w + px;
                f.a[k] = Backdrop.mix(f.a[k], 0xFFE8E6FF, 0.24f * lit * (1f - d / 3.3f));
            }
    }
}
