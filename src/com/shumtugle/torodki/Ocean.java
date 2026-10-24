package com.shumtugle.torodki;

import java.util.Random;

/**
 * The winter ocean beyond the portal. A low heavy sky, neither night nor day; fog that
 * swallows the horizon and drifts across it in banks; a grey sea running in long swells,
 * whitecaps breaking on them. The player stands on an ice floe that rocks; the building
 * stands on a block of ice on another floe that rises and sinks on the swell. Sailing
 * boats ride at anchor on both sides, rolling. Snow drives across slantwise, now and then
 * wound up into a whirl.
 *
 * The sky and the player's floe are painted once per camera; the water is shaded again
 * every frame from what was found out about each of its pixels, so the swell runs. The
 * target floe, the boats, the buoy and the snow are drawn every frame.
 */
final class Ocean {
    static final int FOG = 0xFFB4BEC6;
    static final float FOG_M = 85f;            // meters for the fog to take two thirds of a color
    static final int ICE = 0xFFDCE7ED, ICE_RIM = 0xFF8DAEC2, WATER = 0xFF41586A, WATER_DEEP = 0xFF263848, CREST = 0xFF6C8798;
    static final float FLOE_X = World.CAM_X + 0.4f, FLOE_Z = 1.6f, FLOE_RX = 5.0f, FLOE_RZ = 5.2f;

    /** What is known about the water seen through one camera: a list of its pixels. */
    static final class Mask {
        int n;
        int[] k = new int[0];
        float[] x = new float[0], z = new float[0], ph = new float[0];
        byte[] fog = new byte[0], cell = new byte[0];

        void size(int len) {
            if (k.length == len) return;
            k = new int[len];
            x = new float[len];
            z = new float[len];
            ph = new float[len];
            fog = new byte[len];
            cell = new byte[len];
        }
    }

    private final float[] d = new float[3], s = new float[2];

    /** How far a point at this distance is lost in fog, 0 clear to 1 gone. */
    static float fog(float t) {
        return 1f - (float) Math.exp(-t / FOG_M);
    }

    /** The edge of a ragged floe, as a share of its radii, in a direction. */
    private static float rag(float a, float seed) {
        return 1f + 0.11f * (float) Math.sin(a * 3f + seed) + 0.07f * (float) Math.sin(a * 7f + seed * 2.3f)
                + 0.035f * (float) Math.sin(a * 17f + seed * 5.1f);
    }

    /** On the player's own floe: a ragged oval of ice round the feet. */
    static boolean onFloe(float x, float z) {
        float dx = (x - FLOE_X) / FLOE_RX, dz = (z - FLOE_Z) / FLOE_RZ;
        return dx * dx + dz * dz < sq(rag((float) Math.atan2(dz, dx), 0.7f));
    }

    private static float sq(float v) {
        return v * v;
    }

    // ---- painted once ----

    void paint(Raster r, Mask m) {
        java.util.Arrays.fill(r.zb, 0f);
        java.util.Arrays.fill(r.idb, 0);
        java.util.Arrays.fill(r.lit, (byte) 0);
        m.size(r.w * r.h);
        m.n = 0;
        for (int y = 0; y < r.h; y++)
            for (int x = 0; x < r.w; x++) {
                r.ray(x + 0.5f, y + 0.5f, d);
                int k = y * r.w + x;
                float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                float dx = d[0] / l, dy = d[1] / l, dz = d[2] / l;
                if (dy < -0.0008f) {
                    float t = -r.camY / d[1];
                    float wx = r.camX + d[0] * t, wz = r.camZ + d[2] * t;
                    float dist = t * l;
                    float fog = fog(dist);
                    r.zb[k] = 1f / t;
                    if (onFloe(wx, wz)) {
                        r.col[k] = Backdrop.mix(ice(wx, wz, dist), FOG, fog);
                        r.lit[k] = 1;
                    } else {
                        int i = m.n++;
                        m.k[i] = k;
                        m.x[i] = wx;
                        m.z[i] = wz;
                        // the swell runs across the view, bent a little by slow noise
                        m.ph[i] = wz * 0.36f + wx * 0.13f + noise(wx * 0.05f, wz * 0.05f) * 3.5f;
                        m.fog[i] = (byte) Math.round(fog * 255f - 128f);
                        m.cell[i] = (byte) (Math.round(Math.max(0f, Math.min(1f, noise(wx * 1.8f, wz * 0.9f))) * 255f) - 128);
                        r.col[k] = Backdrop.mix(WATER, FOG, fog);
                    }
                } else {
                    r.col[k] = sky(dx, dy, dz);
                }
            }
    }

    /** A heavy sky: dark slate overhead, paler down into the fog, a dim glow where the sun is. */
    static int sky(float dx, float dy, float dz) {
        float el = Math.max(0f, dy);
        float k = (float) Math.pow(Math.min(1f, el * 2.4f), 0.55f);
        int c = Backdrop.mix(FOG, 0xFF66737F, k);
        // the sun somewhere behind the cloud, low on the left
        float h = (float) Math.sqrt(dx * dx + dz * dz);
        float toSun = h > 1e-4f ? (dx * -0.62f + dz * 0.78f) / h : 0f;
        float glow = Math.max(0f, toSun);
        glow = glow * glow * glow * glow;
        c = Backdrop.mix(c, 0xFFE6E0D4, glow * (float) Math.exp(-el * 5f) * 0.5f);
        // the undersides of the clouds, a low deck seen in perspective, heavier toward the top
        if (dy > 0.004f) {
            float t = 600f / dy;
            float u = dx * t / 420f, v = dz * t / 260f;
            float n = noise(u, v) * 0.55f + noise(u * 2.2f + 3f, v * 2.2f) * 0.3f + noise(u * 4.7f, v * 4.7f + 7f) * 0.15f;
            float near = Math.min(1f, el * 3f);
            if (n > 0.45f) c = Backdrop.mix(c, 0xFF4E5A67, Math.min(1f, (n - 0.45f) * 2.2f) * near * 0.85f);
            else if (n < 0.3f) c = Backdrop.mix(c, 0xFF8A96A2, (0.3f - n) * 2f * near);
        }
        return c;
    }

    /** The floe underfoot: old snow on ice, cracks, bluer and wet at the worn edge. */
    private static int ice(float x, float z, float t) {
        int c = ICE;
        c = Backdrop.scale(c, 0.96f + noise(x * 3f, z * 3f) * 0.06f);
        float drift = noise(x * 0.55f, z * 0.55f);
        if (drift > 0.58f) c = Backdrop.mix(c, 0xFFF4F8FA, Math.min(1f, (drift - 0.58f) * 6f));
        float cr = Math.abs(noise(x * 0.8f + 7f, z * 0.8f) - 0.5f);
        if (cr < 0.022f) c = Backdrop.mix(c, 0xFF7890A0, 0.65f);
        float dx = (x - FLOE_X) / FLOE_RX, dz = (z - FLOE_Z) / FLOE_RZ;
        float r = (float) Math.sqrt(dx * dx + dz * dz) / rag((float) Math.atan2(dz, dx), 0.7f);
        if (r > 0.9f) c = Backdrop.mix(c, ICE_RIM, Math.min(1f, (r - 0.9f) * 9f));
        return c;
    }

    static float noise(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = Backdrop.hash(xi, yi) / 255f, b = Backdrop.hash(xi + 1, yi) / 255f;
        float c = Backdrop.hash(xi, yi + 1) / 255f, e = Backdrop.hash(xi + 1, yi + 1) / 255f;
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + e) * fx * fy;
    }

    // ---- every frame ----

    private static final int[] SIN = new int[1024];
    // a strip of drifting fog, sampled with an offset that moves
    private static final int BANK_W = 256, BANK_H = 64;
    private static final byte[] BANK = new byte[BANK_W * BANK_H];

    static {
        for (int i = 0; i < 1024; i++) SIN[i] = Math.round((float) Math.sin(i * Math.PI * 2 / 1024) * 256f);
        for (int y = 0; y < BANK_H; y++)
            for (int x = 0; x < BANK_W; x++) {
                // wraps across: the noise is laid on a ring
                float a = (float) (x * Math.PI * 2 / BANK_W);
                float u = 5f + (float) Math.cos(a) * 1.3f, v = 5f + (float) Math.sin(a) * 1.3f;
                float n = noise(u + y * 0.3f, v + y * 0.2f) * 0.6f + noise(u * 2.3f + y * 0.7f, v * 2.3f) * 0.4f;
                BANK[y * BANK_W + x] = (byte) (Math.max(0f, Math.min(1f, (n - 0.42f) * 2.4f)) * 255f - 128f);
            }
    }

    private static int lerp(int a, int b, int t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        return 0xFF000000 | ((ar + ((((b >> 16) & 0xFF) - ar) * t >> 8)) << 16)
                | ((ag + ((((b >> 8) & 0xFF) - ag) * t >> 8)) << 8) | (ab + (((b & 0xFF) - ab) * t >> 8));
    }

    /**
     * The water, shaded again: the swell's light and dark running toward the player,
     * crests whitening as they pass, streaks of foam blown along by the wind.
     */
    void sea(Raster r, Mask m, float time) {
        seaCols(r, m, time, 0, r.w - 1);
    }

    /** The same, for the columns x0 to x1 of the picture only. */
    void seaCols(Raster r, Mask m, float time, int x0, int x1) {
        int[] col = r.col;
        int w = r.w;
        float run = time * 1.25f, ox = time * 1.9f, oz = time * 0.4f;
        for (int i = 0; i < m.n; i++) {
            int k = m.k[i], x = k % w;
            if (x < x0 || x > x1) continue;
            int p = (int) ((m.ph[i] + run) * 162.97f) & 1023;
            int s = SIN[p];
            int c = lerp(WATER_DEEP, WATER, (s + 256) >> 1);
            if (s > 120) c = lerp(c, CREST, (s - 120) * 2 > 255 ? 255 : (s - 120) * 2);
            int cell = m.cell[i] + 128;
            if (s > 228 && cell > 150) c = lerp(c, 0xFFE4ECEF, Math.min(255, (cell - 150) * 3 + (s - 228) * 4));
            else {
                int hh = Backdrop.hash((int) Math.floor(m.x[i] * 1.2f + ox), (int) Math.floor(m.z[i] * 0.42f + oz));
                if (hh > 246) c = lerp(c, 0xFFD8E2E6, 120);
            }
            col[k] = lerp(c, FOG, m.fog[i] + 128);
        }
    }

    /**
     * Everything drawn this frame sinks into the fog by its distance, and banks of fog drift
     * across far off. The backdrop's own pixels were fogged when painted.
     */
    void mist(Raster r, float time) {
        int[] col = r.col, idb = r.idb;
        float[] zb = r.zb;
        int w = r.w, h = r.h;
        int shift = (int) (time * 3.2f);
        // the banks lie along the horizon, thinning out above and below it
        float horizon = r.cy + r.f * r.sp / r.cp;
        for (int y = 0; y < h; y++) {
            int by = (int) ((y - horizon) * 0.35f + BANK_H * 0.62f);
            int edge = by < 0 || by >= BANK_H ? 0 : Math.min(256, Math.min(by, BANK_H - 1 - by) * 24);
            if (by < 0) by = 0;
            if (by >= BANK_H) by = BANK_H - 1;
            for (int x = 0; x < w; x++) {
                int k = y * w + x;
                float q = zb[k];
                float dist = q > 0f ? 1f / q : 1e4f;
                int a = 0;
                // things drawn this frame thin into the fog more slowly than the air itself
                if (idb[k] != 0 && q > 0f) a = (int) (fog(dist * 0.5f) * 256f);
                if (dist > 18f && edge > 0) {
                    int b = (BANK[by * BANK_W + ((x + shift) & (BANK_W - 1))] + 128) * edge >> 8;
                    float far = Math.min(1f, (dist - 18f) / 40f);
                    a = Math.max(a, (int) (b * far * 0.55f));
                }
                if (a > 0) col[k] = lerp(col[k], FOG, a > 255 ? 255 : a);
            }
        }
    }

    /** How high the swell lifts a floe at a place, now: meters. */
    static float heave(float x, float z, float time) {
        return 0.09f * (float) Math.sin(time * 1.05f + z * 0.3f) + 0.035f * (float) Math.sin(time * 2.3f + x * 0.5f);
    }

    // ---- the iceberg ----
    // The building stands on an iceberg: a flat snowy top round the plinth, cliffs of old ice
    // falling away to the water, wider at the waterline, and crags of ice standing up behind
    // and beside it. Its bulk shows green-blue under the water. It rides the swell a little.

    private static final int RIM = 24;
    // rings from the top down: height, how far out past the plinth, how much that varies
    private static final float[][] RINGS = {{0f, 0.06f, 0.08f}, {-0.14f, 0.2f, 0.14f}, {-0.55f, 0.42f, 0.4f}, {-1.15f, 0.7f, 0.5f}};
    private final float[][] ringX = new float[RINGS.length][RIM], ringZ = new float[RINGS.length][RIM];
    private float meshFor = Float.NaN, meshDepth;
    float wake;                       // the iceberg's drift across, m/s, plus to the right
    // crags: x and z off the plinth's middle, base radius, height, sides; then their points
    private static final float[][] CRAGS = {{-1.9f, 1.7f, 1.15f, 2.7f}, {0.6f, 2.0f, 1.35f, 3.6f}, {2.6f, 1.0f, 1.0f, 2.2f}, {-2.9f, 0.3f, 0.8f, 1.6f}};
    private static final int CRAG_SIDES = 6;
    private final float[][] cragX = new float[CRAGS.length][CRAG_SIDES], cragZ = new float[CRAGS.length][CRAG_SIDES];
    private final float[] apex = new float[CRAGS.length * 2];
    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];

    private void mesh(float cz) {
        // a deeper plinth, for two rows, makes a longer iceberg, and the crags stand back with it
        if (cz == meshFor && meshDepth == Builder.PHZ) return;
        meshFor = cz;
        meshDepth = Builder.PHZ;
        float back = Builder.PHZ - Builder.PHZ1;
        Random r = new Random((long) (cz * 1000f) ^ 0x1CEB3L);
        float hx = Builder.PHX, hz = Builder.PHZ;
        float per = 4f * (hx + hz);
        float[] jit = new float[RIM];
        for (int i = 0; i < RIM; i++) jit[i] = r.nextFloat();
        for (int ring = 0; ring < RINGS.length; ring++)
            for (int i = 0; i < RIM; i++) {
                // round the plinth's rectangle, then out along the way from its middle
                float t = (i + 0.5f) / RIM * per, x, z;
                if (t < 2f * hx) {
                    x = -hx + t;
                    z = -hz;
                } else if (t < 2f * hx + 2f * hz) {
                    x = hx;
                    z = -hz + (t - 2f * hx);
                } else if (t < 4f * hx + 2f * hz) {
                    x = hx - (t - 2f * hx - 2f * hz);
                    z = hz;
                } else {
                    x = -hx;
                    z = hz - (t - 4f * hx - 2f * hz);
                }
                float l = (float) Math.sqrt(x * x / (hx * hx) + z * z / (hz * hz));
                float nx = x / (hx * l), nz = z / (hz * l);
                float nl = (float) Math.sqrt(nx * nx + nz * nz);
                float out = RINGS[ring][1] + RINGS[ring][2] * (0.5f * jit[i] + 0.5f * r.nextFloat());
                ringX[ring][i] = x + nx / nl * out;
                ringZ[ring][i] = cz + z + nz / nl * out;
            }
        for (int c = 0; c < CRAGS.length; c++) {
            float bx = CRAGS[c][0], bz = cz + CRAGS[c][1] + back, rad = CRAGS[c][2];
            for (int i = 0; i < CRAG_SIDES; i++) {
                float a = (float) (i * Math.PI * 2 / CRAG_SIDES) + r.nextFloat() * 0.5f;
                float rr = rad * (0.7f + r.nextFloat() * 0.5f);
                cragX[c][i] = bx + (float) Math.cos(a) * rr;
                cragZ[c][i] = bz + (float) Math.sin(a) * rr * 0.8f;
            }
            apex[2 * c] = bx + (r.nextFloat() - 0.5f) * rad * 0.8f;
            apex[2 * c + 1] = bz + (r.nextFloat() - 0.2f) * rad * 0.5f;
        }
    }

    /** One flat face, lit by its own slope. */
    private void face(Raster r, int n, int color, int id, boolean top) {
        float ax = qx[1] - qx[0], ay = qy[1] - qy[0], az = qz[1] - qz[0];
        float bx = qx[n - 1] - qx[0], by = qy[n - 1] - qy[0], bz = qz[n - 1] - qz[0];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-6f) return;
        nx /= l;
        ny /= l;
        nz /= l;
        // turned toward the camera, whichever way the points ran
        if ((qx[0] - r.camX) * nx + (qy[0] - r.camY) * ny + (qz[0] - r.camZ) * nz > 0f) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float ndl = nx * Raster.sunX() + ny * Raster.sunY() + nz * Raster.sunZ();
        r.poly(qx, qy, qz, null, null, n, Raster.light(color, nx, ny, nz, ndl), Raster.PAT_FLAT, id, top || ny > 0.6f);
    }

    /** The iceberg under the plinth at (0, cz), its top at the plinth's top plus lift. */
    void iceberg(Raster r, float cz, float lift, int id, float time) {
        mesh(cz);
        float top = Builder.TOP + lift;
        // its bulk under the water, green-blue, and the swell foaming round its foot
        int last = RINGS.length - 1;
        for (int i = 0; i < RIM; i += 2) {
            float x = ringX[last][i] * 1.15f, z = cz + (ringZ[last][i] - cz) * 1.15f;
            float vz = r.project(x, 0f, z, s);
            if (vz < 1f) continue;
            r.haze(s[0], s[1], 1.1f * r.f / vz, 1f / (vz - 1.2f), 0xFF4E9AAA, 110);
        }
        // drifting, it leaves a trail of foam behind it, longer the faster it goes, and pushes
        // a ridge of foam ahead: which way it goes and how fast can be seen, not only guessed
        if (Math.abs(wake) > 0.04f) {
            float dir = Math.signum(wake), sp = Math.min(1f, Math.abs(wake) / World.DRIFT_V);
            float edge = Builder.PHX + 1.25f;
            for (int i = 0; i < 26; i++) {
                float u = i / 25f;
                // two streaks fanning out behind, churned foam between them
                float side = (i & 1) == 0 ? 1f : -1f;
                float x = -dir * (edge + u * 9f * sp);
                float z = cz + side * (Builder.PHZ * 0.6f + u * 1.6f) + 0.3f * (float) Math.sin(i * 2.7f + time * 0.9f);
                float vz = r.project(x, 0.03f, z, s);
                if (vz < 1f) continue;
                r.haze(s[0], s[1], (0.55f + 0.6f * u) * r.f / vz, 1f / (vz - 0.8f), 0xFFF2F6F8, (int) (235f * (1f - u * 0.85f) * sp));
            }
            // and a ridge of foam pushed up ahead, at the waterline
            for (int i = -4; i <= 4; i++) {
                float x = dir * (edge - 0.35f + 0.2f * (float) Math.sin(time * 3.1f + i)), z = cz + i * (Builder.PHZ + 0.6f) / 4f;
                float vz = r.project(x, 0.05f, z, s);
                if (vz < 1f) continue;
                r.haze(s[0], s[1], 0.6f * r.f / vz, 1f / (vz - 0.8f), 0xFFFFFFFF, (int) (240f * sp));
            }
        }
        // the crags first: they stand behind
        for (int c = 0; c < CRAGS.length; c++) {
            float h = CRAGS[c][3] + lift, ax = apex[2 * c], az = apex[2 * c + 1];
            for (int i = 0; i < CRAG_SIDES; i++) {
                int j = (i + 1) % CRAG_SIDES;
                // ice below, snow on the upper third
                float k = 0.62f;
                float mxi = cragX[c][i] + (ax - cragX[c][i]) * k, mzi = cragZ[c][i] + (az - cragZ[c][i]) * k;
                float mxj = cragX[c][j] + (ax - cragX[c][j]) * k, mzj = cragZ[c][j] + (az - cragZ[c][j]) * k;
                float my = -0.3f + (h + 0.3f) * k;
                qx[0] = cragX[c][i];
                qy[0] = -0.3f;
                qz[0] = cragZ[c][i];
                qx[1] = cragX[c][j];
                qy[1] = -0.3f;
                qz[1] = cragZ[c][j];
                qx[2] = mxj;
                qy[2] = my;
                qz[2] = mzj;
                qx[3] = mxi;
                qy[3] = my;
                qz[3] = mzi;
                face(r, 4, (i & 1) == 0 ? 0xFF9EC5D8 : 0xFF88B6CC, id, false);
                qx[0] = mxi;
                qy[0] = my;
                qz[0] = mzi;
                qx[1] = mxj;
                qy[1] = my;
                qz[1] = mzj;
                qx[2] = ax;
                qy[2] = h;
                qz[2] = az;
                face(r, 3, 0xFFEDF3F7, id, true);
            }
        }
        // the cliffs, ring by ring down to the water
        for (int ring = 0; ring < last; ring++) {
            float y0 = top + RINGS[ring][0];
            float y1 = ring + 1 == last ? -0.3f : top + RINGS[ring + 1][0];
            int c = ring == 0 ? 0xFFEFF5F8 : ring == 1 ? 0xFFADCDDD : 0xFF7FB2C8;
            for (int i = 0; i < RIM; i++) {
                int j = (i + 1) % RIM;
                qx[0] = ringX[ring][i];
                qy[0] = y0;
                qz[0] = ringZ[ring][i];
                qx[1] = ringX[ring][j];
                qy[1] = y0;
                qz[1] = ringZ[ring][j];
                qx[2] = ringX[ring + 1][j];
                qy[2] = y1;
                qz[2] = ringZ[ring + 1][j];
                face(r, 3, (i % 3) == 0 ? Backdrop.scale(c, 0.95f) : c, id, ring == 0);
                qx[1] = ringX[ring + 1][j];
                qy[1] = y1;
                qz[1] = ringZ[ring + 1][j];
                qx[2] = ringX[ring + 1][i];
                qy[2] = y1;
                qz[2] = ringZ[ring + 1][i];
                face(r, 3, (i % 4) == 1 ? Backdrop.scale(c, 1.04f) : c, id, ring == 0);
            }
        }
        // the snowy top, as a fan from the middle
        for (int i = 0; i < RIM; i++) {
            int j = (i + 1) % RIM;
            qx[0] = 0f;
            qy[0] = top;
            qz[0] = cz;
            qx[1] = ringX[0][i];
            qy[1] = top;
            qz[1] = ringZ[0][i];
            qx[2] = ringX[0][j];
            qy[2] = top;
            qz[2] = ringZ[0][j];
            r.poly(qx, qy, qz, null, null, 3, (i & 1) == 0 ? 0xFFF1F6F9 : 0xFFEAF1F5, Raster.PAT_FLAT, id, true);
        }
        for (int i = 0; i < RIM; i++) {
            float vz = r.project(ringX[last][i], 0.03f, ringZ[last][i], s);
            if (vz < 1f) continue;
            float k = 0.5f + 0.5f * (float) Math.sin(i * 1.9f + time * 2.1f);
            r.haze(s[0], s[1], (0.3f + 0.3f * k) * r.f / vz, 1f / (vz - 0.6f), 0xFFE6EEF2, (int) (170 * k));
        }
    }

    // ---- boats ----
    // Small sailing boats at anchor, both sides, rolling and pitching on the swell.
    // x, z, yaw, hull color

    private static final float[][] BOATS = {
            {-8.5f, 27f, 0.5f}, {-11f, 47f, -0.35f}, {-15f, 78f, 0.25f}, {-6f, 118f, 0.9f},
            {13.5f, 22f, -0.6f}, {19f, 45f, 0.55f}, {31f, 74f, -0.25f}, {44f, 104f, 0.4f},
            {8f, -30f, 0.3f}, {-14f, -46f, -0.5f}, {20f, -68f, 0.7f}};
    private static final int[] HULLS = {0xFF3A2E2A, 0xFF6A2420, 0xFF22303E, 0xFFDCD8CE, 0xFF2E4A3A, 0xFFE2DED4,
            0xFF4A3A2E, 0xFF2A3A4E, 0xFF5A2420, 0xFF2E3A44, 0xFFD8D2C6};
    private static final int[] SAILS = {0xFF4A423C, 0xFF5E4A3A, 0xFF34383E, 0xFF6A5644, 0xFF3E3632, 0xFF56504A,
            0xFF4A3E34, 0xFF2E3034, 0xFF5A4A3C, 0xFF3A3632, 0xFF62584C};
    private final float[] m = new float[9];

    void boats(Raster r, float time) {
        for (int i = 0; i < BOATS.length; i++) {
            float bx = BOATS[i][0], bz = BOATS[i][1], yaw = BOATS[i][2];
            float t = r.depth(bx, 1f, bz);
            if (t < 2f) continue;
            r.project(bx, 1f, bz, s);
            if (s[0] < -120f || s[0] > r.w + 120f) continue;
            float roll = 0.17f * (float) Math.sin(time * 0.95f + i * 1.7f) + 0.05f * (float) Math.sin(time * 2.1f + i);
            float pitch = 0.06f * (float) Math.sin(time * 0.8f + i * 2.3f);
            float heave = heave(bx, bz, time) * 2.5f - 0.1f;
            frame(yaw, pitch, roll);
            int hull = HULLS[i];
            // the hull, narrower at the bow, and the deckhouse
            box(r, bx, heave, bz, 0f, 0.3f, 0f, 0.8f, 0.45f, 2.2f, hull);
            box(r, bx, heave, bz, 0f, 0.42f, 2.55f, 0.45f, 0.33f, 0.45f, hull);
            box(r, bx, heave, bz, 0f, 0.78f, 0.05f, 0.82f, 0.05f, 2.25f, Backdrop.scale(hull, 1.25f));
            box(r, bx, heave, bz, 0f, 1.08f, -0.55f, 0.5f, 0.27f, 0.75f, 0xFFD6D0C2);
            // the mast and the boom
            box(r, bx, heave, bz, 0f, 4.2f, 0.45f, 0.05f, 3.4f, 0.05f, 0xFF3A3430);
            box(r, bx, heave, bz, 0f, 1.6f, -0.45f, 0.04f, 0.04f, 0.9f, 0xFF3A3430);
            // a reefed mainsail, a small jib before the mast
            // tanned sails, dark with weather
            int sail = SAILS[i];
            tri(r, bx, heave, bz, 0f, 6.8f, 0.5f, 0f, 1.65f, 0.5f, 0f, 1.65f, -1.35f, sail);
            tri(r, bx, heave, bz, 0f, 6.5f, 0.6f, 0f, 1.25f, 0.6f, 0f, 1.25f, 2.85f, Backdrop.scale(sail, 0.85f));
        }
    }

    /** The boat's frame: yaw, then pitch about its across axis, then roll about its length. */
    private void frame(float yaw, float pitch, float roll) {
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw), cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
        // R = Ry(yaw) * Rx(pitch) * Rz(roll), row-major
        float a00 = cr, a01 = -sr, a02 = 0f;
        float a10 = cp * sr, a11 = cp * cr, a12 = -sp;
        float a20 = sp * sr, a21 = sp * cr, a22 = cp;
        m[0] = cy * a00 + sy * a20;
        m[1] = cy * a01 + sy * a21;
        m[2] = cy * a02 + sy * a22;
        m[3] = a10;
        m[4] = a11;
        m[5] = a12;
        m[6] = -sy * a00 + cy * a20;
        m[7] = -sy * a01 + cy * a21;
        m[8] = -sy * a02 + cy * a22;
    }

    private void box(Raster r, float bx, float by, float bz, float lx, float ly, float lz, float hx, float hy, float hz, int c) {
        float x = bx + m[0] * lx + m[1] * ly + m[2] * lz, y = by + m[3] * lx + m[4] * ly + m[5] * lz, z = bz + m[6] * lx + m[7] * ly + m[8] * lz;
        r.box(x, y, z, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], hx, hy, hz, c, Raster.PAT_FLAT, 0);
    }

    private final float[] tx = new float[3], ty = new float[3], tz = new float[3];

    private void tri(Raster r, float bx, float by, float bz, float ax, float ay, float az, float bx2, float by2, float bz2,
                     float cx, float cy, float cz, int c) {
        put(0, bx, by, bz, ax, ay, az);
        put(1, bx, by, bz, bx2, by2, bz2);
        put(2, bx, by, bz, cx, cy, cz);
        r.poly(tx, ty, tz, null, null, 3, c, Raster.PAT_FLAT, 0, false);
    }

    private void put(int i, float bx, float by, float bz, float lx, float ly, float lz) {
        tx[i] = bx + m[0] * lx + m[1] * ly + m[2] * lz;
        ty[i] = by + m[3] * lx + m[4] * ly + m[5] * lz;
        tz[i] = bz + m[6] * lx + m[7] * ly + m[8] * lz;
    }

    /**
     * The buoy that carries the call box: red and white, swaying on the swell. The box sits
     * on its post at (x, y, z) when the buoy is upright; where it is now goes into out.
     */
    void buoy(Raster r, float x, float y, float z, float time, float[] out) {
        float roll = 0.1f * (float) Math.sin(time * 1.3f), pitch = 0.06f * (float) Math.sin(time * 0.9f + 1f);
        frame(0.3f, pitch, roll);
        float h = heave(x, z, time) * 1.6f;
        float bx = x + 0.35f;
        box(r, bx, h, z, 0f, 0.1f, 0f, 0.62f, 0.35f, 0.62f, 0xFFB02C1C);
        box(r, bx, h, z, 0f, 0.5f, 0f, 0.5f, 0.06f, 0.5f, 0xFFE8E4DA);
        box(r, bx, h, z, 0f, 1.2f, 0f, 0.07f, 0.66f, 0.07f, 0xFF4A4542);
        box(r, bx, h, z, 0f, 2.0f, 0f, 0.16f, 0.14f, 0.16f, 0xFFC9A44A);
        // the call box itself, grey, facing the player
        float ly = y;
        box(r, bx, h, z, -0.1f, ly, 0f, 0.05f, 0.2f, 0.13f, 0xFF8A9198);
        out[0] = bx + m[0] * -0.16f + m[1] * ly;
        out[1] = h + m[3] * -0.16f + m[4] * ly;
        out[2] = z + m[6] * -0.16f + m[7] * ly;
    }

    // ---- orcas ----
    // A pod of killer whales goes by now and then, far off, in front of the player and
    // behind: little more than black backs and tall fins rolling up out of the swell and
    // sliding under again, as the cars and the passers-by were on the street. Below the
    // surface the water hides them by itself.

    private static final int PODS = 2, WHALES = 3;
    // where a pod goes: its middle line (z) and which way along x; when it starts again
    private final float[] podZ = {26f, -24f}, podDir = {1f, -1f}, podT = {0f, 0f}, podIn = {4f, 11f};
    private final boolean[] podOn = new boolean[PODS];
    private final float[][] whaleLag = new float[PODS][WHALES], whaleOff = new float[PODS][WHALES], whaleSize = new float[PODS][WHALES];
    private final boolean[][] whaleLeap = new boolean[PODS][WHALES];
    private final Random pod = new Random(7);
    static final float ORCA_V = 3.6f;

    void orcaStep(float dt) {
        for (int p = 0; p < PODS; p++) {
            if (!podOn[p]) {
                if ((podIn[p] -= dt) > 0f) continue;
                podOn[p] = true;
                podT[p] = 0f;
                podDir[p] = pod.nextBoolean() ? 1f : -1f;
                // in front they pass beyond the iceberg, never between it and the player
                podZ[p] = p == 0 ? 68f + pod.nextFloat() * 25f : -(26f + pod.nextFloat() * 20f);
                for (int k = 0; k < WHALES; k++) {
                    whaleLag[p][k] = k * (1.4f + pod.nextFloat() * 1.2f);
                    whaleOff[p][k] = (pod.nextFloat() - 0.5f) * 5f;
                    whaleSize[p][k] = k == 0 ? 1.15f : 0.75f + pod.nextFloat() * 0.3f;
                    whaleLeap[p][k] = false;
                }
                continue;
            }
            podT[p] += dt;
            if (podT[p] * ORCA_V > 62f) {
                podOn[p] = false;
                podIn[p] = 8f + pod.nextFloat() * 14f;
            }
        }
    }

    /** The height of a whale's middle at its own time tt: up out of the swell and under again. */
    private float rise(int p, int k, float tt) {
        float cycle = 5.5f, ph = (tt % cycle + cycle) % cycle;
        boolean leap = whaleLeap[p][k] && ((int) Math.floor(tt / cycle) % 3 == 1);
        if (ph >= 2.2f) return -2.6f * whaleSize[p][k];
        float u = ph / 2.2f;
        // the back just breaks the surface; mostly the fin shows
        float y = leap ? -1.5f + 3.6f * (float) Math.sin(Math.PI * u) : -1.05f + 0.95f * (float) Math.sin(Math.PI * u);
        return y * whaleSize[p][k];
    }

    /**
     * Where a point of a whale's spine is, a share s of the way from its snout (0) to its tail
     * (1). The body is stiff: it lies along the way its middle is going, nose up as it comes
     * out, nose down as it goes under. Returns the whale's own time.
     */
    private float spine(int p, int k, float s, float[] out) {
        float t = podT[p] - whaleLag[p][k];
        float len = 6.4f * whaleSize[p][k];
        float mid = World.CAM_X + podZ[p] * 0.2f;
        float x = mid - 26f * podDir[p] + podDir[p] * ORCA_V * t;
        float y = rise(p, k, t);
        float dy = (rise(p, k, t + 0.05f) - rise(p, k, t - 0.05f)) / 0.1f;
        float dl = (float) Math.sqrt(ORCA_V * ORCA_V + dy * dy);
        float ax = podDir[p] * ORCA_V / dl, ay = dy / dl;
        float off = (0.5f - s) * len;
        out[0] = x + ax * off;
        out[1] = y + ay * off;
        out[2] = podZ[p] + whaleOff[p][k];
        return t;
    }

    private final float[] sp0 = new float[3], sp1 = new float[3];
    private static final float[] ORCA_R = {0.28f, 0.48f, 0.62f, 0.66f, 0.6f, 0.5f, 0.38f, 0.26f, 0.17f, 0.12f};

    private static final int NS = 12, NR = 10;
    private final float[][] wX = new float[NS][NR], wY = new float[NS][NR], wZ = new float[NS][NR];
    private final float[] q4x = new float[4], q4y = new float[4], q4z = new float[4];

    /**
     * One whale's body, of a piece: rings round its spine from snout to tail, joined by
     * faces; black above, the belly white, a white patch behind the eye, a grey saddle
     * behind the fin. False when none of it is above the water.
     */
    private boolean body(Raster r, int p, int k, float size, int id) {
        spine(p, k, 0f, sp0);
        spine(p, k, 1f, sp1);
        float tx0 = sp1[0] - sp0[0], ty0 = sp1[1] - sp0[1], tl = (float) Math.sqrt(tx0 * tx0 + ty0 * ty0);
        float txn = tx0 / tl, tyn = ty0 / tl;
        // up, square to the spine in its vertical plane; side is across the way it swims
        float ux = -tyn, uy = txn;
        if (uy < 0f) {
            ux = -ux;
            uy = -uy;
        }
        boolean above = false;
        for (int i = 0; i < NS; i++) {
            float u = i / (float) (NS - 1), fi = u * (ORCA_R.length - 1);
            int i0 = Math.min(ORCA_R.length - 2, (int) fi);
            float rad = (ORCA_R[i0] + (ORCA_R[i0 + 1] - ORCA_R[i0]) * (fi - i0)) * size;
            // the snout comes to a blunt point; the tail stock is flattened side to side
            if (i == 0) rad *= 0.5f;
            float wide = u > 0.7f ? 0.6f : 0.9f;
            spine(p, k, u, sp0);
            for (int j = 0; j < NR; j++) {
                double th = j * Math.PI * 2 / NR;
                float c = (float) Math.cos(th) * rad, sn = (float) Math.sin(th) * rad * wide;
                wX[i][j] = sp0[0] + ux * c;
                wY[i][j] = sp0[1] + uy * c;
                wZ[i][j] = sp0[2] + sn;
                if (wY[i][j] > -0.03f) above = true;
            }
        }
        if (!above) return false;
        for (int i = 0; i < NS - 1; i++) {
            float u = (i + 0.5f) / (NS - 1);
            for (int j = 0; j < NR; j++) {
                int jn = (j + 1) % NR;
                if (wY[i][j] < -0.3f && wY[i][jn] < -0.3f && wY[i + 1][j] < -0.3f && wY[i + 1][jn] < -0.3f) continue;
                double thm = (j + 0.5) * Math.PI * 2 / NR;
                float cm = (float) Math.cos(thm), sm = (float) Math.sin(thm);
                float nx = ux * cm, ny = uy * cm, nz = sm;
                q4x[0] = wX[i][j];
                q4y[0] = wY[i][j];
                q4z[0] = wZ[i][j];
                q4x[1] = wX[i][jn];
                q4y[1] = wY[i][jn];
                q4z[1] = wZ[i][jn];
                q4x[2] = wX[i + 1][jn];
                q4y[2] = wY[i + 1][jn];
                q4z[2] = wZ[i + 1][jn];
                q4x[3] = wX[i + 1][j];
                q4y[3] = wY[i + 1][j];
                q4z[3] = wZ[i + 1][j];
                if ((r.camX - q4x[0]) * nx + (r.camY - q4y[0]) * ny + (r.camZ - q4z[0]) * nz <= 0f) continue;
                int c = 0xFF16181C;
                if (cm < -0.3f && u > 0.08f && u < 0.62f) c = 0xFFE6EAEC;              // the belly
                else if (u > 0.1f && u < 0.22f && cm > 0.1f && cm < 0.75f) c = 0xFFF0F2F2; // behind the eye
                else if (u > 0.45f && u < 0.6f && cm > 0.75f) c = 0xFF54585E;          // the saddle
                float ndl = nx * Raster.sunX() + ny * Raster.sunY() + nz * Raster.sunZ();
                int lc = Raster.light(c, nx, ny, nz, ndl);
                // wet: a sheen where it faces the sky
                if (ny > 0.6f) lc = Backdrop.mix(lc, 0xFFB8C4CC, (ny - 0.6f) * 0.5f);
                r.poly(q4x, q4y, q4z, null, null, 4, lc, Raster.PAT_FLAT, id, false);
            }
        }
        return true;
    }

    void orcas(Raster r) {
        for (int p = 0; p < PODS; p++) {
            if (!podOn[p]) continue;
            for (int k = 0; k < WHALES; k++) {
                float size = whaleSize[p][k];
                float t = spine(p, k, 0f, sp0);
                if (t < 0f) continue;
                float vz = r.depth(sp0[0], 0f, sp0[2]);
                if (vz < 2f) continue;
                r.project(sp0[0], 0f, sp0[2], s);
                if (s[0] < -80f || s[0] > r.w + 80f) continue;
                int id = (980 + p * 4 + k) << 3;
                boolean any = body(r, p, k, size, id);
                // the tall fin, over the middle of the back
                spine(p, k, 0.36f, sp0);
                spine(p, k, 0.5f, sp1);
                float top = sp0[1] + ORCA_R[3] * size * 0.85f;
                float fh = 1.25f * size * (k == 0 ? 1.3f : 0.8f);
                if (top + fh > 0f) {
                    any = true;
                    tx[0] = sp0[0];
                    ty[0] = top;
                    tz[0] = sp0[2];
                    tx[1] = sp1[0];
                    ty[1] = sp1[1] + ORCA_R[5] * size * 0.85f;
                    tz[1] = sp1[2];
                    tx[2] = sp1[0] + (sp1[0] - sp0[0]) * 0.35f;
                    ty[2] = top + fh;
                    tz[2] = sp0[2];
                    r.poly(tx, ty, tz, null, null, 3, 0xFF1C1E22, Raster.PAT_FLAT, id, false);
                }
                // the flukes, flat, spread to both sides of the tail
                spine(p, k, 1f, sp0);
                spine(p, k, 0.9f, sp1);
                if (sp0[1] > -0.1f) {
                    any = true;
                    float bx = sp0[0] - sp1[0], by = sp0[1] - sp1[1];
                    for (int side = -1; side <= 1; side += 2) {
                        tx[0] = sp1[0];
                        ty[0] = sp1[1];
                        tz[0] = sp1[2];
                        tx[1] = sp0[0] + bx * 0.6f;
                        ty[1] = sp0[1] + by * 0.6f;
                        tz[1] = sp0[2] + side * 1.1f * size;
                        tx[2] = sp0[0] + bx * 0.3f;
                        ty[2] = sp0[1] + by * 0.3f;
                        tz[2] = sp0[2];
                        r.poly(tx, ty, tz, null, null, 3, 0xFF1C1E22, Raster.PAT_FLAT, id, false);
                    }
                }
                if (!any) continue;
                // the swell breaking round it where it cuts the surface
                for (int i = 0; i < ORCA_R.length; i += 3) {
                    spine(p, k, i / (float) (ORCA_R.length - 1), sp0);
                    if (Math.abs(sp0[1]) > ORCA_R[i] * size * 1.1f) continue;
                    float z = r.project(sp0[0], 0.05f, sp0[2], s);
                    if (z < 1f) continue;
                    r.haze(s[0], s[1], ORCA_R[i] * size * 2.4f * r.f / z, 1f / (z - 1f), 0xFFE8EEF2, 170);
                }
            }
        }
    }

    // ---- the blizzard ----
    // Flakes in the air before the eyes, nearer ones bigger, faster and drawn out into
    // streaks, all driven slantwise by a wind that gusts; in a gust the air goes white and
    // the snow thickens. Whirls wander across and wind the flakes round them, and low over
    // the ice the snow runs along in streams.

    private static final int FLAKES = 560, DRIFTS = 46, WHIRLS = 2;
    private final float[] fx = new float[FLAKES], fy = new float[FLAKES], fz = new float[FLAKES];
    private final float[] fvx = new float[FLAKES], fvy = new float[FLAKES];
    private final float[] dx = new float[DRIFTS], dy = new float[DRIFTS], dl = new float[DRIFTS], dv = new float[DRIFTS];
    private final Random rnd = new Random(31);
    private final float[] whirlX = new float[WHIRLS], whirlY = new float[WHIRLS], whirlT = new float[WHIRLS], whirlIn = new float[WHIRLS];
    private float gustT, gust, gustIn = 6f, gustLen, gustAt = -1f;
    private int w = 1, h = 1;

    void snowFor(int width, int height) {
        w = width;
        h = height;
        for (int i = 0; i < FLAKES; i++) {
            fx[i] = rnd.nextFloat() * w;
            fy[i] = rnd.nextFloat() * h;
            fz[i] = 0.2f + rnd.nextFloat() * 0.8f;
        }
        for (int i = 0; i < DRIFTS; i++) {
            dx[i] = rnd.nextFloat() * w;
            dy[i] = h * (0.72f + rnd.nextFloat() * 0.28f);
            dl[i] = 8f + rnd.nextFloat() * 22f;
            dv[i] = 0.6f + rnd.nextFloat() * 0.8f;
        }
        for (int k = 0; k < WHIRLS; k++) {
            whirlT[k] = -1f;
            whirlIn[k] = 2f + k * 4f + rnd.nextFloat() * 3f;
        }
    }

    /** How hard the wind blows now, 1 as usual, up to about 2.6 in a gust. */
    float wind() {
        return gust;
    }

    void snow(float dt) {
        gustT += dt;
        // gusts: now and then the wind rises for a second or two and the air goes white
        float g = 0f;
        if (gustAt >= 0f) {
            gustAt += dt;
            float k = gustAt / gustLen;
            if (k >= 1f) {
                gustAt = -1f;
                gustIn = 5f + rnd.nextFloat() * 8f;
            } else g = (float) Math.sin(Math.PI * k);
        } else if ((gustIn -= dt) <= 0f) {
            gustAt = 0f;
            gustLen = 1.4f + rnd.nextFloat() * 1.6f;
        }
        gust = 1f + 0.45f * (float) Math.sin(gustT * 0.6f) + 0.2f * (float) Math.sin(gustT * 1.7f) + 1.4f * g;
        for (int k = 0; k < WHIRLS; k++) {
            if (whirlT[k] < 0f && (whirlIn[k] -= dt) <= 0f) {
                whirlT[k] = 0f;
                whirlX[k] = -90f;
                whirlY[k] = h * (0.15f + rnd.nextFloat() * 0.7f);
            }
            if (whirlT[k] >= 0f) {
                whirlT[k] += dt;
                whirlX[k] += (70f + 30f * gust) * dt;
                whirlY[k] += (float) Math.sin(whirlT[k] * 1.3f + k) * 18f * dt;
                if (whirlX[k] > w + 100f) {
                    whirlT[k] = -1f;
                    whirlIn[k] = 3f + rnd.nextFloat() * 6f;
                }
            }
        }
        for (int i = 0; i < FLAKES; i++) {
            float z = fz[i];
            float vx = 75f * z * gust, vy = 30f * z + 10f * (float) Math.sin(gustT * 2f + i);
            for (int k = 0; k < WHIRLS; k++) {
                if (whirlT[k] < 0f) continue;
                float ex = fx[i] - whirlX[k], ey = fy[i] - whirlY[k], dd = (float) Math.sqrt(ex * ex + ey * ey) + 1f;
                if (dd < 150f) {
                    float s = (1f - dd / 150f) * 380f * z;
                    vx += ey / dd * s;
                    vy += -ex / dd * s - 26f * (1f - dd / 150f);
                }
            }
            fvx[i] = vx;
            fvy[i] = vy;
            fx[i] += vx * dt;
            fy[i] += vy * dt;
            if (fx[i] > w + 8) {
                fx[i] -= w + 16;
                fy[i] = rnd.nextFloat() * h;
            }
            if (fx[i] < -8) fx[i] += w + 16;
            if (fy[i] > h + 6) {
                fy[i] = -6f;
                fx[i] = rnd.nextFloat() * w;
            }
            if (fy[i] < -8) fy[i] = h + 4f;
        }
        for (int i = 0; i < DRIFTS; i++) {
            dx[i] += 160f * dv[i] * gust * dt;
            if (dx[i] - dl[i] > w) {
                dx[i] = -rnd.nextFloat() * 40f;
                dy[i] = h * (0.72f + rnd.nextFloat() * 0.28f);
            }
        }
    }

    void drawSnow(Px f) {
        float g = Math.max(0f, gust - 1.45f) / 1.4f;
        // a gust: the air itself goes white
        if (g > 0f) {
            int a = (int) (Math.min(1f, g) * 92f);
            for (int k = 0; k < f.a.length; k++) f.a[k] = lerp(f.a[k], 0xFFE2E8EC, a);
        }
        // the snow running low over the ice
        for (int i = 0; i < DRIFTS; i++) {
            int y = (int) dy[i], x1 = (int) dx[i], len = (int) (dl[i] * (0.7f + 0.3f * gust));
            int a = (int) ((50 + 50 * Math.min(1f, gust - 0.6f)) * dv[i]);
            for (int j = 0; j < len; j++) {
                float k = (float) j / len;
                blend(f, x1 - j, y + (int) (Math.sin(j * 0.3f + i) * 1.2f), 0xFFF2F6F8, (int) (a * (1f - k) * Math.min(1f, k * 6f)));
            }
        }
        int extra = (int) (FLAKES * (0.6f + 0.4f * Math.min(1f, g)));
        for (int i = 0; i < extra; i++) {
            int x = (int) fx[i], y = (int) fy[i];
            float z = fz[i];
            int a = (int) (110 + 140 * z);
            if (z > 0.62f) {
                // near ones are drawn out along their flight
                float vx = fvx[i], vy = fvy[i], sp = (float) Math.sqrt(vx * vx + vy * vy) + 1f;
                int n = Math.min(7, (int) (sp * 0.025f * z) + 1);
                for (int j = 0; j < n; j++)
                    blend(f, x - (int) (vx / sp * j), y - (int) (vy / sp * j), 0xFFFFFFFF, a * (n - j) / n);
                if (z > 0.85f) blend(f, x + 1, y, 0xFFF4F8FA, a * 2 / 3);
            } else blend(f, x, y, 0xFFF4F8FA, a);
        }
    }

    private static void blend(Px f, int x, int y, int c, int a) {
        if (x < 0 || y < 0 || x >= f.w || y >= f.h) return;
        int k = y * f.w + x;
        f.a[k] = lerp(f.a[k], c, a > 255 ? 255 : a);
    }
}
