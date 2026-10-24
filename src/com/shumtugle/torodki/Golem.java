package com.shumtugle.torodki;

/**
 * The golem: a hunched giant heaped together from what the buildings are made of. Stone
 * blocks and courses of brick, planks for sinews, rods of rusty iron sticking out, fists
 * like rubble. It comes up the street in leaps: it crouches, springs, comes down with a
 * blow that shakes the street, and stands a moment to gather itself; between leaps it
 * tears parked cars from their places and throws them. Its weak points glow: two knees
 * and the eyes. Break one knee and its leaps grow short; break both and it can only drag
 * itself along on its knees, its head brought low; three blows to the head and it falls
 * apart.
 *
 * Plain geometry and timing; the world decides what hits it and draws it with the raster.
 */
final class Golem {
    // the build, in its own units; everything is drawn G times bigger
    static final float G = 1.6f;
    static final float THIGH = 0.95f, SHIN = 0.95f, HIP = THIGH + SHIN, TORSO = 2.0f, HALF_W = 1.0f;
    static final float KNEE_HIT = 0.55f * G, HEAD_HIT = 0.6f * G, BODY_HIT = 1.15f * G;
    static final float SHIN_R = SHIN, BODY = TORSO;

    static final int STAND = 0, CROUCH = 1, AIR = 2, LAND = 3, CRAWL = 4;

    float x, z;                 // where its feet are
    int kneeL = 1, kneeR = 1, head = 3;
    float stagger;              // seconds of stunned stillness left
    float hurt;                 // flash when hit, fades
    boolean down;               // fallen apart
    float raise;                // the throwing arm: 0 down, 1 raised high
    boolean holding;
    int act = STAND;
    float actT, jumpIn = 2.0f;
    int landings;               // how many times it has come down: the world shakes for each
    private float z0, z1, jumpH;
    // a leap the world asks for, to a given spot, instead of one straight at the player
    private boolean hopping;
    private float hopX, hopZ, x0, x1;
    float time;
    private float squat, air;   // how low it crouches, how high it is

    // its frame: right, and toward the player
    float rx, rz, fx, fz;

    Golem(float x, float z) {
        this.x = x;
        this.z = z;
        face();
    }

    int weakLeft() {
        return kneeL + kneeR + head;
    }

    private void face() {
        float dx = World.CAM_X * 0.6f - x, dz = 0f - z;
        float l = (float) Math.sqrt(dx * dx + dz * dz);
        fx = dx / l;
        fz = dz / l;
        rx = -fz;
        rz = fx;
    }

    /** The speed it closes in at, on average, for the bot and the throws that lead it. */
    float pace() {
        if (act == AIR) return (z0 - z1) / 1.0f;
        return act == CRAWL ? 0.55f : 0f;
    }

    /** How far it comes in one leap; shorter on a broken knee. */
    private float leap(float near) {
        float k = kneeL + kneeR == 2 ? 1f : 0.55f;
        return (6f + 2.5f * near) * k;
    }

    void update(float dt, float near) {
        time += dt;
        hurt = Math.max(0f, hurt - dt * 3f);
        if (down) return;
        if (stagger > 0f && act != AIR) {
            stagger -= dt;
            settle(dt);
            return;
        }
        actT += dt;
        boolean crawling = kneeL + kneeR == 0;
        if (crawling && act != AIR) act = CRAWL;
        switch (act) {
            case STAND:
                if (!holding) jumpIn -= dt;
                squat += (0f - squat) * Math.min(1f, dt * 6f);
                if (jumpIn <= 0f && !holding) {
                    act = CROUCH;
                    actT = 0f;
                }
                break;
            case CROUCH:
                squat = Math.min(1f, actT / 0.45f);
                if (actT >= 0.45f) {
                    act = AIR;
                    actT = 0f;
                    z0 = z;
                    x0 = x1 = x;
                    z1 = z - leap(near);
                    if (hopping) {
                        x1 = hopX;
                        z1 = hopZ;
                        hopping = false;
                    }
                    jumpH = (kneeL + kneeR == 2 ? 3.2f : 1.8f) / G;
                }
                break;
            case AIR: {
                float k = Math.min(1f, actT / 1.0f);
                z = z0 + (z1 - z0) * k;
                x = x0 + (x1 - x0) * k;
                air = jumpH * 4f * k * (1f - k);
                squat = Math.max(0f, 1f - k * 3f) * 0.6f;
                if (k >= 1f) {
                    air = 0f;
                    act = LAND;
                    actT = 0f;
                    landings++;
                }
                break;
            }
            case LAND:
                squat = 1f - Math.min(1f, actT / 0.6f) * 0.7f;
                if (actT >= 0.6f) {
                    act = STAND;
                    actT = 0f;
                    jumpIn = 0.9f + 1.0f * (1f - near);
                }
                break;
            case CRAWL:
                squat = 1f;
                z -= 0.55f * dt;
                break;
        }
        face();
    }

    private void settle(float dt) {
        squat += ((act == CRAWL ? 1f : 0.3f) - squat) * Math.min(1f, dt * 4f);
    }

    /** A leap to a spot of the world's choosing: crouch, spring, come down there. */
    void hop(float tx, float tz) {
        hopping = true;
        hopX = tx;
        hopZ = tz;
        act = CROUCH;
        actT = 0f;
    }

    /** True while it stands still, ready for the next thing. */
    boolean idle() {
        return act == STAND && !hopping;
    }

    /** True when it has come close enough to strike the player. */
    boolean arrived() {
        return z < 8f;
    }

    /** True while it is in the air: blows glance off something moving that fast. */
    boolean flying() {
        return act == AIR;
    }

    // ---- the skeleton ----

    private final float[] t3 = new float[3], j = new float[3];

    /** A point from its frame (right, up, toward the player), its units, into the world. */
    void at(float r, float u, float f, float[] out) {
        u += air;
        out[0] = x + (rx * r + fx * f) * G;
        out[1] = u * G;
        out[2] = z + (rz * r + fz * f) * G;
    }

    private float kneeling() {
        return kneeL + kneeR == 0 ? 1f : 0f;
    }

    private float hipY() {
        float breathe = (float) Math.sin(time * 2.4f) * 0.03f;
        float low = kneeling() > 0f ? HIP - 0.95f : HIP - squat * 0.6f;
        return low + breathe;
    }

    /** The bend of a leg: thigh forward, shin back; deep in a crouch. */
    private float bend(int side) {
        if (kneeling() > 0f) return 1.45f;
        float lame = (side < 0 ? kneeL : kneeR) == 0 ? 0.25f : 0f;
        return 0.15f + squat * 0.85f + lame;
    }

    void knee(int side, float[] out) {
        float b = bend(side), hip = hipY();
        at(side * 0.75f, hip - (float) Math.cos(b) * THIGH, (float) Math.sin(b) * THIGH, out);
    }

    private float lean() {
        return 0.38f + squat * 0.25f;
    }

    void chest(float[] out) {
        float l = lean(), hip = hipY();
        at(0f, hip + (float) Math.cos(l) * TORSO * 0.55f, (float) Math.sin(l) * TORSO * 0.55f, out);
    }

    private float topU() {
        return hipY() + (float) Math.cos(lean()) * TORSO;
    }

    private float topF() {
        return (float) Math.sin(lean()) * TORSO;
    }

    void headCenter(float[] out) {
        at(0f, topU() - 0.05f, topF() + 0.5f, out);
    }

    /** The right fist, raised to throw or hanging by the knees. */
    void hand(float[] out) {
        float su = topU() - 0.2f, sf = topF() - 0.1f;
        float a = armUpper(1), b = armFore(1);
        float eu = su - (float) Math.cos(a) * 1.3f, ef = sf + (float) Math.sin(a) * 1.3f;
        at(1.45f, eu - (float) Math.cos(b) * 1.45f, ef + (float) Math.sin(b) * 1.45f, out);
    }

    private float armUpper(int side) {
        if (side > 0 && raise > 0f) return 0.35f - raise * 3.0f;
        float swing = act == AIR ? -0.9f : 0.35f + (float) Math.sin(time * 1.7f + side) * 0.06f;
        return swing;
    }

    private float armFore(int side) {
        if (side > 0 && raise > 0f) return 0.6f - raise * 3.2f;
        return act == AIR ? -0.4f : 0.65f;
    }

    /** Distance from a world point to its bulk, for blows that hit the body. */
    float bodyDist(float px, float py, float pz) {
        chest(t3);
        float dx = px - t3[0], dz = pz - t3[2];
        float hd = (float) Math.sqrt(dx * dx + dz * dz);
        float lo = (hipY() + air - 0.3f) * G, hi = (topU() + air + 0.3f) * G;
        float dy = py < lo ? lo - py : py > hi ? py - hi : 0f;
        return (float) Math.sqrt(hd * hd + dy * dy);
    }

    // ---- drawing ----

    private Raster ras;
    private int id;

    private static final int STONE = 0xFFC9BEAD, STONE2 = 0xFFB0A592, BRICK = 0xFFB4523A, BRICK2 = 0xFF9C4632,
            WOOD = 0xFFA87444, IRON = 0xFF6E3A22, GLOW = 0xFFFFA02A, DARK = 0xFF2E2628, EYE = 0xFFFFC94A;

    // the rubble each part is heaped from: fixed once, so it does not flicker
    private static final int SEG = 10, BITS = 9;
    private static final float[][] bit = new float[SEG * BITS][];

    static {
        java.util.Random r = new java.util.Random(19);
        int[] mats = {0, 0, 0, 1, 2, 0};
        for (int i = 0; i < bit.length; i++) {
            // along the part, around it, out from it, size, material
            bit[i] = new float[]{0.08f + r.nextFloat() * 0.84f, r.nextFloat() * 6.283f, 0.7f + r.nextFloat() * 0.3f,
                    0.13f + r.nextFloat() * 0.12f, mats[r.nextInt(mats.length)]};
        }
    }

    private int tint(int c) {
        return hurt > 0f ? Backdrop.mix(c, 0xFFFFE0B0, hurt * 0.6f) : c;
    }

    /**
     * A box from point a to point b of its frame (same right offset), this thick; with a
     * heap of smaller stones, bricks and planks stuck on it all round.
     */
    private void part(float r, float ua, float fa, float ub, float fb, float hw, float hd, int color, int pat, int seg) {
        float du = ub - ua, df = fb - fa, len = (float) Math.sqrt(du * du + df * df);
        if (len < 1e-3f) return;
        float yu = du / len, yf = df / len;
        float ux = fx * yf, uy = yu, uz = fz * yf;
        float wx = -rz * uy, wy = rz * ux - rx * uz, wz = rx * uy;
        at(r, (ua + ub) * 0.5f, (fa + fb) * 0.5f, t3);
        ras.box(t3[0], t3[1], t3[2], rx, ux, wx, 0f, uy, wy, rz, uz, wz, hw * G, len * 0.5f * G, hd * G,
                tint(color), pat, id);
        if (seg < 0) return;
        for (int i = 0; i < BITS; i++) {
            float[] q = bit[seg * BITS + i];
            float t = q[0], ang = q[1], out = q[2], s = q[3];
            float cr = (float) Math.cos(ang) * hw * out, cf = (float) Math.sin(ang) * hd * out;
            // across the part: right, and its own depth (perpendicular to it in the up-forward plane)
            float pu = ua + du * t - yf * cf, pf = fa + df * t + yu * cf;
            at(r + cr, pu, pf, t3);
            int m = (int) q[4];
            int c = m == 0 ? (i % 2 == 0 ? STONE : STONE2) : m == 1 ? (i % 2 == 0 ? BRICK : BRICK2) : WOOD;
            int p = m == 0 ? Raster.PAT_STONE : m == 2 ? Raster.PAT_GRAIN : Raster.PAT_BRICK;
            float sw = s * (m == 1 ? 1.3f : 1f), sh = s * (m == 1 ? 0.6f : 1f);
            ras.box(t3[0], t3[1], t3[2], rx, ux, wx, 0f, uy, wy, rz, uz, wz, sw * G, sh * G, s * G, tint(c), p, id);
        }
    }

    /** A rod of iron sticking out of it, from a point, in a direction. */
    private void rod(float r, float u, float f, float dr, float du, float df) {
        float len = (float) Math.sqrt(dr * dr + du * du + df * df);
        at(r + dr * 0.5f, u + du * 0.5f, f + df * 0.5f, t3);
        float wx = (rx * dr + fx * df) / len, wy = du / len, wz = (rz * dr + fz * df) / len;
        // two axes square to it: any will do for something this thin
        float ax = -wz, az = wx, ay = 0f;
        float al = (float) Math.sqrt(ax * ax + az * az);
        if (al < 1e-3f) {
            ax = 1f;
            az = 0f;
            al = 1f;
        }
        ax /= al;
        az /= al;
        float bx = wy * az - wz * ay, by = wz * ax - wx * az, bz = wx * ay - wy * ax;
        ras.box(t3[0], t3[1], t3[2], ax, wx, bx, ay, wy, by, az, wz, bz, 0.035f * G, len * 0.5f * G, 0.035f * G,
                tint(IRON), Raster.PAT_FLAT, id);
    }

    void draw(Raster r, int id) {
        if (down) return;
        ras = r;
        this.id = id;
        float hip = hipY();
        // legs: stone thighs, brick shins, feet of great slabs; a glowing crack at each knee
        for (int side = -1; side <= 1; side += 2) {
            float lr = side * 0.75f, b = bend(side);
            float ku = hip - (float) Math.cos(b) * THIGH, kf = (float) Math.sin(b) * THIGH;
            float fu = Math.max(0.15f - air, ku - (float) Math.cos(b * 0.9f) * SHIN), ff = kf - (float) Math.sin(b * 0.9f) * SHIN;
            if (kneeling() > 0f) {
                fu = 0.15f;
                ff = kf - SHIN;
            }
            part(lr, hip + 0.1f, 0f, ku, kf, 0.42f, 0.42f, STONE, Raster.PAT_STONE, side < 0 ? 0 : 1);
            part(lr, ku, kf, fu, ff, 0.36f, 0.38f, STONE2, Raster.PAT_STONE, side < 0 ? 2 : 3);
            if (kneeling() == 0f) part(lr, fu + 0.1f, ff + 0.15f, fu - 0.15f, ff + 0.15f, 0.48f, 0.62f, STONE2, Raster.PAT_STONE, -1);
            boolean broken = (side < 0 ? kneeL : kneeR) == 0;
            float pulse = 0.8f + 0.2f * (float) Math.sin(time * 6f + side);
            at(lr, ku, kf + 0.3f, t3);
            ras.box(t3[0], t3[1], t3[2], rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz, 0.3f * G, 0.24f * G, 0.18f * G,
                    broken ? DARK : Backdrop.scale(GLOW, pulse * 1.2f), Raster.PAT_FLAT, id);
        }
        // the hunched body: a core of stone faced with brick, planks across it
        float l = lean(), tu = topU(), tf = topF();
        part(0f, hip, 0f, tu, tf, HALF_W, 0.7f, STONE2, Raster.PAT_STONE, 4);
        part(0f, hip + 0.7f, 0.3f, tu - 0.45f, tf + 0.3f, HALF_W * 0.6f, 0.5f, BRICK, Raster.PAT_BRICK, -1);
        for (int k = 0; k < 3; k++) {
            float t = 0.25f + k * 0.27f;
            float u = hip + (tu - hip) * t, f = tf * t + 0.62f;
            at(0f, u, f + 0.05f, t3);
            ras.box(t3[0], t3[1], t3[2], rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz, 0.95f * G, 0.07f * G, 0.06f * G,
                    tint(WOOD), Raster.PAT_GRAIN, id);
        }
        // shoulders: two great boulders, iron rods sticking out of them
        for (int side = -1; side <= 1; side += 2) {
            float sr = side * 1.35f;
            part(sr, tu - 0.6f, tf - 0.1f, tu + 0.25f, tf - 0.05f, 0.55f, 0.6f, STONE, Raster.PAT_STONE, side < 0 ? 6 : 7);
            rod(sr * 0.8f, tu + 0.2f, tf - 0.3f, side * 0.25f, 0.9f, -0.35f);
            rod(sr * 0.6f, tu + 0.1f, tf - 0.4f, side * 0.1f, 0.7f, -0.2f);
        }
        // arms down to the knees, fists of rubble; the right one rises with a car
        for (int side = -1; side <= 1; side += 2) {
            float sr = side * 1.45f, su = tu - 0.2f, sf = tf - 0.1f;
            float a = armUpper(side), b = armFore(side);
            float eu = su - (float) Math.cos(a) * 1.3f, ef = sf + (float) Math.sin(a) * 1.3f;
            float hu = eu - (float) Math.cos(b) * 1.45f, hf = ef + (float) Math.sin(b) * 1.45f;
            part(sr, su, sf, eu, ef, 0.3f, 0.32f, STONE2, Raster.PAT_STONE, side < 0 ? 8 : 9);
            part(sr, eu, ef, hu, hf, 0.34f, 0.36f, BRICK2, Raster.PAT_BRICK, side < 0 ? 5 : 4);
            float ku = hu - (float) Math.cos(b) * 0.3f, kf = hf + (float) Math.sin(b) * 0.3f;
            part(sr, hu, hf, ku, kf, 0.55f, 0.55f, STONE, Raster.PAT_STONE, side < 0 ? 0 : 3);
        }
        // the head: a block of stone sunk between the shoulders, a jaw of bricks, eyes alight
        headCenter(j);
        float hu = topU() - 0.05f, hf = topF() + 0.5f;
        at(0f, hu + 0.08f, hf, t3);
        int stone = head == 3 ? STONE : head == 2 ? STONE2 : 0xFF8E8676;
        ras.box(t3[0], t3[1], t3[2], rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz, 0.5f * G, 0.38f * G, 0.45f * G,
                tint(stone), Raster.PAT_STONE, id);
        at(0f, hu - 0.36f, hf + 0.08f, t3);
        ras.box(t3[0], t3[1], t3[2], rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz, 0.42f * G, 0.14f * G, 0.38f * G,
                tint(BRICK), Raster.PAT_BRICK, id);
        // the brow juts over the eyes
        at(0f, hu + 0.24f, hf + 0.42f, t3);
        ras.box(t3[0], t3[1], t3[2], rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz, 0.48f * G, 0.08f * G, 0.08f * G,
                tint(STONE2), Raster.PAT_STONE, id);
        int eye = head == 3 ? EYE : head == 2 ? 0xFFFFA02A : 0xFFE0502A;
        for (int side = -1; side <= 1; side += 2) {
            at(side * 0.2f, hu + 0.1f, hf + 0.47f, t3);
            ras.box(t3[0], t3[1], t3[2], rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz, 0.1f * G, 0.06f * G, 0.03f * G,
                    eye, Raster.PAT_FLAT, id);
        }
        // iron rods out of its back, like a ruin's
        rod(-0.4f, tu - 0.3f, tf - 0.7f, -0.2f, 0.8f, -0.4f);
        rod(0.3f, tu - 0.6f, tf - 0.75f, 0.15f, 0.7f, -0.5f);
    }
}
