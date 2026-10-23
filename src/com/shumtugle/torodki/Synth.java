package com.shumtugle.torodki;

import java.util.Random;

/**
 * Every sound in the game, synthesized once at start: knocks of wood and stone,
 * the dull thud of the ground, the whirr of a spinning hammer, the slap of it
 * coming back to the hand, and thunder for a building that falls at one blow.
 * A small mixer plays them back at any pitch. Plain Java: the phone streams the
 * mixer, the desktop stand can write it to a file.
 */
final class Synth {
    static final int RATE = 22050;
    static final int WOOD = 0, STONE = 1, GROUND = 2, WHOOSH = 3, THUNDER = 4, CATCH = 5, TOCK = 6, GLASS = 7;
    static final int SQUEAL = 8, REV = 9, HONK = 10;

    final float[][] bank = new float[11][];

    Synth() {
        Random r = new Random(7);
        bank[WOOD] = knock(r, 0.16f, new float[]{560f, 1190f, 1910f, 2730f}, new float[]{1f, 0.5f, 0.28f, 0.12f},
                new float[]{26f, 34f, 48f, 60f}, 0.35f, 0.25f);
        bank[STONE] = knock(r, 0.2f, new float[]{340f, 910f, 1530f, 2470f}, new float[]{0.7f, 0.6f, 0.4f, 0.25f},
                new float[]{30f, 38f, 52f, 70f}, 0.75f, 0.55f);
        bank[GROUND] = thud(r);
        bank[WHOOSH] = whoosh(r);
        bank[THUNDER] = thunder(r);
        bank[CATCH] = slap(r);
        bank[TOCK] = knock(r, 0.12f, new float[]{760f, 1640f}, new float[]{0.8f, 0.3f}, new float[]{40f, 55f}, 0.15f, 0.12f);
        bank[GLASS] = knock(r, 0.4f, new float[]{1870f, 2950f, 4410f}, new float[]{0.6f, 0.4f, 0.3f},
                new float[]{9f, 12f, 16f}, 0.4f, 0.8f);
        bank[SQUEAL] = squeal(r);
        bank[REV] = rev(r);
        bank[HONK] = honk();
    }

    /** Tyres skidding: a hard whistle of band noise that wavers and fades. */
    private static float[] squeal(Random r) {
        int n = (int) (1.1f * RATE);
        float[] s = new float[n];
        float lp1 = 0, lp2 = 0, ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float nz = r.nextFloat() * 2f - 1f;
            lp1 += (nz - lp1) * 0.45f;
            lp2 += (lp1 - lp2) * 0.45f;
            float f = 1350f + 180f * (float) Math.sin(2 * Math.PI * 7f * t) - 300f * t;
            ph += f / RATE;
            float tone = (float) Math.sin(2 * Math.PI * ph);
            float env = Math.min(1f, t * 30f) * (float) Math.exp(-1.6f * t);
            s[i] = (tone * 0.55f + (lp1 - lp2) * 1.4f) * env * 0.5f;
        }
        return s;
    }

    /** An engine floored: a buzzing saw rising through two gears. */
    private static float[] rev(Random r) {
        int n = (int) (1.8f * RATE);
        float[] s = new float[n];
        float ph = 0, ph2 = 0, lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float gear = t < 0.9f ? t / 0.9f : (t - 0.9f) / 0.9f * 0.8f + 0.25f;
            float f = 55f + 120f * gear;
            ph += f / RATE;
            ph -= (float) Math.floor(ph);
            ph2 += f * 0.5f / RATE;
            ph2 -= (float) Math.floor(ph2);
            float e = (ph < 0.3f ? 1f : -0.4f) + (ph2 * 2f - 1f) * 0.5f + (r.nextFloat() - 0.5f) * 0.3f;
            lp += (e - lp) * 0.25f;
            float env = Math.min(1f, t * 8f) * (t > 1.4f ? (1.8f - t) / 0.4f : 1f);
            s[i] = lp * env * 0.55f;
        }
        return s;
    }

    /** Two short honks. */
    private static float[] honk() {
        int n = (int) (0.55f * RATE);
        float[] s = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            boolean on = t < 0.16f || (t > 0.24f && t < 0.5f);
            float a = (float) Math.sin(2 * Math.PI * 392f * t) > 0 ? 1f : -1f;
            float b = (float) Math.sin(2 * Math.PI * 494f * t) > 0 ? 1f : -1f;
            lp += ((on ? (a + b) * 0.5f : 0f) - lp) * 0.2f;
            s[i] = lp * 0.45f;
        }
        return s;
    }

    /** Decaying partials with a short burst of filtered noise at the strike. */
    private static float[] knock(Random r, float dur, float[] hz, float[] amp, float[] decay, float noise, float bright) {
        int n = (int) (dur * RATE);
        float[] s = new float[n];
        float lp = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float v = 0f;
            for (int k = 0; k < hz.length; k++)
                v += amp[k] * (float) Math.exp(-decay[k] * t) * (float) Math.sin(2 * Math.PI * hz[k] * t);
            float nz = r.nextFloat() * 2f - 1f;
            lp += (nz - lp) * bright;
            v += lp * noise * (float) Math.exp(-90f * t);
            float attack = Math.min(1f, t * 2000f);
            s[i] = v * attack * 0.6f;
        }
        return s;
    }

    private static float[] thud(Random r) {
        int n = (int) (0.3f * RATE);
        float[] s = new float[n];
        float lp = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float f = 95f - 40f * t;
            float v = (float) Math.sin(2 * Math.PI * f * t) * (float) Math.exp(-16f * t);
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.08f;
            v += lp * 2.2f * (float) Math.exp(-28f * t);
            s[i] = v * Math.min(1f, t * 900f) * 0.8f;
        }
        return s;
    }

    /** Air torn by a spinning hammer: band noise beating at the turn rate. */
    private static float[] whoosh(Random r) {
        int n = (int) (0.55f * RATE);
        float[] s = new float[n];
        float lp1 = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float k = t / 0.55f;
            float env = (float) Math.sin(Math.PI * Math.min(1f, k * 1.1f)) * (1f - k * 0.4f);
            float beat = 0.55f + 0.45f * (float) Math.sin(2 * Math.PI * 11f * t);
            float nz = r.nextFloat() * 2f - 1f;
            float cut = 0.05f + 0.12f * (1f - k);
            lp1 += (nz - lp1) * cut;
            lp2 += (lp1 - lp2) * cut;
            s[i] = (lp1 - lp2) * 3.2f * env * beat;
        }
        return s;
    }

    /** A crack, then a long rolling rumble. */
    private static float[] thunder(Random r) {
        int n = (int) (2.8f * RATE);
        float[] s = new float[n];
        float lp = 0, lp2 = 0, swell = 1f, target = 1f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            if (i % 1400 == 0) target = 0.35f + r.nextFloat() * 0.9f;
            swell += (target - swell) * 0.0008f;
            float nz = r.nextFloat() * 2f - 1f;
            lp += (nz - lp) * 0.035f;
            lp2 += (lp - lp2) * 0.06f;
            float rumble = lp2 * 9f * swell * (float) Math.exp(-1.1f * t) * Math.min(1f, t * 12f);
            float crack = nz * (float) Math.exp(-24f * t) * 0.55f;
            s[i] = rumble + crack;
        }
        return s;
    }

    private static float[] slap(Random r) {
        int n = (int) (0.14f * RATE);
        float[] s = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.3f;
            float v = lp * (float) Math.exp(-60f * t) + 0.6f * (float) Math.sin(2 * Math.PI * 170f * t) * (float) Math.exp(-30f * t);
            s[i] = v * 0.7f;
        }
        return s;
    }

    // ---- mixer ----

    private static final int VOICES = 14;
    private final float[][] vBuf = new float[VOICES][];
    private final float[] vPos = new float[VOICES], vRate = new float[VOICES], vGain = new float[VOICES];

    /** Starts a sound; rate 1 is its own pitch. Safe from any thread. */
    synchronized void play(int id, float gain, float rate) {
        if (gain <= 0.01f) return;
        int slot = -1;
        float quietest = 1e9f;
        for (int i = 0; i < VOICES; i++) {
            if (vBuf[i] == null) {
                slot = i;
                break;
            }
            float left = vGain[i] * (1f - vPos[i] / vBuf[i].length);
            if (left < quietest) {
                quietest = left;
                slot = i;
            }
        }
        vBuf[slot] = bank[id];
        vPos[slot] = 0f;
        vRate[slot] = rate;
        vGain[slot] = Math.min(1.5f, gain);
    }

    /** Mixes n samples into out, as 16-bit. */
    synchronized void mix(short[] out, int n) {
        for (int k = 0; k < n; k++) {
            float s = 0f;
            for (int i = 0; i < VOICES; i++) {
                float[] b = vBuf[i];
                if (b == null) continue;
                int p = (int) vPos[i];
                if (p >= b.length - 1) {
                    vBuf[i] = null;
                    continue;
                }
                float fr = vPos[i] - p;
                s += (b[p] + (b[p + 1] - b[p]) * fr) * vGain[i];
                vPos[i] += vRate[i];
            }
            // soft clip
            s = s / (1f + Math.abs(s) * 0.6f);
            out[k] = (short) (s * 30000f);
        }
    }
}
