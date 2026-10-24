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
    static final int SPLASH = 8, PLASTIC = 9, CRASH = 10, ALARM = 11, POP = 12, ROAR = 13, BEAT = 14, RING = 15, DOOR = 16, DTMF = 17;
    // after the twelve keypad tones
    static final int CLANG = DTMF + 12, SAND = DTMF + 13, RUBBER = DTMF + 14, BUSY = DTMF + 15;
    // luggage: a leather slap, brass latches snapping, a mattress groaning, ticking torn open
    static final int LEATHER = DTMF + 16, LATCH = DTMF + 17, MATTRESS = DTMF + 18, TEAR = DTMF + 19;
    // a heavy ball rolling away down the asphalt
    static final int ROLL = DTMF + 20;
    // more thunder, each made differently: near and sharp, rolling, far and low, a double crack, a long roll
    static final int THUNDERS = 6;
    private static final int THUNDER_MORE = DTMF + 21;

    /** One of the thunders: 0 the plain one, then the others. */
    static int thunder(int k) {
        return k <= 0 ? THUNDER : THUNDER_MORE + (k - 1) % (THUNDERS - 1);
    }

    final float[][] bank = new float[DTMF + 21 + THUNDERS - 1][];

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
        bank[SPLASH] = splash(r);
        bank[PLASTIC] = knock(r, 0.18f, new float[]{190f, 455f, 780f}, new float[]{1f, 0.45f, 0.2f},
                new float[]{30f, 42f, 60f}, 0.25f, 0.2f);
        // sheet metal crumpling: low panels ringing under a burst of grit
        bank[CRASH] = knock(r, 0.55f, new float[]{170f, 410f, 1120f, 2390f, 3310f}, new float[]{0.8f, 0.7f, 0.5f, 0.35f, 0.25f},
                new float[]{8f, 10f, 15f, 19f, 24f}, 1.1f, 0.6f);
        bank[ALARM] = alarm();
        bank[POP] = pop(r);
        bank[ROAR] = roar(r);
        bank[BEAT] = beat();
        bank[RING] = ring(r);
        bank[DOOR] = door(r);
        // the keypad's tones, in the order of its keys: 1 2 3, 4 5 6, 7 8 9, C 0 CALL
        float[] lo = {697f, 770f, 852f, 941f}, hi = {1209f, 1336f, 1477f};
        for (int k = 0; k < 12; k++) bank[DTMF + k] = tone(lo[k / 3], hi[k % 3]);
        // an empty steel drum: a few far-apart modes that ring on
        bank[CLANG] = knock(r, 0.7f, new float[]{214f, 538f, 911f, 1447f, 2210f}, new float[]{0.8f, 0.75f, 0.5f, 0.32f, 0.2f},
                new float[]{6f, 8f, 11f, 15f, 21f}, 0.5f, 0.5f);
        bank[SAND] = sand(r);
        bank[RUBBER] = rubber(r);
        bank[BUSY] = busy();
        bank[LEATHER] = leather(r);
        bank[LATCH] = latch(r);
        bank[MATTRESS] = mattress(r);
        bank[TEAR] = tear(r);
        bank[ROLL] = roll(r);
        // near: a split crack, a short hard rumble
        bank[THUNDER_MORE] = thunder(r, 2.0f, 1.1f, 0f, 0.05f, 1.6f, 1100);
        // overhead, rolling on in waves
        bank[THUNDER_MORE + 1] = thunder(r, 3.2f, 0.45f, 0f, 0.035f, 0.8f, 2600);
        // far off: no crack, a low growl that swells late
        bank[THUNDER_MORE + 2] = thunder(r, 3.6f, 0f, 0f, 0.02f, 0.55f, 3200);
        // a double crack, then the rumble
        bank[THUNDER_MORE + 3] = thunder(r, 2.6f, 0.8f, 0.16f, 0.04f, 1.1f, 1500);
        // a long roll, banging about between the houses
        bank[THUNDER_MORE + 4] = thunder(r, 4.2f, 0.35f, 0f, 0.03f, 0.45f, 2000);
        bank[GLASS] = knock(r, 0.4f, new float[]{1870f, 2950f, 4410f}, new float[]{0.6f, 0.4f, 0.3f},
                new float[]{9f, 12f, 16f}, 0.4f, 0.8f);
    }

    /** A burst of water: a wet slap, a sheet of spray, then a few gulping bubbles. */
    private static float[] splash(Random r) {
        int n = (int) (1.1f * RATE);
        float[] s = new float[n];
        float lp = 0f, lp2 = 0f;
        float[] bt = new float[7], bf = new float[7];
        for (int i = 0; i < bt.length; i++) {
            bt[i] = 0.06f + r.nextFloat() * 0.5f;
            bf[i] = 380f + r.nextFloat() * 700f;
        }
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float nz = r.nextFloat() * 2f - 1f;
            float cut = 0.5f * (float) Math.exp(-6f * t) + 0.04f;
            lp += (nz - lp) * cut;
            lp2 += (lp - lp2) * 0.5f;
            float v = lp2 * 1.6f * (float) Math.exp(-5f * t) * Math.min(1f, t * 600f);
            v += (nz - lp) * 0.35f * (float) Math.exp(-14f * t);
            for (int k = 0; k < bt.length; k++) {
                float u = t - bt[k];
                if (u < 0f || u > 0.07f) continue;
                float f = bf[k] * (1f + 2.5f * u);
                v += 0.35f * (float) Math.sin(2 * Math.PI * f * u) * (float) Math.exp(-50f * u);
            }
            s[i] = v * 0.8f;
        }
        return s;
    }

    /** A roar of stone: low rasping tones, grinding grit, a throb in it. */
    private static float[] roar(Random r) {
        int n = (int) (1.5f * RATE);
        float[] s = new float[n];
        float p1 = 0f, p2 = 0f, lp = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float f = 62f + 18f * (float) Math.sin(t * 3.1f) - 10f * t;
            p1 += f / RATE;
            p2 += f * 1.51f / RATE;
            float saw = (p1 % 1f) * 2f - 1f, saw2 = (p2 % 1f) * 2f - 1f;
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.15f;
            float throb = 0.65f + 0.35f * (float) Math.sin(t * 2f * Math.PI * 17f);
            float env = Math.min(1f, t * 6f) * Math.min(1f, (1.5f - t) * 2.5f);
            s[i] = (saw * 0.45f + saw2 * 0.25f + lp * 0.9f) * throb * env * 0.6f;
        }
        return s;
    }

    /** A call to the dispatcher: the click of the button, then the ringing tone down a line. */
    private static float[] ring(Random r) {
        int n = (int) (1.1f * RATE);
        float[] s = new float[n];
        float lp = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float v = 0f;
            if (t < 0.03f) v = (r.nextFloat() * 2f - 1f) * (1f - t / 0.03f);
            float tt = t - 0.12f;
            if (tt > 0f && tt < 0.85f) {
                float tone = (float) Math.sin(2 * Math.PI * 425f * tt) + 0.35f * (float) Math.sin(2 * Math.PI * 850f * tt);
                float env = Math.min(1f, tt * 30f) * Math.min(1f, (0.85f - tt) * 30f);
                lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.5f;
                v += (tone * 0.5f + lp * 0.08f) * env;
            }
            s[i] = v * 0.55f;
        }
        return s;
    }

    /** A key of the telephone: two tones together, briefly. */
    private static float[] tone(float a, float b) {
        int n = (int) (0.13f * RATE);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float env = Math.min(1f, t * 300f) * Math.min(1f, (0.13f - t) * 300f);
            s[i] = ((float) Math.sin(2 * Math.PI * a * t) + (float) Math.sin(2 * Math.PI * b * t)) * 0.25f * env;
        }
        return s;
    }

    /** A bag of sand dropped: no ring at all, a dull low push and a hiss of grains. */
    private static float[] sand(Random r) {
        int n = (int) (0.28f * RATE);
        float[] s = new float[n];
        float lp = 0f, lp2 = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float v = (float) Math.sin(2 * Math.PI * (70f - 30f * t) * t) * (float) Math.exp(-22f * t);
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.3f;
            lp2 += (lp - lp2) * 0.3f;
            v += (lp - lp2) * 1.6f * (float) Math.exp(-14f * t);
            s[i] = v * Math.min(1f, t * 600f) * 0.75f;
        }
        return s;
    }

    /** A suitcase struck: a dull flat slap of leather over cloth, no ring in it. */
    private static float[] leather(Random r) {
        int n = (int) (0.22f * RATE);
        float[] s = new float[n];
        float lp = 0f, lp2 = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float v = (float) Math.sin(2 * Math.PI * (118f - 70f * t) * t) * (float) Math.exp(-30f * t);
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.45f;
            lp2 += (lp - lp2) * 0.35f;
            v += lp2 * 1.5f * (float) Math.exp(-55f * t);
            s[i] = v * Math.min(1f, t * 3000f) * 0.8f;
        }
        return s;
    }

    /** Brass latches torn open: two thin bright dings close together. */
    private static float[] latch(Random r) {
        int n = (int) (0.4f * RATE);
        float[] s = new float[n];
        float[] at = {0f, 0.045f};
        float[] hz = {3150f, 3420f};
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float v = 0f;
            for (int k = 0; k < 2; k++) {
                float u = t - at[k];
                if (u < 0f) continue;
                v += (float) Math.sin(2 * Math.PI * hz[k] * u) * (float) Math.exp(-14f * u) * 0.4f;
                v += (float) Math.sin(2 * Math.PI * hz[k] * 2.37f * u) * (float) Math.exp(-30f * u) * 0.15f;
                if (u < 0.004f) v += (r.nextFloat() * 2f - 1f) * 0.5f;
            }
            s[i] = v;
        }
        return s;
    }

    /**
     * A spring mattress hit: a deep muffled whump, then the steel wire inside complains,
     * a long creak that sags and wavers as the springs settle.
     */
    private static float[] mattress(Random r) {
        int n = (int) (1.3f * RATE);
        float[] s = new float[n];
        float lp = 0f, lp2 = 0f, ph = 0f, ph2 = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float v = (float) Math.sin(2 * Math.PI * (62f - 22f * t) * t) * (float) Math.exp(-11f * t) * 1.1f;
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.06f;
            v += lp * 2.4f * (float) Math.exp(-20f * t);
            // the creak: a thin rasping tone, sliding down, rocking with the springs
            float u = t - 0.07f;
            if (u > 0f) {
                float f = 780f - 210f * u + 60f * (float) Math.sin(u * 2 * Math.PI * 2.6f);
                ph += f / RATE;
                ph2 += f * 1.5f / RATE;
                float rasp = ((ph % 1f) < 0.5f ? 1f : -1f) * 0.5f + ((ph2 % 1f) * 2f - 1f) * 0.3f;
                lp2 += (rasp - lp2) * 0.35f;
                float grain = 0.6f + 0.4f * (float) Math.sin(u * 2 * Math.PI * 31f);
                float env = Math.min(1f, u * 12f) * (float) Math.exp(-2.4f * u);
                v += lp2 * grain * env * 0.3f;
            }
            s[i] = v * Math.min(1f, t * 900f) * 0.7f;
        }
        return s;
    }

    /** Ticking torn open: a ripping hiss, then a spring jumping out with a twang. */
    private static float[] tear(Random r) {
        int n = (int) (0.75f * RATE);
        float[] s = new float[n];
        float lp = 0f, lp2 = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float nz = r.nextFloat() * 2f - 1f;
            lp += (nz - lp) * 0.5f;
            lp2 += (lp - lp2) * 0.2f;
            float rip = (lp - lp2) * (0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI * 47f)) * Math.min(1f, t * 60f)
                    * (t < 0.22f ? 1f : (float) Math.exp(-30f * (t - 0.22f))) * 1.4f;
            float u = t - 0.2f;
            float twang = u > 0f ? (float) Math.sin(2 * Math.PI * (330f + 90f * (float) Math.exp(-6f * u)) * u)
                    * (float) Math.exp(-5f * u) * 0.4f : 0f;
            s[i] = rip + twang;
        }
        return s;
    }

    /**
     * A heavy ball rolling away over asphalt: a low growl of grit that thrums with the turns
     * of the ball and fades as it goes off.
     */
    private static float[] roll(Random r) {
        int n = (int) (2.6f * RATE);
        float[] s = new float[n];
        float lp = 0f, lp2 = 0f, ph = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.05f;
            lp2 += (lp - lp2) * 0.08f;
            ph += (5.5f - 1.2f * t) / RATE;
            float thrum = 0.7f + 0.3f * (float) Math.sin(2 * Math.PI * ph);
            float tone = (float) Math.sin(2 * Math.PI * 48f * t) * 0.25f;
            float env = Math.min(1f, t * 30f) * (float) Math.exp(-0.7f * t) * Math.min(1f, (2.6f - t) * 3f);
            s[i] = (lp2 * 9f + tone) * thrum * env * 0.8f;
        }
        return s;
    }

    /** A tyre bouncing: a low rubbery bonk that sags in pitch. */
    private static float[] rubber(Random r) {
        int n = (int) (0.3f * RATE);
        float[] s = new float[n];
        float ph = 0f, lp = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            ph += (150f - 120f * t) / RATE;
            float v = (float) Math.sin(2 * Math.PI * ph) * (float) Math.exp(-13f * t);
            v += 0.3f * (float) Math.sin(4 * Math.PI * ph) * (float) Math.exp(-30f * t);
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.1f;
            v += lp * 0.8f * (float) Math.exp(-60f * t);
            s[i] = v * Math.min(1f, t * 800f) * 0.7f;
        }
        return s;
    }

    /** The line's answer to a number that is not there: three short rising beeps. */
    private static float[] busy() {
        int n = (int) (0.9f * RATE);
        float[] s = new float[n];
        float[] f = {950f, 1400f, 1800f};
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            int k = (int) (t / 0.3f);
            float u = t - k * 0.3f;
            float env = u < 0.24f ? Math.min(1f, u * 400f) * Math.min(1f, (0.24f - u) * 400f) : 0f;
            s[i] = (float) Math.sin(2 * Math.PI * f[Math.min(2, k)] * t) * 0.3f * env;
        }
        return s;
    }

    /** The folding door of the booth: a creak, the rattle of glass, a soft knock. */
    private static float[] door(Random r) {
        int n = (int) (0.7f * RATE);
        float[] s = new float[n];
        float ph = 0f, lp = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            ph += (300f + 200f * (float) Math.sin(t * 9f)) / RATE;
            float creak = ((ph % 1f) < 0.5f ? 1f : -1f) * (float) Math.exp(-4f * t) * 0.18f;
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.6f;
            float rattle = lp * (0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI * 23f)) * 0.15f * Math.min(1f, t * 8f);
            float knock = t > 0.55f ? (float) Math.sin(2 * Math.PI * 110f * (t - 0.55f)) * (float) Math.exp(-30f * (t - 0.55f)) * 0.6f : 0f;
            s[i] = creak + rattle + knock;
        }
        return s;
    }

    /** A heartbeat: two soft low thumps. */
    private static float[] beat() {
        int n = (int) (0.45f * RATE);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float a = (float) Math.sin(2 * Math.PI * 55f * t) * (float) Math.exp(-22f * t);
            float t2 = t - 0.2f;
            float b = t2 > 0f ? (float) Math.sin(2 * Math.PI * 48f * t2) * (float) Math.exp(-24f * t2) * 0.7f : 0f;
            s[i] = (a + b) * Math.min(1f, t * 600f);
        }
        return s;
    }

    /** A balloon bursting: a sharp crack of noise and a rubbery flap after it. */
    private static float[] pop(Random r) {
        int n = (int) (0.22f * RATE);
        float[] s = new float[n];
        float lp = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            lp += ((r.nextFloat() * 2f - 1f) - lp) * 0.7f;
            float v = lp * (float) Math.exp(-70f * t) * 1.4f;
            v += (float) Math.sin(2 * Math.PI * (210f - 300f * t) * t) * (float) Math.exp(-30f * t) * 0.35f;
            s[i] = v * Math.min(1f, t * 4000f);
        }
        return s;
    }

    /** A car alarm, briefly: two hoarse tones taking turns, then it gives up. */
    private static float[] alarm() {
        int n = (int) (1.8f * RATE);
        float[] s = new float[n];
        float ph = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float f = ((int) (t / 0.16f) & 1) == 0 ? 720f : 960f;
            ph += f / RATE;
            float sq = (ph % 1f) < 0.5f ? 1f : -1f;
            float tri = 4f * Math.abs((ph % 1f) - 0.5f) - 1f;
            float env = Math.min(1f, t * 40f) * Math.min(1f, (1.8f - t) * 6f);
            s[i] = (sq * 0.25f + tri * 0.45f) * env * 0.35f;
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
    /**
     * Thunder made to measure: a crack (as loud as crack, a second one after crack2 seconds
     * when that is above zero), a rumble of noise this dark (cut), dying away at this rate
     * (decay), swelling and sinking afresh every so many samples (roll).
     */
    private static float[] thunder(Random r, float dur, float crack, float crack2, float cut, float decay, int roll) {
        int n = (int) (dur * RATE);
        float[] s = new float[n];
        float lp = 0, lp2 = 0, swell = 1f, target = 1f;
        float attack = crack > 0f ? 12f : 2.5f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            if (i % roll == 0) target = 0.3f + r.nextFloat() * 1.0f;
            swell += (target - swell) * (1.6f / roll);
            float nz = r.nextFloat() * 2f - 1f;
            lp += (nz - lp) * cut;
            lp2 += (lp - lp2) * cut * 1.7f;
            float rumble = lp2 * (0.32f / cut) * swell * (float) Math.exp(-decay * t) * Math.min(1f, t * attack);
            float c = nz * (float) Math.exp(-24f * t) * 0.55f * crack;
            if (crack2 > 0f && t > crack2) c += nz * (float) Math.exp(-30f * (t - crack2)) * 0.45f * crack;
            s[i] = rumble + c;
        }
        // as loud as the plain one at its loudest
        float peak = 1e-6f;
        for (float v : s) peak = Math.max(peak, Math.abs(v));
        float k = 1.15f / peak;
        for (int i = 0; i < n; i++) s[i] *= k;
        return s;
    }

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
