package com.shumtugle.torodki;

import java.util.Random;

/**
 * Between buildings the view tips up into the evening sky. Above the street stand
 * towering clouds lit gold from below, and in them the old gods of the place, carved
 * of cloud like marble: a bearded thunderer, a goddess with laurel in her hair, a
 * thinker with his chin on his fist, an elder, and one who is easily bored. They watch
 * the street and judge each building's fall: laughter and lightning for one throw,
 * smiles for a few, a polite stare for many, a yawn when the stars run out. Then two
 * of them pick up blocks and throw them down: the next building is their work.
 *
 * Everything up there is sculpted from spheres, each lit like a lump of cloud: gold
 * where the low sun reaches, lavender in the folds. The tilt is a scroll: the street
 * slides down, and a strip of sky painted once above the frame slides in from the top.
 */
final class Sky {
    static final int OVATION = 0, CHEER = 1, POLITE = 2, YAWN = 3;

    private int w, sh;
    private int[] strip;
    private float[] zb;           // depth for the gods, one screen
    private int zbH;

    private int verdict = POLITE;
    float time;
    private final Random rnd = new Random(5);

    // light on the clouds: from low on the left, toward the viewer (screen y points down)
    private static final float LX, LY, LZ;

    static {
        float x = -0.55f, y = 0.45f, z = 0.7f, l = (float) Math.sqrt(x * x + y * y + z * z);
        LX = x / l;
        LY = y / l;
        LZ = z / l;
    }

    // ---- the strip: sky and the cloud banks ----

    void build(Raster r) {
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
        // towering cumulus: banks of lumps, thinning toward the horizon
        float[] depth = new float[w * sh];
        java.util.Arrays.fill(depth, -1e9f);
        Random cr = new Random(31);
        for (int bank = 0; bank < 6; bank++) {
            float bx = cr.nextFloat() * w, by = sh * (0.08f + cr.nextFloat() * 0.7f);
            float size = w * (0.16f + cr.nextFloat() * 0.16f);
            int lumps = 150 + cr.nextInt(80);
            for (int i = 0; i < lumps; i++) {
                float a = (float) (cr.nextFloat() * Math.PI * 2), rr = (float) Math.sqrt(cr.nextFloat());
                float x = bx + (float) Math.cos(a) * rr * size * 1.4f;
                float y = by + (float) Math.sin(a) * rr * size * 0.75f;
                if (y > sh - 70) continue;
                float rad = size * (0.08f + cr.nextFloat() * 0.16f) * (1f - rr * 0.4f);
                splat(strip, depth, w, sh, x, y, 200f - rr * size, rad, 1f, 0, 200f - size, 0.9f / size,
                        bx, by, size * 1.5f, size * 0.85f, 0.4f);
            }
        }
    }

    // ---- sphere sculpture ----

    /**
     * One lump: a sphere at screen (x, y) with depth z, radius r. Shade 1 is plain cloud,
     * lower is a fold in shadow; tint 0 is cloud, otherwise a solid color lit the same way.
     */
    private static void splat(int[] out, float[] zbuf, int w, int h, float x, float y, float z, float r, float shade,
                              int tint, float aoBase, float aoScale) {
        splat(out, zbuf, w, h, x, y, z, r, shade, tint, aoBase, aoScale, x, y, 1f, 1f, 1f);
    }

    /**
     * The same, with the light also following the whole mass the lump belongs to: an
     * ellipse centered at (gcx, gcy) with radii (grx, gry). Without it every lump is lit
     * alike and the cloud reads as a heap of balls; with it the mass turns gold on one
     * side and lavender on the other. localW is how much the lump's own roundness counts.
     */
    private static void splat(int[] out, float[] zbuf, int w, int h, float x, float y, float z, float r, float shade,
                              int tint, float aoBase, float aoScale, float gcx, float gcy, float grx, float gry, float localW) {
        int x0 = Math.max(0, (int) (x - r)), x1 = Math.min(w - 1, (int) (x + r) + 1);
        int y0 = Math.max(0, (int) (y - r)), y1 = Math.min(h - 1, (int) (y + r) + 1);
        float rr = r * r;
        for (int py = y0; py <= y1; py++) {
            float dy = py + 0.5f - y;
            for (int px = x0; px <= x1; px++) {
                float dx = px + 0.5f - x;
                float d2 = dx * dx + dy * dy;
                if (d2 > rr) continue;
                float dz = (float) Math.sqrt(rr - d2);
                int k = py * w + px;
                float zz = z + dz;
                if (zz <= zbuf[k]) continue;
                zbuf[k] = zz;
                float inv = 1f / r;
                // what lies deeper in the mass sits in its own shade
                float ao = Math.max(0.42f, Math.min(1f, 0.42f + (zz - aoBase) * aoScale));
                // a little fluff on every lump
                float fluff = (((px * 73 + py * 151) * 2654435761L >>> 24) & 0xFF) / 255f - 0.5f;
                float nx = dx * inv, ny = dy * inv, nz = dz * inv;
                if (localW < 1f) {
                    float ux = (px + 0.5f - gcx) / grx, uy = (py + 0.5f - gcy) / gry;
                    float uu = ux * ux + uy * uy;
                    if (uu > 1f) {
                        float q = (float) Math.sqrt(uu);
                        ux /= q;
                        uy /= q;
                        uu = 1f;
                    }
                    float uz = (float) Math.sqrt(1f - uu);
                    nx = nx * localW + ux * (1f - localW);
                    ny = ny * localW + uy * (1f - localW);
                    nz = nz * localW + uz * (1f - localW);
                    float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    nx /= nl;
                    ny /= nl;
                    nz /= nl;
                }
                out[k] = cloudColor(nx, ny, nz, shade * ao * (1f + fluff * 0.12f), tint);
            }
        }
    }

    /** Cloud light: gold where the sun reaches, warm in the middle, lavender in the folds. */
    static int cloudColor(float nx, float ny, float nz, float shade, int tint) {
        float d0 = nx * LX + ny * LY + nz * LZ;
        float diff = Math.max(0f, Math.min(1f, (d0 - 0.05f) * 1.35f)) * shade;
        float rim = (1f - nz) * (1f - nz) * Math.max(0f, -nx * 0.7f + ny * 0.5f) * Math.min(1f, shade * 1.3f);
        int c;
        if (tint == 0) {
            c = diff < 0.45f ? Backdrop.mix(0xFF6F6C9E, 0xFFD9B4BC, diff / 0.45f)
                    : Backdrop.mix(0xFFD9B4BC, 0xFFFFE9BC, (diff - 0.45f) / 0.55f);
            c = Backdrop.mix(c, 0xFFFFCB78, Math.min(1f, rim * 1.1f));
        } else {
            c = Backdrop.scale(tint, 0.45f + diff * 0.8f);
        }
        return c;
    }

    // ---- the gods ----

    private static final int ZEUS = 0, GODDESS = 1, THINKER = 2, ELDER = 3, BORED = 4;
    private static final int GODS = 5;
    private final float[] gx = new float[GODS], gy = new float[GODS], gR = new float[GODS];
    private final int[] gType = {ZEUS, GODDESS, THINKER, ELDER, BORED};

    // the sculpture of one god, rebuilt every frame: sphere centers, radii, shade, tint
    private static final int MAXS = 520;
    private final float[] sx = new float[MAXS], sy = new float[MAXS], sz = new float[MAXS], sr = new float[MAXS], ss = new float[MAXS];
    private final int[] st = new int[MAXS];
    private final float[] sl = new float[MAXS];
    private float localNow = 0.45f;
    private int sn;

    // their mood
    private float smile, mouth, eyesShut, frown;

    // blocks thrown down to build the next level
    private static final int BLOCKS = 40;
    private final float[] kx = new float[BLOCKS], ky = new float[BLOCKS], kvx = new float[BLOCKS], kvy = new float[BLOCKS];
    private final float[] ka = new float[BLOCKS], ks = new float[BLOCKS];
    private final int[] kc = new int[BLOCKS];
    private int kn;
    private float nextThrow;
    static final int[] BLOCK_COLORS = {0xFFE3B47C, 0xFFD39E62, 0xFFC4864F, 0xFF8FCBB2, 0xFFEE9272, 0xFFE0D3B8};

    // lightning
    int bolts;                    // how many have struck: the world thunders for each
    private int boltIdx = -1;
    private final float[] boltX = new float[24], boltY = new float[24];
    private int boltN;

    /** Seats the gods for a new verdict. */
    void begin(int verdict) {
        this.verdict = verdict;
        frames = 0;

        time = 0f;
        kn = 0;
        nextThrow = 0.7f;
        boltIdx = -1;
        // places in the strip: a crowd of heads among the clouds
        float[][] at = {{0.64f, 0.17f, 0.13f}, {0.27f, 0.31f, 0.11f}, {0.76f, 0.47f, 0.105f}, {0.25f, 0.61f, 0.1f}, {0.7f, 0.76f, 0.095f}};
        for (int g = 0; g < GODS; g++) {
            gx[g] = at[g][0] * w;
            gy[g] = at[g][1] * sh;
            gR[g] = at[g][2] * w;
        }
    }

    void update(float dt) {
        time += dt;
        // throws: the elder and the thunderer send blocks down to the street
        nextThrow -= dt;
        if (nextThrow <= 0f && time > 0.5f && kn < BLOCKS) {
            int g = rnd.nextBoolean() ? ELDER : ZEUS;
            float hx = gx[g] + gR[g] * (g == ELDER ? 1.25f : -1.3f), hy = gy[g] - gR[g] * 0.9f;
            kx[kn] = hx;
            ky[kn] = hy;
            kvx[kn] = (w * 0.5f - hx) * (0.4f + rnd.nextFloat() * 0.4f);
            kvy[kn] = -40f - rnd.nextFloat() * 40f;
            ka[kn] = rnd.nextFloat() * 6f;
            ks[kn] = gR[g] * (0.12f + rnd.nextFloat() * 0.1f);
            kc[kn] = BLOCK_COLORS[rnd.nextInt(BLOCK_COLORS.length)];
            kn++;
            nextThrow = 0.18f + rnd.nextFloat() * 0.2f;
        }
        for (int i = 0; i < kn; i++) {
            kvy[i] += 520f * dt;
            kx[i] += kvx[i] * dt;
            ky[i] += kvy[i] * dt;
            ka[i] += dt * 7f;
        }
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

    private int[] layer;          // the gods, in strip rows; 0 where there is nothing
    private int frames;

    /** Sculpts all the gods into their layer. */
    private void renderGods() {
        if (layer == null) {
            layer = new int[w * sh];
            zb = new float[w * sh];
        }
        java.util.Arrays.fill(layer, 0);
        java.util.Arrays.fill(zb, -1e9f);
        float t = time;
        for (int g = 0; g < GODS; g++) {
            mood(gType[g], t);
            sn = 0;
            sculpt(gType[g], t);
            float cx = gx[g], cy = gy[g], R = gR[g];
            float tilt = gType[g] == GODDESS ? -0.22f : gType[g] == THINKER ? 0.12f : 0f;
            float ct = (float) Math.cos(tilt), stl = (float) Math.sin(tilt);
            for (int i = 0; i < sn; i++) {
                float x = sx[i] * ct - sy[i] * stl, y = sx[i] * stl + sy[i] * ct;
                splat(layer, zb, w, sh, cx + x * R, cy + y * R, 300f + sz[i] * R, sr[i] * R, ss[i], st[i],
                        300f - 0.4f * R, 0.6f / R, cx, cy + 0.45f * R, 1.9f * R, 2.0f * R, sl[i]);
            }
        }
    }

    /** The gods and what they do, drawn where the strip now is on screen (shift = off - sh). */
    void drawClouds(Px f, float shift) {
        // sculpting is the dear part: every third frame is enough for their slow moves
        if (frames++ % 3 == 0 || layer == null) renderGods();
        int s0 = Math.round(shift);
        for (int y = 0; y < f.h; y++) {
            int j = y - s0;
            if (j < 0 || j >= sh) continue;
            int lo = j * w, so = y * w;
            for (int x = 0; x < w; x++) {
                int c = layer[lo + x];
                if (c != 0) f.a[so + x] = c;
            }
        }
        float t = time;
        // blocks on their way down
        for (int i = 0; i < kn; i++) block(f, kx[i], ky[i] + shift, ks[i], ka[i], kc[i]);
        // lightning on a great fall: three strikes from the thunderer's hand
        if (verdict == OVATION) {
            float bt = t - 0.35f;
            if (bt > 0f) {
                int idx = (int) (bt / 0.85f);
                float since = bt - idx * 0.85f;
                if (idx < 3) {
                    if (idx != boltIdx) {
                        boltIdx = idx;
                        bolts++;
                        makeBolt(gx[ZEUS] - gR[ZEUS] * 1.3f, gy[ZEUS] - gR[ZEUS] * 0.9f);
                    }
                    if (since < 0.2f || (since > 0.3f && since < 0.4f)) drawBolt(f, shift);
                }
            }
        }
    }

    /** How each god feels about the verdict, and how that moves as time goes. */
    private void mood(int type, float t) {
        smile = 0f;
        mouth = 0f;
        eyesShut = 0f;
        frown = 0f;
        switch (verdict) {
            case OVATION:
                smile = 1f;
                mouth = 0.35f + 0.25f * Math.abs((float) Math.sin(t * 9f + type));
                eyesShut = 0.7f;
                break;
            case CHEER:
                smile = 0.75f;
                mouth = 0.15f;
                eyesShut = 0.4f;
                break;
            case POLITE:
                smile = 0.15f;
                break;
            default:
                if (type == BORED) {
                    mouth = Math.min(1f, Math.max(0f, (t - 0.3f) / 0.5f));
                    eyesShut = 1f;
                } else if (type == ZEUS) {
                    frown = 1f;
                    smile = -0.5f;
                } else {
                    eyesShut = 0.8f;
                    smile = -0.2f;
                }
                break;
        }
        if (type == GODDESS && verdict != YAWN) smile = Math.max(smile, 0.45f);
    }

    private void s(float x, float y, float z, float r, float shade, int tint) {
        if (sn >= MAXS) return;
        sx[sn] = x;
        sy[sn] = y;
        sz[sn] = z;
        sr[sn] = r;
        ss[sn] = shade;
        st[sn] = tint;
        sl[sn] = localNow;
        sn++;
    }

    private static float hash(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    /**
     * Builds a god in its own units (head radius 1; x right, y down, z toward us):
     * a cloud body under it, the head, the features, hair and beard in curls, and the
     * hand that each one uses.
     */
    private void sculpt(int type, float t) {
        // shoulders: the cloud bank the god rises from
        localNow = 0.35f;
        for (int i = 0; i < 26; i++) {
            float a = hash(type, i) * 3.2f - 1.6f;
            s((float) Math.sin(a) * 1.5f, 1.45f + (float) Math.cos(a * 1.3f) * 0.3f + hash(i, type) * 0.45f, -0.5f - hash(i, 3) * 0.4f,
                    0.38f + hash(type * 7, i) * 0.3f, 1f, 0);
        }
        boolean beard = type != GODDESS;
        localNow = 0.5f;
        // the head
        s(0f, -0.2f, 0f, 0.95f, 1f, 0);
        s(0f, 0.3f, 0.12f, 0.78f, 1f, 0);
        localNow = 0.85f;
        // brow: heavy, and lowered in a frown
        float by = -0.2f + frown * 0.06f;
        for (int i = -2; i <= 2; i++) s(i * 0.2f, by + Math.abs(i) * (0.02f - frown * 0.05f), 0.72f, 0.17f, 1f, 0);
        // eyes in their hollows
        for (int side = -1; side <= 1; side += 2) {
            float ex = side * 0.3f;
            if (eyesShut > 0.5f) s(ex, -0.02f, 0.69f, 0.13f, 0.85f, 0);   // a closed lid
            else {
                s(ex, -0.02f, 0.62f, 0.14f, 0.3f, 0);                    // the hollow
                s(ex, -0.02f + (smile > 0.5f ? 0.03f : 0f), 0.68f, 0.06f, 0.55f, 0);
            }
            // cheeks lift when smiling
            s(side * 0.42f, 0.28f - smile * 0.06f, 0.45f, 0.36f, 1f, 0);
        }
        // nose
        for (int i = 0; i < 5; i++) s(0f, -0.06f + i * 0.085f, 0.8f + i * 0.04f, 0.1f + i * 0.012f, 1f, 0);
        s(-0.09f, 0.33f, 0.84f, 0.08f, 0.9f, 0);
        s(0.09f, 0.33f, 0.84f, 0.08f, 0.9f, 0);
        // mouth: corners rise with a smile; it opens to laugh or to yawn
        float my = 0.55f;
        if (mouth > 0.05f) s(0f, my + mouth * 0.12f, 0.66f, 0.12f + mouth * 0.2f, 0.25f, 0);
        for (int i = -2; i <= 2; i++) {
            float lx = i * 0.07f, ly = my - smile * 0.05f * (i * i) / 4f - (mouth * 0.04f);
            s(lx, ly, 0.8f, 0.055f, 0.95f, 0);
            s(lx, my + 0.08f + mouth * 0.28f + smile * 0.02f * (i * i) / 4f, 0.78f, 0.05f, 0.95f, 0);
        }
        localNow = 0.6f;
        if (beard) {
            // moustache
            for (int i = -3; i <= 3; i++)
                s(i * 0.1f, 0.47f + Math.abs(i) * 0.03f + (Math.abs(i) == 3 ? 0.06f : 0f), 0.84f, 0.1f, 1f, 0);
            // beard: curls from cheek to chest, longer on the elder and the thunderer
            float len = type == ELDER || type == ZEUS ? 1.25f : type == THINKER ? 0.75f : 0.9f;
            int n = 0;
            for (float y = 0.45f; y < 0.45f + len; y += 0.11f)
                for (float x = -0.8f; x <= 0.8f; x += 0.12f) {
                    float half = 0.82f - (y - 0.45f) / len * 0.55f;
                    if (Math.abs(x) > half) continue;
                    if (Math.abs(x) < 0.16f && y < 0.72f) continue;              // the mouth shows
                    float j1 = hash(n, type * 3) - 0.5f, j2 = hash(type * 5, n) - 0.5f;
                    n++;
                    s(x + j1 * 0.06f, y + j2 * 0.05f, 0.62f - Math.abs(x) * 0.35f - (y - 0.45f) * 0.12f,
                            0.12f + hash(n, 9) * 0.07f, 1f, 0);
                }
        } else {
            s(0f, 0.78f, 0.5f, 0.28f, 1f, 0);                                      // a soft chin
        }
        // hair: curls round the crown and down the sides
        int curls = type == GODDESS ? 34 : 28;
        for (int layer = 0; layer < 2; layer++)
            for (int i = 0; i < curls; i++) {
                float a = (float) (Math.PI * (1.0f + 1.0f * (i + layer * 0.5f) / (curls - 1f)));
                float rr = (layer == 0 ? 0.95f : 0.8f) + hash(i, type + 11 + layer) * 0.1f;
                s((float) Math.cos(a) * rr, -0.15f + (float) Math.sin(a) * rr * 0.98f, layer == 0 ? 0.1f : 0.35f,
                        (layer == 0 ? 0.26f : 0.22f) + hash(type, i + 50 + layer) * 0.08f, 1f, 0);
            }
        if (type == GODDESS) {
            // long waves to the shoulders, and a laurel wreath
            for (int side = -1; side <= 1; side += 2)
                for (int i = 0; i < 6; i++) s(side * (0.92f + i * 0.04f), 0.1f + i * 0.2f, 0f, 0.22f, 1f, 0);
            for (int i = 0; i < 9; i++) {
                float a = (float) (Math.PI * (1.12f + 0.76f * i / 8f));
                s((float) Math.cos(a) * 0.98f, -0.25f + (float) Math.sin(a) * 0.95f, 0.4f, 0.09f, 0.9f, 0);
                s((float) Math.cos(a) * 1.04f, -0.2f + (float) Math.sin(a) * 1.0f, 0.42f, 0.07f, 1f, 0);
            }
        }
        localNow = 0.6f;
        // hands
        if (type == THINKER || type == GODDESS) {
            // a fist under the chin (or a palm to the cheek), the forearm down into the cloud
            float hx = type == THINKER ? 0.15f : 0.62f, hy = type == THINKER ? 1.05f : 0.55f;
            for (int i = 0; i < 4; i++) s(hx - 0.18f + i * 0.12f, hy, 0.85f, 0.12f, 1f, 0);
            s(hx, hy + 0.15f, 0.8f, 0.2f, 1f, 0);
            for (int i = 1; i <= 14; i++) s(hx + i * 0.05f, hy + 0.15f + i * 0.085f, 0.7f - i * 0.02f, 0.21f, 1f, 0);
        } else if (type == ELDER || type == ZEUS) {
            // an arm raised to throw, swinging
            float side = type == ELDER ? 1f : -1f;
            float sw = (float) Math.sin(t * 6f + type) * 0.25f;
            float shx = side * 1.05f, shy = 1.15f;
            float hx = side * (1.45f + sw * 0.2f), hy = -0.15f + sw * 0.6f;
            for (int i = 0; i <= 14; i++) {
                float k = i / 14f;
                // a bent arm: out from the shoulder, then up to the hand
                float bend = (float) Math.sin(k * Math.PI) * 0.25f * side;
                s(shx + (hx - shx) * k + bend, shy + (hy - shy) * k, 0.5f + k * 0.25f, 0.32f - k * 0.1f, 1f, 0);
            }
            for (int i = 0; i < 4; i++) s(hx - side * 0.1f + i * 0.07f * side, hy - 0.12f, 0.75f, 0.09f, 1f, 0);
            s(hx, hy, 0.75f, 0.17f, 1f, 0);
            // the block in hand
            s(hx, hy - 0.3f, 0.95f, 0.17f, 1f, BLOCK_COLORS[(int) (t * 3f) % BLOCK_COLORS.length]);
        } else if (type == BORED && mouth > 0.3f) {
            // a hand over the yawn
            for (int i = 0; i < 4; i++) s(-0.15f + i * 0.1f, 0.62f, 0.95f, 0.1f, 1f, 0);
            s(0.05f, 0.75f, 0.9f, 0.17f, 1f, 0);
        }
    }

    // ---- blocks and bolts ----

    private void block(Px f, float x, float y, float size, float a, int color) {
        if (y > f.h + size || x < -size || x > w + size) return;
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        int r = (int) Math.ceil(size * 1.5f);
        int lit = Backdrop.scale(color, 1.15f), dark = Backdrop.scale(color, 0.7f);
        for (int py = -r; py <= r; py++)
            for (int px = -r; px <= r; px++) {
                float u = px * ca + py * sa, v = -px * sa + py * ca;
                if (Math.abs(u) > size || Math.abs(v) > size * 0.7f) continue;
                int c = u < -size * 0.6f ? lit : v > size * 0.4f ? dark : color;
                f.set(Math.round(x) + px, Math.round(y) + py, c);
            }
    }

    private void makeBolt(float x0, float y0) {
        boltN = 0;
        float x = x0, y = y0;
        boltX[boltN] = x;
        boltY[boltN++] = y;
        while (boltN < boltX.length && y < sh * 1.2f) {
            x += -7f + (rnd.nextFloat() - 0.5f) * 30f;
            y += 18f + rnd.nextFloat() * 22f;
            boltX[boltN] = x;
            boltY[boltN++] = y;
        }
    }

    private void drawBolt(Px f, float shift) {
        for (int i = 0; i < boltN - 1; i++) {
            float x0 = boltX[i], y0 = boltY[i] + shift, x1 = boltX[i + 1], y1 = boltY[i + 1] + shift;
            int n = (int) (Math.abs(x1 - x0) + Math.abs(y1 - y0)) + 1;
            for (int k = 0; k <= n; k++) {
                float q = k / (float) n;
                float x = x0 + (x1 - x0) * q, y = y0 + (y1 - y0) * q;
                glow(f, x, y);
            }
        }
        for (int i = 0; i < boltN - 1; i++) {
            float x0 = boltX[i], y0 = boltY[i] + shift, x1 = boltX[i + 1], y1 = boltY[i + 1] + shift;
            int n = (int) (Math.abs(x1 - x0) + Math.abs(y1 - y0)) + 1;
            for (int k = 0; k <= n; k++) {
                float q = k / (float) n;
                int x = Math.round(x0 + (x1 - x0) * q), y = Math.round(y0 + (y1 - y0) * q);
                f.set(x, y, 0xFFFFFFF4);
                f.set(x + 1, y, 0xFFFFF2C0);
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
