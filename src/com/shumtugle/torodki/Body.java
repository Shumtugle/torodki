package com.shumtugle.torodki;

/**
 * One rigid box, or a ball: position, orientation, velocities, mass properties and the
 * points on its surface that are tested against other boxes. Plain fields, no allocation in
 * the hot loops; the rotation matrix is kept in step with the quaternion.
 */
final class Body {
    static final int WOOD = 0, STONE = 1, HAMMER = 2, PLINTH = 3, GLASS = 4, WATER = 5, BALL = 6, CAR = 7, BALLOON = 8, GOLD = 9;
    // street things: an empty steel drum, a road cone, a car tyre, a bag of sand
    static final int BARREL = 10, CONE = 11, TYRE = 12, SANDBAG = 13;
    // an old suitcase full of clothes, a spring mattress
    static final int SUITCASE = 14, MATTRESS = 15;
    static final float[] DENSITY = {600f, 1200f, 8400f, 0f, 900f, 760f, 1500f, 0f, 160f, 4000f, 380f, 220f, 320f, 1600f,
            300f, 140f};
    static final float[] FRICTION = {0.65f, 0.75f, 0.5f, 0.7f, 0.35f, 0.55f, 0.45f, 0.6f, 0.95f, 0.8f, 0.45f, 0.6f, 0.95f, 0.95f,
            0.9f, 0.9f};
    // how far a contact gives under load, as XPBD compliance in m/N: only the mattress is soft
    static final float[] GIVE = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1e-5f};
    // what soaks up a blow instead of bouncing it back
    static final boolean[] DEAD = {false, false, false, false, false, false, false, false, false, false, false, false, false,
            false, true, true};

    static final float INERTIA = 3f;

    int id;
    int material;
    int tint;                       // base color
    float hx, hy, hz;               // half extents
    float radius;                   // above zero for a ball: a sphere of this radius
    float lift;                     // upward pull against gravity, m/s^2: a balloon floats
    float mass, invMass;
    float iix, iiy, iiz;            // inverse inertia, body frame

    float px, py, pz;
    float qw = 1, qx, qy, qz;
    float vx, vy, vz, wx, wy, wz;

    // pose at the start of the substep
    float ppx, ppy, ppz, pqw, pqx, pqy, pqz;
    // rotation matrix, row-major: world = R * local
    float r00, r01, r02, r10, r11, r12, r20, r21, r22;
    float p00, p01, p02, p10, p11, p12, p20, p21, p22;

    float[] samples;                // local points, xyz triples
    float minX, minY, minZ, maxX, maxY, maxZ; // world bounds

    boolean asleep;
    float still;                    // seconds spent nearly motionless
    boolean ghost;                  // collides with nothing
    boolean gone;                   // burst: no longer part of the building
    float sunk;                     // meters gone under the water, out at sea
    boolean icy;                    // out at sea: a block of ice, not of wood or stone
    boolean snowy;                  // out at sea: a cap of snow on its top, till it is knocked off
    float lastHit;                  // game time of the last sound it made
    float homeX, homeY, homeZ;      // where the generator put it
    float homeQw = 1f, homeQx, homeQy, homeQz;  // and turned how, where that matters
    float shown = 1f;               // drawn at this share of its size, while it flies into place
    // a suitcase burst open or a mattress torn: game time it happened, or below zero
    float broke = -1f;
    float markX, markY, markZ;      // where, in the body's own frame
    int markAxis;                   // the face: 0, 1, 2 for x, y, z; plus 3 for the negative side

    Body(float hx, float hy, float hz, int material, int tint) {
        this.hx = hx;
        this.hy = hy;
        this.hz = hz;
        this.material = material;
        this.tint = tint;
        float d = DENSITY[material];
        if (d <= 0f) {
            mass = 0f;
            invMass = 0f;
        } else {
            mass = d * 8f * hx * hy * hz;
            invMass = 1f / mass;
            // inertia a few times the true value: stacks hold still, falls still read right
            float k = mass / 3f * INERTIA;
            iix = 1f / (k * (hy * hy + hz * hz));
            iiy = 1f / (k * (hx * hx + hz * hz));
            iiz = 1f / (k * (hx * hx + hy * hy));
        }
        buildSamples();
        updateR();
    }

    /** A heavy polished ball, the kind rolled down a lane at pins. */
    static Body ball(float r, int tint) {
        Body b = new Body(r, r, r, BALL, tint);
        b.radius = r;
        b.mass = DENSITY[BALL] * 4.18879f * r * r * r;
        b.invMass = 1f / b.mass;
        // a solid sphere, its inertia raised a little like the boxes', so it rolls but not wildly
        float ii = 1f / (0.4f * b.mass * r * r * 1.6f);
        b.iix = ii;
        b.iiy = ii;
        b.iiz = ii;
        b.updateR();
        return b;
    }

    /**
     * A big rubber balloon, firm enough to carry a plank. Its pull upward is a little more
     * than its weight: loaded it stays put, freed it drifts up into the sky.
     */
    static Body balloon(float r, int tint) {
        Body b = ball(r, tint);
        b.material = BALLOON;
        b.mass = DENSITY[BALLOON] * 4.18879f * r * r * r;
        b.invMass = 1f / b.mass;
        float ii = 1f / (0.4f * b.mass * r * r * 1.6f);
        b.iix = ii;
        b.iiy = ii;
        b.iiz = ii;
        b.lift = 9.81f * 1.3f;
        return b;
    }

    boolean dynamic() {
        return invMass > 0f;
    }

    /** Corners, then points along every edge, a little apart, so crossed beams still meet. */
    private void buildSamples() {
        float step = 0.11f;
        float[] tmp = new float[3 * 512];
        int n = 0;
        // a hair inside the surface, so a box resting flush on an equal box still finds it
        float m = 0.004f;
        float[] h = {hx - m, hy - m, hz - m};
        for (int i = 0; i < 8; i++) {
            tmp[n++] = (i & 1) == 0 ? -h[0] : h[0];
            tmp[n++] = (i & 2) == 0 ? -h[1] : h[1];
            tmp[n++] = (i & 4) == 0 ? -h[2] : h[2];
        }
        for (int axis = 0; axis < 3; axis++) {
            int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
            int seg = (int) Math.ceil(2f * h[axis] / step);
            for (int s = 1; s < seg; s++) {
                float t = -h[axis] + 2f * h[axis] * s / seg;
                for (int c = 0; c < 4; c++) {
                    if (n + 3 > tmp.length) break;
                    float[] p = new float[3];
                    p[axis] = t;
                    p[a1] = (c & 1) == 0 ? -h[a1] : h[a1];
                    p[a2] = (c & 2) == 0 ? -h[a2] : h[a2];
                    tmp[n++] = p[0];
                    tmp[n++] = p[1];
                    tmp[n++] = p[2];
                }
            }
        }
        samples = new float[n];
        System.arraycopy(tmp, 0, samples, 0, n);
    }

    void setPose(float x, float y, float z, float w, float a, float b, float c) {
        px = x;
        py = y;
        pz = z;
        qw = w;
        qx = a;
        qy = b;
        qz = c;
        normalize();
        updateR();
        vx = vy = vz = wx = wy = wz = 0f;
    }

    void normalize() {
        float l = (float) Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        if (l < 1e-9f) {
            qw = 1;
            qx = qy = qz = 0;
            return;
        }
        l = 1f / l;
        qw *= l;
        qx *= l;
        qy *= l;
        qz *= l;
    }

    void updateR() {
        float xx = qx * qx, yy = qy * qy, zz = qz * qz;
        float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        float wx_ = qw * qx, wy_ = qw * qy, wz_ = qw * qz;
        r00 = 1 - 2 * (yy + zz);
        r01 = 2 * (xy - wz_);
        r02 = 2 * (xz + wy_);
        r10 = 2 * (xy + wz_);
        r11 = 1 - 2 * (xx + zz);
        r12 = 2 * (yz - wx_);
        r20 = 2 * (xz - wy_);
        r21 = 2 * (yz + wx_);
        r22 = 1 - 2 * (xx + yy);
        float ex = Math.abs(r00) * hx + Math.abs(r01) * hy + Math.abs(r02) * hz;
        float ey = Math.abs(r10) * hx + Math.abs(r11) * hy + Math.abs(r12) * hz;
        float ez = Math.abs(r20) * hx + Math.abs(r21) * hy + Math.abs(r22) * hz;
        if (radius > 0f) ex = ey = ez = radius;
        minX = px - ex;
        maxX = px + ex;
        minY = py - ey;
        maxY = py + ey;
        minZ = pz - ez;
        maxZ = pz + ez;
    }

    /** Makes the body heavier or lighter, keeping its shape: mass and inertia scale together. */
    /** Gives up the raised inertia that keeps stacks still: it topples as freely as a real block. */
    void trueInertia() {
        iix *= INERTIA;
        iiy *= INERTIA;
        iiz *= INERTIA;
    }

    void scaleMass(float k) {
        if (invMass == 0f) return;
        mass *= k;
        invMass /= k;
        iix /= k;
        iiy /= k;
        iiz /= k;
    }

    void savePrev() {
        ppx = px;
        ppy = py;
        ppz = pz;
        pqw = qw;
        pqx = qx;
        pqy = qy;
        pqz = qz;
        p00 = r00;
        p01 = r01;
        p02 = r02;
        p10 = r10;
        p11 = r11;
        p12 = r12;
        p20 = r20;
        p21 = r21;
        p22 = r22;
    }

    /** Applies the world-space inverse inertia to (x, y, z); result in out[0..2]. */
    void invInertia(float x, float y, float z, float[] out) {
        // into the body frame
        float lx = r00 * x + r10 * y + r20 * z;
        float ly = r01 * x + r11 * y + r21 * z;
        float lz = r02 * x + r12 * y + r22 * z;
        lx *= iix;
        ly *= iiy;
        lz *= iiz;
        out[0] = r00 * lx + r01 * ly + r02 * lz;
        out[1] = r10 * lx + r11 * ly + r12 * lz;
        out[2] = r20 * lx + r21 * ly + r22 * lz;
    }

    /** Rotates the orientation by a small world-space rotation vector. */
    void rotateBy(float ax, float ay, float az) {
        float dw = -ax * qx - ay * qy - az * qz;
        float dx = ax * qw + ay * qz - az * qy;
        float dy = -ax * qz + ay * qw + az * qx;
        float dz = ax * qy - ay * qx + az * qw;
        qw += 0.5f * dw;
        qx += 0.5f * dx;
        qy += 0.5f * dy;
        qz += 0.5f * dz;
        normalize();
    }

    float speed2() {
        return vx * vx + vy * vy + vz * vz + 0.25f * (wx * wx + wy * wy + wz * wz);
    }

    /** Height of the lowest point above the ground plane. */
    float bottom() {
        return minY;
    }
}
