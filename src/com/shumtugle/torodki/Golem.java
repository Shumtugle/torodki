package com.shumtugle.torodki;

/**
 * The golem: a giant put together from what the buildings are made of. Stone blocks
 * for legs, a wall of bricks for a body, water bottles for arms, a polished ball for a
 * head. It walks up the street toward the player, tearing parked cars from their places
 * and throwing them. Its weak points glow: two knees and the head. Break one knee and it
 * limps; break both and it drops to its knees and crawls, its head brought low; three
 * blows to the head and it falls apart.
 *
 * Plain geometry and timing; the world decides what hits it and draws it with the raster.
 */
final class Golem {
    // size, meters
    static final float SHIN = 1.25f, THIGH = 1.25f, BODY = 2.4f, HALF_W = 1.2f, HALF_D = 0.6f, HEAD_R = 0.78f;
    static final float ARM = 2.3f;
    static final float KNEE_R = 0.6f;
    // everything above is drawn this much bigger: a giant, not a man
    static final float G = 1.5f;
    static final float KNEE_HIT = KNEE_R * G, HEAD_HIT = HEAD_R * G, BODY_HIT = HALF_W * G;

    float x, z;                 // where its feet are
    float speed = 1.1f;         // meters a second at a healthy stride
    int kneeL = 1, kneeR = 1, head = 3;
    float stagger;              // seconds of stunned stillness left
    float kneel;                // 0 standing, 1 on its knees
    float phase;                // stride
    float time;
    float hurt;                 // flash when hit, fades
    boolean down;               // fallen apart
    // the throwing arm: 0 hanging, 1 raised high with a car
    float raise;
    boolean holding;

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
        // it looks toward the player: toward the camera's spot at the near end of the street
        float dx = World.CAM_X - x, dz = 0f - z;
        float l = (float) Math.sqrt(dx * dx + dz * dz);
        fx = dx / l;
        fz = dz / l;
        rx = -fz;
        rz = fx;
    }

    float pace() {
        int knees = kneeL + kneeR;
        return knees == 2 ? speed : knees == 1 ? speed * 0.62f : speed * 0.42f;
    }

    void update(float dt) {
        time += dt;
        hurt = Math.max(0f, hurt - dt * 3f);
        if (down) return;
        boolean onKnees = kneeL + kneeR == 0;
        kneel = onKnees ? Math.min(1f, kneel + dt * 1.6f) : 0f;
        if (stagger > 0f) {
            stagger -= dt;
            return;
        }
        float v = pace();
        x += fx * v * dt * 0.15f;
        z += fz * v * dt;
        phase += dt * v * 2.2f;
        face();
    }

    /** True when it has come close enough to strike the player. */
    boolean arrived() {
        return z < 8f;
    }

    // ---- where things are ----

    private float hipY() {
        float bob = (float) Math.abs(Math.sin(phase)) * 0.12f;
        return SHIN + THIGH - bob * (1f - kneel) - (SHIN + THIGH - 0.95f) * kneel;
    }

    /** Swing of a leg at the hip, radians forward, by stride (side -1 left, +1 right). */
    private float swing(int side) {
        if (kneel > 0f) return 0f;
        float limp = (side < 0 ? kneeL : kneeR) == 0 ? 0.4f : 1f;
        return (float) Math.sin(phase + (side < 0 ? 0f : (float) Math.PI)) * 0.42f * limp;
    }

    private final float[] t3 = new float[3];

    /** A point from the golem's frame (right, up, toward the player) into the world. */
    void at(float r, float u, float f, float[] out) {
        // the whole bulk rolls from foot to foot as it walks
        r += (float) Math.sin(phase) * 0.14f * (1f - kneel) * (stagger > 0f ? 0.3f : 1f);
        out[0] = x + (rx * r + fx * f) * G;
        out[1] = u * G;
        out[2] = z + (rz * r + fz * f) * G;
    }

    void knee(int side, float[] out) {
        float a = swing(side);
        float hip = hipY();
        if (kneel > 0f) {
            // the thighs come down upright, the knees go to the ground in front
            float k = kneel;
            at(side * HALF_W * 0.55f, hip - THIGH + (0.3f - (hip - THIGH)) * k, 0.25f * k, out);
            return;
        }
        at(side * HALF_W * 0.55f, hip - (float) Math.cos(a) * THIGH, (float) Math.sin(a) * THIGH, out);
    }

    void headCenter(float[] out) {
        at(0f, hipY() + BODY + HEAD_R * 0.92f, 0f, out);
    }

    void chest(float[] out) {
        at(0f, hipY() + BODY * 0.55f, 0f, out);
    }

    void hand(float[] out) {
        float a = -raise * 2.8f;     // raised up and a little back
        float sy = hipY() + BODY - 0.25f;
        at(HALF_W + 0.35f, sy - (float) Math.cos(a) * ARM, (float) Math.sin(a) * ARM * 0.6f, out);
    }

    /** Distance from a world point to the body's middle line, for blows that hit the bulk. */
    float bodyDist(float px, float py, float pz) {
        float dx = px - x, dz = pz - z;
        float hd = (float) Math.sqrt(dx * dx + dz * dz);
        float top = (hipY() + BODY) * G;
        float dy = py < 0f ? -py : py > top ? py - top : 0f;
        return (float) Math.sqrt(hd * hd + dy * dy);
    }

    // ---- drawing ----

    private Raster ras;
    private int id;
    private float lightK = 1f;

    /** A box hanging from a joint: its top at (ar, au, af), leaning forward by angle. */
    private void limb(float ar, float au, float af, float angle, float len, float hw, float hd, int color, int pat) {
        float c = (float) Math.cos(angle), sn = (float) Math.sin(angle);
        at(ar, au - c * len * 0.5f, af + sn * len * 0.5f, t3);
        // its own up runs from its lower end to its top; its depth is right x up
        float ux = -sn * fx, uy = c, uz = -sn * fz;
        float wx = -rz * uy, wy = rz * ux - rx * uz, wz = rx * uy;
        ras.box(t3[0], t3[1], t3[2], rx, ux, wx, 0f, uy, wy, rz, uz, wz, hw * G, len * 0.5f * G, hd * G, tint(color), pat, id);
    }

    private void block(float r, float u, float f, float hw, float hh, float hd, int color, int pat) {
        at(r, u, f, t3);
        ras.box(t3[0], t3[1], t3[2], rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz, hw * G, hh * G, hd * G, tint(color), pat, id);
    }

    private int tint(int c) {
        return hurt > 0f ? Backdrop.mix(c, 0xFFFFE0B0, hurt * 0.6f) : c;
    }

    private static final int STONE = 0xFFC9BEAD, STONE2 = 0xFFB3A794, WOOD = 0xFFC4864F, WOOD2 = 0xFFD39E62,
            BOTTLE = 0xFF8FC4EE, GLOW = 0xFFFFB13A, DARK = 0xFF3A3236;

    void draw(Raster r, int id) {
        if (down) return;
        ras = r;
        this.id = id;
        float hip = hipY();
        for (int side = -1; side <= 1; side += 2) {
            float a = swing(side);
            float hr = side * HALF_W * 0.55f;
            boolean broken = (side < 0 ? kneeL : kneeR) == 0;
            if (kneel > 0f) {
                // thigh upright above the knee, shin lying back along the ground
                float k = kneel;
                knee(side, t3);
                float ku = t3[1] / G;
                limb(hr, hip, 0f, 0.25f * k, hip - ku, 0.36f, 0.4f, STONE, Raster.PAT_STONE);
                limb(hr, ku, 0.25f * k, -1.45f * k, SHIN, 0.32f, 0.36f, STONE2, Raster.PAT_STONE);
            } else {
                limb(hr, hip, 0f, a, THIGH, 0.38f, 0.42f, STONE, Raster.PAT_STONE);
                float ku = hip - (float) Math.cos(a) * THIGH, kf = (float) Math.sin(a) * THIGH;
                // the leg in the air bends at the knee: from the front its foot is seen to rise
                float lift = Math.max(0f, (float) Math.sin(phase + (side < 0 ? 0f : (float) Math.PI) - 0.6f));
                float sa = a - lift * 1.3f;
                limb(hr, ku, kf, sa, SHIN, 0.34f, 0.38f, STONE2, Raster.PAT_STONE);
            }
            // the knee: a glowing core while whole, a dark crack once broken
            knee(side, t3);
            t3[1] /= G;
            float pulse = 0.85f + 0.15f * (float) Math.sin(time * 5f + side);
            float kr = ((t3[0] - x) * rx + (t3[2] - z) * rz) / G, kf2 = ((t3[0] - x) * fx + (t3[2] - z) * fz) / G;
            block(kr, t3[1], kf2 + 0.22f, 0.34f, 0.32f, 0.3f,
                    broken ? DARK : Backdrop.scale(GLOW, pulse * 1.15f), Raster.PAT_FLAT);
        }
        // the body: a core of dark planks, faced with courses of bricks, every other one offset
        block(0f, hip + BODY * 0.5f, -0.06f, HALF_W - 0.04f, BODY * 0.5f, HALF_D - 0.06f, 0xFF7A4E2E, Raster.PAT_GRAIN);
        float y = hip;
        int courses = 8;
        float ch = BODY / courses;
        for (int c = 0; c < courses; c++) {
            float off = (c & 1) == 0 ? 0f : 0.3f;
            for (int i = -3; i <= 2; i++) {
                float b0 = Math.max(-HALF_W, (i - 0.5f) * 0.6f + off), b1 = Math.min(HALF_W, (i + 0.5f) * 0.6f + off);
                if (b1 - b0 < 0.1f) continue;
                block((b0 + b1) * 0.5f, y + ch * 0.5f, 0.02f, (b1 - b0) * 0.5f - 0.015f, ch * 0.47f, HALF_D,
                        ((c + i) & 1) == 0 ? WOOD : WOOD2, Raster.PAT_GRAIN);
            }
            y += ch;
        }
        // shoulders of stone
        block(0f, hip + BODY - 0.12f, 0f, HALF_W + 0.25f, 0.15f, HALF_D + 0.05f, STONE2, Raster.PAT_STONE);
        // arms: three water bottles each; the right one rises to throw
        float sy = hip + BODY - 0.25f;
        for (int side = -1; side <= 1; side += 2) {
            float a = side > 0 ? -raise * 2.8f : (float) Math.sin(phase) * 0.25f;
            float seg = ARM / 3f;
            for (int k = 0; k < 3; k++) {
                float u = sy - (float) Math.cos(a) * seg * k, f = (float) Math.sin(a) * seg * k * (side > 0 ? 0.6f : 1f);
                limb(side * (HALF_W + 0.35f), u, f, a, seg * 0.94f, 0.26f, 0.26f, BOTTLE, Raster.PAT_FLAT);
            }
        }
        // the head: a great polished ball, eyes alight
        headCenter(t3);
        float hx = t3[0], hy = t3[1], hz = t3[2], hyl = hy / G;
        ras.ball(hx, hy, hz, HEAD_R * G, rx, 0f, fx, 0f, 1f, 0f, rz, 0f, fz,
                tint(head == 3 ? 0xFF2A2F48 : head == 2 ? 0xFF3A2A3E : 0xFF4A2230), tint(0xFF55607A), id);
        // eyes: they dim with every blow
        float[] s = new float[2];
        for (int side = -1; side <= 1; side += 2) {
            at(side * 0.28f, hyl + 0.12f, HEAD_R * 0.93f, t3);
            float t3x = t3[0], t3y = t3[1], t3z = t3[2];
            // pulled a little toward the head's center, so the ball does not hide them
            float vz = ras.project(t3x + (hx - t3x) * 0.05f, t3y, t3z + (hz - t3z) * 0.05f, s);
            if (vz < 0.5f) continue;
            vz -= 0.3f;
            float rad = Math.max(0.8f, 0.11f * G * ras.f / vz);
            int eye = head == 3 ? 0xFFFFE07A : head == 2 ? 0xFFFFB13A : 0xFFE0602A;
            ras.disc(s[0], s[1], rad, 1f / Math.max(0.3f, vz - 0.25f), eye, id);
        }
    }
}
