package com.shumtugle.torodki;

import java.util.Random;

/**
 * The street around the plinth, kept in the background: people strolling along the
 * pavements and cars that come down the road, turn round short of the plinth and
 * drive back the way they came. Nobody here notices the game. They are drawn as soft
 * flat silhouettes in the haze, each with a long summer shadow on the ground, and
 * never touch the physics or make a sound.
 */
final class Life {
    // ---- people ----
    private static final int PEOPLE = 12;
    private final float[] px = new float[PEOPLE], pz = new float[PEOPLE], pYaw = new float[PEOPLE];
    private final float[] pSpeed = new float[PEOPLE], pPhase = new float[PEOPLE];
    private final float[] pScale = new float[PEOPLE], pDir = new float[PEOPLE];
    private final int[] pTone = new int[PEOPLE];
    private final boolean[] pHat = new boolean[PEOPLE];

    // ---- cars ----
    private static final int CARS = 3;
    private static final int OFF = 0, COME = 1, LOOK = 2, TURN = 3, GO = 4;
    private final float[] cx = new float[CARS], cz = new float[CARS], cYaw = new float[CARS], cV = new float[CARS];
    private final float[] cStop = new float[CARS], cTime = new float[CARS], cLane = new float[CARS], cTheta = new float[CARS];
    private final int[] cState = new int[CARS], cTone = new int[CARS];
    private float nextCar = 3f;

    private final Random rnd = new Random(2026);

    static final int[] PERSON_TONES = {0xFF5C5650, 0xFF655A52, 0xFF57585E, 0xFF62574F};
    static final int[] CAR_TONES = {0xFF6E6964, 0xFF77706A, 0xFF686A70};

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
    }

    private void spawnPerson(int i, boolean anywhere) {
        float side = rnd.nextBoolean() ? 1f : -1f;
        px[i] = side * (4.7f + rnd.nextFloat() * 2.1f);
        boolean up = rnd.nextBoolean();
        pDir[i] = up ? 1f : -1f;
        pz[i] = anywhere ? 3f + rnd.nextFloat() * 70f : (up ? -2f : 80f);
        pYaw[i] = up ? 0f : (float) Math.PI;
        pSpeed[i] = 1.0f + rnd.nextFloat() * 0.6f;
        pPhase[i] = rnd.nextFloat() * 6f;
        pScale[i] = rnd.nextInt(6) == 0 ? 0.72f : 0.93f + rnd.nextFloat() * 0.14f;
        pTone[i] = PERSON_TONES[rnd.nextInt(PERSON_TONES.length)];
        pHat[i] = rnd.nextInt(3) == 0;
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
            nextCar = 6f + rnd.nextFloat() * 8f;
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
                    float want = Math.min(11f, (float) Math.sqrt(Math.max(0f, 2f * 4.5f * d)));
                    cV[c] = approach(cV[c], want, 6f, dt);
                    // keep a distance to the car ahead in the same lane
                    for (int o = 0; o < CARS; o++)
                        if (o != c && cState[o] != OFF && Math.abs(cx[o] - cx[c]) < 1.5f && cz[o] < cz[c]
                                && cz[c] - cz[o] < 10f) cV[c] = Math.min(cV[c], Math.max(0f, cz[c] - cz[o] - 7f));
                    cz[c] -= cV[c] * dt;
                    if (d < 0.3f && cV[c] < 0.3f) {
                        cState[c] = LOOK;
                        cTime[c] = 0.6f + rnd.nextFloat() * 0.8f;
                        cV[c] = 0f;
                    }
                    break;
                }
                case LOOK:
                    cTime[c] -= dt;
                    if (cTime[c] <= 0f) {
                        // the other lane must be clear before turning across it
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
                    cTheta[c] += 3.4f / r * dt;
                    float t = Math.min((float) Math.PI, cTheta[c]);
                    cx[c] = cLane[c] * (float) Math.cos(t);
                    cz[c] = cStop[c] - r * (float) Math.sin(t);
                    float dx = -cLane[c] * (float) Math.sin(t), dz = -r * (float) Math.cos(t);
                    cYaw[c] = (float) Math.atan2(dx, dz);
                    if (cTheta[c] >= Math.PI) {
                        cState[c] = GO;
                        cV[c] = 3.4f;
                        cYaw[c] = 0f;
                    }
                    break;
                }
                case GO:
                    cV[c] = approach(cV[c], 13f, 3.5f, dt);
                    cz[c] += cV[c] * dt;
                    if (cz[c] > 170f) cState[c] = OFF;
                    break;
                default:
                    break;
            }
        }
    }

    private void spawnCar(int c) {
        cLane[c] = rnd.nextBoolean() ? 2.45f : -2.45f;
        cx[c] = cLane[c];
        cz[c] = 150f + rnd.nextFloat() * 20f;
        cYaw[c] = (float) Math.PI;
        cV[c] = 11f;
        cStop[c] = Builder.CZ + 10f + rnd.nextFloat() * 8f;
        cTone[c] = CAR_TONES[rnd.nextInt(CAR_TONES.length)];
        cState[c] = COME;
    }

    private static float approach(float v, float target, float rate, float dt) {
        if (v < target) return Math.min(target, v + rate * dt);
        return Math.max(target, v - rate * 1.6f * dt);
    }

    // ---- drawing ----

    private float bx, by, bz, cyw, syw;
    private int tone;
    private Raster ras;
    private final float[] qx = new float[8], qz = new float[8], hx8 = new float[8], hz8 = new float[8];
    private static final float[] CAR_X = {-0.95f, 0.95f, 0.95f, -0.95f}, CAR_Z = {-2.1f, -2.1f, 2.1f, 2.1f};

    void draw(Raster r) {
        ras = r;
        // shadows first, so every figure stands in its own
        for (int c = 0; c < CARS; c++) if (cState[c] != OFF) carShadow(c);
        for (int i = 0; i < PEOPLE; i++) personShadow(i);
        r.flat = true;
        for (int c = 0; c < CARS; c++) if (cState[c] != OFF) drawCar(c);
        for (int i = 0; i < PEOPLE; i++) drawPerson(i);
        r.flat = false;
    }

    /** A figure's tone, washed out with distance like the houses behind it. */
    private int toneAt(int base, float x, float z) {
        float d = Math.max(1f, ras.depth(x, 1f, z));
        float fog = 1f - (float) Math.exp(-d / 140f);
        return Backdrop.mix(base, Backdrop.HAZE, 0.08f + fog * 0.75f);
    }

    private void frame(float x, float z, float yaw) {
        bx = x;
        by = 0f;
        bz = z;
        cyw = (float) Math.cos(yaw);
        syw = (float) Math.sin(yaw);
    }

    /**
     * One part: a box at a local center, tilted forward by pitch about a pivot.
     * Local axes: x to the right of the figure, y up, z ahead.
     */
    private void part(float pivX, float pivY, float pivZ, float offY, float hx, float hy, float hz, float tilt) {
        float cp = (float) Math.cos(tilt), sp = (float) Math.sin(tilt);
        float ly = pivY + offY * cp, lz = pivZ + offY * sp;
        float lx = pivX;
        float wx = bx + lx * cyw + lz * syw;
        float wy = by + ly;
        float wz = bz - lx * syw + lz * cyw;
        // rotation = Ry(yaw) * Rx(tilt)
        ras.box(wx, wy, wz, cyw, syw * sp, syw * cp, 0f, cp, -sp, -syw, cyw * sp, cyw * cp, hx, hy, hz,
                tone, Raster.PAT_FLAT, 0);
    }

    private void drawPerson(int i) {
        float s = pScale[i];
        float depth = ras.depth(px[i], 1f, pz[i]);
        if (depth < 0.8f || depth > 70f) return;
        float swing = (float) Math.sin(pPhase[i]) * 0.5f;
        frame(px[i], pz[i], pYaw[i]);
        tone = toneAt(pTone[i], px[i], pz[i]);
        float leg = 0.82f * s;
        for (int side = -1; side <= 1; side += 2)
            part(side * 0.09f * s, leg, 0f, -0.41f * s, 0.07f * s, 0.41f * s, 0.075f * s, swing * side);
        part(0f, leg + 0.3f * s, 0f, 0f, 0.19f * s, 0.31f * s, 0.11f * s, 0f);
        for (int side = -1; side <= 1; side += 2)
            part(side * 0.25f * s, leg + 0.56f * s, 0f, -0.28f * s, 0.055f * s, 0.29f * s, 0.06f * s, -swing * side * 0.9f);
        float hy = leg + 0.75f * s;
        part(0f, hy, 0f, 0f, 0.12f * s, 0.13f * s, 0.12f * s, 0f);
        if (pHat[i]) {
            part(0f, hy + 0.13f * s, 0f, 0f, 0.23f * s, 0.016f * s, 0.23f * s, 0f);
            part(0f, hy + 0.19f * s, 0f, 0f, 0.13f * s, 0.06f * s, 0.13f * s, 0f);
        }
    }

    private void drawCar(int c) {
        float depth = ras.depth(cx[c], 1f, cz[c]);
        if (depth < 1.5f || depth > 175f) return;
        frame(cx[c], cz[c], cYaw[c]);
        tone = toneAt(cTone[c], cx[c], cz[c]);
        part(0f, 0.56f, 0f, 0f, 0.9f, 0.28f, 2.08f, 0f);
        part(0f, 1.06f, -0.25f, 0f, 0.8f, 0.24f, 1.05f, 0f);
        for (int side = -1; side <= 1; side += 2)
            for (int end = -1; end <= 1; end += 2)
                part(side * 0.84f, 0.31f, end * 1.33f, 0f, 0.13f, 0.31f, 0.31f, 0f);
    }

    /** A person's shadow: a long tapering strip from the feet, away from the sun. */
    private void personShadow(int i) {
        float depth = ras.depth(px[i], 0f, pz[i]);
        if (depth < 0.8f || depth > 70f) return;
        float s = pScale[i], len = 1.75f * s * SH_LEN, w0 = 0.2f * s, w1 = 0.13f * s;
        float nx = -SHZ, nz = SHX;
        float x = px[i], z = pz[i];
        qx[0] = x - nx * w0;
        qz[0] = z - nz * w0;
        qx[1] = x + nx * w0;
        qz[1] = z + nz * w0;
        qx[2] = x + SHX * len + nx * w1;
        qz[2] = z + SHZ * len + nz * w1;
        qx[3] = x + SHX * len - nx * w1;
        qz[3] = z + SHZ * len - nz * w1;
        ras.groundShade(qx, qz, 4);
    }

    /** A car's shadow: its footprint swept away from the sun by its height. */
    private void carShadow(int c) {
        float depth = ras.depth(cx[c], 0f, cz[c]);
        if (depth < 1.5f || depth > 175f) return;
        float s = (float) Math.sin(cYaw[c]), k = (float) Math.cos(cYaw[c]);
        float len = 1.0f * SH_LEN;
        float[] lx = CAR_X, lz = CAR_Z, wx = hx8, wz = hz8;
        for (int i = 0; i < 4; i++) {
            wx[i] = cx[c] + lx[i] * k + lz[i] * s;
            wz[i] = cz[c] - lx[i] * s + lz[i] * k;
            wx[i + 4] = wx[i] + SHX * len;
            wz[i + 4] = wz[i] + SHZ * len;
        }
        int n = hull(wx, wz, 8, qx, qz);
        ras.groundShade(qx, qz, n);
    }

    /** Convex hull of n points, counter-clockwise, into ox/oz; returns its size. */
    private static int hull(float[] x, float[] z, int n, float[] ox, float[] oz) {
        int start = 0;
        for (int i = 1; i < n; i++) if (x[i] < x[start] || (x[i] == x[start] && z[i] < z[start])) start = i;
        int m = 0, p = start;
        do {
            ox[m] = x[p];
            oz[m] = z[p];
            m++;
            int q = (p + 1) % n;
            for (int i = 0; i < n; i++) {
                float cr = (x[q] - x[p]) * (z[i] - z[p]) - (z[q] - z[p]) * (x[i] - x[p]);
                if (cr < 0f) q = i;
            }
            p = q;
        } while (p != start && m < n);
        return m;
    }
}
