package com.shumtugle.torodki;

import java.util.ArrayList;

/**
 * Rigid boxes on a ground plane, solved on positions with many small substeps.
 * Contacts are found by testing the surface points of one box against the volume
 * of the other, both ways round; each contact pushes the pair apart along the face
 * the point is nearest to. Friction holds what should not slide, so stacks stand
 * still; bodies that stay still fall asleep and cost nothing until they are hit.
 */
final class Physics {
    static final float G = -9.81f;
    static final int SUBSTEPS = 10;
    static final int ITERS = 2;
    private int flip;

    final ArrayList<Body> bodies = new ArrayList<Body>();
    float time;

    /** Called for every hard knock: the body, the other one (or null for the ground), closing speed. */
    interface Listener {
        void knock(Body a, Body b, float speed, float x, float y, float z);
    }

    Listener listener;
    boolean sleeping = true;

    // contacts, structure of arrays
    private int cn;
    private int[] ca = new int[1024], cb = new int[1024];
    private float[] cra = new float[3 * 1024], crb = new float[3 * 1024]; // local points
    private float[] cnr = new float[3 * 1024];                          // normal out of b
    private float[] cln = new float[1024];                              // normal lambda
    private float[] cvn = new float[1024];                              // closing speed before solve
    private final float[] tmp = new float[3], tmp2 = new float[3];

    void add(Body b) {
        b.id = bodies.size();
        bodies.add(b);
    }

    void clear() {
        bodies.clear();
    }

    void step(float dt) {
        float h = dt / SUBSTEPS;
        for (int s = 0; s < SUBSTEPS; s++) substep(h);
        time += dt;
        if (sleeping) updateSleep(dt);
    }

    private boolean moving(Body b) {
        return b.dynamic() && !b.asleep && !b.ghost;
    }

    private void substep(float h) {
        int n = bodies.size();
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            if (!b.dynamic() || b.asleep) continue;
            b.savePrev();
            if (b.ghost) continue;
            b.vy += G * h;
            b.px += b.vx * h;
            b.py += b.vy * h;
            b.pz += b.vz * h;
            b.rotateBy(b.wx * h, b.wy * h, b.wz * h);
            b.updateR();
        }
        collide();
        for (int it = 0; it < ITERS; it++) solvePositions(((flip++) & 1) == 1);
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            if (!b.dynamic() || b.asleep || b.ghost) continue;
            float ih = 1f / h;
            b.vx = (b.px - b.ppx) * ih;
            b.vy = (b.py - b.ppy) * ih;
            b.vz = (b.pz - b.ppz) * ih;
            // dq = q * conj(qprev)
            float aw = b.qw, ax = b.qx, ay = b.qy, az = b.qz;
            float bw = b.pqw, bx = -b.pqx, by = -b.pqy, bz = -b.pqz;
            float dw = aw * bw - ax * bx - ay * by - az * bz;
            float dx = aw * bx + ax * bw + ay * bz - az * by;
            float dy = aw * by - ax * bz + ay * bw + az * bx;
            float dz = aw * bz + ax * by - ay * bx + az * bw;
            float k = 2f * ih * (dw < 0 ? -1f : 1f);
            b.wx = dx * k;
            b.wy = dy * k;
            b.wz = dz * k;
        }
        solveVelocities(h);
    }

    // ---- contacts ----

    private void collide() {
        cn = 0;
        int n = bodies.size();
        for (int i = 0; i < n; i++) {
            Body a = bodies.get(i);
            if (!moving(a)) continue;
            if (a.minY < 0f) groundContacts(a);
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                Body b = bodies.get(j);
                if (b.ghost) continue;
                // each pair once when both move; static and sleeping ones are seen from the mover
                if (moving(b) && j < i) continue;
                if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY
                        || a.maxZ < b.minZ || b.maxZ < a.minZ) continue;
                pointsInto(a, b);
                pointsInto(b, a);
            }
        }
    }

    private void groundContacts(Body a) {
        float[] s = a.samples;
        for (int k = 0; k < s.length; k += 3) {
            float lx = s[k], ly = s[k + 1], lz = s[k + 2];
            float wy = a.py + a.r10 * lx + a.r11 * ly + a.r12 * lz;
            if (wy >= 0f) continue;
            float wx = a.px + a.r00 * lx + a.r01 * ly + a.r02 * lz;
            float wz = a.pz + a.r20 * lx + a.r21 * ly + a.r22 * lz;
            int c = alloc();
            if (c < 0) return;
            ca[c] = a.id;
            cb[c] = -1;
            cra[3 * c] = lx;
            cra[3 * c + 1] = ly;
            cra[3 * c + 2] = lz;
            crb[3 * c] = wx;       // for the ground, the world point it is pushed to
            crb[3 * c + 1] = 0f;
            crb[3 * c + 2] = wz;
            cnr[3 * c] = 0f;
            cnr[3 * c + 1] = 1f;
            cnr[3 * c + 2] = 0f;
            cln[c] = 0f;
            cvn[c] = closing(a, null, wx, wy, wz, 0f, 1f, 0f);
        }
    }

    /** Points of a that sit inside b. */
    private void pointsInto(Body a, Body b) {
        float[] s = a.samples;
        for (int k = 0; k < s.length; k += 3) {
            float lx = s[k], ly = s[k + 1], lz = s[k + 2];
            float wx = a.px + a.r00 * lx + a.r01 * ly + a.r02 * lz;
            float wy = a.py + a.r10 * lx + a.r11 * ly + a.r12 * lz;
            float wz = a.pz + a.r20 * lx + a.r21 * ly + a.r22 * lz;
            if (wx < b.minX || wx > b.maxX || wy < b.minY || wy > b.maxY || wz < b.minZ || wz > b.maxZ) continue;
            float dx = wx - b.px, dy = wy - b.py, dz = wz - b.pz;
            float bx = b.r00 * dx + b.r10 * dy + b.r20 * dz;
            float by = b.r01 * dx + b.r11 * dy + b.r21 * dz;
            float bz = b.r02 * dx + b.r12 * dy + b.r22 * dz;
            float ex = b.hx - Math.abs(bx), ey = b.hy - Math.abs(by), ez = b.hz - Math.abs(bz);
            if (ex <= 0 || ey <= 0 || ez <= 0) continue;
            // the nearest face decides the normal
            float nx, ny, nz, sx = bx, sy = by, sz = bz;
            if (ex <= ey && ex <= ez) {
                float sg = bx < 0 ? -1f : 1f;
                nx = b.r00 * sg;
                ny = b.r10 * sg;
                nz = b.r20 * sg;
                sx = b.hx * sg;
            } else if (ey <= ez) {
                float sg = by < 0 ? -1f : 1f;
                nx = b.r01 * sg;
                ny = b.r11 * sg;
                nz = b.r21 * sg;
                sy = b.hy * sg;
            } else {
                float sg = bz < 0 ? -1f : 1f;
                nx = b.r02 * sg;
                ny = b.r12 * sg;
                nz = b.r22 * sg;
                sz = b.hz * sg;
            }
            int c = alloc();
            if (c < 0) return;
            ca[c] = a.id;
            cb[c] = b.id;
            cra[3 * c] = lx;
            cra[3 * c + 1] = ly;
            cra[3 * c + 2] = lz;
            crb[3 * c] = sx;
            crb[3 * c + 1] = sy;
            crb[3 * c + 2] = sz;
            cnr[3 * c] = nx;
            cnr[3 * c + 1] = ny;
            cnr[3 * c + 2] = nz;
            cln[c] = 0f;
            cvn[c] = closing(a, b, wx, wy, wz, nx, ny, nz);
        }
    }

    private int alloc() {
        if (cn == ca.length) {
            if (cn >= 16384) return -1;
            int m = cn * 2;
            ca = grow(ca, m);
            cb = grow(cb, m);
            cra = grow(cra, 3 * m);
            crb = grow(crb, 3 * m);
            cnr = grow(cnr, 3 * m);
            cln = grow(cln, m);
            cvn = grow(cvn, m);
        }
        return cn++;
    }

    private static int[] grow(int[] a, int m) {
        int[] r = new int[m];
        System.arraycopy(a, 0, r, 0, a.length);
        return r;
    }

    private static float[] grow(float[] a, int m) {
        float[] r = new float[m];
        System.arraycopy(a, 0, r, 0, a.length);
        return r;
    }

    /** Relative velocity along n of a against b at the world point (negative when closing). */
    private float closing(Body a, Body b, float x, float y, float z, float nx, float ny, float nz) {
        float rx = x - a.px, ry = y - a.py, rz = z - a.pz;
        float vx = a.vx + a.wy * rz - a.wz * ry;
        float vy = a.vy + a.wz * rx - a.wx * rz;
        float vz = a.vz + a.wx * ry - a.wy * rx;
        if (b != null && b.dynamic() && !b.asleep) {
            rx = x - b.px;
            ry = y - b.py;
            rz = z - b.pz;
            vx -= b.vx + b.wy * rz - b.wz * ry;
            vy -= b.vy + b.wz * rx - b.wx * rz;
            vz -= b.vz + b.wx * ry - b.wy * rx;
        }
        return vx * nx + vy * ny + vz * nz;
    }

    // ---- solve ----

    private boolean solid(Body b) {
        return b != null && b.dynamic() && !b.asleep;
    }

    /** Generalized inverse mass of b at world offset r for direction n. */
    private float wOf(Body b, float rx, float ry, float rz, float nx, float ny, float nz) {
        float cx = ry * nz - rz * ny, cy = rz * nx - rx * nz, cz = rx * ny - ry * nx;
        b.invInertia(cx, cy, cz, tmp);
        return b.invMass + cx * tmp[0] + cy * tmp[1] + cz * tmp[2];
    }

    /** Moves b by the positional impulse (px, py, pz) applied at world offset r. */
    private void push(Body b, float rx, float ry, float rz, float px, float py, float pz) {
        b.px += px * b.invMass;
        b.py += py * b.invMass;
        b.pz += pz * b.invMass;
        float cx = ry * pz - rz * py, cy = rz * px - rx * pz, cz = rx * py - ry * px;
        b.invInertia(cx, cy, cz, tmp2);
        b.rotateBy(tmp2[0], tmp2[1], tmp2[2]);
        b.updateR();
    }

    private void solvePositions(boolean backwards) {
        for (int k = 0; k < cn; k++) {
            int c = backwards ? cn - 1 - k : k;
            Body a = bodies.get(ca[c]);
            Body b = cb[c] < 0 ? null : bodies.get(cb[c]);
            boolean am = solid(a), bm = solid(b);
            if (b != null && b.asleep) wakeIfPushed(b, a);
            if (a.asleep && b != null) wakeIfPushed(a, b);
            if (!am && !bm) continue;
            float lx = cra[3 * c], ly = cra[3 * c + 1], lz = cra[3 * c + 2];
            float ax = a.px + a.r00 * lx + a.r01 * ly + a.r02 * lz;
            float ay = a.py + a.r10 * lx + a.r11 * ly + a.r12 * lz;
            float az = a.pz + a.r20 * lx + a.r21 * ly + a.r22 * lz;
            float bx, by, bz, qbx, qby, qbz;
            float mx = crb[3 * c], my = crb[3 * c + 1], mz = crb[3 * c + 2];
            if (b == null) {
                bx = mx;
                by = my;
                bz = mz;
                qbx = mx;
                qby = my;
                qbz = mz;
            } else {
                bx = b.px + b.r00 * mx + b.r01 * my + b.r02 * mz;
                by = b.py + b.r10 * mx + b.r11 * my + b.r12 * mz;
                bz = b.pz + b.r20 * mx + b.r21 * my + b.r22 * mz;
                if (bm) {
                    qbx = b.ppx + b.p00 * mx + b.p01 * my + b.p02 * mz;
                    qby = b.ppy + b.p10 * mx + b.p11 * my + b.p12 * mz;
                    qbz = b.ppz + b.p20 * mx + b.p21 * my + b.p22 * mz;
                } else {
                    qbx = bx;
                    qby = by;
                    qbz = bz;
                }
            }
            float nx = cnr[3 * c], ny = cnr[3 * c + 1], nz = cnr[3 * c + 2];
            float d = (bx - ax) * nx + (by - ay) * ny + (bz - az) * nz;
            if (d <= 0f) continue;
            float rax = ax - a.px, ray = ay - a.py, raz = az - a.pz;
            float rbx = 0, rby = 0, rbz = 0;
            float w = am ? wOf(a, rax, ray, raz, nx, ny, nz) : 0f;
            if (bm) {
                rbx = bx - b.px;
                rby = by - b.py;
                rbz = bz - b.pz;
                w += wOf(b, rbx, rby, rbz, nx, ny, nz);
            }
            float lam = d / w;
            cln[c] += lam;
            if (am) push(a, rax, ray, raz, nx * lam, ny * lam, nz * lam);
            if (bm) push(b, rbx, rby, rbz, -nx * lam, -ny * lam, -nz * lam);

            // static friction: undo the sideways slip of the contact point over this substep
            ax = a.px + a.r00 * lx + a.r01 * ly + a.r02 * lz;
            ay = a.py + a.r10 * lx + a.r11 * ly + a.r12 * lz;
            az = a.pz + a.r20 * lx + a.r21 * ly + a.r22 * lz;
            float qax = ax, qay = ay, qaz = az;
            if (am) {
                qax = a.ppx + a.p00 * lx + a.p01 * ly + a.p02 * lz;
                qay = a.ppy + a.p10 * lx + a.p11 * ly + a.p12 * lz;
                qaz = a.ppz + a.p20 * lx + a.p21 * ly + a.p22 * lz;
            }
            if (b != null) {
                bx = b.px + b.r00 * mx + b.r01 * my + b.r02 * mz;
                by = b.py + b.r10 * mx + b.r11 * my + b.r12 * mz;
                bz = b.pz + b.r20 * mx + b.r21 * my + b.r22 * mz;
            }
            float sx = (ax - qax) - (bx - qbx);
            float sy = (ay - qay) - (by - qby);
            float sz = (az - qaz) - (bz - qbz);
            float sn = sx * nx + sy * ny + sz * nz;
            sx -= sn * nx;
            sy -= sn * ny;
            sz -= sn * nz;
            float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-7f) continue;
            float tx = sx / sl, ty = sy / sl, tz = sz / sl;
            rax = ax - a.px;
            ray = ay - a.py;
            raz = az - a.pz;
            float wt = am ? wOf(a, rax, ray, raz, tx, ty, tz) : 0f;
            if (bm) {
                rbx = bx - b.px;
                rby = by - b.py;
                rbz = bz - b.pz;
                wt += wOf(b, rbx, rby, rbz, tx, ty, tz);
            }
            float lt = sl / wt;
            float mu = friction(a, b);
            if (lt > mu * 1.15f * cln[c]) continue;
            if (am) push(a, rax, ray, raz, -tx * lt, -ty * lt, -tz * lt);
            if (bm) push(b, rbx, rby, rbz, tx * lt, ty * lt, tz * lt);
        }
    }

    private static float friction(Body a, Body b) {
        float fa = Body.FRICTION[a.material];
        float fb = b == null ? 0.8f : Body.FRICTION[b.material];
        return (fa + fb) * 0.5f;
    }

    private void wakeIfPushed(Body sleeper, Body by) {
        if (by.speed2() > 0.15f) wake(sleeper);
    }

    void wake(Body b) {
        if (!b.asleep) return;
        b.asleep = false;
        b.still = 0f;
        b.savePrev();
    }

    private void solveVelocities(float h) {
        for (int c = 0; c < cn; c++) {
            if (cln[c] <= 0f) continue;
            Body a = bodies.get(ca[c]);
            Body b = cb[c] < 0 ? null : bodies.get(cb[c]);
            boolean am = solid(a), bm = solid(b);
            if (!am && !bm) continue;
            float lx = cra[3 * c], ly = cra[3 * c + 1], lz = cra[3 * c + 2];
            float rax = a.r00 * lx + a.r01 * ly + a.r02 * lz;
            float ray = a.r10 * lx + a.r11 * ly + a.r12 * lz;
            float raz = a.r20 * lx + a.r21 * ly + a.r22 * lz;
            float wx = a.px + rax, wy = a.py + ray, wz = a.pz + raz;
            float vx = a.vx + a.wy * raz - a.wz * ray;
            float vy = a.vy + a.wz * rax - a.wx * raz;
            float vz = a.vz + a.wx * ray - a.wy * rax;
            float rbx = 0, rby = 0, rbz = 0;
            if (bm) {
                rbx = wx - b.px;
                rby = wy - b.py;
                rbz = wz - b.pz;
                vx -= b.vx + b.wy * rbz - b.wz * rby;
                vy -= b.vy + b.wz * rbx - b.wx * rbz;
                vz -= b.vz + b.wx * rby - b.wy * rbx;
            }
            float nx = cnr[3 * c], ny = cnr[3 * c + 1], nz = cnr[3 * c + 2];
            float vn = vx * nx + vy * ny + vz * nz;
            float tx = vx - vn * nx, ty = vy - vn * ny, tz = vz - vn * nz;
            float vt = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            // dynamic friction
            float fn = cln[c] / (h * h);
            float mu = friction(a, b) * 0.85f;
            float dvx = 0, dvy = 0, dvz = 0;
            if (vt > 1e-6f) {
                float k = -Math.min(h * mu * fn, vt) / vt;
                dvx = tx * k;
                dvy = ty * k;
                dvz = tz * k;
            }
            // a little bounce for hard hits, none for resting contacts
            float pre = cvn[c];
            float e = pre < -2.5f ? 0.15f : 0f;
            float want = Math.max(-e * pre, 0f);
            if (vn < want) {
                dvx += nx * (want - vn);
                dvy += ny * (want - vn);
                dvz += nz * (want - vn);
            }
            float dl = (float) Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
            if (dl < 1e-7f) continue;
            float ux = dvx / dl, uy = dvy / dl, uz = dvz / dl;
            float w = am ? wOf(a, rax, ray, raz, ux, uy, uz) : 0f;
            if (bm) w += wOf(b, rbx, rby, rbz, ux, uy, uz);
            float p = dl / w;
            if (am) impulse(a, rax, ray, raz, ux * p, uy * p, uz * p);
            if (bm) impulse(b, rbx, rby, rbz, -ux * p, -uy * p, -uz * p);

            if (pre < -1.2f && listener != null) {
                float last = Math.max(a.lastHit, b == null ? 0 : b.lastHit);
                if (time - last > 0.09f) {
                    a.lastHit = time;
                    if (b != null) b.lastHit = time;
                    listener.knock(a, b, -pre, wx, wy, wz);
                }
            }
            cvn[c] = 0f;
        }
    }

    private void impulse(Body b, float rx, float ry, float rz, float px, float py, float pz) {
        b.vx += px * b.invMass;
        b.vy += py * b.invMass;
        b.vz += pz * b.invMass;
        float cx = ry * pz - rz * py, cy = rz * px - rx * pz, cz = rx * py - ry * px;
        b.invInertia(cx, cy, cz, tmp2);
        b.wx += tmp2[0];
        b.wy += tmp2[1];
        b.wz += tmp2[2];
    }

    private void updateSleep(float dt) {
        int n = bodies.size();
        // whatever moves stirs what it touches: a sleeper must not hang in the air
        for (int i = 0; i < n; i++) {
            Body m = bodies.get(i);
            if (!m.dynamic() || m.asleep || m.ghost || m.speed2() < 0.02f) continue;
            float e = 0.03f;
            for (int j = 0; j < n; j++) {
                Body s = bodies.get(j);
                if (!s.asleep) continue;
                if (m.maxX + e < s.minX || s.maxX + e < m.minX || m.maxY + e < s.minY || s.maxY + e < m.minY
                        || m.maxZ + e < s.minZ || s.maxZ + e < m.minZ) continue;
                wake(s);
            }
        }
        for (int i = 0; i < n; i++) {
            Body b = bodies.get(i);
            if (!b.dynamic() || b.asleep || b.ghost) continue;
            if (b.speed2() < 0.004f) {
                b.still += dt;
                if (b.still > 0.5f) {
                    b.asleep = true;
                    b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0f;
                }
            } else {
                b.still = 0f;
            }
        }
    }

    /** True when nothing moves any more. */
    boolean settled() {
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            if (b.dynamic() && !b.ghost && !b.asleep && b.speed2() > 0.01f) return false;
        }
        return true;
    }
}
