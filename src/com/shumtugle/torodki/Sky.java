package com.shumtugle.torodki;

/**
 * Between buildings the view tips up into the evening sky. Rays of the low sun fan up
 * from the left, far clouds hang behind, and the old gods of the place gather, each
 * in a cloud of his own: they sail in from the sides, nearer ones faster, and their faces
 * come out of the vapour as they settle. Then they judge the fall. A wisp of haze passes
 * over each face and, as it clears, the face has changed: laughter, and lightning from
 * the thunderer, for a building felled at one throw; smiles for a few throws; a sceptic
 * look for many; anger, sleep and yawns when the stars run out. The next building then
 * falls out of the sky as the view comes back down.
 *
 * The sky, its rays and the far clouds are painted once into a strip above the frame;
 * the tilt is a scroll that slides the street down and the strip in from the top. The
 * gods are painted pictures, three expressions each, read from an asset and drawn every
 * frame over the strip.
 */
final class Sky {
    static final int OVATION = 0, CHEER = 1, POLITE = 2, YAWN = 3;

    static final int THUNDERER = 0, GODDESS = 1, THINKER = 2, ELDER = 3, IDLER = 4;
    private static final int GODS = 5;
    private static final int MAX_PICTURES = 24;

    private int w, sh;
    private int[] strip;
    private int verdict = POLITE;
    float time;
    int bolts;                    // how many strokes of lightning so far: the world thunders for each
    private int boltIdx = -1;
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
            {1, 1, 1, 1, 1},          // ovation: all laugh
            {0, 1, 1, 1, 1},          // cheer: the thunderer stays stern
            {0, 0, 2, 0, 0},          // polite: only the thinker raises a brow
            {2, 2, 2, 2, 2},          // yawn: anger, sleep, scepticism, yawns
    };

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
        farClouds(far);
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
    private void farClouds(byte[] b) {
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
                for (int k = 0; k < img.length; k++, p += 4)
                    img[k] = ((b[p + 3] & 0xFF) << 24) | ((b[p] & 0xFF) << 16) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
                frames[e] = img;
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
        this.verdict = verdict;
        // each god comes in one of his pictures
        for (int g = 0; g < GODS; g++) {
            int n = 0;
            for (int e = 0; e < pictures; e++) if (role[e] == g) n++;
            if (n == 0) continue;
            int k = rnd.nextInt(n);
            for (int e = 0; e < pictures; e++)
                if (role[e] == g && k-- == 0) pick[g] = e;
        }
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

    /** The gods and the lightning, drawn where the strip now is on screen (shift = off - sh). */
    void drawClouds(Px f, float shift) {
        float t = time;
        // lightning on a great fall: three strokes from behind the thunderer once he laughs,
        // drawn first so the clouds of the gods stand in front of it
        int th = pick[THUNDERER];
        if (verdict == OVATION && th >= 0) {
            float bt = t - CHANGE[THUNDERER] - 0.15f;
            if (bt > 0f) {
                int idx = (int) (bt / 0.85f);
                float since = bt - idx * 0.85f;
                if (idx < 3) {
                    if (idx != boltIdx) {
                        boltIdx = idx;
                        bolts++;
                        makeBolt(AT[THUNDERER][0] * w - gw[th] * 0.38f,
                                AT[THUNDERER][1] * sh + gh[th] * 0.2f);
                    }
                    if (since < 0.2f || (since > 0.3f && since < 0.4f)) drawBolt(f, shift * NEAR[THUNDERER]);
                }
            }
        }
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
            int to = FACE_FOR[verdict][g];
            if (to >= face[e].length) to = 0;
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
                f.a[k] = over(lerp(0, c, al), f.a[k]);
            }
        }
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
