package com.shumtugle.torodki;

import java.util.Random;

/**
 * The street behind the game, on a summer evening by the sea. To the left a
 * promenade with two rows of palms, a stone parapet and the sea beyond it; to the
 * right a row of old seaside houses, pastel, with shutters, iron balconies and
 * awnings over the shops. A low sun from the left lays the palms' shadows across the
 * road. Painted once per camera pose into the raster, then kept and copied under
 * every frame.
 */
final class Backdrop {
    static final int HAZE = 0xFFE9D6BE;

    private final float[] d = new float[3], s = new float[2], k3 = new float[3];

    // palms, kept for the shadows they throw: base x, z, height, lean
    private final float[] pmX = new float[64], pmZ = new float[64], pmH = new float[64], pmL = new float[64];
    private int pmN;

    // the parapet between the promenade and the sea
    static final float PARAPET_X = -14.6f;
    static final float HOUSES_X = 12.5f;

    void paint(Raster r, long seed) {
        Random rnd = new Random(seed);
        java.util.Arrays.fill(r.zb, 0f);
        java.util.Arrays.fill(r.idb, 0);
        java.util.Arrays.fill(r.lit, (byte) 0);
        pmN = 0;
        groundAndSky(r);
        houses(r, rnd);
        props(r, rnd);
        planPalms(rnd);
        palmShadows(r);
        hazeOver(r);
        palms(r, rnd);
    }

    // ---- sky and ground, per pixel ----

    private void groundAndSky(Raster r) {
        Raster.lightK(1f, Raster.sunY(), k3);
        float gr = k3[0], gg = k3[1], gb = k3[2];
        float shx = Raster.sunX(), shz = Raster.sunZ();
        float sl = (float) Math.sqrt(shx * shx + shz * shz);
        shx /= sl;
        shz /= sl;
        for (int y = 0; y < r.h; y++) {
            for (int x = 0; x < r.w; x++) {
                r.ray(x + 0.5f, y + 0.5f, d);
                int k = y * r.w + x;
                float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                float dx = d[0] / l, dy = d[1] / l, dz = d[2] / l;
                if (dy < -0.002f) {
                    float t = -r.camY / d[1];
                    float wx = r.camX + d[0] * t, wz = r.camZ + d[2] * t;
                    if (wx < PARAPET_X - 0.6f) {
                        r.col[k] = sea(wx, wz, dx, dy, dz, shx, shz, t);
                        r.zb[k] = 1f / t;
                        r.lit[k] = 0;
                        continue;
                    }
                    int c = ground(wx, wz, t);
                    int cr = Math.min(255, (int) (((c >> 16) & 0xFF) * gr));
                    int cg = Math.min(255, (int) (((c >> 8) & 0xFF) * gg));
                    int cb = Math.min(255, (int) ((c & 0xFF) * gb));
                    c = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
                    float fog = 1f - (float) Math.exp(-t / 150f);
                    r.col[k] = mix(c, HAZE, fog);
                    r.zb[k] = 1f / t;
                    r.lit[k] = 1;
                } else {
                    r.col[k] = sky(dx, dy, dz, shx, shz);
                }
            }
        }
    }

    /** The evening sky: deep blue overhead, warm toward the low sun, streaks of pink cloud. */
    static int sky(float dx, float dy, float dz, float shx, float shz) {
        float el = Math.max(0f, dy);
        float h = (float) Math.sqrt(dx * dx + dz * dz);
        float toSun = h > 1e-4f ? (dx * shx + dz * shz) / h : 0f;    // 1 facing the sun
        float k = (float) Math.pow(Math.min(1f, el * 2.4f), 0.5f);
        int low = mix(0xFFF1DCC4, 0xFFF6C08A, Math.max(0f, toSun) * 0.9f);
        int c = mix(low, 0xFF3C73C0, k);
        // glow round the sun side, close to the horizon
        float glow = Math.max(0f, toSun);
        float halo = glow * glow * (float) Math.exp(-el * 5f);
        glow = glow * glow * glow * glow * (float) Math.exp(-el * 9f);
        c = mix(c, 0xFFFFC97E, halo * 0.55f);
        c = mix(c, 0xFFFFF0C8, glow * 0.8f);
        // clouds: thin streaks, stretched along the horizon
        double az = Math.atan2(dx, dz);
        float n = fbm((float) (az * 2.6), el * 22f + 3f);
        float dens = smooth(0.66f, 0.82f, n) * (float) Math.exp(-el * 2.2f) * (el > 0.01f ? 1f : el * 100f);
        if (dens > 0.01f) {
            float under = fbm((float) (az * 5.0) + 11f, el * 40f);
            int cl = mix(0xFFEFA98F, 0xFFFFD9B8, Math.max(0f, toSun) * 0.7f);
            cl = mix(cl, 0xFFC1A3B6, smooth(0.45f, 0.75f, under) * 0.55f);
            c = mix(c, cl, Math.min(1f, dens * 1.3f));
        }
        return c;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static float vnoise(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(xi, yi) / 255f, b = hash(xi + 1, yi) / 255f, c = hash(xi, yi + 1) / 255f, d2 = hash(xi + 1, yi + 1) / 255f;
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d2) * fx * fy;
    }

    private static float fbm(float x, float y) {
        return vnoise(x, y) * 0.55f + vnoise(x * 2.1f + 5f, y * 2.1f) * 0.3f + vnoise(x * 4.3f, y * 4.3f + 9f) * 0.15f;
    }

    /** The sea: darker near, paler toward the horizon, glittering on the sun side. */
    private static int sea(float x, float z, float dx, float dy, float dz, float shx, float shz, float t) {
        float far = Math.min(1f, -dy * 9f);
        int c = mix(0xFFB6CBD0, 0xFF2D6990, far);
        int n = hash((int) (x * 1.5f), (int) (z * 0.6f));
        float h = (float) Math.sqrt(dx * dx + dz * dz);
        float toSun = (dx * shx + dz * shz) / h;
        if (toSun > 0.2f && n > 236 - (int) (toSun * 30f)) c = mix(c, 0xFFFFE3B0, 0.75f);
        else if (n < 40) c = scale(c, 0.9f);
        float fog = 1f - (float) Math.exp(-t / 260f);
        return mix(c, HAZE, fog * 0.6f);
    }

    static int hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0xFF;
    }

    /** Ground color at a world point seen from distance t (unlit). */
    private static int ground(float x, float z, float t) {
        int n = hash((int) Math.floor(x * 9f), (int) Math.floor(z * 9f));
        float grit = t < 30f ? (n - 128) / 128f * (1f - t / 30f) : 0f;
        float ax = Math.abs(x);
        int c;
        if (ax < 3.75f) {
            // asphalt: dark, worn, patched
            int patch = hash((int) Math.floor(x * 0.7f + 31), (int) Math.floor(z * 0.5f));
            c = patch < 40 ? 0xFF655F58 : 0xFF77706A;
            if (z > 3.4f && z < 5.0f && ax < 3.5f) {
                float m = (x + 20f) % 0.9f;
                if (m < 0.5f) c = 0xFFE4DFD3;
            }
            if (((z > 3.15f && z < 3.36f) || (z > 5.04f && z < 5.25f)) && ax < 3.5f) {
                float m = (x + 20f) % 0.9f;
                if (m < 0.5f) c = 0xFFE0A43A;
            }
            if (ax < 0.06f && z > 5.4f && (z % 6f) < 2.6f) c = 0xFFE2DED4;
            if (z > 2.55f && z < 2.75f && x < 0f && x > -3.5f) c = 0xFFE2DED4;
            if (z > 1.5f && z < 9f && ((ax > 0.55f && ax < 0.75f) || (ax > 1.85f && ax < 2.05f))
                    && hash((int) (z * 3f), (int) (x * 20f)) < 120) c = mix(c, 0xFF3A3734, 0.45f);
        } else if (ax < 3.88f) {
            c = 0xFFDDA638;
        } else if (ax < 4.05f) {
            c = 0xFF5E5A55;
        } else if (ax < 4.3f) {
            c = 0xFFC9BFAE;                                            // curb
        } else if (x > 0f) {
            // pavement in front of the houses: square slabs
            c = 0xFFCDBFA6;
            float fx = (x - 4.3f) % 0.7f, fz = ((z % 0.7f) + 0.7f) % 0.7f;
            if (t < 35f && (fx < 0.035f || fz < 0.035f)) c = 0xFFA99A80;
            else if (hash((int) (x / 0.7f), (int) (z / 0.7f)) < 50) c = scale(c, 0.94f);
            if (x > 4.9f && x < 6.3f && soil(z)) c = 0xFF8C6E4C;
        } else {
            // the promenade: big pale stone in a herringbone of joints
            c = 0xFFDCCDB2;
            float u = (x + z) * 0.7f, v = (z - x) * 0.7f;
            float fu = ((u % 1f) + 1f) % 1f, fv = ((v % 1f) + 1f) % 1f;
            if (t < 35f && (fu < 0.04f || fv < 0.04f)) c = 0xFFB4A488;
            else if (hash((int) Math.floor(u), (int) Math.floor(v)) < 60) c = scale(c, 0.95f);
            if ((x < -4.9f && x > -6.3f || x < -9.4f && x > -10.8f) && soil(z)) c = 0xFF8C6E4C;
        }
        if (grit != 0f) c = scale(c, 1f + grit * 0.12f);
        return c;
    }

    private static boolean soil(float z) {
        float m = ((z - 7f) % 7.5f + 7.5f) % 7.5f;
        return Math.min(m, 7.5f - m) < 0.6f;
    }

    // ---- houses ----

    static final int[] WALLS = {0xFFE9A684, 0xFFEAC27C, 0xFFF0E0C2, 0xFFDB8D68, 0xFFF1DAA2, 0xFFE6B9A2, 0xFFD8C9A8};

    private void houses(Raster r, Random rnd) {
        float z = -12f;
        int n = 0;
        while (z < 230f) {
            float len = 8.7f + rnd.nextInt(3) * 2.9f;
            int storeys = 4 + rnd.nextInt(2);
            float height = 3.3f * (storeys + 1) + 0.5f;
            float depth = 14f;
            float xc = HOUSES_X + depth * 0.5f;
            int tint = WALLS[rnd.nextInt(WALLS.length)];
            r.facadeSeed = n++;
            r.box(xc, height * 0.5f, z + len * 0.5f, 1, 0, 0, 0, 1, 0, 0, 0, 1,
                    depth * 0.5f, height * 0.5f, len * 0.5f, tint, Raster.PAT_FACADE, 0);
            // the cornice that crowns each house
            r.box(HOUSES_X - 0.15f, height - 0.2f, z + len * 0.5f, 1, 0, 0, 0, 1, 0, 0, 0, 1,
                    0.25f, 0.2f, len * 0.5f, scale(tint, 1.05f), Raster.PAT_FLAT, 0);
            z += len;
        }
        // the far end of the street
        r.facadeSeed = 77;
        r.box(-2f, 10f, 250f, 1, 0, 0, 0, 1, 0, 0, 0, 1, 30f, 10f, 6f, 0xFFEBD2AC, Raster.PAT_FACADE, 0);
        // the parapet along the sea
        r.box(PARAPET_X, 0.45f, 100f, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.3f, 0.45f, 120f, 0xFFE2D4BA, Raster.PAT_STONE, 0);
        r.box(PARAPET_X, 0.95f, 100f, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.38f, 0.06f, 120f, 0xFFEDE2CC, Raster.PAT_FLAT, 0);
    }

    /** Lamp posts and bollards along the curbs, cafe parasols in front of the houses. */
    private void props(Raster r, Random rnd) {
        for (float z = 2f; z < 120f; z += 14f) {
            for (int side = -1; side <= 1; side += 2) {
                float x = side * 4.55f;
                r.box(x, 2.1f, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.06f, 2.1f, 0.06f, 0xFF2E2D2F, Raster.PAT_FLAT, 0);
                r.box(x, 4.35f, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.16f, 0.22f, 0.16f, 0xFF3A3836, Raster.PAT_FLAT, 0);
                r.box(x, 4.3f, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.13f, 0.12f, 0.13f, 0xFFF3E2B4, Raster.PAT_FLAT, 0);
                // a call box on one post: a grey case facing the street, its button lit by the game
                if (side == 1 && z == World.CALL_Z)
                    r.box(x - 0.12f, World.CALL_Y, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.07f, 0.26f, 0.17f, 0xFF8E959C, Raster.PAT_FLAT, 0);
            }
            for (int b = 1; b < 4; b++)
                r.box(4.45f, 0.4f, z + b * 3.2f, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.08f, 0.4f, 0.08f, 0xFF2E2D2F, Raster.PAT_FLAT, 0);
        }
        for (float z = 6f; z < 90f; z += 9f + rnd.nextFloat() * 8f) {
            if (rnd.nextInt(3) == 0) continue;
            float x = 10.3f + rnd.nextFloat() * 0.8f;
            int cloth = rnd.nextBoolean() ? 0xFFF3EDE0 : 0xFFE4D5B6;
            r.box(x, 1.2f, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.04f, 1.2f, 0.04f, 0xFF4A4542, Raster.PAT_FLAT, 0);
            r.box(x, 2.35f, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 1.2f, 0.12f, 1.2f, cloth, Raster.PAT_FLAT, 0);
            r.box(x, 2.5f, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.8f, 0.06f, 0.8f, cloth, Raster.PAT_FLAT, 0);
            // a table and two chairs under it
            r.box(x, 0.38f, z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.35f, 0.38f, 0.35f, 0xFF6A5A4C, Raster.PAT_FLAT, 0);
        }
    }

    /** Distance haze over the houses and props (the ground has its own). */
    private void hazeOver(Raster r) {
        for (int k = 0; k < r.col.length; k++) {
            float q = r.zb[k];
            if (q <= 0f || r.idb[k] != 0) continue;
            float t = 1f / q;
            if (t < 12f) continue;
            int x = k % r.w, y = k / r.w;
            r.ray(x + 0.5f, y + 0.5f, d);
            if (d[1] < -0.002f && Math.abs(-r.camY / d[1] - t) < 0.01f * t) continue;
            float fog = 1f - (float) Math.exp(-t / 150f);
            r.col[k] = mix(r.col[k], HAZE, fog * 0.9f);
        }
    }

    // ---- palms ----

    private void planPalms(Random rnd) {
        float[] rows = {5.6f, -5.6f, -10.1f};
        for (int i = 13; i >= 0; i--) {
            for (float rx : rows) {
                if (pmN >= pmX.length) break;
                float z = 7f + i * 7.5f + (rx < -8f ? 3.7f : 0f);
                pmX[pmN] = rx + (rnd.nextFloat() - 0.5f) * 0.3f;
                pmZ[pmN] = z + (rnd.nextFloat() - 0.5f) * 0.6f;
                pmH[pmN] = 7.2f + rnd.nextFloat() * 2.6f;
                pmL[pmN] = (rnd.nextFloat() - 0.5f) * 0.6f;
                pmN++;
            }
        }
    }

    /**
     * Every sunlit pixel of the ground and the houses asks whether a palm stands
     * between it and the sun: the trunk is a thin column, the crown a flat star of
     * fronds, so the shadows come out dappled.
     */
    private void palmShadows(Raster r) {
        float lx = Raster.sunX(), ly = Raster.sunY(), lz = Raster.sunZ();
        float hl = (float) Math.sqrt(lx * lx + lz * lz);
        float[] wpos = new float[3];
        for (int y = 0; y < r.h; y++)
            for (int x = 0; x < r.w; x++) {
                int k = y * r.w + x;
                if (r.lit[k] != 1) continue;
                float q = r.zb[k];
                if (q <= 0f) continue;
                float vz = 1f / q;
                if (vz > 160f) continue;
                r.viewToWorld((x + 0.5f - r.cx) * vz / r.f, (r.cy - y - 0.5f) * vz / r.f, vz, wpos);
                float px = r.camX + wpos[0], py = r.camY + wpos[1], pz = r.camZ + wpos[2];
                if (inPalmShadow(px, py, pz, lx, ly, lz, hl)) {
                    int c = r.col[k];
                    int rr = ((c >> 16) & 0xFF) * Raster.SH_R >> 8, gg = ((c >> 8) & 0xFF) * Raster.SH_G >> 8;
                    int bb = (c & 0xFF) * Raster.SH_B >> 8;
                    r.col[k] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                    r.lit[k] = 0;
                }
            }
    }

    private boolean inPalmShadow(float px, float py, float pz, float lx, float ly, float lz, float hl) {
        for (int i = 0; i < pmN; i++) {
            float bx = pmX[i] - px, bz = pmZ[i] - pz;
            // quick: the palm must lie toward the sun, within reach of its height
            float along = (bx * lx + bz * lz) / hl;
            if (along < -1.5f || along > (pmH[i] + 1.5f - py) / ly * hl + 3f) continue;
            float side = Math.abs(bx * lz - bz * lx) / hl;
            if (side > 3.2f) continue;
            // the trunk: where the ray passes the trunk's axis, it must be inside the trunk's height
            float hy = py + ly * (along / hl);
            if (side < 0.24f && hy > 0f && hy < pmH[i]) return true;
            // the crown: where the ray reaches the crown's height
            float tc = (pmH[i] - 0.3f - py) / ly;
            if (tc <= 0f) continue;
            float cx = px + lx * tc - (pmX[i] + pmL[i]), cz = pz + lz * tc - pmZ[i];
            float rad = (float) Math.sqrt(cx * cx + cz * cz);
            if (rad > 2.9f) continue;
            double ang = Math.atan2(cz, cx) + i * 0.7;
            float star = 0.5f + 0.5f * (float) Math.cos(ang * 8.0);
            float reach = 1.0f + 1.9f * star;
            if (rad < reach && (rad < 0.8f || hash((int) (cx * 6f), (int) (cz * 6f)) > 70)) return true;
        }
        return false;
    }

    private void palms(Raster r, Random rnd) {
        for (int i = 0; i < pmN; i++) palm(r, rnd, pmX[i], pmZ[i], pmH[i], pmL[i]);
    }

    private void palm(Raster r, Random rnd, float x, float z, float height, float lean) {
        float sunSideX = -Raster.sunX();
        float rad0 = 0.28f, rad1 = 0.2f;
        float step = 0.04f;
        for (float y = 0f; y < height; y += step) {
            float k = y / height;
            float px = x + lean * k * k;
            float vz = r.project(px, y, z, s);
            if (vz < 0.2f) continue;
            float rad = (rad0 + (rad1 - rad0) * k) * (1f + (y < 0.6f ? (0.6f - y) * 0.5f : 0f));
            float rs = rad * r.f / vz;
            boolean ring = (y % 0.2f) < 0.05f;
            int c = ring ? 0xFF3E3024 : 0xFF5E4A38;
            r.disc(s[0], s[1], rs, 1f / vz, c, 0);
            // the sun catches the trunk on its left
            r.disc(s[0] - rs * 0.4f * sunSideX, s[1], rs * 0.5f, 1f / vz + 1e-5f, ring ? 0xFF7A5C3E : 0xFFB98A5A, 0);
        }
        float tx = x + lean, ty = height, tz = z;
        int dry = 4 + rnd.nextInt(4);
        for (int i = 0; i < dry; i++) {
            float a = (float) (rnd.nextFloat() * Math.PI * 2);
            frond(r, rnd, tx, ty - 0.15f, tz, a, 1.6f + rnd.nextFloat() * 0.5f, -1.4f, 0xFF7A5E38, 0xFFA07E4C);
        }
        int n = 15 + rnd.nextInt(5);
        int[] greens = {0xFF3F5F22, 0xFF4F6F28, 0xFF5E7E2E, 0xFF6C8434};
        for (int i = 0; i < n; i++) {
            float a = (float) (i * Math.PI * 2 / n + rnd.nextFloat() * 0.3f);
            float lift = 0.2f + rnd.nextFloat() * 0.5f;
            int g = greens[rnd.nextInt(greens.length)];
            frond(r, rnd, tx, ty, tz, a, 2.4f + rnd.nextFloat() * 0.9f, lift, g, rnd.nextInt(5) == 0 ? 0xFF9C8A48 : g);
        }
    }

    /** One frond: a drooping spine with leaflets either side, lit gold on the sun side. */
    private void frond(Raster r, Random rnd, float x, float y, float z, float angle, float len, float lift,
                       int c0, int c1) {
        float dx = (float) Math.cos(angle), dz = (float) Math.sin(angle);
        float facing = (dx * Raster.sunX() + dz * Raster.sunZ()) / (float) Math.sqrt(Raster.sunX() * Raster.sunX() + Raster.sunZ() * Raster.sunZ());
        int seg = 26;
        for (int i = 0; i <= seg; i++) {
            float t = i / (float) seg;
            float px = x + dx * len * t;
            float pz = z + dz * len * t;
            float py = y + len * (lift * t - 0.9f * t * t);
            float vz = r.project(px, py, pz, s);
            if (vz < 0.2f) continue;
            float wleaf = 0.09f + 0.34f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.15f));
            int c = mix(c0, c1, t);
            // the sun side of the crown glows warm, the far side sinks into shade
            float lit = Math.max(0f, facing) * 0.9f + 0.2f * (1f - t);
            c = mix(scale(c, 0.65f), mix(c, 0xFFD8C060, 0.35f), Math.min(1f, lit + 0.25f));
            float ss = r.f / vz;
            r.disc(s[0], s[1], Math.max(0.5f, 0.05f * ss), 1f / vz, scale(c, 0.85f), 0);
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
                            1f / vz2, ((k + i) & 3) == 0 ? scale(c, 1.15f) : c, 0);
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

    static int scale(int c, float k) {
        int r = Math.min(255, Math.max(0, (int) (((c >> 16) & 0xFF) * k)));
        int g = Math.min(255, Math.max(0, (int) (((c >> 8) & 0xFF) * k)));
        int b = Math.min(255, Math.max(0, (int) ((c & 0xFF) * k)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
