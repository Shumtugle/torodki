package com.shumtugle.torodki;

import java.util.Random;

/**
 * Between buildings the view tips up into the evening sky, and the clouds there are
 * the audience: puffed shapes in which a face can just be made out. They react to how
 * the last building came down. Felled with one throw, they bounce and burst into
 * little puffs of applause; with a few, they nod and sway; not at all, and one of them
 * yawns and drifts away. While everyone looks up, the next building rises below.
 *
 * The tilt is a scroll: the street frame slides down and the sky above it, painted once
 * as a tall strip that continues the real sky upward, slides in from the top.
 */
final class Sky {
    static final int OVATION = 0, CHEER = 1, POLITE = 2, YAWN = 3;

    private int w, sh;
    private int[] strip;

    private static final int CLOUDS = 6, PUFFS = 9;
    private final float[] cx = new float[CLOUDS], cy = new float[CLOUDS], cs = new float[CLOUDS], cph = new float[CLOUDS];
    private final float[][] pdx = new float[CLOUDS][PUFFS], pdy = new float[CLOUDS][PUFFS], pr = new float[CLOUDS][PUFFS];
    private int verdict = POLITE;
    private int yawner;
    float time;

    // applause: little puffs flying off the clouds
    private static final int BITS = 80;
    private final float[] bx = new float[BITS], by = new float[BITS], bvx = new float[BITS], bvy = new float[BITS], bage = new float[BITS];
    private int bn;
    private final Random rnd = new Random(5);

    /** Paints the sky above the frame, as tall as the frame itself. */
    void build(Raster r) {
        w = r.w;
        sh = r.h;
        strip = new int[w * sh];
        float[] d = new float[3];
        float shx = Raster.sunX(), shz = Raster.sunZ();
        float sl = (float) Math.sqrt(shx * shx + shz * shz);
        shx /= sl;
        shz /= sl;
        for (int j = 0; j < sh; j++)
            for (int x = 0; x < w; x++) {
                r.ray(x + 0.5f, j - sh + 0.5f, d);
                float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                strip[j * w + x] = Backdrop.sky(d[0] / l, d[1] / l, d[2] / l, shx, shz);
            }
    }

    /** Seats the audience for a new verdict. */
    void begin(int verdict) {
        this.verdict = verdict;
        time = 0f;
        bn = 0;
        yawner = rnd.nextInt(CLOUDS);
        for (int c = 0; c < CLOUDS; c++) {
            int col = c % 3, row = c / 3;
            cs[c] = w * (0.11f + rnd.nextFloat() * 0.05f);
            cx[c] = w * (0.2f + 0.3f * col) + (rnd.nextFloat() - 0.5f) * w * 0.1f;
            cy[c] = sh * (0.22f + 0.3f * row) + (rnd.nextFloat() - 0.5f) * sh * 0.08f + (col == 1 ? sh * 0.07f : 0f);
            cph[c] = rnd.nextFloat() * 6f;
            for (int p = 0; p < PUFFS; p++) {
                if (p < 5) {
                    pdx[c][p] = -1.1f + p * 0.55f + (rnd.nextFloat() - 0.5f) * 0.15f;
                    pdy[c][p] = 0.18f + (rnd.nextFloat() - 0.5f) * 0.08f;
                    pr[c][p] = 0.36f + rnd.nextFloat() * 0.14f;
                } else {
                    pdx[c][p] = -0.65f + (p - 5) * 0.45f + (rnd.nextFloat() - 0.5f) * 0.15f;
                    pdy[c][p] = -0.22f - rnd.nextFloat() * 0.12f;
                    pr[c][p] = 0.42f + rnd.nextFloat() * 0.18f;
                }
            }
        }
    }

    void update(float dt) {
        time += dt;
        if (verdict == OVATION && time > 0.6f && rnd.nextFloat() < dt * 26f && bn < BITS) {
            int c = rnd.nextInt(CLOUDS);
            float a = (float) (rnd.nextFloat() * Math.PI * 2);
            bx[bn] = cx[c] + (float) Math.cos(a) * cs[c] * 1.1f;
            by[bn] = cy[c] + (float) Math.sin(a) * cs[c] * 0.6f;
            bvx[bn] = (float) Math.cos(a) * (30f + rnd.nextFloat() * 40f);
            bvy[bn] = (float) Math.sin(a) * (20f + rnd.nextFloat() * 30f) - 20f;
            bage[bn] = 0f;
            bn++;
        }
        for (int i = 0; i < bn; i++) {
            bage[i] += dt;
            bx[i] += bvx[i] * dt;
            by[i] += bvy[i] * dt;
            if (bage[i] > 0.9f) {
                bn--;
                bx[i] = bx[bn];
                by[i] = by[bn];
                bvx[i] = bvx[bn];
                bvy[i] = bvy[bn];
                bage[i] = bage[bn];
                i--;
            }
        }
    }

    /**
     * The frame tipped up by `off` rows (0 = the street as it is, sh = only sky): the
     * scene slides down and the strip fills in above it.
     */
    void compose(int[] scene, int[] out, int h, int off) {
        for (int y = 0; y < h; y++) {
            int src = y - off;
            if (src >= 0) System.arraycopy(scene, src * w, out, y * w, w);
            else {
                int j = sh + src;
                if (j < 0) j = 0;
                System.arraycopy(strip, j * w, out, y * w, w);
            }
        }
    }

    /** The audience, drawn where the strip now is on screen (shift = off - sh). */
    void drawClouds(Px f, float shift) {
        float t = time;
        for (int c = 0; c < CLOUDS; c++) {
            float x = cx[c], y = cy[c] + shift, s = cs[c];
            float joy = 0f, mouthOpen = 0f;
            switch (verdict) {
                case OVATION:
                    joy = 1f;
                    y -= Math.abs((float) Math.sin(t * 7f + cph[c])) * s * 0.35f;
                    s *= 1f + 0.06f * (float) Math.sin(t * 9f + cph[c]);
                    break;
                case CHEER:
                    joy = 0.7f;
                    y -= Math.abs((float) Math.sin(t * 4.5f + cph[c])) * s * 0.15f;
                    break;
                case POLITE:
                    joy = 0.35f;
                    x += (float) Math.sin(t * 1.6f + cph[c]) * s * 0.08f;
                    break;
                default:
                    joy = 0f;
                    if (c == yawner) {
                        mouthOpen = Math.min(1f, Math.max(0f, (t - 0.4f) / 0.6f));
                        if (t > 1.3f) x += (t - 1.3f) * (t - 1.3f) * w * 0.35f;
                    } else {
                        y += Math.min(1f, t) * s * 0.06f;
                    }
                    break;
            }
            if (y < -s * 2f || y > f.h + s * 2f || x > w + s * 2f) continue;
            cloud(f, c, x, y, s, joy, mouthOpen);
        }
        for (int i = 0; i < bn; i++) {
            float k = bage[i] / 0.9f;
            f.disc(bx[i], by[i] + shift, 2.5f + 4f * k, k < 0.6f ? 0xFFFFF6EE : 0xFFF6E2DA);
        }
    }

    private void cloud(Px f, int c, float x, float y, float s, float joy, float mouth) {
        // undersides first, then bodies, then the sunlit tops on the left
        for (int p = 0; p < PUFFS; p++)
            f.disc(x + pdx[c][p] * s, y + pdy[c][p] * s + pr[c][p] * s * 0.18f, pr[c][p] * s, 0xFFC6AEC2);
        for (int p = 0; p < PUFFS; p++)
            f.disc(x + pdx[c][p] * s, y + pdy[c][p] * s, pr[c][p] * s * 0.94f, 0xFFF4D9D0);
        for (int p = 0; p < PUFFS; p++)
            f.disc(x + pdx[c][p] * s - pr[c][p] * s * 0.22f, y + pdy[c][p] * s - pr[c][p] * s * 0.22f,
                    pr[c][p] * s * 0.62f, 0xFFFFF2E6);
        // a face, only just there
        int ink = 0xFFC8ABBE;
        float ex = s * 0.3f, ey = y + s * 0.02f, er = Math.max(1.2f, s * 0.07f);
        for (int side = -1; side <= 1; side += 2) {
            float exx = x + side * ex;
            if (joy > 0.6f) arc(f, exx, ey + er * 0.4f, er * 1.3f, true, ink);       // smiling eyes
            else if (mouth > 0.5f) f.rect(Math.round(exx - er), Math.round(ey), Math.round(exx + er), Math.round(ey + 1), ink);
            else f.ellipse(exx, ey, er * 0.8f, er, ink);
        }
        float my = y + s * 0.24f;
        if (mouth > 0f) f.ellipse(x, my + s * 0.04f * mouth, s * 0.1f * (0.4f + mouth), s * 0.14f * mouth + 1f, 0xFF9C7E98);
        else arc(f, x, my - s * 0.06f * joy, s * (0.12f + 0.1f * joy), false, ink);
    }

    /** A thin arc: a smile (up = false) or a happy eye (up = true). */
    private static void arc(Px f, float x, float y, float r, boolean up, int c) {
        int n = Math.max(6, (int) (r * 4f));
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * i / n;
            float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r * 0.55f;
            f.set(Math.round(x + dx), Math.round(up ? y - dy : y + dy), c);
        }
    }
}
