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
    boolean flat;                        // boxes take their color as it is: silhouettes                 // yaw: forward is (syw, 0, cyw)

    // sun: direction toward it, and the shadow map basis
    static final float LX = -0.42f, LY = 0.80f, LZ = -0.43f;
    private float e1x, e1y, e1z, e2x, e2y, e2z;
    static final int SM = 224;
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

    static final int PAT_FLAT = 0, PAT_GRAIN = 1, PAT_WINDOWS = 2, PAT_STONE = 3, PAT_BANDS = 4;

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
                int m = shade(pat, uu, vv2);
                int r = cr * m >> 8, g = cg * m >> 8, bl = cb * m >> 8;
                if (r > 255) r = 255;
                if (g > 255) g = 255;
                if (bl > 255) bl = 255;
                col[k] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
    }

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
                return 238 + (NOISE[i] >> 3);
            }
            case PAT_WINDOWS: {
                float fu = u - (float) Math.floor(u), fv = v - (float) Math.floor(v);
                boolean win = fu > 0.28f && fu < 0.72f && fv > 0.3f && fv < 0.78f;
                if (!win) return 256;
                int i = ((int) Math.floor(u) * 13 + (int) Math.floor(v) * 7) & 1023;
                return 150 + (NOISE[i] >> 3);
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
                if (pat == PAT_STONE || pat == PAT_BANDS) {
                    bu[k] = axis == 0 ? lz + ly * 0.3f : axis == 1 ? lx + lz * 0.3f : lx + ly * 0.3f;
                    bv[k] = axis == 1 ? lz : ly;
                }
            }
            poly(bx, by, bz, bu, bv, 4, c, pat, id, ndl > 0.02f);
        }
    }

    /** Sun and sky light on a base color for a face normal. */
    static int light(int color, float nx, float ny, float nz, float ndl) {
        float sun = Math.max(0f, ndl);
        float sky = 0.5f + 0.5f * ny;
        float rr = 0.36f + 0.14f * sky + 0.62f * sun;
        float gg = 0.37f + 0.15f * sky + 0.58f * sun;
        float b = 0.42f + 0.18f * sky + 0.50f * sun;
        int r = (int) (((color >> 16) & 0xFF) * rr), g = (int) (((color >> 8) & 0xFF) * gg), bl = (int) ((color & 0xFF) * b);
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl);
    }

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
                    int r = ((c >> 16) & 0xFF) * 150 >> 8, g = ((c >> 8) & 0xFF) * 160 >> 8, b = (c & 0xFF) * 196 >> 8;
                    col[k] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
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
                int r = ((col0 >> 16) & 0xFF) * 168 >> 8, g = ((col0 >> 8) & 0xFF) * 176 >> 8, bl = (col0 & 0xFF) * 205 >> 8;
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
