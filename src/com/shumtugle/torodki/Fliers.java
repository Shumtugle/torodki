package com.shumtugle.torodki;

import java.util.Random;

/**
 * What crosses the sky far away, seldom: now and then a small flock of birds, and more
 * seldom still an airliner high up, drawing a white trail that spreads and fades behind it.
 * Both are painted only over open sky, behind everything on the street.
 */
final class Fliers {
    private final Random rnd = new Random(5);
    private final float[] scr = new float[2];

    // seconds of street time until the next flock and the next plane
    private float birdsIn, planeIn;

    // the flock: where its middle is, how fast it goes, each bird's place in it and wing beat
    private static final int MAX_BIRDS = 7;
    private boolean flock;
    private float fx, fy, fz, fv, ft;
    private int birds;
    private final float[] bx = new float[MAX_BIRDS], by = new float[MAX_BIRDS], bz = new float[MAX_BIRDS], beat = new float[MAX_BIRDS];

    // the plane and its trail: points it has passed, with their age
    private static final int TRAIL = 140;
    private boolean plane;
    private float px, py, pz, pv, pt;
    private final float[] tx = new float[TRAIL], ty = new float[TRAIL], tz = new float[TRAIL], ta = new float[TRAIL];
    private int tn;
    private float dropAt;

    Fliers() {
        birdsIn = 60f + rnd.nextFloat() * 90f;
        planeIn = 170f + rnd.nextFloat() * 200f;
    }

    /** Time goes on; street is true while the street is what the player sees. */
    void update(float dt, boolean street) {
        if (street) {
            if (!flock && (birdsIn -= dt) <= 0f) startFlock();
            if (!plane && tn == 0 && (planeIn -= dt) <= 0f) startPlane();
        }
        if (flock) {
            ft += dt;
            fx += fv * dt;
            // a flock rises and sinks a little as it goes, each bird drifting in its place
            fy += (float) Math.sin(ft * 0.5f) * 1.2f * dt;
            for (int i = 0; i < birds; i++) {
                bx[i] += (float) Math.sin(ft * 0.9f + i * 1.3f) * 0.6f * dt;
                by[i] += (float) Math.cos(ft * 1.1f + i * 0.8f) * 0.5f * dt;
            }
            if (Math.abs(fx) > 90f && fx * fv > 0f) {
                flock = false;
                birdsIn = 100f + rnd.nextFloat() * 110f;
            }
        }
        if (plane) {
            pt += dt;
            px += pv * dt;
            dropAt += dt;
            if (dropAt > 0.25f && tn < TRAIL) {
                dropAt = 0f;
                tx[tn] = px - Math.signum(pv) * 14f;
                ty[tn] = py;
                tz[tn] = pz;
                ta[tn] = 0f;
                tn++;
            }
            if (Math.abs(px) > 1300f && px * pv > 0f) {
                plane = false;
                planeIn = 220f + rnd.nextFloat() * 220f;
            }
        }
        for (int i = 0; i < tn; i++) ta[i] += dt;
        // the oldest end of the trail is gone once it has faded out
        int gone = 0;
        while (gone < tn && ta[gone] > TRAIL_LIFE) gone++;
        if (gone > 0) {
            System.arraycopy(tx, gone, tx, 0, tn - gone);
            System.arraycopy(ty, gone, ty, 0, tn - gone);
            System.arraycopy(tz, gone, tz, 0, tn - gone);
            System.arraycopy(ta, gone, ta, 0, tn - gone);
            tn -= gone;
        }
    }

    private static final float TRAIL_LIFE = 30f;

    /** A flock now, across the far end of the street. */
    void startFlock() {
        flock = true;
        ft = 0f;
        boolean right = rnd.nextBoolean();
        fv = (right ? 1f : -1f) * (8f + rnd.nextFloat() * 4f);
        fx = right ? -80f : 80f;
        fz = 170f + rnd.nextFloat() * 90f;
        fy = 34f + rnd.nextFloat() * 26f;
        birds = 3 + rnd.nextInt(MAX_BIRDS - 2);
        for (int i = 0; i < birds; i++) {
            // loosely in a wedge behind the first
            float back = i == 0 ? 0f : 2.5f + rnd.nextFloat() * 6f;
            bx[i] = -Math.signum(fv) * back;
            by[i] = (rnd.nextFloat() - 0.5f) * 5f - back * 0.2f;
            bz[i] = (rnd.nextFloat() - 0.5f) * 10f;
            beat[i] = rnd.nextFloat() * 6.28f;
        }
    }

    /** A plane now, high over the town. */
    void startPlane() {
        plane = true;
        pt = 0f;
        tn = 0;
        dropAt = 0f;
        boolean right = rnd.nextBoolean();
        pv = (right ? 1f : -1f) * (55f + rnd.nextFloat() * 20f);
        px = right ? -1250f : 1250f;
        pz = 2200f + rnd.nextFloat() * 900f;
        py = 1100f + rnd.nextFloat() * 700f;
    }

    /** Over open sky only: where nothing has been drawn nearer than the sky itself. */
    void draw(Raster r) {
        for (int i = 1; i < tn; i++) trailPiece(r, i - 1, i);
        if (plane && tn > 0) {
            // the last stretch, from the newest trail point up to the plane
            float vz = r.project(px, py, pz, scr);
            if (vz > 1f) {
                int sx = Math.round(scr[0]), sy = Math.round(scr[1]);
                sky(r, sx, sy, 0xFFF4F6F8, 255);
                sky(r, sx + (pv > 0 ? -1 : 1), sy, 0xFFC9CED6, 255);
                if (((int) (pt * 2.5f) & 3) == 0) sky(r, sx, sy - 1, 0xFFFFFFFF, 200);
            }
        }
        if (flock) {
            for (int i = 0; i < birds; i++) {
                float vz = r.project(fx + bx[i], fy + by[i], fz + bz[i], scr);
                if (vz < 1f) continue;
                int sx = Math.round(scr[0]), sy = Math.round(scr[1]);
                // the wing beat: up, level, down; now and then a glide with wings held out
                float b = ft * 7.5f + beat[i];
                boolean glide = Math.sin(ft * 0.6f + i) > 0.55f;
                int wy = glide ? 0 : (int) Math.round(Math.sin(b) * 1.2f);
                int c = 0xFF2C2A30, a = 210;
                sky(r, sx, sy, c, a);
                sky(r, sx - 1, sy + (wy < 0 ? -1 : 0), c, a);
                sky(r, sx + 1, sy + (wy < 0 ? -1 : 0), c, a);
                if (wy != 0) {
                    sky(r, sx - 2, sy + wy, c, a - 60);
                    sky(r, sx + 2, sy + wy, c, a - 60);
                } else {
                    sky(r, sx - 2, sy, c, a - 60);
                    sky(r, sx + 2, sy, c, a - 60);
                }
            }
        }
    }

    /** One stretch of the trail: thin and bright where fresh, wider and fainter as it ages. */
    private void trailPiece(Raster r, int i, int j) {
        float z0 = r.project(tx[i], ty[i], tz[i], scr);
        float x0 = scr[0], y0 = scr[1];
        float z1 = r.project(tx[j], ty[j], tz[j], scr);
        float x1 = scr[0], y1 = scr[1];
        if (z0 < 1f || z1 < 1f) return;
        float age = ta[j];
        int a = (int) (190f * Math.max(0f, 1f - age / TRAIL_LIFE) * Math.min(1f, age * 3f + 0.4f));
        if (a <= 4) return;
        int wide = age < 5f ? 1 : age < 14f ? 2 : 3;
        float len = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        int n = Math.max(1, (int) Math.ceil(len));
        for (int k = 0; k <= n; k++) {
            float t = k / (float) n;
            int x = Math.round(x0 + (x1 - x0) * t), y = Math.round(y0 + (y1 - y0) * t);
            for (int d = 0; d < wide; d++) {
                // the middle of the trail stays brightest as it spreads
                int aa = d == 0 ? a : a / (d + 1);
                sky(r, x, y + d - wide / 2, 0xFFFFFFFF, aa);
            }
        }
    }

    private static void sky(Raster r, int x, int y, int c, int a) {
        if (x < 0 || y < 0 || x >= r.w || y >= r.h) return;
        int k = y * r.w + x;
        if (r.zb[k] != 0f) return;
        int o = r.col[k];
        int ro = (o >> 16) & 0xFF, go = (o >> 8) & 0xFF, bo = o & 0xFF;
        int rc = (c >> 16) & 0xFF, gc = (c >> 8) & 0xFF, bc = c & 0xFF;
        r.col[k] = 0xFF000000 | ((ro + ((rc - ro) * a >> 8)) << 16) | ((go + ((gc - go) * a >> 8)) << 8) | (bo + ((bc - bo) * a >> 8));
    }
}
