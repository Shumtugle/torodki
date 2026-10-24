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

    static final float TF_RX = 4.4f, TF_RZ = 3.3f;

    /** On the floe the building stands on, centered at (0, cz). */
    static boolean onTarget(float x, float z, float cz) {
        float dx = x / TF_RX, dz = (z - cz) / TF_RZ;
        return dx * dx + dz * dz < sq(rag((float) Math.atan2(dz, dx), 2.1f));
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
        int[] col = r.col;
        float run = time * 1.25f, ox = time * 1.9f, oz = time * 0.4f;
        for (int i = 0; i < m.n; i++) {
            int p = (int) ((m.ph[i] + run) * 162.97f) & 1023;
            int s = SIN[p];                       // -256..256
            int c = lerp(WATER_DEEP, WATER, (s + 256) >> 1);
            if (s > 120) c = lerp(c, CREST, (s - 120) * 2 > 255 ? 255 : (s - 120) * 2);
            int cell = m.cell[i] + 128;
            // a whitecap: on the top of a crest, where this patch of water breaks
            if (s > 228 && cell > 150) c = lerp(c, 0xFFE4ECEF, Math.min(255, (cell - 150) * 3 + (s - 228) * 4));
            else {
                // foam streaks along the wind
                int hh = Backdrop.hash((int) Math.floor(m.x[i] * 1.2f + ox), (int) Math.floor(m.z[i] * 0.42f + oz));
                if (hh > 246) c = lerp(c, 0xFFD8E2E6, 120);
            }
            col[m.k[i]] = lerp(c, FOG, m.fog[i] + 128);
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
                if (idb[k] != 0 && q > 0f) a = (int) (fog(dist) * 256f);
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

    private final float[] px = new float[24], pz = new float[24];
    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];

    /** The floe the building stands on: a ragged slab of ice round the block, its top at this height. */
    void targetFloe(Raster r, float cz, float top, int id) {
        int n = 20;
        for (int i = 0; i < n; i++) {
            float a = (float) (i * Math.PI * 2 / n);
            float rad = rag(a, 2.1f);
            px[i] = (float) Math.cos(a) * TF_RX * rad;
            pz[i] = cz + (float) Math.sin(a) * TF_RZ * rad;
        }
        float bottom = top - 0.55f;
        // the rim: blue ice, wet and darker at the waterline
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            qx[0] = px[i];
            qx[1] = px[j];
            qx[2] = px[j];
            qx[3] = px[i];
            qz[0] = pz[i];
            qz[1] = pz[j];
            qz[2] = pz[j];
            qz[3] = pz[i];
            qy[0] = qy[1] = top;
            qy[2] = qy[3] = bottom;
            int c = (i % 3) == 0 ? ICE_RIM : Backdrop.scale(ICE_RIM, 0.92f + 0.04f * (i % 2));
            r.poly(qx, qy, qz, null, null, 4, c, Raster.PAT_FLAT, id, false);
        }
        // a lip of snow along the top edge
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            qx[0] = px[i];
            qx[1] = px[j];
            qx[2] = px[j] * 0.93f;
            qx[3] = px[i] * 0.93f;
            qz[0] = pz[i];
            qz[1] = pz[j];
            qz[2] = cz + (pz[j] - cz) * 0.93f;
            qz[3] = cz + (pz[i] - cz) * 0.93f;
            qy[0] = qy[1] = qy[2] = qy[3] = top;
            r.poly(qx, qy, qz, null, null, 4, 0xFFC9DAE4, Raster.PAT_FLAT, id, true);
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            qx[0] = 0f;
            qx[1] = px[i] * 0.93f;
            qx[2] = px[j] * 0.93f;
            qz[0] = cz;
            qz[1] = cz + (pz[i] - cz) * 0.93f;
            qz[2] = cz + (pz[j] - cz) * 0.93f;
            qy[0] = qy[1] = qy[2] = top;
            r.poly(qx, qy, qz, null, null, 3, (i & 1) == 0 ? ICE : Backdrop.scale(ICE, 0.975f), Raster.PAT_FLAT, id, true);
        }
        // the swell slaps at its foot: a ring of foam just above the water
        for (int i = 0; i < n; i += 1) {
            float vz = r.project(px[i] * 1.03f, 0.02f, cz + (pz[i] - cz) * 1.03f, s);
            if (vz < 1f) continue;
            float k = 0.5f + 0.5f * (float) Math.sin(i * 1.9f + top * 30f);
            r.haze(s[0], s[1], (0.25f + 0.25f * k) * r.f / vz, 1f / (vz - 0.4f), 0xFFE6EEF2, (int) (150 * k));
        }
    }

    // ---- boats ----
    // Small sailing boats at anchor, both sides, rolling and pitching on the swell.
    // x, z, yaw, hull color

    private static final float[][] BOATS = {
            {-5.2f, 33f, 0.5f}, {-11f, 47f, -0.35f}, {-15f, 78f, 0.25f}, {-6f, 118f, 0.9f},
            {10f, 24f, -0.6f}, {19f, 45f, 0.55f}, {31f, 74f, -0.25f}, {44f, 104f, 0.4f}};
    private static final int[] HULLS = {0xFF3A2E2A, 0xFF6A2420, 0xFF22303E, 0xFFDCD8CE, 0xFF2E4A3A, 0xFFE2DED4,
            0xFF4A3A2E, 0xFF2A3A4E};
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
            int sail = 0xFFE6E0D0;
            tri(r, bx, heave, bz, 0f, 6.8f, 0.5f, 0f, 1.65f, 0.5f, 0f, 1.65f, -1.35f, sail);
            tri(r, bx, heave, bz, 0f, 6.5f, 0.6f, 0f, 1.25f, 0.6f, 0f, 1.25f, 2.85f, Backdrop.scale(sail, 0.92f));
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

    // ---- snow ----
    // Flakes in the air before the eyes, nearer ones bigger and faster, all driven slantwise
    // by the wind; now and then a whirl wanders across and winds them round it.

    private static final int FLAKES = 300;
    private final float[] fx = new float[FLAKES], fy = new float[FLAKES], fz = new float[FLAKES];
    private final Random rnd = new Random(31);
    private float whirlX = -100f, whirlY, whirlT = -1f, whirlIn = 3f, gustT;
    private int w = 1, h = 1;

    void snowFor(int width, int height) {
        w = width;
        h = height;
        for (int i = 0; i < FLAKES; i++) {
            fx[i] = rnd.nextFloat() * w;
            fy[i] = rnd.nextFloat() * h;
            fz[i] = 0.2f + rnd.nextFloat() * 0.8f;
        }
    }

    boolean whirling() {
        return whirlT >= 0f;
    }

    void snow(float dt) {
        gustT += dt;
        whirlIn -= dt;
        if (whirlT < 0f && whirlIn <= 0f) {
            whirlT = 0f;
            whirlX = -60f;
            whirlY = h * (0.2f + rnd.nextFloat() * 0.55f);
        }
        if (whirlT >= 0f) {
            whirlT += dt;
            whirlX += 85f * dt;
            whirlY += (float) Math.sin(whirlT * 1.3f) * 14f * dt;
            if (whirlX > w + 80f) {
                whirlT = -1f;
                whirlIn = 5f + rnd.nextFloat() * 7f;
            }
        }
        float gust = 1f + 0.55f * (float) Math.sin(gustT * 0.6f) + 0.25f * (float) Math.sin(gustT * 1.7f);
        for (int i = 0; i < FLAKES; i++) {
            float z = fz[i];
            float vx = 70f * z * gust, vy = 34f * z + 8f * (float) Math.sin(gustT * 2f + i);
            if (whirlT >= 0f) {
                float dx = fx[i] - whirlX, dy = fy[i] - whirlY, dd = (float) Math.sqrt(dx * dx + dy * dy) + 1f;
                if (dd < 110f) {
                    float k = (1f - dd / 110f) * 330f * z;
                    vx += dy / dd * k;
                    vy += -dx / dd * k - 20f * (1f - dd / 110f);
                }
            }
            fx[i] += vx * dt;
            fy[i] += vy * dt;
            if (fx[i] > w + 4) {
                fx[i] -= w + 8;
                fy[i] = rnd.nextFloat() * h;
            }
            if (fx[i] < -4) fx[i] += w + 8;
            if (fy[i] > h + 4) {
                fy[i] = -4f;
                fx[i] = rnd.nextFloat() * w;
            }
            if (fy[i] < -6) fy[i] = h + 2f;
        }
    }

    void drawSnow(Px f) {
        for (int i = 0; i < FLAKES; i++) {
            int x = (int) fx[i], y = (int) fy[i];
            float z = fz[i];
            int a = (int) (110 + 140 * z);
            blend(f, x, y, 0xFFFFFFFF, a);
            if (z > 0.7f) {
                blend(f, x + 1, y, 0xFFF4F8FA, a * 3 / 4);
                blend(f, x, y + 1, 0xFFF4F8FA, a * 3 / 4);
                if (z > 0.88f) blend(f, x + 1, y + 1, 0xFFF4F8FA, a / 2);
            }
        }
    }

    private static void blend(Px f, int x, int y, int c, int a) {
        if (x < 0 || y < 0 || x >= f.w || y >= f.h) return;
        int k = y * f.w + x;
        f.a[k] = lerp(f.a[k], c, a > 255 ? 255 : a);
    }
}
