package com.shumtugle.torodki;

import java.util.Random;

/**
 * The square of the opening: another corner of the same seaside town, built light enough
 * to be drawn afresh every frame while the camera flies. A paved square with the pedestal
 * in the middle; behind it a road across, and another along its right side, with cars
 * going both ways; old pastel houses round it, the same fronts as the game's street, with
 * shutters, balconies and awnings; to the left a promenade with palms, a parapet and the
 * sea. People cross the square. The sky is looked up from a table made once.
 */
final class Town {
    static final float Z = Intro.Z;
    // the square, the roads round it, where the houses begin
    static final float SQ_X = 14f, SQ_Z0 = Z - 14f, SQ_Z1 = Z + 11f;
    static final float BACK_Z0 = SQ_Z1, BACK_Z1 = Z + 17f, BACK_HOUSES = Z + 20f;
    static final float RIGHT_X0 = SQ_X, RIGHT_X1 = 20f, RIGHT_HOUSES = 23f;
    static final float FRONT_HOUSES = Z - 18f;
    static final float PROM_X = -14f, PARAPET_X = -24f, SEA_X = -24.6f;

    private final float[] d = new float[3], s = new float[2];

    // houses: center, half sizes, storeys' height, tint
    private final int houseN;
    private final float[] hX, hZ, hHx, hHz, hH;
    private final int[] hTint;

    // palms: x, z, height, lean, and the seed each is drawn from
    private static final float[][] PALMS = {{-17f, Z - 12f, 8.6f, 0.6f}, {-21f, Z - 6f, 9.4f, -0.5f}, {-17f, Z + 1f, 8.2f, 0.4f},
            {-21f, Z + 8f, 9.8f, 0.7f}, {-17f, Z + 15f, 8.8f, -0.4f}, {-21f, Z + 22f, 9f, 0.5f}, {11.5f, Z - 11f, 7.6f, 0.5f},
            {11.5f, Z + 8.5f, 8.2f, -0.6f}};
    private final Backdrop palms = new Backdrop();

    // cars: lane (0, 1 across behind the square, 2, 3 along its right), where on it at the
    // start, speed, color
    private static final int CARS = 9;
    private final int[] carLane = new int[CARS], carCol = new int[CARS];
    private final float[] carAt = new float[CARS], carV = new float[CARS];
    private static final int[] CAR_COLORS = {0xFFE8C04A, 0xFFB8443A, 0xFF4F8C8A, 0xFFEDE4CE, 0xFF2F4A6E, 0xFF8A9A5A, 0xFFD87A3A, 0xFF6E3A5A};

    // people: a walk from a to b and back, a pace, colors of shirt and trousers
    private static final int PEOPLE = 14;
    private final float[] pAx = new float[PEOPLE], pAz = new float[PEOPLE], pBx = new float[PEOPLE], pBz = new float[PEOPLE];
    private final float[] pV = new float[PEOPLE], pPh = new float[PEOPLE];
    private final int[] pShirt = new int[PEOPLE], pLegs = new int[PEOPLE], pSkin = new int[PEOPLE];
    private static final int[] SHIRTS = {0xFFF2EEE2, 0xFF5A86B8, 0xFFD9654E, 0xFFE8C860, 0xFF6EA87A, 0xFFE59AB0, 0xFF3A3A40};
    private static final int[] LEGS = {0xFF2E3446, 0xFF4A4038, 0xFFD8CCB0, 0xFF5A6E8A};
    private static final int[] SKINS = {0xFFE8BC98, 0xFFC8946E, 0xFF8C5E40, 0xFFF0CDB0};

    // the sky, looked up by direction: azimuth across, elevation up
    private static final int SKY_W = 720, SKY_H = 160;
    private static final float SKY_EL = 1.4f;
    private final int[] sky = new int[SKY_W * SKY_H];

    Town() {
        Random r = new Random(0x70A11L);
        float[] x = new float[64], z = new float[64], hx = new float[64], hz = new float[64], hh = new float[64];
        int[] tint = new int[64];
        int n = 0;
        // behind the road across the square, a row facing it
        for (float px = PROM_X - 4f; px < 70f && n < 64; ) {
            float len = 8.7f + r.nextInt(3) * 2.9f;
            x[n] = px + len * 0.5f;
            z[n] = BACK_HOUSES + 7f;
            hx[n] = len * 0.5f;
            hz[n] = 7f;
            hh[n] = 3.3f * (4 + r.nextInt(2) + 1) + 0.5f;
            tint[n] = Backdrop.WALLS[r.nextInt(Backdrop.WALLS.length)];
            n++;
            px += len;
        }
        // along the right of the square, beyond its road
        for (float pz = FRONT_HOUSES - 30f; pz < BACK_Z1 && n < 64; ) {
            float len = 8.7f + r.nextInt(3) * 2.9f;
            x[n] = RIGHT_HOUSES + 7f;
            z[n] = pz + len * 0.5f;
            hx[n] = 7f;
            hz[n] = len * 0.5f;
            hh[n] = 3.3f * (4 + r.nextInt(2) + 1) + 0.5f;
            tint[n] = Backdrop.WALLS[r.nextInt(Backdrop.WALLS.length)];
            n++;
            pz += len;
        }
        // before the square, the row the camera comes past: open at the promenade's end,
        // where the view swings down in its arc
        for (float px = -4f; px < RIGHT_X0 && n < 64; ) {
            float len = Math.min(RIGHT_X0 - px, 8.7f + r.nextInt(3) * 2.9f);
            x[n] = px + len * 0.5f;
            z[n] = FRONT_HOUSES - 7f;
            hx[n] = len * 0.5f;
            hz[n] = 7f;
            hh[n] = 3.3f * (3 + r.nextInt(2) + 1) + 0.5f;
            tint[n] = Backdrop.WALLS[r.nextInt(Backdrop.WALLS.length)];
            n++;
            px += len;
        }
        houseN = n;
        hX = java.util.Arrays.copyOf(x, n);
        hZ = java.util.Arrays.copyOf(z, n);
        hHx = java.util.Arrays.copyOf(hx, n);
        hHz = java.util.Arrays.copyOf(hz, n);
        hH = java.util.Arrays.copyOf(hh, n);
        hTint = java.util.Arrays.copyOf(tint, n);
        for (int i = 0; i < CARS; i++) {
            carLane[i] = i % 4;
            carAt[i] = r.nextFloat() * 120f;
            carV[i] = 7f + r.nextFloat() * 5f;
            carCol[i] = CAR_COLORS[r.nextInt(CAR_COLORS.length)];
        }
        for (int i = 0; i < PEOPLE; i++) {
            // across the square, round the pedestal, along the promenade
            if (i < 9) {
                pAx[i] = -SQ_X + 2f + r.nextFloat() * (2f * SQ_X - 4f);
                pAz[i] = r.nextBoolean() ? SQ_Z0 + 1f + r.nextFloat() * 4f : Z + 2.5f + r.nextFloat() * 7f;
                pBx[i] = -SQ_X + 2f + r.nextFloat() * (2f * SQ_X - 4f);
                pBz[i] = pAz[i] + (r.nextFloat() - 0.5f) * 3f;
            } else {
                pAx[i] = -19f + (r.nextFloat() - 0.5f) * 2f;
                pAz[i] = Z - 20f + r.nextFloat() * 10f;
                pBx[i] = pAx[i];
                pBz[i] = pAz[i] + 22f + r.nextFloat() * 8f;
            }
            pV[i] = 1.1f + r.nextFloat() * 0.5f;
            pPh[i] = r.nextFloat() * 60f;
            pShirt[i] = SHIRTS[r.nextInt(SHIRTS.length)];
            pLegs[i] = LEGS[r.nextInt(LEGS.length)];
            pSkin[i] = SKINS[r.nextInt(SKINS.length)];
        }
        Raster.overcast(false);
        float shx = Raster.sunX(), shz = Raster.sunZ(), sl = (float) Math.sqrt(shx * shx + shz * shz);
        for (int j = 0; j < SKY_H; j++)
            for (int i = 0; i < SKY_W; i++) {
                double az = (i + 0.5) / SKY_W * Math.PI * 2 - Math.PI, el = (j + 0.5) / SKY_H * SKY_EL;
                float c = (float) Math.cos(el);
                sky[j * SKY_W + i] = Backdrop.sky((float) (Math.sin(az) * c), (float) Math.sin(el), (float) (Math.cos(az) * c), shx / sl, shz / sl);
            }
    }

    // ---- the ground and the sky, every pixel ----

    /** Clears the frame for what stands on the ground: drawn first, the ground fills round it. */
    void begin(Raster r) {
        java.util.Arrays.fill(r.zb, 0f);
        java.util.Arrays.fill(r.idb, 0);
        java.util.Arrays.fill(r.lit, (byte) 0);
    }

    /**
     * The ground and the sky, only where nothing stands: from a camera above the ground
     * nothing standing on it can be hidden by it, so it is drawn after everything else.
     * The ray through each pixel is stepped along the row.
     */
    void ground(Raster r) {
        Raster.lightK(1f, Raster.sunY(), d);
        float gr = d[0], gg = d[1], gb = d[2];
        int[] col = r.col;
        float[] zb = r.zb;
        byte[] lit = r.lit;
        float[] e = new float[3];
        for (int y = 0; y < r.h; y++) {
            r.ray(0.5f, y + 0.5f, d);
            r.ray(1.5f, y + 0.5f, e);
            float sx = e[0] - d[0], sy = e[1] - d[1], sz = e[2] - d[2];
            float dx = d[0] - sx, dy = d[1] - sy, dz = d[2] - sz;
            for (int x = 0; x < r.w; x++) {
                dx += sx;
                dy += sy;
                dz += sz;
                int k = y * r.w + x;
                if (zb[k] != 0f) continue;
                if (dy < -1e-4f) {
                    float t = -r.camY / dy;
                    float wx = r.camX + dx * t, wz = r.camZ + dz * t;
                    float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float dist = t * l;
                    int c;
                    if (wx < SEA_X) {
                        // the sea: deep blue, lighter far off, glinting toward the sun
                        int n = Backdrop.hash((int) Math.floor(wx * 0.8f + wz * 0.3f), (int) Math.floor(wz * 1.6f));
                        c = Backdrop.mix(0xFF2E6A8E, 0xFF7FA9C0, Math.min(1f, dist / 220f));
                        if (n > 236) c = Backdrop.mix(c, 0xFFF2E6CC, 0.5f);
                        lit[k] = 0;
                    } else {
                        c = pave(wx, wz, dist);
                        c = 0xFF000000 | (Math.min(255, (int) (((c >> 16) & 0xFF) * gr)) << 16)
                                | (Math.min(255, (int) (((c >> 8) & 0xFF) * gg)) << 8) | Math.min(255, (int) ((c & 0xFF) * gb));
                        lit[k] = 1;
                    }
                    float fog = dist / (dist + 180f);
                    col[k] = Backdrop.mix(c, Backdrop.HAZE, fog);
                    zb[k] = 1f / dist;
                } else {
                    float h = (float) Math.sqrt(dx * dx + dz * dz);
                    float el = (float) Math.atan2(dy, h);
                    float az = (float) Math.atan2(dx, dz);
                    int i = (int) ((az + Math.PI) / (Math.PI * 2) * SKY_W);
                    int j = (int) (Math.max(0f, el) / SKY_EL * SKY_H);
                    col[k] = sky[Math.min(SKY_H - 1, j) * SKY_W + Math.max(0, Math.min(SKY_W - 1, i))];
                    zb[k] = 0f;
                    lit[k] = 0;
                }
            }
        }
    }

    /** The unlit ground at (x, z): paving, asphalt, curbs, the promenade, a little grit near. */
    private static int pave(float x, float z, float dist) {
        int c;
        boolean back = z > BACK_Z0 && z < BACK_Z1 && x > PROM_X, right = x > RIGHT_X0 && x < RIGHT_X1 && z < BACK_Z1;
        if (back || right) {
            int patch = Backdrop.hash((int) Math.floor(x * 0.6f + 31), (int) Math.floor(z * 0.5f));
            c = patch < 40 ? 0xFF655F58 : 0xFF77706A;
            // the middle line, dashed
            if (back && Math.abs(z - (BACK_Z0 + BACK_Z1) * 0.5f) < 0.08f && ((x % 6f) + 6f) % 6f < 2.6f) c = 0xFFE2DED4;
            if (right && !back && Math.abs(x - (RIGHT_X0 + RIGHT_X1) * 0.5f) < 0.08f && ((z % 6f) + 6f) % 6f < 2.6f) c = 0xFFE2DED4;
            // curbs
            if (back && (z < BACK_Z0 + 0.25f || z > BACK_Z1 - 0.25f)) c = 0xFFC9BFAE;
            if (right && !back && (x < RIGHT_X0 + 0.25f || x > RIGHT_X1 - 0.25f)) c = 0xFFC9BFAE;
        } else if (x < PROM_X) {
            // the promenade: big pale stone in a herringbone of joints, beds round the palms
            c = 0xFFDCCDB2;
            float u = (x + z) * 0.7f, v = (z - x) * 0.7f;
            float fu = ((u % 1f) + 1f) % 1f, fv = ((v % 1f) + 1f) % 1f;
            if (dist < 45f && (fu < 0.04f || fv < 0.04f)) c = 0xFFB4A488;
            else if (Backdrop.hash((int) Math.floor(u), (int) Math.floor(v)) < 60) c = Backdrop.scale(c, 0.95f);
        } else if (Math.abs(x) < SQ_X && z > SQ_Z0 && z < SQ_Z1) {
            // the square: warm stone laid in rings round the pedestal, a darker band at the rim
            float rx = x, rz = z - Z, rr = (float) Math.sqrt(rx * rx + rz * rz * 1.4f);
            c = 0xFFD9C3A0;
            float ring = rr % 1.2f;
            if (dist < 50f && ring < 0.05f) c = 0xFFB5A07E;
            if (rr > 9.5f && rr < 10.3f) c = 0xFFB88E6A;
            if (Backdrop.hash((int) Math.floor(x * 1.3f), (int) Math.floor(z * 1.3f)) < 30) c = Backdrop.scale(c, 0.95f);
        } else {
            // pavements: square slabs
            c = 0xFFCDBFA6;
            float fx = ((x % 0.7f) + 0.7f) % 0.7f, fz = ((z % 0.7f) + 0.7f) % 0.7f;
            if (dist < 35f && (fx < 0.035f || fz < 0.035f)) c = 0xFFA99A80;
        }
        if (dist < 25f) {
            int n = Backdrop.hash((int) Math.floor(x * 9f), (int) Math.floor(z * 9f));
            c = Backdrop.scale(c, 1f + (n - 128) / 128f * 0.1f * (1f - dist / 25f));
        }
        return c;
    }

    // ---- the houses, the parapet, the palms ----

    void build(Raster r) {
        // the roofs first: flat, of old tiles or tarred, with chimneys; drawn before the walls
        // they hide the tops of the walls from above, which then cost nothing
        for (int i = 0; i < houseN; i++) {
            int roof = (i % 3) == 0 ? 0xFF8E8478 : (i & 1) == 0 ? 0xFFB8704E : 0xFFA9654A;
            r.box(hX[i], hH[i] + 0.08f, hZ[i], 1, 0, 0, 0, 1, 0, 0, 0, 1, hHx[i] - 0.05f, 0.08f, hHz[i] - 0.05f, roof, Raster.PAT_STONE, 0);
            for (int c = 0; c < 2; c++) {
                float ox = (c == 0 ? -0.45f : 0.35f) * hHx[i], oz = (c == 0 ? 0.3f : -0.4f) * hHz[i];
                r.box(hX[i] + ox, hH[i] + 0.7f, hZ[i] + oz, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.35f, 0.6f, 0.35f, 0xFFC9B9A0, Raster.PAT_FLAT, 0);
            }
        }
        for (int i = 0; i < houseN; i++) {
            r.facadeSeed = 200 + i;
            r.box(hX[i], hH[i] * 0.5f, hZ[i], 1, 0, 0, 0, 1, 0, 0, 0, 1, hHx[i], hH[i] * 0.5f, hHz[i], hTint[i], Raster.PAT_FACADE, 0);
            r.box(hX[i], hH[i] - 0.2f, hZ[i], 1, 0, 0, 0, 1, 0, 0, 0, 1, hHx[i] + 0.15f, 0.2f, hHz[i] + 0.15f,
                    Backdrop.scale(hTint[i], 1.05f), Raster.PAT_FLAT, 0);
        }
        // the parapet along the sea
        r.box(PARAPET_X, 0.45f, Z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.3f, 0.45f, 90f, 0xFFE2D4BA, Raster.PAT_STONE, 0);
        r.box(PARAPET_X, 0.95f, Z, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0.38f, 0.06f, 90f, 0xFFEDE2CC, Raster.PAT_FLAT, 0);
        for (int i = 0; i < PALMS.length; i++) {
            float[] p = PALMS[i];
            // a palm far off on the screen is not worth its fronds
            float vz = r.project(p[0], p[2] * 0.6f, p[1], s);
            if (vz < 0.5f || s[0] < -80 || s[0] > r.w + 80) continue;
            palms.palmAt(r, 900L + i, p[0], p[1], p[2], p[3]);
        }
    }

    // ---- traffic and people ----

    void life(Raster r, float t, int idBase) {
        int id = idBase;
        for (int i = 0; i < CARS; i++) {
            int lane = carLane[i];
            float run = ((carAt[i] + carV[i] * t) % 130f + 130f) % 130f - 65f;
            float x, z, yaw;
            if (lane == 0) {
                x = run;
                z = BACK_Z0 + 1.6f;
                yaw = (float) Math.PI * 0.5f;
            } else if (lane == 1) {
                x = -run;
                z = BACK_Z1 - 1.6f;
                yaw = -(float) Math.PI * 0.5f;
            } else if (lane == 2) {
                x = RIGHT_X0 + 1.6f;
                z = Z - run;
                yaw = (float) Math.PI;
            } else {
                x = RIGHT_X1 - 1.6f;
                z = Z + run;
                yaw = 0f;
            }
            // the cars of the road across go round the promenade's end, out of sight
            if ((lane < 2 && x < PROM_X + 2f) || (lane >= 2 && z > BACK_Z1)) continue;
            car(r, x, z, yaw, carCol[i], (id++) << 3);
        }
        for (int i = 0; i < PEOPLE; i++) {
            float dx = pBx[i] - pAx[i], dz = pBz[i] - pAz[i], len = (float) Math.sqrt(dx * dx + dz * dz) + 0.01f;
            float w = (pPh[i] + t * pV[i]) % (2f * len);
            float k = w < len ? w / len : 2f - w / len;
            float yaw = (float) Math.atan2(dx, dz) + (w < len ? 0f : (float) Math.PI);
            person(r, pAx[i] + dx * k, pAz[i] + dz * k, yaw, t * pV[i] * 3.2f + i, pShirt[i], pLegs[i], pSkin[i], (id++) << 3);
        }
    }

    private void car(Raster r, float x, float z, float yaw, int col, int id) {
        float c = (float) Math.cos(yaw), sn = (float) Math.sin(yaw);
        // local x across, z along the way it goes
        box(r, x, z, c, sn, 0f, 0.6f, 0f, 0.9f, 0.36f, 2.1f, col, id);
        box(r, x, z, c, sn, 0f, 1.18f, -0.2f, 0.78f, 0.26f, 1.1f, Backdrop.scale(col, 0.95f), id);
        box(r, x, z, c, sn, 0f, 1.18f, -0.2f, 0.8f, 0.2f, 1.0f, 0xFF39424C, id + 8);           // the windows
        for (int w = 0; w < 4; w++)
            box(r, x, z, c, sn, (w & 1) == 0 ? -0.82f : 0.82f, 0.32f, (w & 2) == 0 ? -1.35f : 1.35f, 0.14f, 0.32f, 0.32f, 0xFF242222, id + 16);
    }

    private void person(Raster r, float x, float z, float yaw, float phase, int shirt, int legs, int skin, int id) {
        float c = (float) Math.cos(yaw), sn = (float) Math.sin(yaw);
        float swing = (float) Math.sin(phase) * 0.16f;
        box(r, x, z, c, sn, -0.1f, 0.44f, swing, 0.075f, 0.44f, 0.08f, legs, id);
        box(r, x, z, c, sn, 0.1f, 0.44f, -swing, 0.075f, 0.44f, 0.08f, legs, id);
        box(r, x, z, c, sn, 0f, 1.17f, 0f, 0.2f, 0.3f, 0.12f, shirt, id + 8);
        box(r, x, z, c, sn, -0.26f, 1.15f, -swing * 0.8f, 0.055f, 0.27f, 0.06f, shirt, id + 8);
        box(r, x, z, c, sn, 0.26f, 1.15f, swing * 0.8f, 0.055f, 0.27f, 0.06f, shirt, id + 8);
        box(r, x, z, c, sn, 0f, 1.6f, 0f, 0.1f, 0.12f, 0.11f, skin, id + 16);
    }

    /** A box turned about the vertical by (c, sn), at an offset in its own frame from (x, z). */
    private static void box(Raster r, float x, float z, float c, float sn, float ox, float oy, float oz,
                            float hx, float hy, float hz, int col, int id) {
        float wx = x + c * ox + sn * oz, wz = z - sn * ox + c * oz;
        r.box(wx, oy, wz, c, 0, sn, 0, 1, 0, -sn, 0, c, hx, hy, hz, col, Raster.PAT_FLAT, id);
        r.shadowBox(wx, oy, wz, c, 0, sn, 0, 1, 0, -sn, 0, c, hx, hy, hz);
    }
}
