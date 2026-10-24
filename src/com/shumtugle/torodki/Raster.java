package com.shumtugle.torodki;

/**
 * A software 3D renderer over the game frame: a pinhole camera with pitch, flat-lit
 * polygons with a depth buffer, perspective-correct surface patterns (wood grain,
 * windows, stone), a sun shadow map, and dark outlines where one object meets another.
 * Plain Java; the frame is a Px.
 */
final class Raster {
    final int w, h;
    final int[] col;
    final float[] zb;         // 1 / view depth; 0 is infinitely far
    final int[] idb;          // what each pixel shows: 0 backdrop, then object ids
    final byte[] lit;         // 1 where the surface faces the sun and can take a shadow

    // camera
    float camX, camY, camZ, pitch, f, cx, cy, cp, sp;
    float cyw = 1f, syw;
    int alpha = 255;                     // below 255 polygons are see-through
    boolean depthWrite = true;
    boolean flat;                        // boxes take their color as it is: silhouettes                 // yaw: forward is (syw, 0, cyw)

    // sun: direction toward it, and the shadow map basis
    // a low evening sun from the left, over the promenade: long shadows across the street
    static final float LX = -0.80f, LY = 0.30f, LZ = -0.10f;
    private float e1x, e1y, e1z, e2x, e2y, e2z;
    static final int SM = 320;
    private final float[] sm = new float[SM * SM];
    private float smCx, smCz, smHalf = 5f;

    private static final int[] NOISE = new int[1024];

    static {
        int s = 0x1234567;
        for (int i = 0; i < NOISE.length; i++) {
            s = s * 1103515245 + 12345;
            NOISE[i] = (s >>> 16) & 0xFF;
        }
    }

    Raster(int w, int h) {
        this.w = w;
        this.h = h;
        col = new int[w * h];
        zb = new float[w * h];
        idb = new int[w * h];
        lit = new byte[w * h];
        float l = (float) Math.sqrt(LX * LX + LY * LY + LZ * LZ);
        float lx = LX / l, ly = LY / l, lz = LZ / l;
        // e1 = normalize(L x up), e2 = e1 x L
        e1x = -lz;
        e1y = 0;
        e1z = lx;
        float k = (float) Math.sqrt(e1x * e1x + e1z * e1z);
        e1x /= k;
        e1z /= k;
        e2x = e1y * lz - e1z * ly;
        e2y = e1z * lx - e1x * lz;
        e2z = e1x * ly - e1y * lx;
    }

    static float sunX() {
        return LX / (float) Math.sqrt(LX * LX + LY * LY + LZ * LZ);
    }

    static float sunY() {
        return LY / (float) Math.sqrt(LX * LX + LY * LY + LZ * LZ);
    }

    static float sunZ() {
        return LZ / (float) Math.sqrt(LX * LX + LY * LY + LZ * LZ);
    }

    void camera(float x, float y, float z, float yawRad, float pitchRad, float focal, float centerY) {
        cyw = (float) Math.cos(yawRad);
        syw = (float) Math.sin(yawRad);
        camX = x;
        camY = y;
        camZ = z;
        pitch = pitchRad;
        cp = (float) Math.cos(pitchRad);
        sp = (float) Math.sin(pitchRad);
        f = focal;
        cx = w * 0.5f;
        cy = centerY;
    }

    // ---- projection helpers ----

    /** View depth of a world point (along the camera axis). */
    float depth(float x, float y, float z) {
        float dy = y - camY, dz = (x - camX) * syw + (z - camZ) * cyw;
        return dz * cp + dy * sp;
    }

    /** A view-space vector (right, up, forward) turned into world axes, into out[0..2]. */
    void viewToWorld(float vx, float vy, float vz, float[] out) {
        float y = vy * cp + vz * sp;
        float z1 = vz * cp - vy * sp;
        out[0] = vx * cyw + z1 * syw;
        out[1] = y;
        out[2] = -vx * syw + z1 * cyw;
    }

    /** Screen position of a world point into out[0], out[1]; returns view depth. */
    float project(float x, float y, float z, float[] out) {
        float ex = x - camX, dy = y - camY, ez = z - camZ;
        float dx = ex * cyw - ez * syw, dz = ex * syw + ez * cyw;
        float vz = dz * cp + dy * sp;
        float vy = dy * cp - dz * sp;
        if (vz < 0.01f) vz = 0.01f;
        out[0] = cx + f * dx / vz;
        out[1] = cy - f * vy / vz;
        return vz;
    }

    /** World direction of the ray through screen point (sx, sy), into out[0..2], not normalized. */
    void ray(float sx, float sy, float[] out) {
        viewToWorld((sx - cx) / f, (cy - sy) / f, 1f, out);
    }

    // ---- polygons ----

    static final int PAT_FLAT = 0, PAT_GRAIN = 1, PAT_WINDOWS = 2, PAT_STONE = 3, PAT_BANDS = 4, PAT_FACADE = 5, PAT_BRICK = 6;
    // patterns that bring colors of their own, lit like a facade: striped mattress ticking,
    // a leather suitcase with straps, latches and hotel stickers, a loud flowered shirt
    static final int PAT_TICK = 7, PAT_CASE = 8, PAT_ALOHA = 9;

    private final float[] vx = new float[16], vy = new float[16], vz = new float[16], vu = new float[16], vv = new float[16];
    private final float[] cxs = new float[16], cys = new float[16], czs = new float[16], cus = new float[16], cvs = new float[16];
    private final float[] sx = new float[16], sy = new float[16], iz = new float[16];

    /**
     * Draws a convex polygon given in world space with per-vertex pattern coordinates.
     * The color is already lit; pattern varies brightness across the surface.
     */
    void poly(float[] wx, float[] wy, float[] wz, float[] u, float[] v, int n, int color, int pat, int id, boolean sunlit) {
        // into view space
        for (int i = 0; i < n; i++) {
            float ex = wx[i] - camX, dy = wy[i] - camY, ez = wz[i] - camZ;
            float dx = ex * cyw - ez * syw, dz = ex * syw + ez * cyw;
            vz[i] = dz * cp + dy * sp;
            vy[i] = dy * cp - dz * sp;
            vx[i] = dx;
            vu[i] = u == null ? 0 : u[i];
            vv[i] = v == null ? 0 : v[i];
        }
        // clip against the near plane
        float near = 0.06f;
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            boolean in1 = vz[i] >= near, in2 = vz[j] >= near;
            if (in1) {
                cxs[m] = vx[i];
                cys[m] = vy[i];
                czs[m] = vz[i];
                cus[m] = vu[i];
                cvs[m] = vv[i];
                m++;
            }
            if (in1 != in2) {
                float t = (near - vz[i]) / (vz[j] - vz[i]);
                cxs[m] = vx[i] + (vx[j] - vx[i]) * t;
                cys[m] = vy[i] + (vy[j] - vy[i]) * t;
                czs[m] = near;
                cus[m] = vu[i] + (vu[j] - vu[i]) * t;
                cvs[m] = vv[i] + (vv[j] - vv[i]) * t;
                m++;
            }
        }
        if (m < 3) return;
        for (int i = 0; i < m; i++) {
            float q = 1f / czs[i];
            sx[i] = cx + f * cxs[i] * q;
            sy[i] = cy - f * cys[i] * q;
            iz[i] = q;
            cus[i] *= q;
            cvs[i] *= q;
        }
        for (int i = 1; i < m - 1; i++) tri(0, i, i + 1, color, pat, id, sunlit);
    }

    private void tri(int a, int b, int c, int color, int pat, int id, boolean sunlit) {
        float x0 = sx[a], y0 = sy[a], x1 = sx[b], y1 = sy[b], x2 = sx[c], y2 = sy[c];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minx = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxx = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int miny = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxy = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minx > maxx || miny > maxy) return;
        float ia = 1f / area;
        float z0 = iz[a], z1 = iz[b], z2 = iz[c];
        float u0 = cus[a], u1 = cus[b], u2 = cus[c];
        float v0 = cvs[a], v1 = cvs[b], v2 = cvs[c];
        int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
        byte lf = sunlit ? (byte) 1 : (byte) 0;
        for (int py = miny; py <= maxy; py++) {
            float pyc = py + 0.5f;
            int row = py * w;
            for (int px = minx; px <= maxx; px++) {
                float pxc = px + 0.5f;
                float w0 = ((x1 - pxc) * (y2 - pyc) - (x2 - pxc) * (y1 - pyc)) * ia;
                float w1 = ((x2 - pxc) * (y0 - pyc) - (x0 - pxc) * (y2 - pyc)) * ia;
                float w2 = 1f - w0 - w1;
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                float z = w0 * z0 + w1 * z1 + w2 * z2;
                int k = row + px;
                if (z <= zb[k]) continue;
                if (alpha < 255) {
                    int c0 = col[k];
                    int r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
                    col[k] = 0xFF000000 | ((r0 + ((cr - r0) * alpha >> 8)) << 16) | ((g0 + ((cg - g0) * alpha >> 8)) << 8)
                            | (b0 + ((cb - b0) * alpha >> 8));
                    lit[k] = 0;
                    if (depthWrite) {
                        zb[k] = z;
                        idb[k] = id;
                    }
                    continue;
                }
                zb[k] = z;
                idb[k] = id;
                lit[k] = lf;
                if (pat == PAT_FLAT) {
                    col[k] = color;
                    continue;
                }
                float iq = 1f / z;
                float uu = (w0 * u0 + w1 * u1 + w2 * u2) * iq;
                float vv2 = (w0 * v0 + w1 * v1 + w2 * v2) * iq;
                if (pat >= PAT_TICK) {
                    int fc = paint(pat, uu, vv2);
                    int r = (int) (((fc >> 16) & 0xFF) * fk[0]), g = (int) (((fc >> 8) & 0xFF) * fk[1]), bl = (int) ((fc & 0xFF) * fk[2]);
                    col[k] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl);
                    continue;
                }
                if (pat == PAT_FACADE) {
                    int fc = facade(uu, vv2, facadeBase, facadeSeed);
                    int r = (int) (((fc >> 16) & 0xFF) * fk[0]), g = (int) (((fc >> 8) & 0xFF) * fk[1]), bl = (int) ((fc & 0xFF) * fk[2]);
                    col[k] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl);
                    continue;
                }
                int m = shade(pat, uu, vv2);
                int r = cr * m >> 8, g = cg * m >> 8, bl = cb * m >> 8;
                if (r > 255) r = 255;
                if (g > 255) g = 255;
                if (bl > 255) bl = 255;
                col[k] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
    }

    // the facade being drawn: its wall color, its own seed, and the light on the face
    int facadeBase;
    int facadeSeed;
    private final float[] fk = new float[3];

    private static int nz(int a, int b, int seed) {
        int h = a * 374761393 + b * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0xFF;
    }

    /**
     * A street front in a seaside quarter, unlit, at (u along the wall, v up), meters:
     * shops with awnings at street level; above, tall windows with shutters and small
     * iron balconies, floor after floor, with a cornice line at every floor.
     */
    static int facade(float u, float v, int base, int seed) {
        final float fh = 3.3f, bw = 2.9f;
        int storey = (int) Math.floor(v / fh);
        float fv = v - storey * fh;
        int bay = (int) Math.floor(u / bw);
        float fu = u - bay * bw;
        int h = nz(bay, storey, seed);
        // stucco: patches of the wall a little lighter or darker
        int st = nz((int) (u * 3f), (int) (v * 3f), seed + 7);
        int wall = Backdrop.scale(base, 0.93f + st / 2550f + (nz(bay, storey / 2, seed + 3) - 128) / 2200f);
        if (fv < 0.14f && storey > 0) return Backdrop.scale(base, 0.78f);           // cornice line
        if (fv < 0.22f && storey > 0) return Backdrop.scale(base, 1.06f);
        if (storey == 0) {
            if (fu > 0.3f && fu < 2.6f && fv < 2.75f) {
                if (fv > 2.25f && (h & 3) != 0) {
                    // an awning, striped or plain
                    boolean stripe = (h & 4) != 0 && ((int) (fu * 4f) & 1) == 0;
                    return stripe ? 0xFFF4EEE2 : ((h & 8) != 0 ? 0xFFB5523E : 0xFF4F7A5E);
                }
                int glow = nz(bay, 99, seed) > 150 ? 0xFF6E4A2E : 0xFF35302D;
                return fv < 0.2f ? 0xFF4A4440 : glow;
            }
            return Backdrop.scale(wall, 0.92f);
        }
        boolean closed = (h & 7) == 0;
        int shutter = (h & 16) != 0 ? 0xFF3F6A50 : 0xFF557587;
        boolean balcony = (h & 32) != 0 || storey == 1;
        // balcony: slab and iron railing in front of the lower half of the window
        if (balcony && fu > 0.7f && fu < 2.2f) {
            if (fv > 0.22f && fv < 0.34f) return 0xFF5C5450;
            if (fv > 1.18f && fv < 1.26f) return 0xFF2B2A2C;
            if (fv > 0.34f && fv < 1.18f && (((int) (fu * 9f)) & 1) == 0 && fu * 9f - (int) (fu * 9f) < 0.35f)
                return 0xFF2B2A2C;
            if (fv > 0.34f && fv < 0.62f && (h & 64) != 0 && fu > 0.85f && fu < 2.05f) {
                // flower pots
                int f = nz((int) (fu * 6f), storey, seed + 11);
                return f < 90 ? 0xFF4D7A3A : f < 150 ? 0xFFD9505A : 0xFF6E9440;
            }
        }
        if (balcony && fu > 0.7f && fu < 2.2f && fv > 0.1f && fv < 0.22f) return Backdrop.scale(wall, 0.62f);
        if (fv > 0.36f && fv < 2.7f) {
            if (fu > 0.95f && fu < 1.95f) {
                if (closed) return ((int) (fv * 9f) & 1) == 0 ? shutter : Backdrop.scale(shutter, 0.8f);
                if (fu < 1.01f || fu > 1.89f || fv > 2.62f) return 0xFFE9E3D6;               // frame
                return fv > 1.6f ? 0xFF3A4048 : 0xFF2C2E33;                                    // glass
            }
            if ((fu > 0.5f && fu < 0.95f) || (fu > 1.95f && fu < 2.4f)) {
                if (closed) return wall;
                return ((int) (fv * 9f) & 1) == 0 ? shutter : Backdrop.scale(shutter, 0.8f);  // open shutters
            }
            if (fu > 0.42f && fu < 2.48f && fv > 2.7f - 0.08f) return Backdrop.scale(wall, 1.08f);
        }
        if (fv > 2.7f && fv < 2.95f && fu > 0.85f && fu < 2.05f) return Backdrop.scale(wall, 1.1f); // lintel
        return wall;
    }

    // the face being painted with a colored pattern: its base color, its half sizes along
    // u and v, which axis it faces along, and a seed of the thing it belongs to
    private int patBase, patAxis;
    private float patU, patV;
    int patSeed;

    /** The unlit color of a colored pattern at (u, v), meters from the middle of the face. */
    private int paint(int pat, float u, float v) {
        if (pat == PAT_TICK) {
            // blue stripes along the mattress, a pair of thin ones between the wide ones
            // on the top they run the length of it; round the border they run along it too
            float sp = (patAxis == 1 ? u : v) / 0.07f;
            float fu = sp - (float) Math.floor(sp);
            int c = fu < 0.32f ? 0xFF5A82AE : fu > 0.62f && fu < 0.7f ? 0xFF9DB4CF : patBase;
            // the piping round the edges of the side
            if (patAxis != 1 && Math.abs(v) > patV - 0.018f) c = 0xFFC9C6BC;
            // tufts on the top
            if (patAxis == 1) {
                float gu = u / 0.2f - (float) Math.floor(u / 0.2f), gv = v / 0.2f - (float) Math.floor(v / 0.2f);
                if (Math.abs(gu - 0.5f) < 0.09f && Math.abs(gv - 0.5f) < 0.09f) c = Backdrop.scale(c, 0.78f);
            }
            // rust from the springs, soaked through in brown blots
            int a = nz((int) Math.floor(u * 7f), (int) Math.floor(v * 7f), patSeed + patAxis * 5);
            int b = nz((int) Math.floor(u * 15f), (int) Math.floor(v * 15f), patSeed + 3);
            if (a > 236 || (a > 214 && b > 120)) c = Backdrop.mix(c, 0xFF9A5A30, a > 236 ? 0.55f : 0.3f);
            return c;
        }
        if (pat == PAT_CASE) {
            int c = patBase;
            float au = Math.abs(u), av = Math.abs(v);
            // worn corners and edges: the leather rubbed pale
            boolean edgeU = au > patU - 0.03f, edgeV = av > patV - 0.025f;
            if (edgeU && edgeV) return Backdrop.mix(c, 0xFFE6D2A6, 0.55f);
            if (edgeU || edgeV) c = Backdrop.mix(c, 0xFFD9C29A, 0.18f);
            // two straps round it, dark leather with a brass buckle at the front
            float su = Math.abs(au - patU * 0.52f);
            if (patAxis != 0 && su < 0.032f) {
                if (patAxis == 2 && Math.abs(v + patV * 0.1f) < 0.03f && su < 0.03f)
                    return su < 0.012f ? 0xFF6A4A1A : 0xFFE0B64A;
                return su > 0.026f ? 0xFF2A1A10 : Backdrop.mix(Backdrop.scale(c, 0.55f), 0xFF3A2414, 0.5f);
            }
            if (patAxis != 1) {
                // the line where the lid closes
                float seam = patV * 0.25f;
                if (Math.abs(v - seam) < 0.011f) return Backdrop.scale(c, 0.62f);
                // brass latches on the front
                if (patAxis == 2 && Math.abs(au - patU * 0.8f) < 0.022f && Math.abs(v - seam) < 0.032f)
                    return Math.abs(v - seam) < 0.012f ? 0xFFFFE08A : 0xFFC9962E;
            }
            // hotel stickers, round and square, here and there
            int cu = (int) Math.floor(u / 0.1f), cv = (int) Math.floor(v / 0.1f);
            int h = nz(cu, cv, patSeed * 7 + patAxis);
            if (h < 40) {
                float ox = (cu + 0.5f) * 0.1f + (nz(cu, cv, patSeed + 11) - 128) / 4000f;
                float oy = (cv + 0.5f) * 0.1f + (nz(cu, cv, patSeed + 13) - 128) / 4000f;
                float dx = u - ox, dy = v - oy;
                boolean round = (h & 1) == 0;
                float r = 0.032f + (h & 6) * 0.003f;
                float d = round ? (float) Math.sqrt(dx * dx + dy * dy) : Math.max(Math.abs(dx) * 0.8f, Math.abs(dy) * 1.2f);
                if (d < r) {
                    int k = (h >> 3) % STICKERS.length;
                    if (d > r * 0.62f) return STICKERS[k];
                    return d < r * 0.3f ? 0xFFF4EEE2 : STICKERS[(k + 3) % STICKERS.length];
                }
            }
            // the grain of old leather
            int g = nz((int) Math.floor(u * 40f), (int) Math.floor(v * 40f), patSeed);
            return Backdrop.scale(c, 0.94f + g / 2300f);
        }
        // a loud shirt: big pale flowers and leaves on a bright ground
        int c = patBase;
        int cu = (int) Math.floor(u / 0.07f), cv = (int) Math.floor(v / 0.07f);
        int h = nz(cu, cv, patSeed);
        float dx = u - (cu + 0.5f) * 0.07f, dy = v - (cv + 0.5f) * 0.07f;
        float d = dx * dx + dy * dy;
        if (h < 110 && d < 0.0006f) return d < 0.00012f ? 0xFFF2C230 : 0xFFF8F1E2;
        if (h > 200 && d < 0.0004f) return 0xFF2F7A4A;
        return c;
    }

    static final int[] STICKERS = {0xFFE8483C, 0xFFF2C230, 0xFF3FA6A0, 0xFF3F6FB8, 0xFFE87AB0, 0xFF5DBE6A, 0xFFF4EEE2};

    /** Brightness multiplier in 1/256 for a surface pattern at (u, v). */
    private static int shade(int pat, float u, float v) {
        switch (pat) {
            case PAT_GRAIN: {
                int i = (int) (u * 90f + NOISE[((int) (v * 7f)) & 1023] * 0.05f);
                int n = NOISE[i & 1023];
                return 240 + (n >> 3) - ((n & 31) == 0 ? 26 : 0);
            }
            case PAT_STONE: {
                int i = ((int) (u * 24f) * 31 + (int) (v * 24f) * 17) & 1023;
                int j = ((int) (u * 5f) * 13 + (int) (v * 5f) * 29) & 1023;
                int n = NOISE[i];
                int m = 206 + (n >> 2) + (NOISE[j] >> 3) - 16;
                if (n < 10) m = 150;
                return m;
            }
            case PAT_WINDOWS: {
                float fu = u - (float) Math.floor(u), fv = v - (float) Math.floor(v);
                boolean win = fu > 0.28f && fu < 0.72f && fv > 0.3f && fv < 0.78f;
                if (!win) return 256;
                int i = ((int) Math.floor(u) * 13 + (int) Math.floor(v) * 7) & 1023;
                return 150 + (NOISE[i] >> 3);
            }
            case PAT_BRICK: {
                // courses of brick in running bond, dark mortar between them, each brick its own shade
                float row = v / 0.11f;
                int ri = (int) Math.floor(row);
                float col = u / 0.24f + ((ri & 1) == 0 ? 0f : 0.5f);
                int ci = (int) Math.floor(col);
                float fr = row - ri, fc = col - ci;
                if (fr < 0.16f || fc < 0.07f) return 150;
                return 214 + (NOISE[(ri * 31 + ci * 17) & 1023] >> 3);
            }
            case PAT_BANDS: {
                float fu = u - (float) Math.floor(u);
                return fu < 0.18f ? 150 : 256;
            }
            default:
                return 256;
        }
    }

    /** A screen disc at a given depth, for sprites and particles. */
    private final float[] ballK = new float[3], ballN = new float[3], ballP = new float[2];

    // the three finger holes, as directions in the ball's own frame
    private static final float[][] HOLES = {{0f, 0.96f, 0.28f}, {-0.24f, 0.95f, -0.2f}, {0.24f, 0.95f, -0.2f}};

    /**
     * A polished ball: shaded per pixel as a true sphere, with a marbled swirl and three
     * finger holes that turn with it, and a highlight of the low sun on its gloss.
     */
    void ball(float x, float y, float z, float r, float r00, float r01, float r02, float r10, float r11, float r12,
              float r20, float r21, float r22, int color, int color2, int id) {
        ball(x, y, z, r, r00, r01, r02, r10, r11, r12, r20, r21, r22, color, color2, id, false);
    }

    /** The same, or a rubber balloon: plain, no holes, a broader shine. */
    void ball(float x, float y, float z, float r, float r00, float r01, float r02, float r10, float r11, float r12,
              float r20, float r21, float r22, int color, int color2, int id, boolean rubber) {
        float vz = project(x, y, z, ballP);
        if (vz < 0.3f) return;
        float sx = ballP[0], sy = ballP[1], sr = r * f / vz;
        int x0 = Math.max(0, (int) Math.floor(sx - sr)), x1 = Math.min(w - 1, (int) Math.ceil(sx + sr));
        int y0 = Math.max(0, (int) Math.floor(sy - sr)), y1 = Math.min(h - 1, (int) Math.ceil(sy + sr));
        float slx = sunX(), sly = sunY(), slz = sunZ();
        // toward the viewer, for the gloss: the half way between it and the sun
        viewToWorld(0f, 0f, -1f, ballN);
        float hx = slx + ballN[0], hy = sly + ballN[1], hz = slz + ballN[2];
        float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
        hx /= hl;
        hy /= hl;
        hz /= hl;
        for (int py = y0; py <= y1; py++) {
            float v = (py + 0.5f - sy) / sr;
            for (int px = x0; px <= x1; px++) {
                float u = (px + 0.5f - sx) / sr;
                float d2 = u * u + v * v;
                if (d2 > 1f) continue;
                float n3 = (float) Math.sqrt(1f - d2);
                float q = 1f / (vz - n3 * r);
                int k = py * w + px;
                if (q <= zb[k]) continue;
                viewToWorld(u, -v, -n3, ballN);
                float nx = ballN[0], ny = ballN[1], nz = ballN[2];
                // the same direction in the ball's own frame: holes and swirl turn with it
                float lx = r00 * nx + r10 * ny + r20 * nz;
                float ly = r01 * nx + r11 * ny + r21 * nz;
                float lz = r02 * nx + r12 * ny + r22 * nz;
                boolean hole = false;
                for (int i = 0; i < HOLES.length && !rubber; i++) {
                    float[] hd = HOLES[i];
                    if (lx * hd[0] + ly * hd[1] + lz * hd[2] > 0.988f) hole = true;
                }
                float swirl = (float) Math.sin(lx * 7f + (float) Math.sin(ly * 5f + lz * 3f) * 2.2f);
                int base = hole ? 0xFF14121A : !rubber && swirl > 0.25f ? color2 : color;
                float ndl = nx * slx + ny * sly + nz * slz;
                lightK(-n3, ny, ndl, ballK);
                int cr = (int) (((base >> 16) & 0xFF) * ballK[0]);
                int cg = (int) (((base >> 8) & 0xFF) * ballK[1]);
                int cb = (int) ((base & 0xFF) * ballK[2]);
                if (!hole) {
                    float sp = Math.max(0f, nx * hx + ny * hy + nz * hz);
                    sp = sp * sp;
                    sp = sp * sp;
                    sp = sp * sp;
                    if (!rubber) sp = sp * sp;
                    int g = (int) (sp * (rubber ? 170f : 230f));
                    cr += g;
                    cg += g * 0.92f;
                    cb += g * 0.8f;
                }
                col[k] = 0xFF000000 | (Math.min(255, cr) << 16) | (Math.min(255, cg) << 8) | Math.min(255, cb);
                zb[k] = q;
                idb[k] = id;
                lit[k] = (byte) (ndl > 0.02f ? 1 : 0);
            }
        }
    }

    /** A ball in the shadow map: two boxes turned against each other make a round enough shade. */
    void shadowBall(float x, float y, float z, float r) {
        float k = r * 0.8f, c = 0.70710677f;
        shadowBox(x, y, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, k, k, k);
        shadowBox(x, y, z, c, 0, c, 0, 1, 0, -c, 0, c, k, k * 0.9f, k);
    }

    void disc(float px, float py, float r, float invz, int color, int id) {
        int x0 = Math.max(0, (int) Math.floor(px - r)), x1 = Math.min(w - 1, (int) Math.ceil(px + r));
        int y0 = Math.max(0, (int) Math.floor(py - r)), y1 = Math.min(h - 1, (int) Math.ceil(py + r));
        float rr = r * r + 0.25f;
        for (int y = y0; y <= y1; y++) {
            float dy = y + 0.5f - py;
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - px;
                if (dx * dx + dy * dy > rr) continue;
                int k = y * w + x;
                if (invz <= zb[k]) continue;
                zb[k] = invz;
                col[k] = color;
                idb[k] = id;
                lit[k] = 0;
            }
        }
    }

    /** Blends a translucent disc over whatever is nearer than nothing; alpha 0..255. */
    void haze(float px, float py, float r, float invz, int color, int alpha) {
        int x0 = Math.max(0, (int) Math.floor(px - r)), x1 = Math.min(w - 1, (int) Math.ceil(px + r));
        int y0 = Math.max(0, (int) Math.floor(py - r)), y1 = Math.min(h - 1, (int) Math.ceil(py + r));
        float rr = r * r;
        int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
        for (int y = y0; y <= y1; y++) {
            float dy = y + 0.5f - py;
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - px;
                float d = dx * dx + dy * dy;
                if (d > rr) continue;
                int k = y * w + x;
                if (invz < zb[k]) continue;
                int a = (int) (alpha * (1f - d / rr));
                int c = col[k];
                int r2 = ((c >> 16) & 0xFF) + (((cr - ((c >> 16) & 0xFF)) * a) >> 8);
                int g2 = ((c >> 8) & 0xFF) + (((cg - ((c >> 8) & 0xFF)) * a) >> 8);
                int b2 = (c & 0xFF) + (((cb - (c & 0xFF)) * a) >> 8);
                col[k] = 0xFF000000 | (r2 << 16) | (g2 << 8) | b2;
            }
        }
    }

    // ---- boxes ----

    private final float[] bx = new float[4], by = new float[4], bz = new float[4], bu = new float[4], bv = new float[4];
    private final float[] cor = new float[24];
    private final float[] loc3 = new float[3], rw = new float[3];

    /** Face table: four corner indices and the face's local normal axis/sign. */
    private static final int[][] FACES = {
            {1, 3, 7, 5}, {0, 4, 6, 2},   // +x, -x
            {2, 6, 7, 3}, {0, 1, 5, 4},   // +y, -y
            {4, 5, 7, 6}, {0, 2, 3, 1},   // +z, -z
    };

    /**
     * Draws an oriented box. Rotation is row-major (world = R * local). Colors are
     * lit per face from the sun and the sky; the pattern scale follows the box size.
     */
    void box(float px, float py, float pz, float r00, float r01, float r02, float r10, float r11, float r12,
             float r20, float r21, float r22, float hx, float hy, float hz, int color, int pat, int id) {
        for (int i = 0; i < 8; i++) {
            float lx = (i & 1) == 0 ? -hx : hx, ly = (i & 2) == 0 ? -hy : hy, lz = (i & 4) == 0 ? -hz : hz;
            cor[3 * i] = px + r00 * lx + r01 * ly + r02 * lz;
            cor[3 * i + 1] = py + r10 * lx + r11 * ly + r12 * lz;
            cor[3 * i + 2] = pz + r20 * lx + r21 * ly + r22 * lz;
        }
        // the grain runs along the longest side
        int longAxis = hx >= hy && hx >= hz ? 0 : hy >= hz ? 1 : 2;
        for (int fi = 0; fi < 6; fi++) {
            int axis = fi >> 1;
            float sg = (fi & 1) == 0 ? 1f : -1f;
            float nx, ny, nz;
            if (axis == 0) {
                nx = r00 * sg;
                ny = r10 * sg;
                nz = r20 * sg;
            } else if (axis == 1) {
                nx = r01 * sg;
                ny = r11 * sg;
                nz = r21 * sg;
            } else {
                nx = r02 * sg;
                ny = r12 * sg;
                nz = r22 * sg;
            }
            int[] fc = FACES[fi];
            float fx = cor[3 * fc[0]], fy = cor[3 * fc[0] + 1], fz = cor[3 * fc[0] + 2];
            if ((fx - camX) * nx + (fy - camY) * ny + (fz - camZ) * nz >= 0f) continue; // facing away
            float ndl = nx * sunX() + ny * sunY() + nz * sunZ();
            int c = flat ? color : light(color, nx, ny, nz, ndl);
            if (pat == PAT_FACADE) {
                lightK(nz, ny, ndl, fk);
                facadeBase = color;
            }
            if (pat >= PAT_TICK) {
                if (flat) fk[0] = fk[1] = fk[2] = 1f;
                else lightK(nz, ny, ndl, fk);
                patBase = color;
                patAxis = axis;
                patU = axis == 0 ? hz : hx;
                patV = axis == 1 ? hz : hy;
            }
            for (int k = 0; k < 4; k++) {
                int ci = fc[k];
                bx[k] = cor[3 * ci];
                by[k] = cor[3 * ci + 1];
                bz[k] = cor[3 * ci + 2];
                float lx = (ci & 1) == 0 ? -hx : hx, ly = (ci & 2) == 0 ? -hy : hy, lz = (ci & 4) == 0 ? -hz : hz;
                float[] loc = loc3;
                loc[0] = lx;
                loc[1] = ly;
                loc[2] = lz;
                if (axis == longAxis) {
                    // end grain
                    bu[k] = loc[(axis + 1) % 3] * 0.5f + loc[(axis + 2) % 3] * 0.3f;
                    bv[k] = loc[(axis + 2) % 3];
                } else {
                    bu[k] = loc[3 - axis - longAxis];
                    bv[k] = loc[longAxis];
                }
                if (pat == PAT_FACADE) {
                    bu[k] = axis == 0 ? lz + hz : lx + hx;
                    bv[k] = ly + hy;
                }
                if (pat == PAT_STONE || pat == PAT_BANDS) {
                    bu[k] = axis == 0 ? lz + ly * 0.3f : axis == 1 ? lx + lz * 0.3f : lx + ly * 0.3f;
                    bv[k] = axis == 1 ? lz : ly;
                }
                if (pat == PAT_BRICK || pat >= PAT_TICK) {
                    bu[k] = axis == 0 ? lz : lx;
                    bv[k] = axis == 1 ? lz : ly;
                }
            }
            poly(bx, by, bz, bu, bv, 4, c, pat, id, ndl > 0.3f);
        }
    }

    /** Sun and sky light on a base color for a face normal. */
    static int light(int color, float nx, float ny, float nz, float ndl) {
        float[] k = KL.get();
        lightK(nz, ny, ndl, k);
        float rr = k[0], gg = k[1], b = k[2];
        int r = (int) (((color >> 16) & 0xFF) * rr), g = (int) (((color >> 8) & 0xFF) * gg), bl = (int) ((color & 0xFF) * b);
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl);
    }

    private static final ThreadLocal<float[]> KL = new ThreadLocal<float[]>() {
        @Override
        protected float[] initialValue() {
            return new float[3];
        }
    };

    /** Light on a surface as three channel factors: blue sky from above, a warm low sun. */
    static void lightK(float ny, float ndl, float[] out) {
        lightK(0f, ny, ndl, out);
    }

    /**
     * The same, with a warm fill on faces turned toward the viewer, as the evening
     * light bounces back off the street and the houses.
     */
    static void lightK(float nz, float ny, float ndl, float[] out) {
        float fill = Math.max(0f, -nz) * (1f - Math.abs(ny)) * (overcast() ? 0.1f : 0.42f);
        lightK0(ny, ndl, out);
        out[0] += fill * 1.0f;
        out[1] += fill * 0.78f;
        out[2] += fill * 0.55f;
    }

    // under a heavy overcast: light from the whole sky, cold and grey, the sun only a pale glow.
    // Kept per thread: a view painted in the background keeps its own light
    private static final ThreadLocal<boolean[]> OVERCAST = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    static void overcast(boolean on) {
        OVERCAST.get()[0] = on;
    }

    static boolean overcast() {
        return OVERCAST.get()[0];
    }

    private static void lightK0(float ny, float ndl, float[] out) {
        float sun = Math.max(0f, ndl);
        float sky = 0.5f + 0.5f * ny;
        if (overcast()) {
            out[0] = 0.30f + 0.26f * sky + 0.62f * sun;
            out[1] = 0.33f + 0.27f * sky + 0.60f * sun;
            out[2] = 0.41f + 0.30f * sky + 0.55f * sun;
            return;
        }
        out[0] = 0.27f + 0.10f * sky + 1.08f * sun;
        out[1] = 0.30f + 0.11f * sky + 0.84f * sun;
        out[2] = 0.40f + 0.16f * sky + 0.60f * sun;
    }

    // multiply for what the sun does not reach: dark and a little blue
    static final int SH_R = 112, SH_G = 122, SH_B = 162;

    // ---- shadows ----

    void shadowBegin(float centerX, float centerZ, float half) {
        smCx = centerX;
        smCz = centerZ;
        smHalf = half;
        java.util.Arrays.fill(sm, -1e9f);
    }

    private float smS(float x, float y, float z) {
        return ((x - smCx) * e1x + y * e1y + (z - smCz) * e1z) / (2f * smHalf) * SM + SM * 0.5f;
    }

    private float smT(float x, float y, float z) {
        return ((x - smCx) * e2x + y * e2y + (z - smCz) * e2z) / (2f * smHalf) * SM + SM * 0.5f;
    }

    private float smD(float x, float y, float z) {
        return (x * sunX() + y * sunY() + z * sunZ());
    }

    private final float[] ts = new float[3], tt = new float[3], td = new float[3];

    /** Adds an oriented box to the shadow map. */
    void shadowBox(float px, float py, float pz, float r00, float r01, float r02, float r10, float r11, float r12,
                   float r20, float r21, float r22, float hx, float hy, float hz) {
        for (int i = 0; i < 8; i++) {
            float lx = (i & 1) == 0 ? -hx : hx, ly = (i & 2) == 0 ? -hy : hy, lz = (i & 4) == 0 ? -hz : hz;
            cor[3 * i] = px + r00 * lx + r01 * ly + r02 * lz;
            cor[3 * i + 1] = py + r10 * lx + r11 * ly + r12 * lz;
            cor[3 * i + 2] = pz + r20 * lx + r21 * ly + r22 * lz;
        }
        for (int fi = 0; fi < 6; fi++) {
            int[] fc = FACES[fi];
            for (int t = 1; t < 3; t++) {
                for (int k = 0; k < 3; k++) {
                    int ci = k == 0 ? fc[0] : fc[t + k - 1];
                    float x = cor[3 * ci], y = cor[3 * ci + 1], z = cor[3 * ci + 2];
                    ts[k] = smS(x, y, z);
                    tt[k] = smT(x, y, z);
                    td[k] = smD(x, y, z);
                }
                smTri();
            }
        }
    }

    private void smTri() {
        float x0 = ts[0], y0 = tt[0], x1 = ts[1], y1 = tt[1], x2 = ts[2], y2 = tt[2];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minx = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxx = Math.min(SM - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int miny = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxy = Math.min(SM - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        float ia = 1f / area;
        for (int py = miny; py <= maxy; py++)
            for (int px = minx; px <= maxx; px++) {
                float pxc = px + 0.5f, pyc = py + 0.5f;
                float w0 = ((x1 - pxc) * (y2 - pyc) - (x2 - pxc) * (y1 - pyc)) * ia;
                float w1 = ((x2 - pxc) * (y0 - pyc) - (x0 - pxc) * (y2 - pyc)) * ia;
                float w2 = 1f - w0 - w1;
                if (w0 < -0.02f || w1 < -0.02f || w2 < -0.02f) continue;
                float d = w0 * td[0] + w1 * td[1] + w2 * td[2];
                int k = py * SM + px;
                if (d > sm[k]) sm[k] = d;
            }
    }

    /** Darkens sunlit pixels that the shadow map says are blocked from the sun. */
    void shadowApply(int sx0, int sy0, int sx1, int sy1) {
        sx0 = Math.max(0, sx0);
        sy0 = Math.max(0, sy0);
        sx1 = Math.min(w - 1, sx1);
        sy1 = Math.min(h - 1, sy1);
        float bias = 0.12f;
        for (int y = sy0; y <= sy1; y++) {
            for (int x = sx0; x <= sx1; x++) {
                int k = y * w + x;
                if (lit[k] != 1) continue;
                float q = zb[k];
                if (q <= 0f) continue;
                float vz = 1f / q;
                float vx2 = (x + 0.5f - cx) * vz / f, vy2 = (cy - y - 0.5f) * vz / f;
                viewToWorld(vx2, vy2, vz, rw);
                float wx = camX + rw[0], wy = camY + rw[1], wz = camZ + rw[2];
                int s = (int) smS(wx, wy, wz), t = (int) smT(wx, wy, wz);
                if (s < 0 || t < 0 || s >= SM || t >= SM) continue;
                if (sm[t * SM + s] > smD(wx, wy, wz) + bias) {
                    int c = col[k];
                    boolean oc = overcast();
                    int sr = oc ? 150 : SH_R, sg = oc ? 160 : SH_G, sb = oc ? 186 : SH_B;
                    int r = ((c >> 16) & 0xFF) * sr >> 8, g = ((c >> 8) & 0xFF) * sg >> 8, b = (c & 0xFF) * sb >> 8;
                    col[k] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
    }

    private final float[] sd = new float[3];

    /**
     * A soft shadow on the ground: a capsule from (ax, az) to (bx, bz), radius ra at the
     * first end and rb at the other, its edge blurred and its tail fading. Only ground
     * the sun still reaches, and nothing standing in front, takes it.
     */
    boolean anyLight;                    // soft shadows darken shaded ground too: contact shadows

    void softShadow(float ax, float az, float bx, float bz, float ra, float rb, float strength) {
        float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
        float rm = Math.max(ra, rb);
        for (int i = 0; i < 8; i++) {
            float x = ((i & 4) == 0 ? ax : bx) + ((i & 1) == 0 ? -rm : rm);
            float z = ((i & 4) == 0 ? az : bz) + ((i & 2) == 0 ? -rm : rm);
            float v = depth(x, 0f, z);
            if (v < 0.3f) return;
            project(x, 0f, z, rw);
            minX = Math.min(minX, rw[0]);
            maxX = Math.max(maxX, rw[0]);
            minY = Math.min(minY, rw[1]);
            maxY = Math.max(maxY, rw[1]);
        }
        int x0 = Math.max(0, (int) minX), x1 = Math.min(w - 1, (int) maxX + 1);
        int y0 = Math.max(0, (int) minY), y1 = Math.min(h - 1, (int) maxY + 1);
        float ex = bx - ax, ez = bz - az, el2 = Math.max(1e-6f, ex * ex + ez * ez);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int k = y * w + x;
                if (lit[k] != 1 && !(anyLight && lit[k] != 3)) continue;
                viewToWorld((x + 0.5f - cx) / f, (cy - y - 0.5f) / f, 1f, sd);
                if (sd[1] >= -1e-3f) continue;
                float t = -camY / sd[1];
                if (Math.abs(zb[k] * t - 1f) > 0.02f) continue;     // not the ground here
                float gxw = camX + sd[0] * t - ax, gzw = camZ + sd[2] * t - az;
                float u = Math.max(0f, Math.min(1f, (gxw * ex + gzw * ez) / el2));
                float dx = gxw - ex * u, dz = gzw - ez * u;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                float r = ra + (rb - ra) * u;
                if (dist >= r) continue;
                float edge = (r - dist) / (r * 0.65f);
                if (edge > 1f) edge = 1f;
                edge = edge * edge * (3f - 2f * edge);
                float a = strength * edge * (1f - 0.55f * u);
                int c = col[k];
                int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
                int sr = cr * SH_R >> 8, sg = cg * SH_G >> 8, sb = cb * SH_B >> 8;
                col[k] = 0xFF000000 | ((int) (cr + (sr - cr) * a) << 16) | ((int) (cg + (sg - cg) * a) << 8)
                        | (int) (cb + (sb - cb) * a);
                if (anyLight) lit[k] = 3;
                else if (a > 0.55f) lit[k] = 2;
            }
    }

    /**
     * A puddle on a level surface at height py: a wet, darker patch with a ragged edge
     * that mirrors a little of the sky. Only where that surface is what the eye sees.
     */
    void puddle(float px, float py, float pz, float rad, float strength, int seed) {
        float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
        float rm = rad * 1.3f;
        for (int i = 0; i < 4; i++) {
            float x = px + ((i & 1) == 0 ? -rm : rm), z = pz + ((i & 2) == 0 ? -rm : rm);
            if (depth(x, py, z) < 0.3f) return;
            project(x, py, z, rw);
            minX = Math.min(minX, rw[0]);
            maxX = Math.max(maxX, rw[0]);
            minY = Math.min(minY, rw[1]);
            maxY = Math.max(maxY, rw[1]);
        }
        int x0 = Math.max(0, (int) minX), x1 = Math.min(w - 1, (int) maxX + 1);
        int y0 = Math.max(0, (int) minY), y1 = Math.min(h - 1, (int) maxY + 1);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int k = y * w + x;
                viewToWorld((x + 0.5f - cx) / f, (cy - y - 0.5f) / f, 1f, sd);
                if (Math.abs(sd[1]) < 1e-4f) continue;
                float t = (py - camY) / sd[1];
                if (t <= 0f || Math.abs(zb[k] * t - 1f) > 0.02f) continue;
                float dx = camX + sd[0] * t - px, dz = camZ + sd[2] * t - pz;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                float ang = (float) Math.atan2(dz, dx);
                float edge = rad * (0.82f + 0.12f * (float) Math.sin(ang * 3f + seed) + 0.08f * (float) Math.sin(ang * 7f + seed * 2.3f));
                if (d > edge) continue;
                float a = strength * Math.min(1f, (edge - d) / (rad * 0.12f));
                int c = col[k];
                int wet = Backdrop.scale(c, 0.6f);
                wet = Backdrop.mix(wet, 0xFF9FC2E2, 0.28f);
                if (Backdrop.hash((int) (dx * 9f) + seed, (int) (dz * 9f)) > 250) wet = 0xFFF4F8FF;    // a glint
                col[k] = Backdrop.mix(c, wet, a);
            }
    }

    /**
     * Water running down a wall that faces the street (the plane z = zc): a wet band
     * from yTop downward, ragged where the drips end.
     */
    void wetWall(float xc, float zc, float yTop, float halfW, float len, float strength, int seed) {
        float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
        for (int i = 0; i < 4; i++) {
            float x = xc + ((i & 1) == 0 ? -halfW : halfW), y = (i & 2) == 0 ? yTop : yTop - len;
            if (depth(x, y, zc) < 0.3f) return;
            project(x, y, zc, rw);
            minX = Math.min(minX, rw[0]);
            maxX = Math.max(maxX, rw[0]);
            minY = Math.min(minY, rw[1]);
            maxY = Math.max(maxY, rw[1]);
        }
        int x0 = Math.max(0, (int) minX), x1 = Math.min(w - 1, (int) maxX + 1);
        int y0 = Math.max(0, (int) minY), y1 = Math.min(h - 1, (int) maxY + 1);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int k = y * w + x;
                viewToWorld((x + 0.5f - cx) / f, (cy - y - 0.5f) / f, 1f, sd);
                if (Math.abs(sd[2]) < 1e-4f) continue;
                float t = (zc - camZ) / sd[2];
                if (t <= 0f || Math.abs(zb[k] * t - 1f) > 0.02f) continue;
                float wxp = camX + sd[0] * t - xc, wyp = camY + sd[1] * t;
                float u = wxp / halfW;
                if (u < -1f || u > 1f || wyp > yTop) continue;
                // drips: each narrow column runs down its own length
                int col2 = (int) Math.floor((wxp + 40f) * 7f);
                float reach = len * (0.35f + 0.65f * (((col2 * 73 + seed * 31) & 255) / 255f)) * (1f - u * u * 0.7f);
                float down = yTop - wyp;
                if (down > reach) continue;
                float a = strength * (1f - down / reach * 0.5f);
                col[k] = Backdrop.mix(col[k], Backdrop.scale(col[k], 0.62f), a);
            }
    }

    private final float[] gx = new float[8], gy = new float[8], gz = new float[8];

    /**
     * Darkens the ground under a polygon given on the ground plane (world x and z),
     * wherever nothing nearer stands in front. Each pixel is darkened once a frame.
     */
    void groundShade(float[] px, float[] pz, int n) {
        for (int i = 0; i < n; i++) {
            float ex = px[i] - camX, dy = 0.004f - camY, ez = pz[i] - camZ;
            float dx = ex * cyw - ez * syw, dz = ex * syw + ez * cyw;
            float vz2 = dz * cp + dy * sp;
            float vy2 = dy * cp - dz * sp;
            if (vz2 < 0.3f) return;
            float q = 1f / vz2;
            gx[i] = cx + f * dx * q;
            gy[i] = cy - f * vy2 * q;
            gz[i] = q;
        }
        for (int t = 1; t < n - 1; t++) shadeTri(0, t, t + 1);
    }

    private void shadeTri(int a, int b, int c) {
        float x0 = gx[a], y0 = gy[a], x1 = gx[b], y1 = gy[b], x2 = gx[c], y2 = gy[c];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minx = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxx = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int miny = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxy = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        float ia = 1f / area;
        for (int py = miny; py <= maxy; py++)
            for (int px = minx; px <= maxx; px++) {
                float pxc = px + 0.5f, pyc = py + 0.5f;
                float w0 = ((x1 - pxc) * (y2 - pyc) - (x2 - pxc) * (y1 - pyc)) * ia;
                float w1 = ((x2 - pxc) * (y0 - pyc) - (x0 - pxc) * (y2 - pyc)) * ia;
                float w2 = 1f - w0 - w1;
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue;
                int k = py * w + px;
                if (lit[k] != 1) continue;
                float z = w0 * gz[a] + w1 * gz[b] + w2 * gz[c];
                if (zb[k] > z * 1.01f + 1e-5f) continue;   // something nearer stands in front
                int col0 = col[k];
                int r = ((col0 >> 16) & 0xFF) * SH_R >> 8, g = ((col0 >> 8) & 0xFF) * SH_G >> 8, bl = (col0 & 0xFF) * SH_B >> 8;
                col[k] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                lit[k] = 2;
            }
    }

    /** Dark lines where one object meets another or the backdrop. */
    void outlines(int sx0, int sy0, int sx1, int sy1, int minId) {
        sx0 = Math.max(0, sx0);
        sy0 = Math.max(0, sy0);
        sx1 = Math.min(w - 2, sx1);
        sy1 = Math.min(h - 2, sy1);
        for (int y = sy0; y <= sy1; y++)
            for (int x = sx0; x <= sx1; x++) {
                int k = y * w + x;
                int a = idb[k] >> 3;
                int r = idb[k + 1] >> 3, d = idb[k + w] >> 3;
                if (a == r && a == d) continue;
                // darken the nearer side of the edge
                int t = k;
                if (a != r && zb[k + 1] > zb[t]) t = k + 1;
                if (a != d && zb[k + w] > zb[t]) t = k + w;
                if ((idb[t] >> 3) < minId) continue;
                int c = col[t];
                col[t] = 0xFF000000 | ((((c >> 16) & 0xFF) * 170 >> 8) << 16) | ((((c >> 8) & 0xFF) * 160 >> 8) << 8)
                        | ((c & 0xFF) * 150 >> 8);
            }
    }
}
