package com.shumtugle.torodki;

import java.util.Random;

/**
 * The summer street behind the game: sky, a wide road with a zebra crossing, curbs,
 * pavements, low houses and two rows of palms. Painted once per camera pose into the
 * raster, then kept and copied under every frame.
 */
final class Backdrop {
    static final int SKY_TOP = 0xFF4A8BD4, SKY_LOW = 0xFFCFE3EE, HAZE = 0xFFDCE4E2;

    private final float[] d = new float[3], s = new float[2];

    void paint(Raster r, long seed) {
        Random rnd = new Random(seed);
        java.util.Arrays.fill(r.zb, 0f);
        java.util.Arrays.fill(r.idb, 0);
        java.util.Arrays.fill(r.lit, (byte) 0);
        groundAndSky(r);
        houses(r, rnd);
        palms(r, rnd);
    }

    // ---- sky and ground, per pixel ----

    private void groundAndSky(Raster r) {
        int sunlitGround = Raster.light(0xFFFFFFFF, 0, 1, 0, Raster.sunY());
        for (int y = 0; y < r.h; y++) {
            for (int x = 0; x < r.w; x++) {
                r.ray(x + 0.5f, y + 0.5f, d);
                int k = y * r.w + x;
                if (d[1] < -0.002f) {
                    float t = -r.camY / d[1];
                    float wx = r.camX + d[0] * t, wz = r.camZ + d[2] * t;
                    int c = ground(wx, wz, t);
                    c = mul(c, sunlitGround);
                    float fog = 1f - (float) Math.exp(-t / 140f);
                    r.col[k] = mix(c, HAZE, fog);
                    r.zb[k] = 1f / t;
                    r.lit[k] = 1;
                } else {
                    float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                    float el = Math.max(0f, d[1] / l);
                    float k2 = (float) Math.pow(Math.min(1f, el * 2.2f), 0.55f);
                    r.col[k] = mix(SKY_LOW, SKY_TOP, k2);
                }
            }
        }
    }

    private static int hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0xFF;
    }

    /** Ground color at a world point seen from distance t. */
    private static int ground(float x, float z, float t) {
        float ax = Math.abs(x);
        int n = hash((int) Math.floor(x * 9f), (int) Math.floor(z * 9f));
        float grit = t < 25f ? (n - 128) / 128f * (1f - t / 25f) : 0f;
        int c;
        if (ax < 3.75f) {
            // asphalt, warm and sun-bleached, with darker patches
            int patch = hash((int) Math.floor(x * 0.7f + 31), (int) Math.floor(z * 0.5f));
            c = patch < 40 ? 0xFF6A665F : 0xFF7B766E;
            // the zebra
            if (z > 3.4f && z < 5.0f && ax < 3.5f) {
                float m = (x + 20f) % 0.9f;
                if (m < 0.5f) c = 0xFFE9E5DB;
            }
            // ochre blocks either side of the zebra
            if (((z > 3.15f && z < 3.36f) || (z > 5.04f && z < 5.25f)) && ax < 3.5f) {
                float m = (x + 20f) % 0.9f;
                if (m < 0.5f) c = 0xFFE2A93E;
            }
            // dashed center line beyond the crossing
            if (ax < 0.06f && z > 5.4f && (z % 6f) < 2.6f) c = 0xFFE6E2D8;
            // stop line
            if (z > 2.55f && z < 2.75f && x < 0f && x > -3.5f) c = 0xFFE6E2D8;
            // skid marks near the thrower
            if (z > 1.5f && z < 9f && ((ax > 0.55f && ax < 0.75f) || (ax > 1.85f && ax < 2.05f))
                    && hash((int) (z * 3f), (int) (x * 20f)) < 120) c = mix(c, 0xFF4E4A45, 0.45f);
        } else if (ax < 3.88f) {
            c = 0xFFE0B23C;                      // yellow edge line
        } else if (ax < 4.05f) {
            c = 0xFF7B766E;
        } else if (ax < 4.3f) {
            c = 0xFFCBC4B6;                      // curb
        } else if (ax < 7.2f) {
            c = 0xFFD8CCB4;                      // pavement with joints
            float fx = (ax - 4.3f) % 0.6f, fz = z % 0.6f;
            if (t < 30f && (fx < 0.03f || fz < 0.03f)) c = 0xFFBFB39C;
            if (ax > 4.9f && ax < 5.9f && z > 6f && soil(z))
                c = 0xFFA28A62;                  // soil round a palm
        } else if (ax < 9.5f) {
            int g = hash((int) (x * 4f), (int) (z * 4f));
            c = g < 60 ? 0xFF7F9346 : g < 200 ? 0xFF93A452 : 0xFFA9AE5E;   // dry summer lawn
        } else {
            c = 0xFFD3C8B2;
        }
        if (grit != 0f) c = scale(c, 1f + grit * 0.09f);
        return c;
    }

    private static boolean soil(float z) {
        float m = ((z - 7f) % 7.5f + 7.5f) % 7.5f;
        return Math.min(m, 7.5f - m) < 0.55f;
    }

    // ---- houses ----

    private void houses(Raster r, Random rnd) {
        int[] tints = {0xFFEADDC2, 0xFFDDC7A0, 0xFFE9C3A2, 0xFFF0EBE0, 0xFFE3CFAE, 0xFFD9B99A};
        for (int side = -1; side <= 1; side += 2) {
            float z = 9f + rnd.nextFloat() * 4f;
            while (z < 170f) {
                float len = 9f + rnd.nextFloat() * 10f;
                float depth = 9f + rnd.nextFloat() * 6f;
                float height = 6.5f + rnd.nextFloat() * 9f;
                float xc = side * (12.5f + depth * 0.5f + rnd.nextFloat() * 2f);
                int tint = tints[rnd.nextInt(tints.length)];
                r.box(xc, height * 0.5f, z + len * 0.5f, 1, 0, 0, 0, 1, 0, 0, 0, 1,
                        depth * 0.5f, height * 0.5f, len * 0.5f, tint, Raster.PAT_WINDOWS, 0);
                z += len + 0.6f + rnd.nextFloat() * 3f;
            }
        }
        // a far block closing the street
        r.box(0f, 9f, 240f, 1, 0, 0, 0, 1, 0, 0, 0, 1, 30f, 9f, 6f, 0xFFE6D8BE, Raster.PAT_WINDOWS, 0);
        hazeOver(r);
    }

    /** Distance haze over the houses (the ground has its own). */
    private void hazeOver(Raster r) {
        for (int k = 0; k < r.col.length; k++) {
            float q = r.zb[k];
            if (q <= 0f) continue;
            float t = 1f / q;
            if (t < 10f) continue;
            float fog = 1f - (float) Math.exp(-t / 140f);
            if (r.lit[k] == 1 && isGroundDepth(r, k)) continue;
            r.col[k] = mix(r.col[k], HAZE, fog * 0.85f);
        }
    }

    private boolean isGroundDepth(Raster r, int k) {
        // ground pixels already carry their haze: they lie on y = 0
        int x = k % r.w, y = k / r.w;
        r.ray(x + 0.5f, y + 0.5f, d);
        if (d[1] >= -0.002f) return false;
        float t = -r.camY / d[1];
        return Math.abs(1f / t - r.zb[k]) < 1e-6f;
    }

    // ---- palms ----

    private void palms(Raster r, Random rnd) {
        // far to near so nearer fronds cover farther ones
        for (int i = 13; i >= 0; i--) {
            float z = 7f + i * 7.5f;
            for (int side = -1; side <= 1; side += 2) {
                float x = side * (5.4f + (rnd.nextFloat() - 0.5f) * 0.3f);
                palm(r, rnd, x, z + (rnd.nextFloat() - 0.5f) * 0.6f, 7.2f + rnd.nextFloat() * 2.4f);
            }
        }
    }

    private void palm(Raster r, Random rnd, float x, float z, float height) {
        float lean = (rnd.nextFloat() - 0.5f) * 0.5f;
        // trunk
        float rad0 = 0.28f, rad1 = 0.2f;
        float step = 0.04f;
        for (float y = 0f; y < height; y += step) {
            float k = y / height;
            float px = x + lean * k * k;
            float vz = r.project(px, y, z, s);
            if (vz < 0.2f) continue;
            float rad = (rad0 + (rad1 - rad0) * k) * (1f + (y < 0.6f ? (0.6f - y) * 0.5f : 0f));
            float rs = rad * r.f / vz;
            boolean ring = (y % 0.2f) < 0.045f;
            int c = ring ? 0xFF5E4C39 : 0xFF84705A;
            r.disc(s[0], s[1], rs, 1f / vz, Raster.light(c, 0, 0, -1, 0.35f), 0);
            // the sunny side of the trunk
            r.disc(s[0] - rs * 0.35f, s[1], rs * 0.45f, 1f / vz + 1e-5f, Raster.light(ring ? 0xFF6E5A44 : 0xFF9C8668, -1, 0, 0, 0.9f), 0);
        }
        float tx = x + lean, ty = height, tz = z;
        // a skirt of dry fronds hanging under the crown
        int dry = 4 + rnd.nextInt(4);
        for (int i = 0; i < dry; i++) {
            float a = (float) (rnd.nextFloat() * Math.PI * 2);
            frond(r, rnd, tx, ty - 0.15f, tz, a, 1.6f + rnd.nextFloat() * 0.5f, -1.4f, 0xFF8E764C, 0xFFA88E5C);
        }
        int n = 14 + rnd.nextInt(5);
        for (int i = 0; i < n; i++) {
            float a = (float) (i * Math.PI * 2 / n + rnd.nextFloat() * 0.3f);
            float lift = 0.2f + rnd.nextFloat() * 0.5f;
            int[] greens = {0xFF56732C, 0xFF6A8633, 0xFF7E973C, 0xFF8D9A46};
            int g = greens[rnd.nextInt(greens.length)];
            frond(r, rnd, tx, ty, tz, a, 2.3f + rnd.nextFloat() * 0.9f, lift, g, rnd.nextInt(5) == 0 ? 0xFFB09A55 : g);
        }
    }

    /** One frond: a drooping spine with leaflets either side. */
    private void frond(Raster r, Random rnd, float x, float y, float z, float angle, float len, float lift,
                       int c0, int c1) {
        float dx = (float) Math.cos(angle), dz = (float) Math.sin(angle);
        float sunSide = -dx * Raster.sunX() - dz * Raster.sunZ();
        int seg = 26;
        for (int i = 0; i <= seg; i++) {
            float t = i / (float) seg;
            float px = x + dx * len * t;
            float pz = z + dz * len * t;
            float py = y + len * (lift * t - 0.9f * t * t);
            float vz = r.project(px, py, pz, s);
            if (vz < 0.2f) continue;
            float wleaf = 0.09f + 0.32f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.15f));
            int c = mixColor(c0, c1, t);
            float lightK = 0.75f + 0.25f * Math.max(-1f, Math.min(1f, -sunSide)) + 0.15f * (1f - t);
            c = scale(c, lightK);
            float ss = r.f / vz;
            r.disc(s[0], s[1], Math.max(0.5f, 0.05f * ss), 1f / vz, scale(c, 0.85f), 0);
            // leaflets hang down and out, both sides
            for (int side = -1; side <= 1; side += 2) {
                float lx = px - dz * side * wleaf, lz = pz + dx * side * wleaf;
                float ly = py - wleaf * 0.55f;
                float vz2 = r.project(lx, ly, lz, s);
                if (vz2 < 0.2f) continue;
                float sx0 = s[0], sy0 = s[1];
                r.project(px, py, pz, s);
                float sx1 = s[0], sy1 = s[1];
                int steps = Math.max(2, (int) (Math.abs(sx0 - sx1) + Math.abs(sy0 - sy1)));
                for (int k = 0; k <= steps; k++) {
                    float q = k / (float) steps;
                    r.disc(sx1 + (sx0 - sx1) * q, sy1 + (sy0 - sy1) * q, Math.max(0.5f, 0.03f * r.f / vz2),
                            1f / vz2, ((k + i) & 3) == 0 ? scale(c, 1.12f) : c, 0);
                }
            }
        }
    }

    // ---- color helpers ----

    static int mix(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    static int mixColor(int a, int b, float t) {
        return mix(a, b, t);
    }

    static int scale(int c, float k) {
        int r = Math.min(255, Math.max(0, (int) (((c >> 16) & 0xFF) * k)));
        int g = Math.min(255, Math.max(0, (int) (((c >> 8) & 0xFF) * k)));
        int b = Math.min(255, Math.max(0, (int) ((c & 0xFF) * k)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Multiplies two colors channel by channel, the second one as light. */
    static int mul(int c, int light) {
        int r = ((c >> 16) & 0xFF) * ((light >> 16) & 0xFF) / 255;
        int g = ((c >> 8) & 0xFF) * ((light >> 8) & 0xFF) / 255;
        int b = (c & 0xFF) * (light & 0xFF) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
