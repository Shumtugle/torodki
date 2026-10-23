package com.shumtugle.torodki;

import java.util.Random;

/**
 * The street around the plinth: people strolling along the pavements and cars
 * coming down the road. People stop to watch a throw and scatter when something
 * falls with a crash. A car rolls up, stops to look, and turns round; if the look
 * was at a ruin, it turns with screaming tyres and floors it. Everything here is
 * drawn from boxes, like the rest of the world, and never touches the physics.
 */
final class Life {
    // ---- people ----
    private static final int PEOPLE = 12;
    private static final int WALK = 0, WATCH = 1, JUMP = 2, RUN = 3;
    private final float[] px = new float[PEOPLE], pz = new float[PEOPLE], pYaw = new float[PEOPLE];
    private final float[] pSpeed = new float[PEOPLE], pPhase = new float[PEOPLE], pTime = new float[PEOPLE];
    private final float[] pScale = new float[PEOPLE], pDir = new float[PEOPLE];
    private final int[] pState = new int[PEOPLE];
    private final int[] pSkin = new int[PEOPLE], pShirt = new int[PEOPLE], pShorts = new int[PEOPLE], pHair = new int[PEOPLE];
    private final boolean[] pHat = new boolean[PEOPLE], pShades = new boolean[PEOPLE];

    // ---- cars ----
    private static final int CARS = 4;
    private static final int OFF = 0, COME = 1, LOOK = 2, TURN = 3, FLEE = 4, PASS = 5;
    private final float[] cx = new float[CARS], cz = new float[CARS], cYaw = new float[CARS], cV = new float[CARS];
    private final float[] cStop = new float[CARS], cTime = new float[CARS], cLane = new float[CARS], cTheta = new float[CARS];
    private final int[] cState = new int[CARS], cColor = new int[CARS];
    private final boolean[] cPanic = new boolean[CARS], cPasser = new boolean[CARS];
    private float nextCar = 2f;

    // exhaust puffs
    private static final int PUFFS = 60;
    private final float[] fx = new float[PUFFS], fy = new float[PUFFS], fz = new float[PUFFS], fAge = new float[PUFFS];
    private int fN;

    private final Random rnd = new Random(2026);
    private final World.Host host;
    private final float camX, camZ;
    float damage;           // share of the building already knocked off, set by the world
    private float shock;    // seconds since the last big crash; large when calm

    static final int[] SKINS = {0xFFF1C9A5, 0xFFE0AC85, 0xFFC68A62, 0xFF8D5A3B, 0xFFF5D6BC};
    static final int[] SHIRTS = {0xFFF7F4EC, 0xFFEE8E6E, 0xFFF2CF5E, 0xFF7FC4B6, 0xFF8FB3E3, 0xFFE9A6C2, 0xFFB7D88F};
    static final int[] SHORTS = {0xFF4F6E9C, 0xFFD9C7A3, 0xFF6B8F5A, 0xFF3E3E48, 0xFFE8E2D4};
    static final int[] HAIR = {0xFF3A2A1E, 0xFF6B4A2E, 0xFFD8B46A, 0xFF1E1A18, 0xFFB0522E};
    static final int[] PAINT = {0xFFEFE2B8, 0xFF7FBACB, 0xFFE27F62, 0xFFF2CC5B, 0xFFAED3A0, 0xFFF7F5EE, 0xFFC9A3D9};

    Life(World.Host host, float camX, float camZ) {
        this.host = host;
        this.camX = camX;
        this.camZ = camZ;
        for (int i = 0; i < PEOPLE; i++) spawnPerson(i, true);
        shock = 99f;
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
        pState[i] = WALK;
        pScale[i] = rnd.nextInt(6) == 0 ? 0.72f : 0.93f + rnd.nextFloat() * 0.14f;
        pSkin[i] = SKINS[rnd.nextInt(SKINS.length)];
        pShirt[i] = SHIRTS[rnd.nextInt(SHIRTS.length)];
        pShorts[i] = SHORTS[rnd.nextInt(SHORTS.length)];
        pHair[i] = HAIR[rnd.nextInt(HAIR.length)];
        pHat[i] = rnd.nextInt(3) == 0;
        pShades[i] = rnd.nextInt(3) == 0;
    }

    // ---- events from the game ----

    /** A throw: people near the plinth stop and look. */
    void onThrow() {
        for (int i = 0; i < PEOPLE; i++) {
            if (pState[i] != WALK || dist2Plinth(px[i], pz[i]) > 26f * 26f) continue;
            pState[i] = WATCH;
            pTime[i] = 2.2f + rnd.nextFloat();
        }
    }

    /** Something fell hard: strength 0..1. */
    void onCrash(float strength) {
        if (strength < 0.25f) return;
        shock = 0f;
        for (int i = 0; i < PEOPLE; i++) {
            if (pState[i] == JUMP || pState[i] == RUN) continue;
            if (dist2Plinth(px[i], pz[i]) > 30f * 30f) continue;
            pState[i] = JUMP;
            pTime[i] = 0.8f + rnd.nextFloat() * 0.4f;
        }
        for (int c = 0; c < CARS; c++) {
            if (cState[c] == COME && cz[c] < Builder.CZ + 45f) {
                cPanic[c] = true;
                cPasser[c] = false;
                cStop[c] = Math.max(Builder.CZ + 8f, cz[c] - 4f);
            } else if (cState[c] == LOOK) {
                cPanic[c] = true;
            }
        }
    }

    private static float dist2Plinth(float x, float z) {
        float dz = z - Builder.CZ;
        return x * x + dz * dz;
    }

    // ---- simulation ----

    void update(float dt) {
        shock += dt;
        people(dt);
        cars(dt);
        puffs(dt);
    }

    private void people(float dt) {
        for (int i = 0; i < PEOPLE; i++) {
            pTime[i] -= dt;
            switch (pState[i]) {
                case WALK:
                    pz[i] += pDir[i] * pSpeed[i] * dt;
                    pPhase[i] += dt * pSpeed[i] * 4.2f;
                    pYaw[i] = turnTo(pYaw[i], pDir[i] > 0 ? 0f : (float) Math.PI, dt * 6f);
                    if (pz[i] > 82f || pz[i] < -4f) spawnPerson(i, false);
                    break;
                case WATCH:
                    pYaw[i] = turnTo(pYaw[i], (float) Math.atan2(-px[i], Builder.CZ - pz[i]), dt * 5f);
                    if (pTime[i] <= 0f) pState[i] = WALK;
                    break;
                case JUMP:
                    pYaw[i] = turnTo(pYaw[i], (float) Math.atan2(-px[i], Builder.CZ - pz[i]), dt * 8f);
                    if (pTime[i] <= 0f) {
                        pState[i] = RUN;
                        pTime[i] = 3f + rnd.nextFloat() * 2f;
                        pDir[i] = pz[i] < Builder.CZ ? -1f : 1f;
                    }
                    break;
                case RUN:
                    pz[i] += pDir[i] * 4.3f * dt;
                    pPhase[i] += dt * 13f;
                    pYaw[i] = turnTo(pYaw[i], pDir[i] > 0 ? 0f : (float) Math.PI, dt * 10f);
                    if (pz[i] > 82f || pz[i] < -4f) spawnPerson(i, false);
                    else if (pTime[i] <= 0f) {
                        pState[i] = WALK;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static float turnTo(float a, float b, float k) {
        float d = b - a;
        while (d > Math.PI) d -= (float) (Math.PI * 2);
        while (d < -Math.PI) d += (float) (Math.PI * 2);
        return a + d * Math.min(1f, k);
    }

    private void cars(float dt) {
        nextCar -= dt;
        if (nextCar <= 0f) {
            nextCar = 5f + rnd.nextFloat() * 7f;
            for (int c = 0; c < CARS; c++)
                if (cState[c] == OFF) {
                    spawnCar(c);
                    break;
                }
        }
        for (int c = 0; c < CARS; c++) {
            switch (cState[c]) {
                case COME: {
                    float vmax = cPanic[c] ? 7f : 11f;
                    if (cPasser[c] && !cPanic[c]) {
                        cV[c] = approach(cV[c], vmax, 4f, dt);
                        if (cz[c] < Builder.CZ + 14f) cState[c] = PASS;
                    } else {
                        float d = cz[c] - cStop[c];
                        float want = Math.min(vmax, (float) Math.sqrt(Math.max(0f, 2f * 6f * d)));
                        cV[c] = approach(cV[c], want, cPanic[c] ? 14f : 7f, dt);
                        // keep a distance to the car ahead in the same lane
                        for (int o = 0; o < CARS; o++)
                            if (o != c && cState[o] != OFF && Math.abs(cx[o] - cx[c]) < 1.5f && cz[o] < cz[c]
                                    && cz[c] - cz[o] < 9f) cV[c] = Math.min(cV[c], Math.max(0f, cz[c] - cz[o] - 6f));
                        if (d < 0.3f && cV[c] < 0.3f) {
                            cState[c] = LOOK;
                            cTime[c] = cPanic[c] ? 0.35f : 0.7f + rnd.nextFloat() * 0.6f;
                            cV[c] = 0f;
                        }
                    }
                    cz[c] -= cV[c] * dt;
                    break;
                }
                case LOOK:
                    cTime[c] -= dt;
                    if (damage > 0.15f || shock < 4f) cPanic[c] = true;
                    if (cTime[c] <= 0f) {
                        cState[c] = TURN;
                        cTheta[c] = 0f;
                        if (cPanic[c]) {
                            sound(Synth.HONK, c, 0.8f, 0.95f + rnd.nextFloat() * 0.1f);
                            sound(Synth.SQUEAL, c, 1f, 0.9f + rnd.nextFloat() * 0.2f);
                        }
                    }
                    break;
                case TURN: {
                    float r = Math.abs(cLane[c]);
                    float v = cPanic[c] ? 7.5f : 3.2f;
                    cTheta[c] += v / r * dt;
                    float t = Math.min((float) Math.PI, cTheta[c]);
                    cx[c] = cLane[c] * (float) Math.cos(t);
                    cz[c] = cStop[c] - r * (float) Math.sin(t);
                    float dx = -cLane[c] * (float) Math.sin(t), dz = -r * (float) Math.cos(t);
                    cYaw[c] = (float) Math.atan2(dx, dz);
                    if (cPanic[c] && rnd.nextFloat() < dt * 20f) puff(c);
                    if (cTheta[c] >= Math.PI) {
                        cState[c] = FLEE;
                        cV[c] = v;
                        cYaw[c] = 0f;
                        if (cPanic[c]) sound(Synth.REV, c, 1f, 0.95f + rnd.nextFloat() * 0.15f);
                    }
                    break;
                }
                case FLEE:
                    cV[c] = approach(cV[c], cPanic[c] ? 28f : 13f, cPanic[c] ? 11f : 4f, dt);
                    cz[c] += cV[c] * dt;
                    if (cPanic[c] && rnd.nextFloat() < dt * 14f) puff(c);
                    if (cz[c] > 170f) cState[c] = OFF;
                    break;
                case PASS:
                    cV[c] = approach(cV[c], 11f, 4f, dt);
                    cz[c] -= cV[c] * dt;
                    if (cz[c] < camZ - 12f) cState[c] = OFF;
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
        cPanic[c] = false;
        // only the far lane can drive on past the plinth: the near one leads into the thrower
        cPasser[c] = cLane[c] > 0f && damage < 0.05f && rnd.nextInt(3) == 0;
        cStop[c] = Builder.CZ + 9f + rnd.nextFloat() * 6f;
        cColor[c] = PAINT[rnd.nextInt(PAINT.length)];
        cState[c] = COME;
    }

    private static float approach(float v, float target, float rate, float dt) {
        if (v < target) return Math.min(target, v + rate * dt);
        return Math.max(target, v - rate * 1.6f * dt);
    }

    private void sound(int id, int c, float gain, float rate) {
        float dx = cx[c] - camX, dz = cz[c] - camZ;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        host.sound(id, gain * Math.min(1f, 14f / Math.max(1f, d)), rate);
    }

    private void puff(int c) {
        if (fN >= PUFFS) return;
        float s = (float) Math.sin(cYaw[c]), k = (float) Math.cos(cYaw[c]);
        fx[fN] = cx[c] - s * 2.2f + (rnd.nextFloat() - 0.5f) * 0.3f;
        fy[fN] = 0.35f;
        fz[fN] = cz[c] - k * 2.2f;
        fAge[fN] = 0f;
        fN++;
    }

    private void puffs(float dt) {
        for (int i = 0; i < fN; i++) {
            fAge[i] += dt;
            fy[i] += dt * 0.4f;
            if (fAge[i] > 1.2f) {
                fN--;
                fx[i] = fx[fN];
                fy[i] = fy[fN];
                fz[i] = fz[fN];
                fAge[i] = fAge[fN];
                i--;
            }
        }
    }

    // ---- drawing ----

    private float bx, by, bz, cyw, syw, pitch;
    private Raster ras;
    private boolean shadows;
    private final float[] scr = new float[2];

    void draw(Raster r) {
        ras = r;
        for (int c = 0; c < CARS; c++) if (cState[c] != OFF) drawCar(c);
        for (int i = 0; i < PEOPLE; i++) drawPerson(i);
        for (int i = 0; i < fN; i++) {
            float vz = r.project(fx[i], fy[i], fz[i], scr);
            if (vz < 0.5f) continue;
            float k = fAge[i] / 1.2f;
            r.haze(scr[0], scr[1], (0.25f + 0.8f * k) * r.f / vz, 1f / vz, 0xFFE9E6E0, (int) (170 * (1f - k)));
        }
    }

    /** Sets the frame for the parts that follow: a place on the ground and a heading. */
    private void frame(float x, float z, float yaw) {
        bx = x;
        by = 0f;
        bz = z;
        cyw = (float) Math.cos(yaw);
        syw = (float) Math.sin(yaw);
        shadows = Math.abs(x) < 4.4f && Math.abs(z - Builder.CZ) < 4.4f;
    }

    /**
     * One part: a box at a local center, tilted forward by pitch about a pivot.
     * Local axes: x to the right of the figure, y up, z ahead.
     */
    private void part(float pivX, float pivY, float pivZ, float offY, float hx, float hy, float hz, float tilt, int color, int id) {
        float cp = (float) Math.cos(tilt), sp = (float) Math.sin(tilt);
        // offset from the pivot along the part's own down axis, tilted about x
        float ly = pivY + offY * cp, lz = pivZ + offY * sp;
        float lx = pivX;
        // into the world: rotate about y by the heading
        float wx = bx + lx * cyw + lz * syw;
        float wy = by + ly;
        float wz = bz - lx * syw + lz * cyw;
        // rotation = Ry(yaw) * Rx(tilt); with Rx tilting +y toward +z
        float r00 = cyw, r01 = syw * sp, r02 = syw * cp;
        float r10 = 0f, r11 = cp, r12 = -sp;
        float r20 = -syw, r21 = cyw * sp, r22 = cyw * cp;
        ras.box(wx, wy, wz, r00, r01, r02, r10, r11, r12, r20, r21, r22, hx, hy, hz, color, Raster.PAT_FLAT, id);
        if (shadows) ras.shadowBox(wx, wy, wz, r00, r01, r02, r10, r11, r12, r20, r21, r22, hx, hy, hz);
    }

    private void drawPerson(int i) {
        float s = pScale[i];
        float depth = ras.depth(px[i], 1f, pz[i]);
        if (depth < 0.8f || depth > 70f) return;
        float lift = 0f;
        float swing = 0f, armUp = 0f;
        switch (pState[i]) {
            case WALK:
                swing = (float) Math.sin(pPhase[i]) * 0.55f;
                break;
            case RUN:
                swing = (float) Math.sin(pPhase[i]) * 0.95f;
                break;
            case JUMP:
                lift = Math.abs((float) Math.sin(pTime[i] * 11f)) * 0.28f;
                armUp = 1f;
                break;
            default:
                break;
        }
        frame(px[i], pz[i], pYaw[i]);
        by = lift;
        int id = (2000 + i) << 3;
        float leg = 0.82f * s;
        for (int side = -1; side <= 1; side += 2) {
            float sw = swing * side;
            part(side * 0.09f * s, leg, 0f, -0.2f * s, 0.075f * s, 0.2f * s, 0.08f * s, sw, pShorts[i], id);
            part(side * 0.09f * s, leg, 0f, -0.6f * s, 0.06f * s, 0.22f * s, 0.065f * s, sw, pSkin[i], id);
        }
        part(0f, leg + 0.3f * s, 0f, 0f, 0.19f * s, 0.31f * s, 0.11f * s, 0f, pShirt[i], id);
        for (int side = -1; side <= 1; side += 2) {
            float tilt = armUp > 0f ? (float) Math.PI * 0.92f : -swing * side * 0.9f;
            float sx = side * (armUp > 0f ? 0.26f : 0.25f) * s;
            part(sx, leg + 0.56f * s, 0f, -0.15f * s, 0.06f * s, 0.15f * s, 0.065f * s, tilt, pShirt[i], id);
            part(sx, leg + 0.56f * s, 0f, -0.42f * s, 0.05f * s, 0.13f * s, 0.055f * s, tilt, pSkin[i], id);
        }
        float hy = leg + 0.75f * s;
        part(0f, hy, 0f, 0f, 0.12f * s, 0.13f * s, 0.12f * s, 0f, pSkin[i], id);
        if (pHat[i]) {
            part(0f, hy + 0.13f * s, 0f, 0f, 0.23f * s, 0.016f * s, 0.23f * s, 0f, 0xFFE8CF8A, id);
            part(0f, hy + 0.19f * s, 0f, 0f, 0.13f * s, 0.06f * s, 0.13f * s, 0f, 0xFFDDBF75, id);
        } else {
            part(0f, hy + 0.12f * s, -0.01f * s, 0f, 0.127f * s, 0.04f * s, 0.127f * s, 0f, pHair[i], id);
        }
        if (pShades[i]) part(0f, hy + 0.03f * s, 0.118f * s, 0f, 0.1f * s, 0.024f * s, 0.008f * s, 0f, 0xFF26262C, id);
    }

    private void drawCar(int c) {
        float depth = ras.depth(cx[c], 1f, cz[c]);
        if (depth < 1.5f || depth > 175f) return;
        frame(cx[c], cz[c], cYaw[c]);
        // a little nose dive when braking hard, a squat when flooring it
        float pitchK = cState[c] == FLEE && cPanic[c] ? -0.05f : cState[c] == COME && cPanic[c] ? 0.04f : 0f;
        int id = (3000 + c) << 3;
        int body = cColor[c];
        part(0f, 0.56f, 0f, 0f, 0.9f, 0.28f, 2.08f, pitchK, body, id);
        part(0f, 1.04f, -0.25f, 0f, 0.8f, 0.22f, 1.05f, pitchK, 0xFF3D4E5E, id);
        part(0f, 1.3f, -0.25f, 0f, 0.83f, 0.04f, 1.08f, pitchK, body, id);
        part(0f, 0.6f, 2.09f, 0f, 0.92f, 0.08f, 0.03f, pitchK, 0xFFD7D3CB, id);
        for (int side = -1; side <= 1; side += 2) {
            part(side * 0.58f, 0.66f, 2.1f, 0f, 0.17f, 0.07f, 0.02f, pitchK, 0xFFFFF4C8, id);
            part(side * 0.62f, 0.68f, -2.09f, 0f, 0.16f, 0.06f, 0.02f, pitchK, 0xFFD8483A, id);
            for (int end = -1; end <= 1; end += 2)
                part(side * 0.84f, 0.31f, end * 1.33f, 0f, 0.13f, 0.31f, 0.31f, 0f, 0xFF2C2B2E, id);
        }
    }
}
