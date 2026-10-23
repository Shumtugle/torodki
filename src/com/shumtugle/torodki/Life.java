package com.shumtugle.torodki;

import java.util.Random;

/**
 * The street around the plinth, kept in the background: people strolling along the
 * pavements, cars parked at the curbs, and cars that come down the road, turn round
 * short of the plinth and drive back the way they came. Nobody here notices the game.
 * People are drawn round, from shaded spheres and tapered limbs; cars are small sedans
 * with glass, chrome and lights. Everyone casts a soft evening shadow. None of it
 * touches the physics or makes a sound.
 */
final class Life {
    // ---- people ----
    private static final int PEOPLE = 14;
    private final float[] px = new float[PEOPLE], pz = new float[PEOPLE], pYaw = new float[PEOPLE];
    private final float[] pSpeed = new float[PEOPLE], pPhase = new float[PEOPLE];
    private final float[] pScale = new float[PEOPLE], pDir = new float[PEOPLE];
    private final int[] pSkin = new int[PEOPLE], pTop = new int[PEOPLE], pLegs = new int[PEOPLE], pHair = new int[PEOPLE];
    private final int[] pKind = new int[PEOPLE];      // 0 shorts, 1 trousers, 2 skirt
    private final boolean[] pHat = new boolean[PEOPLE], pLongHair = new boolean[PEOPLE], pBag = new boolean[PEOPLE];

    // ---- cars on the move ----
    private static final int CARS = 3;
    private static final int OFF = 0, COME = 1, LOOK = 2, TURN = 3, GO = 4;
    private static final float LANE = 1.3f;
    private final float[] cx = new float[CARS], cz = new float[CARS], cYaw = new float[CARS], cV = new float[CARS];
    private final float[] cStop = new float[CARS], cTime = new float[CARS], cLane = new float[CARS], cTheta = new float[CARS];
    private final int[] cState = new int[CARS], cColor = new int[CARS];
    private float nextCar = 3f;

    // ---- parked cars and cones ----
    private static final float[] PARK_X = {-3.05f, -3.05f, -3.05f, -3.05f, -3.05f, -3.05f, 3.05f, 3.05f, 3.05f, 3.05f, 3.05f};
    private static final float[] PARK_Z = {15.0f, 20.6f, 29.0f, 34.6f, 47.5f, 61.0f, 17.5f, 23.2f, 31.0f, 41.5f, 55.0f};
    private final int[] parkColor = new int[PARK_X.length];
    private final float[] parkYaw = new float[PARK_X.length];
    private static final float[] CONE_X = {-2.75f, -2.45f, 2.6f};
    private static final float[] CONE_Z = {10.0f, 10.9f, 9.9f};

    private final Random rnd = new Random(2026);

    static final int[] SKINS = {0xFFE9BC98, 0xFFD69F7A, 0xFFB98058, 0xFF7E5236, 0xFFF0CDAE};
    static final int[] TOPS = {0xFFF4F1EA, 0xFFD9735A, 0xFFE8C25A, 0xFF5E9E92, 0xFF6F92C6, 0xFFD58FAE, 0xFF2E2E34, 0xFFB8CF8A};
    static final int[] LEGS = {0xFF3F5679, 0xFFCDBB98, 0xFF54704A, 0xFF2F2F36, 0xFFE3DDCF, 0xFFB44A3E};
    static final int[] HAIRS = {0xFF2E2118, 0xFF5A3C24, 0xFFC9A25E, 0xFF161311, 0xFF8A4A26};
    static final int[] PAINT = {0xFFE9B63A, 0xFFB5352C, 0xFFE9E1CB, 0xFF2D3A57, 0xFF6E9DB0, 0xFF9DBB8E, 0xFF3A3B40, 0xFFF4F2EC};

    // the way shadows fall: away from the sun, along the ground
    private static final float SHX, SHZ, SH_LEN;

    static {
        float hx = -Raster.sunX(), hz = -Raster.sunZ();
        float l = (float) Math.sqrt(hx * hx + hz * hz);
        SHX = hx / l;
        SHZ = hz / l;
        SH_LEN = l / Raster.sunY();
    }

    Life() {
        for (int i = 0; i < PEOPLE; i++) spawnPerson(i, true);
        for (int i = 0; i < PARK_X.length; i++) {
            parkColor[i] = PAINT[rnd.nextInt(PAINT.length)];
            parkYaw[i] = PARK_X[i] < 0 ? 0f : (float) Math.PI;
        }
    }

    private void spawnPerson(int i, boolean anywhere) {
        float side = rnd.nextBoolean() ? 1f : -1f;
        px[i] = side * (4.8f + rnd.nextFloat() * (side > 0 ? 4.6f : 2.0f));
        boolean up = rnd.nextBoolean();
        pDir[i] = up ? 1f : -1f;
        pz[i] = anywhere ? 3f + rnd.nextFloat() * 70f : (up ? -2f : 80f);
        pYaw[i] = up ? 0f : (float) Math.PI;
        pSpeed[i] = 1.0f + rnd.nextFloat() * 0.5f;
        pPhase[i] = rnd.nextFloat() * 6f;
        pScale[i] = rnd.nextInt(7) == 0 ? 0.7f : 0.92f + rnd.nextFloat() * 0.14f;
        pSkin[i] = SKINS[rnd.nextInt(SKINS.length)];
        pTop[i] = TOPS[rnd.nextInt(TOPS.length)];
        pLegs[i] = LEGS[rnd.nextInt(LEGS.length)];
        pHair[i] = HAIRS[rnd.nextInt(HAIRS.length)];
        pKind[i] = rnd.nextInt(3);
        pLongHair[i] = pKind[i] == 2 || rnd.nextInt(5) == 0;
        pHat[i] = rnd.nextInt(4) == 0;
        pBag[i] = rnd.nextInt(3) == 0;
    }

    // ---- simulation ----

    void update(float dt) {
        for (int i = 0; i < PEOPLE; i++) {
            pz[i] += pDir[i] * pSpeed[i] * dt;
            pPhase[i] += dt * pSpeed[i] * 4.2f;
            if (pz[i] > 82f || pz[i] < -4f) spawnPerson(i, false);
        }
        cars(dt);
    }

    private void cars(float dt) {
        nextCar -= dt;
        if (nextCar <= 0f) {
            nextCar = 7f + rnd.nextFloat() * 8f;
            for (int c = 0; c < CARS; c++)
                if (cState[c] == OFF) {
                    spawnCar(c);
                    break;
                }
        }
        for (int c = 0; c < CARS; c++) {
            switch (cState[c]) {
                case COME: {
                    float d = cz[c] - cStop[c];
                    float want = Math.min(10f, (float) Math.sqrt(Math.max(0f, 2f * 4.5f * d)));
                    cV[c] = approach(cV[c], want, 6f, dt);
                    for (int o = 0; o < CARS; o++)
                        if (o != c && cState[o] != OFF && cz[o] < cz[c] && cz[c] - cz[o] < 12f)
                            cV[c] = Math.min(cV[c], Math.max(0f, cz[c] - cz[o] - 8f));
                    cz[c] -= cV[c] * dt;
                    if (d < 0.3f && cV[c] < 0.3f) {
                        cState[c] = LOOK;
                        cTime[c] = 0.8f + rnd.nextFloat() * 0.8f;
                        cV[c] = 0f;
                    }
                    break;
                }
                case LOOK:
                    cTime[c] -= dt;
                    if (cTime[c] <= 0f) {
                        boolean clear = true;
                        for (int o = 0; o < CARS; o++)
                            if (o != c && cState[o] != OFF && Math.abs(cz[o] - cz[c]) < 12f) clear = false;
                        if (clear) {
                            cState[c] = TURN;
                            cTheta[c] = 0f;
                        } else {
                            cTime[c] = 0.5f;
                        }
                    }
                    break;
                case TURN: {
                    float r = Math.abs(cLane[c]);
                    cTheta[c] += 2.2f / r * dt;
                    float t = Math.min((float) Math.PI, cTheta[c]);
                    cx[c] = cLane[c] * (float) Math.cos(t);
                    cz[c] = cStop[c] - r * (float) Math.sin(t);
                    float dx = -cLane[c] * (float) Math.sin(t), dz = -r * (float) Math.cos(t);
                    cYaw[c] = (float) Math.atan2(dx, dz);
                    if (cTheta[c] >= Math.PI) {
                        cState[c] = GO;
                        cV[c] = 2.2f;
                        cYaw[c] = 0f;
                    }
                    break;
                }
                case GO:
                    cV[c] = approach(cV[c], 12f, 3f, dt);
                    cz[c] += cV[c] * dt;
                    if (cz[c] > 170f) cState[c] = OFF;
                    break;
                default:
                    break;
            }
        }
    }

    private void spawnCar(int c) {
        cLane[c] = rnd.nextBoolean() ? LANE : -LANE;
        cx[c] = cLane[c];
        cz[c] = 150f + rnd.nextFloat() * 20f;
        cYaw[c] = (float) Math.PI;
        cV[c] = 10f;
        cStop[c] = Builder.CZ + 10f + rnd.nextFloat() * 8f;
        cColor[c] = PAINT[rnd.nextInt(PAINT.length)];
        cState[c] = COME;
    }

    private static float approach(float v, float target, float rate, float dt) {
        if (v < target) return Math.min(target, v + rate * dt);
        return Math.max(target, v - rate * 1.6f * dt);
    }

    // ---- drawing ----

    private float bx, bz, cyw, syw;
    private Raster ras;
    private float fog;
    private final float[] scr = new float[2], kd = new float[3], kl = new float[3];
    private float sunSx = -1f;      // the sun's side of things on screen: -1 left, 1 right

    void draw(Raster r) {
        ras = r;
        Raster.lightK(0.3f, 0f, kd);
        Raster.lightK(0.3f, 0.9f, kl);
        sunSx = Raster.sunX() * r.cyw - Raster.sunZ() * r.syw < 0f ? -1f : 1f;
        // soft shadows first, then everything that casts them
        for (int i = 0; i < PARK_X.length; i++) carShadow(PARK_X[i], PARK_Z[i]);
        for (int c = 0; c < CARS; c++) if (cState[c] != OFF) carShadow(cx[c], cz[c]);
        for (int i = 0; i < PEOPLE; i++) personShadow(i);
        // the cones stand at the foot of the plinth, wherever it is
        float dz = Builder.CZ - Builder.BASE_CZ;
        for (int i = 0; i < CONE_X.length; i++)
            r.softShadow(CONE_X[i], CONE_Z[i] + dz, CONE_X[i] + SHX * 0.7f * SH_LEN, CONE_Z[i] + dz + SHZ * 0.7f * SH_LEN, 0.2f, 0.06f, 0.75f);
        for (int i = 0; i < PARK_X.length; i++) car(PARK_X[i], PARK_Z[i], parkYaw[i], parkColor[i], (5000 + i) << 3);
        for (int c = 0; c < CARS; c++) if (cState[c] != OFF) car(cx[c], cz[c], cYaw[c], cColor[c], (4000 + c) << 3);
        for (int i = 0; i < CONE_X.length; i++) cone(CONE_X[i], CONE_Z[i] + dz);
        for (int i = 0; i < PEOPLE; i++) drawPerson(i);
    }

    private void fogAt(float x, float z) {
        float d = Math.max(1f, ras.depth(x, 1f, z));
        fog = (1f - (float) Math.exp(-d / 150f)) * 0.9f;
    }

    private int shade(int c, float[] k) {
        int r = Math.min(255, (int) (((c >> 16) & 0xFF) * k[0]));
        int g = Math.min(255, (int) (((c >> 8) & 0xFF) * k[1]));
        int b = Math.min(255, (int) ((c & 0xFF) * k[2]));
        return Backdrop.mix(0xFF000000 | (r << 16) | (g << 8) | b, Backdrop.HAZE, fog);
    }

    private void frame(float x, float z, float yaw) {
        bx = x;
        bz = z;
        cyw = (float) Math.cos(yaw);
        syw = (float) Math.sin(yaw);
    }

    private float wx(float lx, float lz) {
        return bx + lx * cyw + lz * syw;
    }

    private float wz(float lx, float lz) {
        return bz - lx * syw + lz * cyw;
    }

    // ---- people: round shapes ----

    /**
     * A tapered limb from local point a to b, radius ra to rb, drawn as a row of shaded
     * spheres: first the shadow side, then the side the sun lights.
     */
    private void limb(float ax, float ay, float az, float bx2, float by, float bz2, float ra, float rb, int color) {
        float x0 = wx(ax, az), z0 = wz(ax, az), x1 = wx(bx2, bz2), z1 = wz(bx2, bz2);
        float d0 = ras.project(x0, ay, z0, scr);
        if (d0 < 0.5f) return;
        float sx0 = scr[0], sy0 = scr[1];
        float d1 = ras.project(x1, by, z1, scr);
        if (d1 < 0.5f) return;
        float sx1 = scr[0], sy1 = scr[1];
        float len = Math.abs(sx1 - sx0) + Math.abs(sy1 - sy0);
        int n = Math.max(1, (int) (len * 1.5f));
        int dark = shade(color, kd), lit = shade(color, kl);
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n;
                float d = d0 + (d1 - d0) * t;
                float rp = (ra + (rb - ra) * t) * ras.f / d;
                float x = sx0 + (sx1 - sx0) * t, y = sy0 + (sy1 - sy0) * t;
                if (pass == 0) ras.disc(x, y, Math.max(0.5f, rp), 1f / d, dark, 0);
                else if (rp > 0.7f) ras.disc(x + sunSx * rp * 0.28f, y - rp * 0.1f, rp * 0.68f, 1f / d + 2e-5f, lit, 0);
            }
    }

    private void ball(float lx, float ly, float lz, float r, int color) {
        limb(lx, ly, lz, lx, ly, lz, r, r, color);
    }

    private void drawPerson(int i) {
        float s = pScale[i];
        float depth = ras.depth(px[i], 1f, pz[i]);
        if (depth < 0.8f || depth > 75f) return;
        frame(px[i], pz[i], pYaw[i]);
        fogAt(px[i], pz[i]);
        float sw = (float) Math.sin(pPhase[i]);
        float step = sw * 0.28f * s, bob = Math.abs(sw) * 0.025f * s;
        float hip = 0.9f * s + bob, sh = 1.4f * s + bob;
        int skin = pSkin[i], top = pTop[i], legs = pLegs[i];
        // legs
        for (int side = -1; side <= 1; side += 2) {
            float fz = step * side;
            float lx = side * 0.085f * s;
            if (pKind[i] == 0) {
                limb(lx, hip, 0f, lx, hip * 0.55f, fz * 0.45f, 0.085f * s, 0.075f * s, legs);
                limb(lx, hip * 0.55f, fz * 0.45f, lx, 0.07f * s, fz, 0.06f * s, 0.05f * s, skin);
            } else if (pKind[i] == 1) {
                limb(lx, hip, 0f, lx, 0.08f * s, fz, 0.085f * s, 0.065f * s, legs);
            } else {
                limb(lx, hip * 0.6f, fz * 0.4f, lx, 0.07f * s, fz, 0.055f * s, 0.045f * s, skin);
            }
            ball(lx, 0.05f * s, fz + 0.05f * s, 0.06f * s, 0xFF3A302A);   // shoe
        }
        if (pKind[i] == 2) limb(0f, hip + 0.05f * s, 0f, 0f, hip * 0.58f, 0f, 0.17f * s, 0.27f * s, legs);
        // body
        limb(0f, hip, 0f, 0f, sh - 0.06f * s, 0f, 0.15f * s, 0.19f * s, top);
        if (pBag[i]) ball(0f, hip + 0.3f * s, -0.17f * s, 0.12f * s, 0xFF6A4A32);
        // arms swing against the legs
        for (int side = -1; side <= 1; side += 2) {
            float ax = side * 0.21f * s;
            float hz = -step * side * 0.8f;
            limb(ax, sh - 0.05f * s, 0f, ax * 1.1f, hip + 0.12f * s, hz * 0.5f, 0.065f * s, 0.055f * s, top);
            limb(ax * 1.1f, hip + 0.12f * s, hz * 0.5f, ax * 1.12f, hip - 0.1f * s, hz, 0.05f * s, 0.045f * s, skin);
        }
        // neck and head
        limb(0f, sh - 0.04f * s, 0f, 0f, sh + 0.08f * s, 0f, 0.055f * s, 0.05f * s, skin);
        float hy = sh + 0.2f * s;
        ball(0f, hy, 0.01f * s, 0.115f * s, skin);
        ball(0f, hy + 0.04f * s, -0.035f * s, 0.12f * s, pHair[i]);
        if (pLongHair[i]) limb(0f, hy, -0.06f * s, 0f, hy - 0.26f * s, -0.08f * s, 0.11f * s, 0.08f * s, pHair[i]);
        if (pHat[i]) {
            limb(-0.2f * s, hy + 0.1f * s, 0f, 0.2f * s, hy + 0.1f * s, 0f, 0.035f * s, 0.035f * s, 0xFFE3C784);
            ball(0f, hy + 0.15f * s, 0f, 0.1f * s, 0xFFE3C784);
        }
    }

    // ---- cars: little sedans ----

    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];

    /** One flat face of the car, given in its own frame; lit by its normal. */
    private void face(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                      float x3, float y3, float z3, int color, int id, float ox, float oy, float oz) {
        qx[0] = wx(x0, z0);
        qz[0] = wz(x0, z0);
        qy[0] = y0;
        qx[1] = wx(x1, z1);
        qz[1] = wz(x1, z1);
        qy[1] = y1;
        qx[2] = wx(x2, z2);
        qz[2] = wz(x2, z2);
        qy[2] = y2;
        qx[3] = wx(x3, z3);
        qz[3] = wz(x3, z3);
        qy[3] = y3;
        // normal, turned to face outward (away from the given inner point)
        float ux = qx[1] - qx[0], uy = qy[1] - qy[0], uz = qz[1] - qz[0];
        float vx = qx[3] - qx[0], vy = qy[3] - qy[0], vz = qz[3] - qz[0];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-6f) return;
        nx /= nl;
        ny /= nl;
        nz /= nl;
        float cxw = wx(ox, oz), czw = wz(ox, oz);
        if ((qx[0] - cxw) * nx + (qy[0] - oy) * ny + (qz[0] - czw) * nz < 0f) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float ndl = nx * Raster.sunX() + ny * Raster.sunY() + nz * Raster.sunZ();
        int c = Backdrop.mix(Raster.light(color, nx, ny, nz, ndl), Backdrop.HAZE, fog);
        ras.poly(qx, qy, qz, null, null, 4, c, Raster.PAT_FLAT, id, false);
    }

    private void part(float cx2, float cy2, float cz2, float hx, float hy, float hz, int color, int id) {
        ras.box(wx(cx2, cz2), cy2, wz(cx2, cz2), cyw, 0f, syw, 0f, 1f, 0f, -syw, 0f, cyw, hx, hy, hz,
                Backdrop.mix(color, Backdrop.HAZE, fog), Raster.PAT_FLAT, id);
    }

    private void car(float x, float z, float yaw, int paint, int id) {
        float depth = ras.depth(x, 1f, z);
        if (depth < 1.5f || depth > 175f) return;
        frame(x, z, yaw);
        fogAt(x, z);
        int glass = 0xFF33414E, chrome = 0xFFD2CEC6;
        // body, with a darker sill
        part(0f, 0.6f, 0f, 0.9f, 0.25f, 2.2f, paint, id);
        part(0f, 0.36f, 0f, 0.86f, 0.05f, 1.75f, 0xFF2A2829, id);
        // cabin: roof, windscreen, rear window and side glass
        float yb = 0.85f, yt = 1.3f, zb0 = -1.25f, zb1 = 1.0f, zt0 = -0.95f, zt1 = 0.4f, xb = 0.84f, xt = 0.74f;
        face(-xt, yt, zt0, xt, yt, zt0, xt, yt, zt1, -xt, yt, zt1, paint, id, 0f, 1f, 0f);
        face(-xb, yb, zb1, xb, yb, zb1, xt, yt, zt1, -xt, yt, zt1, glass, id, 0f, 1f, 0f);
        face(-xb, yb, zb0, xb, yb, zb0, xt, yt, zt0, -xt, yt, zt0, glass, id, 0f, 1f, 0f);
        for (int side = -1; side <= 1; side += 2) {
            face(side * xb, yb, zb0, side * xb, yb, zb1, side * xt, yt, zt1, side * xt, yt, zt0, glass, id, 0f, 1f, 0f);
            // the pillar between the doors
            float m = 0.004f * side;
            face(side * xb + m, yb, -0.32f, side * xb + m, yb, -0.18f, side * xt + m, yt, -0.2f, side * xt + m, yt, -0.32f,
                    paint, id, 0f, 1f, 0f);
            // wheels and hubcaps
            for (int end = -1; end <= 1; end += 2) {
                part(side * 0.85f, 0.32f, end * 1.38f, 0.12f, 0.32f, 0.32f, 0xFF1C1B1D, id);
                part(side * 0.975f, 0.32f, end * 1.38f, 0.01f, 0.15f, 0.15f, 0xFFB8B4AC, id);
            }
            // lights
            part(side * 0.6f, 0.68f, 2.205f, 0.17f, 0.07f, 0.01f, 0xFFFFF0C2, id);
            part(side * 0.64f, 0.7f, -2.205f, 0.17f, 0.07f, 0.01f, 0xFFD43A2E, id);
        }
        // bumpers, grille, plates
        part(0f, 0.42f, 2.24f, 0.93f, 0.07f, 0.05f, chrome, id);
        part(0f, 0.42f, -2.24f, 0.93f, 0.07f, 0.05f, chrome, id);
        part(0f, 0.64f, 2.205f, 0.3f, 0.08f, 0.01f, 0xFF2A2A2C, id);
        part(0f, 0.43f, 2.3f, 0.24f, 0.06f, 0.01f, 0xFFF2F0E6, id);
        part(0f, 0.43f, -2.3f, 0.24f, 0.06f, 0.01f, 0xFFF2F0E6, id);
    }

    private void cone(float x, float z) {
        frame(x, z, 0f);
        fogAt(x, z);
        part(0f, 0.025f, 0f, 0.2f, 0.025f, 0.2f, 0xFF2E2B2A, 0);
        limb(0f, 0.05f, 0f, 0f, 0.28f, 0f, 0.15f, 0.11f, 0xFFE8682E);
        limb(0f, 0.28f, 0f, 0f, 0.42f, 0f, 0.11f, 0.08f, 0xFFF2EEE6);
        limb(0f, 0.42f, 0f, 0f, 0.68f, 0f, 0.08f, 0.025f, 0xFFE8682E);
    }

    // ---- shadows ----

    /** A person's shadow: a soft strip from the feet, away from the sun. */
    private void personShadow(int i) {
        float depth = ras.depth(px[i], 0f, pz[i]);
        if (depth < 0.8f || depth > 75f) return;
        float s = pScale[i], len = 1.7f * s * SH_LEN;
        ras.softShadow(px[i], pz[i], px[i] + SHX * len, pz[i] + SHZ * len, 0.28f * s, 0.16f * s, 0.8f);
    }

    /** A car's shadow: a broad soft patch under it, stretched away from the sun. */
    private void carShadow(float x, float z) {
        float depth = ras.depth(x, 0f, z);
        if (depth < 1.5f || depth > 175f) return;
        float len = 1.1f * SH_LEN;
        ras.softShadow(x, z - 1.2f, x + SHX * len * 0.5f, z - 1.2f + SHZ * len * 0.5f, 1.25f, 1.0f, 0.85f);
        ras.softShadow(x, z + 1.2f, x + SHX * len * 0.5f, z + 1.2f + SHZ * len * 0.5f, 1.25f, 1.0f, 0.85f);
    }
}
