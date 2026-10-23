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
 * Everything up there is sculpted in relief. Forms are pressed into a height field
 * and merge softly where they meet, so a cloud is one billowing mass rather than a
 * heap of balls, and a face is one piece of stone. The light is read from the slope
 * of the finished surface: gold where the low sun reaches, lavender in the folds.
 * The tilt is a scroll: the street slides down, and a strip of sky painted once
 * above the frame slides in from the top.
 */
final class Sky {
    static final int OVATION = 0, CHEER = 1, POLITE = 2, YAWN = 3;

    private int w, sh;
    private int[] strip;

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

    // ---- relief: a height field that forms are pressed into, then lit ----

    private static final float NONE = -1e9f;
    private int rw, rh;                 // size of the relief in use
    private float[] rz;                 // height toward the viewer, in pixels; NONE where empty
    private float[] rao;                // how open to the light each point is, 0..1
    private float[] rkind;              // 0 stone, 1 cloud: cloud gets fluff, stone stays smooth
    private int[] rtint;                // 0 for cloud, otherwise a solid color lit the same way
    private float sc;                   // pixels per unit of the form being pressed
    private float ox, oy;               // where the unit origin falls in the relief
    private float rot, rotS, rotC;      // rotation of the form being pressed, radians

    private void relief(int width, int height) {
        int n = width * height;
        if (rz == null || rz.length < n) {
            rz = new float[n];
            rao = new float[n];
            rkind = new float[n];
            rtint = new int[n];
        }
        rw = width;
        rh = height;
        java.util.Arrays.fill(rz, 0, n, NONE);
        java.util.Arrays.fill(rao, 0, n, 1f);
        java.util.Arrays.fill(rkind, 0, n, 0f);
        java.util.Arrays.fill(rtint, 0, n, 0);
    }

    private void frame(float scale, float originX, float originY, float rotation) {
        sc = scale;
        ox = originX;
        oy = originY;
        rot = rotation;
        rotS = (float) Math.sin(rotation);
        rotC = (float) Math.cos(rotation);
    }

    /** Two heights joined without a crease: the larger wins, the seam within k is rounded. */
    private static float join(float a, float b, float k) {
        if (a == NONE) return b;
        float d = Math.abs(a - b);
        if (d >= k) return Math.max(a, b);
        float h = k - d;
        return Math.max(a, b) + h * h / (4f * k);
    }

    /**
     * Presses an ellipsoid into the relief: center (x, y) and base height z in form units,
     * half-axes rx, ry across and rz toward the viewer, seam rounded over k.
     */
    private void blob(float x, float y, float z, float rx, float ry, float rz_, float k, float ao, float kind, int tint) {
        float px = ox + (x * rotC - y * rotS) * sc, py = oy + (x * rotS + y * rotC) * sc;
        float arx = rx * sc, ary = ry * sc, arz = rz_ * sc, az = z * sc, ak = k * sc;
        int x0 = Math.max(0, (int) (px - arx)), x1 = Math.min(rw - 1, (int) (px + arx) + 1);
        int y0 = Math.max(0, (int) (py - ary)), y1 = Math.min(rh - 1, (int) (py + ary) + 1);
        float irx = 1f / arx, iry = 1f / ary;
        for (int yy = y0; yy <= y1; yy++) {
            float dy = (yy + 0.5f - py) * iry;
            for (int xx = x0; xx <= x1; xx++) {
                float dx = (xx + 0.5f - px) * irx;
                float q = dx * dx + dy * dy;
                if (q >= 1f) continue;
                float cap = az + arz * (float) Math.sqrt(1f - q);
                int i = yy * rw + xx;
                float old = rz[i];
                float nz = join(old, cap, ak);
                if (old == NONE || cap > old) {
                    // the new form shows here: it brings its own openness and material
                    float t = old == NONE ? 1f : Math.min(1f, (cap - old) / (ak * 0.3f + 0.001f));
                    rao[i] = old == NONE ? ao : rao[i] + (ao - rao[i]) * t;
                    rkind[i] = old == NONE ? kind : rkind[i] + (kind - rkind[i]) * t;
                    if (tint != 0 && t > 0.5f) rtint[i] = tint;
                }
                rz[i] = nz;
            }
        }
    }

    private void dome(float x, float y, float z, float r, float k, float ao, float kind) {
        blob(x, y, z, r, r, r, k, ao, kind, 0);
    }

    /** A rounded hollow: the surface sinks by up to depth within an ellipse, and sits in shadow. */
    private void hollow(float x, float y, float rx, float ry, float depth, float shade) {
        float px = ox + (x * rotC - y * rotS) * sc, py = oy + (x * rotS + y * rotC) * sc;
        float arx = rx * sc, ary = ry * sc, ad = depth * sc;
        int x0 = Math.max(0, (int) (px - arx)), x1 = Math.min(rw - 1, (int) (px + arx) + 1);
        int y0 = Math.max(0, (int) (py - ary)), y1 = Math.min(rh - 1, (int) (py + ary) + 1);
        float irx = 1f / arx, iry = 1f / ary;
        for (int yy = y0; yy <= y1; yy++) {
            float dy = (yy + 0.5f - py) * iry;
            for (int xx = x0; xx <= x1; xx++) {
                float dx = (xx + 0.5f - px) * irx;
                float q = dx * dx + dy * dy;
                if (q >= 1f) continue;
                int i = yy * rw + xx;
                if (rz[i] == NONE) continue;
                float t = 1f - q;
                t = t * t;
                rz[i] -= ad * t;
                rao[i] *= 1f - (1f - shade) * t;
            }
        }
    }

    /** A rounded strand from one point to another, thinning from r0 to r1, bent sideways by bow. */
    private void strand(float x0, float y0, float z0, float x1, float y1, float z1, float r0, float r1, float bow, float k, float ao, float kind) {
        strand(x0, y0, z0, x1, y1, z1, r0, r1, bow, k, ao, kind, 1f);
    }

    /** The same, lying flatter on what is beneath: its height is flat times its width. */
    private void strand(float x0, float y0, float z0, float x1, float y1, float z1, float r0, float r1, float bow, float k, float ao, float kind, float flat) {
        float len = (float) Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
        int n = Math.max(2, (int) (len / (Math.min(r0, r1) * 0.5f)) + 1);
        float nx = -(y1 - y0) / (len + 1e-6f), ny = (x1 - x0) / (len + 1e-6f);
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n;
            float b = (float) Math.sin(u * Math.PI) * bow;
            float r = r0 + (r1 - r0) * u;
            blob(x0 + (x1 - x0) * u + nx * b, y0 + (y1 - y0) * u + ny * b, z0 + (z1 - z0) * u, r, r, r * flat, k, ao, kind, 0);
        }
    }

    /** Height of the relief under a form-space point, or NONE. */
    private float heightAt(float x, float y) {
        int px = (int) (ox + (x * rotC - y * rotS) * sc), py = (int) (oy + (x * rotS + y * rotC) * sc);
        if (px < 0 || py < 0 || px >= rw || py >= rh) return NONE;
        return rz[py * rw + px];
    }

    /**
     * Lights the relief and writes it over out at (atX, atY), out being outW wide and
     * outH tall; empty points are left alone. The slope of the surface gives the normal,
     * so folds between merged forms read as folds, not as seams.
     */
    private void light(int[] out, int outW, int outH, int atX, int atY) {
        for (int yy = 0; yy < rh; yy++) {
            int oyy = atY + yy;
            if (oyy < 0 || oyy >= outH) continue;
            for (int xx = 0; xx < rw; xx++) {
                int oxx = atX + xx;
                if (oxx < 0 || oxx >= outW) continue;
                int i = yy * rw + xx;
                float z = rz[i];
                if (z == NONE) continue;
                float l = xx > 0 ? rz[i - 1] : NONE, r = xx < rw - 1 ? rz[i + 1] : NONE;
                float u = yy > 0 ? rz[i - rw] : NONE, d = yy < rh - 1 ? rz[i + rw] : NONE;
                // at the silhouette the surface turns away steeply
                float edge = 2.5f;
                float gx, gy;
                if (l == NONE && r == NONE) gx = 0f;
                else if (l == NONE) gx = (r - (z - edge)) * 0.5f;
                else if (r == NONE) gx = ((z - edge) - l) * 0.5f;
                else gx = (r - l) * 0.5f;
                if (u == NONE && d == NONE) gy = 0f;
                else if (u == NONE) gy = (d - (z - edge)) * 0.5f;
                else if (d == NONE) gy = ((z - edge) - u) * 0.5f;
                else gy = (d - u) * 0.5f;
                float nx = -gx, ny = -gy, nz = 1f;
                float il = 1f / (float) Math.sqrt(nx * nx + ny * ny + 1f);
                nx *= il;
                ny *= il;
                nz *= il;
                float shade = rao[i] * (1f - 0.55f * shadow(xx, yy, z));
                if (rkind[i] > 0.5f) {
                    // a little fluff on cloud, none on stone
                    float fluff = (((oxx * 73 + oyy * 151) * 2654435761L >>> 24) & 0xFF) / 255f - 0.5f;
                    shade *= 1f + fluff * 0.28f * (rkind[i] - 0.5f);
                }
                out[oyy * outW + oxx] = cloudColor(nx, ny, nz, shade, rtint[i]);
            }
        }
    }

    // the light climbs this much per pixel toward the sun, across the relief
    private static final float SLOPE = LZ / (float) Math.sqrt(LX * LX + LY * LY);
    private static final float STEP = 1.5f;

    /** How much of the light toward the sun is blocked by higher relief, 0..1. */
    private float shadow(int x, int y, float z) {
        float best = 0f;
        float ux = LX / (float) Math.sqrt(LX * LX + LY * LY), uy = LY / (float) Math.sqrt(LX * LX + LY * LY);
        for (int k = 1; k <= 6; k++) {
            float d = k * STEP;
            int sx = (int) (x + 0.5f + ux * d), sy = (int) (y + 0.5f + uy * d);
            if (sx < 0 || sy < 0 || sx >= rw || sy >= rh) break;
            float h = rz[sy * rw + sx];
            if (h == NONE) continue;
            float over = h - (z + SLOPE * d) - 0.5f;
            if (over > best) best = over;
        }
        return Math.min(1f, best * 0.35f);
    }

    /** Cloud light: gold where the sun reaches, warm in the middle, lavender in the folds. */
    static int cloudColor(float nx, float ny, float nz, float shade, int tint) {
        float d0 = nx * LX + ny * LY + nz * LZ;
        float diff = Math.max(0f, Math.min(1f, (d0 - 0.05f) * 1.35f)) * shade;
        float rim = (1f - nz) * (1f - nz) * Math.max(0f, -nx * 0.7f + ny * 0.5f) * Math.min(1f, shade * 1.3f);
        int c;
        if (tint == 0) {
            c = diff < 0.42f ? Backdrop.mix(0xFF7468A6, 0xFFDDB3C0, diff / 0.42f)
                    : Backdrop.mix(0xFFDDB3C0, 0xFFFFF4DC, (diff - 0.42f) / 0.58f);
            c = Backdrop.mix(c, 0xFFFFC56E, Math.min(1f, rim * 1.3f));
        } else {
            c = Backdrop.scale(tint, 0.45f + diff * 0.8f);
        }
        return c;
    }

    // ---- cumulus ----

    /**
     * Grows a cumulus mass in form units around (cx, cy): a flat-bottomed base, big
     * billows on top of it, smaller ones on top of those, and the smallest at the rim,
     * so the edge is torn and the top boils. Width rx, height ry, base height z.
     */
    private void cumulus(Random cr, float cx, float cy, float z, float rx, float ry, float kind) {
        cumulus(cr, cx, cy, z, rx, ry, kind, 1f, 0f, 0f, 0f);
    }

    /**
     * The same, with billows grain times the usual size, and none reaching into the band
     * of rows yLo..yHi within keepX of the center: that is where a face goes.
     */
    private void cumulus(Random cr, float cx, float cy, float z, float rx, float ry, float kind, float grain, float yLo, float yHi, float keepX) {
        float size = Math.max(rx, ry);
        blob(cx, cy, z - size * 0.3f, rx, ry, size * 0.55f, size * 0.3f, 0.5f, kind, 0);
        // billows in three sizes: each sits on whatever is already there
        float[] rads = {0.34f * grain, 0.17f * grain, 0.085f * grain};
        float dens = rx * ry / (size * size) / (grain * grain);
        int[] counts = {(int) (dens * 14f) + 4, (int) (dens * 40f) + 10, (int) (dens * 90f) + 20};
        float[] open = {0.72f, 0.88f, 1f};
        // small billows are gravel on a small mass: curls stop at two sizes
        for (int lvl = 0; lvl < (grain < 1f ? 2 : 3); lvl++)
            for (int i = 0; i < counts[lvl]; i++) {
                float a = (float) (cr.nextFloat() * Math.PI * 2);
                // the top boils, the bottom stays flat: more billows above the middle
                float rr = lvl == 2 ? 0.75f + cr.nextFloat() * 0.35f : (float) Math.sqrt(cr.nextFloat()) * 0.95f;
                float y = cy + (float) Math.sin(a) * rr * ry;
                if (y > cy + ry * 0.55f && cr.nextFloat() < 0.7f) continue;
                float x = cx + (float) Math.cos(a) * rr * rx;
                float r = size * rads[lvl] * (0.75f + cr.nextFloat() * 0.5f);
                if (y + r * 0.7f > yLo && y - r * 0.7f < yHi && Math.abs(x - cx) < keepX) continue;
                float under = heightAt(x, y);
                float base = under == NONE ? z - size * 0.3f : under / sc - r * 0.55f;
                dome(x, y, base, r, r * 0.45f, open[lvl], kind);
            }
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
        // towering cumulus: banks of billows, thinning toward the horizon
        relief(w, sh);
        frame(1f, 0f, 0f, 0f);
        Random cr = new Random(31);
        for (int bank = 0; bank < 6; bank++) {
            float bx = cr.nextFloat() * w, by = sh * (0.08f + cr.nextFloat() * 0.62f);
            float size = w * (0.18f + cr.nextFloat() * 0.16f);
            cumulus(cr, bx, by, 200f - size * 0.6f, size * 1.4f, size * 0.75f, 1f);
        }
        light(strip, w, sh, 0, 0);
        layer = new int[w * sh];
    }

    // ---- the gods ----

    private static final int THUNDERER = 0, GODDESS = 1, THINKER = 2, ELDER = 3, BORED = 4;
    private static final int GODS = 5;
    private final float[] gx = new float[GODS], gy = new float[GODS], gR = new float[GODS];
    private final int[] gType = {THUNDERER, GODDESS, THINKER, ELDER, BORED};

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
            int g = rnd.nextBoolean() ? ELDER : THUNDERER;
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

    // the box a god is sculpted in, in head radii: x in [-BX, BX], y in [-BY0, BY1]
    private static final float BX = 2.3f, BY0 = 1.7f, BY1 = 2.6f;

    /** Sculpts all the gods into their layer. */
    private void renderGods() {
        java.util.Arrays.fill(layer, 0);
        float t = time;
        for (int g = 0; g < GODS; g++) {
            mood(gType[g], t);
            float R = gR[g];
            int bw = (int) (BX * 2f * R) + 2, bh = (int) ((BY0 + BY1) * R) + 2;
            relief(bw, bh);
            float tilt = gType[g] == GODDESS ? -0.22f : gType[g] == THINKER ? 0.12f : 0f;
            frame(R, bw * 0.5f, BY0 * R, tilt);
            sculpt(gType[g], t);
            light(layer, w, sh, Math.round(gx[g] - bw * 0.5f), Math.round(gy[g] - BY0 * R));
        }
    }

    /** The gods and what they do, drawn where the strip now is on screen (shift = off - sh). */
    void drawClouds(Px f, float shift) {
        // sculpting is the dear part: every third frame is enough for their slow moves
        if (frames++ % 3 == 0) renderGods();
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
                        makeBolt(gx[THUNDERER] - gR[THUNDERER] * 1.3f, gy[THUNDERER] - gR[THUNDERER] * 0.9f);
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
                } else if (type == THUNDERER) {
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

    private static float hash(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    /**
     * Carves a god in its own units (head radius 1; x right, y down, z toward us): the
     * cloud it rises from, then the head as one piece of stone with its brow, eyes, nose
     * and mouth, hair and beard as masses with locks carved into them that fray into
     * cloud at the ends, and the hand that each one uses.
     */
    private void sculpt(int type, float t) {
        final float STONE = 0f, CLOUD = 1f;
        Random cr = new Random(type * 977 + 13);
        boolean beard = type != GODDESS;
        // the cloud bank at the shoulders, behind the head
        cumulus(cr, 0f, 1.8f, -1.4f, 2.1f, 0.85f, CLOUD, 1.3f, 0f, 0f, 0f);
        // hair: curls heaped over the crown, and the skull and the face pushed forward from them
        float mane = type == GODDESS ? 1.45f : type == THUNDERER ? 1.4f : 1.25f;
        blob(0f, -0.05f, 0f, 0.95f, 1.12f, 0.95f, 0.3f, 1f, STONE, 0);
        blob(0f, 0.3f, 0.3f, 0.74f, 0.88f, 0.55f, 0.35f, 1f, STONE, 0);
        cumulus(cr, 0f, -0.75f, 0.1f, mane, 0.9f, 0.4f, 0.7f, -0.42f, 9f, 0.9f);
        float len = type == ELDER || type == THUNDERER ? 1.35f : type == THINKER ? 0.75f : 0.95f;
        if (beard) {
            // the beard: curls heaped from the jaw to the chest, longer on the elder and the thunderer
            cumulus(cr, 0f, 1.05f + len * 0.45f, -0.05f, 0.8f, len * 0.5f + 0.3f, 0.4f, 0.7f, -9f, 0.78f, 0.55f);
        }
        // the hairline: curls low over the brow
        for (int i = 0; i < 7; i++) {
            float u = (i + 0.5f) / 7f * 2f - 1f;
            dome(u * 0.82f, -0.56f + u * u * 0.12f + (hash(i, type) - 0.5f) * 0.08f, 0.5f - u * u * 0.2f, 0.19f + hash(i + 3, type) * 0.06f, 0.1f, 1f, STONE);
        }
        // brows: two arches, their inner ends sinking in a frown
        float by = -0.27f + frown * 0.05f;
        for (int side = -1; side <= 1; side += 2) {
            strand(side * 0.1f, by + 0.03f + frown * 0.08f, 0.74f, side * 0.62f, by - 0.06f + frown * 0.02f, 0.6f, 0.11f, 0.08f, side * 0.04f, 0.1f, 1f, STONE);
            float ex = side * 0.33f;
            // the eye: a hollow deep under the brow, the ball inside, the lids over it
            hollow(ex, 0.02f, 0.3f, 0.2f, 0.26f, 0.45f);
            dome(ex, 0.03f, 0.56f, 0.15f, 0.04f, 0.9f, STONE);
            float lid = eyesShut;
            blob(ex, -0.09f + lid * 0.09f, 0.58f, 0.19f, 0.08f + lid * 0.09f, 0.14f, 0.08f, 1f, STONE, 0);
            blob(ex, 0.15f, 0.58f, 0.17f, 0.05f, 0.1f, 0.08f, 1f, STONE, 0);
            // the pupil is drilled, as in stone
            if (lid < 0.5f) hollow(ex, 0.03f, 0.06f, 0.06f, 0.12f, 0.3f);
            // cheekbones, lifted by a smile
            blob(side * 0.52f, 0.28f - smile * 0.07f, 0.5f, 0.34f, 0.26f, 0.22f, 0.3f, 1f, STONE, 0);
        }
        // nose: the bridge from between the brows down to a broad tip, with wings and nostrils
        strand(0f, -0.15f, 0.8f, 0f, 0.28f, 0.9f, 0.1f, 0.16f, 0f, 0.12f, 1f, STONE);
        for (int side = -1; side <= 1; side += 2) dome(side * 0.17f, 0.32f, 0.74f, 0.11f, 0.1f, 1f, STONE);
        for (int side = -1; side <= 1; side += 2) hollow(side * 0.09f, 0.42f, 0.07f, 0.05f, 0.1f, 0.45f);
        // mouth: the opening sinks between the lips; the corners lift in a smile
        float my = 0.6f;
        float open = 0.03f + mouth * 0.17f;
        hollow(0f, my + 0.06f + mouth * 0.12f, 0.28f, open + 0.05f, 0.14f + mouth * 0.3f, 0.3f - mouth * 0.15f);
        for (int side = -1; side <= 1; side += 2) {
            float lift = smile * 0.07f - 0.02f;
            strand(0f, my, 0.8f, side * 0.28f, my - lift, 0.72f, 0.065f, 0.045f, side * 0.015f, 0.06f, 1f, STONE);
            strand(0f, my + 0.12f + mouth * 0.28f, 0.79f, side * 0.24f, my + 0.08f + mouth * 0.26f - lift * 0.5f, 0.72f, 0.07f, 0.045f, side * -0.015f, 0.06f, 1f, STONE);
        }
        // chin
        if (!beard) dome(0f, 0.92f, 0.32f, 0.32f, 0.3f, 1f, STONE);
        if (beard) {
            // moustache: two thick locks curling out and down from under the nose
            for (int side = -1; side <= 1; side += 2)
                strand(side * 0.04f, 0.5f, 0.84f, side * 0.5f, 0.74f, 0.7f, 0.1f, 0.07f, side * -0.1f, 0.08f, 1f, STONE, 0.8f);
        } else {
            blob(0f, 0.8f, 0.45f, 0.42f, 0.3f, 0.3f, 0.3f, 1f, STONE, 0);           // a soft jaw
        }
        if (type == GODDESS) {
            // long waves to the shoulders, and a laurel wreath
            for (int side = -1; side <= 1; side += 2)
                for (int i = 0; i < 4; i++)
                    strand(side * (0.9f + i * 0.1f), -0.1f + i * 0.15f, 0.2f, side * (1.0f + i * 0.14f), 1.1f + i * 0.2f, -0.1f, 0.18f, 0.12f, side * 0.15f, 0.1f, 0.9f, STONE);
            for (int i = 0; i < 9; i++) {
                float a = (float) (Math.PI * (1.12f + 0.76f * i / 8f));
                blob((float) Math.cos(a) * 1.0f, -0.25f + (float) Math.sin(a) * 0.98f, 0.45f, 0.1f, 0.06f, 0.06f, 0.03f, 1f, STONE, 0);
            }
        }
        // hands
        if (type == THINKER || type == GODDESS) {
            // a fist under the chin (or a palm to the cheek), the forearm down into the cloud
            float hx = type == THINKER ? 0.15f : 0.62f, hy = type == THINKER ? 1.05f : 0.55f;
            for (int i = 0; i < 4; i++) strand(hx - 0.2f + i * 0.13f, hy - 0.05f, 0.85f, hx - 0.2f + i * 0.13f, hy + 0.15f, 0.88f, 0.07f, 0.065f, 0f, 0.05f, 1f, STONE);
            dome(hx, hy + 0.2f, 0.72f, 0.24f, 0.12f, 1f, STONE);
            strand(hx, hy + 0.25f, 0.7f, hx + 0.7f, hy + 1.4f, 0.35f, 0.23f, 0.2f, 0.1f, 0.15f, 0.9f, STONE);
        } else if (type == ELDER || type == THUNDERER) {
            // an arm raised to throw, swinging
            float side = type == ELDER ? 1f : -1f;
            float sw = (float) Math.sin(t * 6f + type) * 0.25f;
            float shx = side * 1.05f, shy = 1.15f;
            float hx = side * (1.45f + sw * 0.2f), hy = -0.15f + sw * 0.6f;
            float ex = side * 1.6f, ey = 0.6f + sw * 0.3f;
            strand(shx, shy, 0.35f, ex, ey, 0.5f, 0.32f, 0.24f, side * 0.1f, 0.15f, 0.9f, STONE);
            strand(ex, ey, 0.5f, hx, hy, 0.7f, 0.24f, 0.17f, side * -0.08f, 0.15f, 1f, STONE);
            for (int i = 0; i < 4; i++)
                strand(hx - side * 0.12f + i * 0.08f * side, hy - 0.05f, 0.75f, hx - side * 0.14f + i * 0.09f * side, hy - 0.3f, 0.8f, 0.07f, 0.06f, 0f, 0.05f, 1f, STONE);
            // the block in hand
            blob(hx, hy - 0.3f, 0.85f, 0.17f, 0.14f, 0.1f, 0.02f, 1f, STONE, BLOCK_COLORS[(int) (t * 3f) % BLOCK_COLORS.length]);
        } else if (type == BORED && mouth > 0.3f) {
            // a hand over the yawn
            for (int i = 0; i < 4; i++) strand(-0.18f + i * 0.12f, 0.55f, 0.95f, -0.18f + i * 0.12f, 0.72f, 0.97f, 0.07f, 0.065f, 0f, 0.05f, 1f, STONE);
            dome(0.05f, 0.82f, 0.85f, 0.2f, 0.1f, 1f, STONE);
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
