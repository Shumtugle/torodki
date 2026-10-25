package com.shumtugle.torodki;

import java.util.ArrayList;
import java.util.Random;

/**
 * The opening: the game told in a few seconds, with no picture and no words but its name.
 * High over the seaside town the view glides down in an arc to a square, where out of the
 * sky, letter by letter, the blocks of the name come down onto a long pedestal, each landing
 * with a knock, as the gods throw the buildings down; cars go by, people cross the square.
 * Down on the ground the hammer comes up in the hand and flies; the name flies to pieces
 * as the first building of the game does; the view glides on in an arc down onto the
 * pieces, and a burst of light carries it over into the game, wherever the game is, on the
 * street or out on the ice. A tap goes straight on.
 *
 * Pure Java: its own physics, its own timeline and camera; the world draws it.
 */
final class Intro {
    static final float END = 5.3f, FADE = 0.6f;      // the light: up to END over the opening, then off the game
    static final float BURST = 0.45f;                 // how long the light takes to fill the screen
    static final float LAND = 2.7f, GLIDE_AT = 3.65f; // the view down on the ground; and off again
    static final float B = 0.14f, BH = 0.2f;          // one cell of a letter, across and up, meters
    static final float DEPTH = 0.12f;                 // half the depth of every block
    static final float Z = Builder.BASE_CZ;           // where the pedestal stands
    static final float PED_HX = 2.5f, PED_HY = 0.3f, PED_HZ = 0.55f;
    static final float THROW_AT = 3.0f, FALL_T = 0.42f, FALL_H = 6.5f;
    // the camera: in the street before the pedestal, a little to the left, eyes at the name
    static final float CAM_X = -0.7f, CAM_Y = 1.75f, CAM_Z = Z - 9.6f, LOOK_Y = 1.15f;

    // the letters, six cells high, read from the top; the name is built of these alone
    private static final String WORD = "TORODKI";
    private static String[] glyph(char c) {
        switch (c) {
            case 'T': return new String[]{"####", "####", ".##.", ".##.", ".##.", ".##."};
            case 'O': return new String[]{".##.", "#..#", "#..#", "#..#", "#..#", ".##."};
            case 'R': return new String[]{"###.", "#..#", "#..#", "###.", "#.#.", "#..#"};
            case 'D': return new String[]{"###.", "#..#", "#..#", "#..#", "#..#", "###."};
            case 'K': return new String[]{"#..#", "#.#.", "##..", "##..", "#.#.", "#..#"};
            default: return new String[]{"##", "##", "##", "##", "##", "##"};
        }
    }

    // a color for each letter, from the paint and the woods and stones the buildings are made of
    private static final int[] LETTER = {0xFF8FCBB2, 0xFFE3B47C, 0xFFEE9272, 0xFFD39E62, 0xFFF2D06A, 0xFFF0A9B9, 0xFF8EBDE3};
    private static final boolean[] STONY = {false, false, false, false, false, false, false};

    final Physics phys = new Physics();
    final ArrayList<Body> blocks = new ArrayList<Body>();
    final Body pedestal, hammer;
    private final float[] appear;
    private final boolean[] landed;
    private final World.Host host;
    private final Random rnd = new Random(0x7020DL);
    float t;
    boolean thrown, struck;
    private float acc, soundBudget;
    float hitX, hitY, hitZ;
    // where the pieces are, followed softly, for the view to glide down onto
    private float shX = 0f, shY = 0.8f, shZ = Z;

    Intro(World.Host host) {
        this.host = host;
        phys.sleeping = true;
        pedestal = new Body(PED_HX, PED_HY, PED_HZ, Body.PLINTH, 0xFFF2E3C6);
        pedestal.setPose(0f, PED_HY, Z, 1, 0, 0, 0);
        phys.add(pedestal);
        // the word laid out across the pedestal, each row of a letter in runs: a run of cells
        // side by side is one plank, a single cell a block
        int cols = 0;
        for (int i = 0; i < WORD.length(); i++) cols += glyph(WORD.charAt(i))[0].length() + (i > 0 ? 1 : 0);
        float x0 = -cols * B * 0.5f, top = PED_HY * 2f;
        ArrayList<Float> when = new ArrayList<Float>();
        int col = 0;
        for (int i = 0; i < WORD.length(); i++) {
            String[] g = glyph(WORD.charAt(i));
            int w = g[0].length();
            for (int r = 0; r < 6; r++) {
                String row = g[5 - r];        // from the bottom up
                int c = 0;
                while (c < w) {
                    if (row.charAt(c) != '#') {
                        c++;
                        continue;
                    }
                    int e = c;
                    while (e < w && row.charAt(e) == '#') e++;
                    float cx = x0 + (col + (c + e) * 0.5f) * B, cy = top + (r + 0.5f) * BH;
                    int tint = shade(LETTER[i]);
                    Body b = new Body((e - c) * B * 0.5f - 0.003f, BH * 0.5f - 0.002f, DEPTH, STONY[i] ? Body.STONE : Body.WOOD, tint);
                    b.setPose(cx, cy, Z, 1, 0, 0, 0);
                    b.homeX = cx;
                    b.homeY = cy;
                    b.homeZ = Z;
                    b.asleep = true;
                    phys.add(b);
                    blocks.add(b);
                    // letter by letter, each from the bottom up, never quite in step
                    when.add(0.3f + i * 0.21f + r * 0.045f + rnd.nextFloat() * 0.05f);
                    c = e;
                }
            }
            col += w + 1;
        }
        appear = new float[blocks.size()];
        landed = new boolean[blocks.size()];
        for (int i = 0; i < appear.length; i++) appear[i] = when.get(i);
        hammer = new Body(0.42f, 0.08f, 0.17f, Body.HAMMER, 0xFFD9A066);
        hammer.ghost = true;
        hammer.setPose(CAM_X + 0.6f, CAM_Y - 0.75f, CAM_Z + 1.2f, 1, 0, 0, 0);
        phys.add(hammer);
        phys.listener = new Physics.Listener() {
            @Override
            public void knock(Body a, Body b, float speed, float x, float y, float z) {
                // the blow is the hammer meeting the name, not the pedestal
                if ((a == hammer || b == hammer) && (a != pedestal && b != pedestal)) strike(x, y, z);
                if (soundBudget <= 0f || speed < 1.2f) return;
                soundBudget -= 1f;
                Body m = a == hammer ? b : a;
                int v = m != null && m.material == Body.STONE ? Synth.STONE : Synth.WOOD;
                host.sound(v, Math.min(1f, (speed - 0.8f) / 5f), 0.85f + rnd.nextFloat() * 0.35f);
            }
        };
    }

    private int shade(int c) {
        return Backdrop.scale(c, 0.9f + rnd.nextFloat() * 0.18f);
    }

    /** How far above its place a block is drawn now: below zero, not come yet. */
    float drop(int i) {
        float u = (t - appear[i]) / FALL_T;
        if (u < 0f) return -1f;
        if (u >= 1f) return 0f;
        return FALL_H * (1f - u * u);
    }

    void update(float dt) {
        t += dt;
        soundBudget = Math.min(3f, soundBudget + dt * 30f);
        for (int i = 0; i < landed.length; i++)
            if (!landed[i] && t >= appear[i] + FALL_T) {
                landed[i] = true;
                Body b = blocks.get(i);
                host.sound(Synth.TOCK, 0.16f + b.hx * 0.08f, 0.8f + rnd.nextFloat() * 0.5f);
            }
        if (!thrown && t >= THROW_AT) throwHammer();
        if (struck) {
            float mx = 0f, my = 0f, mz = 0f;
            int n = 0;
            for (Body b : blocks)
                if (Math.abs(b.px) < 12f && b.pz < Z + 12f) {
                    mx += b.px;
                    my += b.py;
                    mz += b.pz;
                    n++;
                }
            if (n > 0) {
                float k = Math.min(1f, dt * 2.5f);
                shX += (mx / n - shX) * k;
                shY += (my / n - shY) * k;
                shZ += (mz / n - shZ) * k;
            }
        }
        acc += dt;
        while (acc >= 1f / 60f) {
            phys.step(1f / 60f);
            acc -= 1f / 60f;
        }
    }

    /** The hammer leaves the hand for the middle of the name, a little above its middle. */
    private void throwHammer() {
        thrown = true;
        float sx = CAM_X + 0.6f, sy = CAM_Y - 0.75f, sz = CAM_Z + 1.2f;
        float tx = B * 0.5f, ty = PED_HY * 2f + 3.4f * BH, tz = Z - DEPTH;
        float dx = tx - sx, dy = ty - sy, dz = tz - sz, ft = 0.5f;
        float yaw = (float) Math.atan2(dx, dz);
        hammer.setPose(sx, sy, sz, (float) Math.cos(yaw / 2), 0f, (float) Math.sin(yaw / 2), 0f);
        hammer.ghost = false;
        hammer.asleep = false;
        hammer.vx = dx / ft;
        hammer.vy = dy / ft - 0.5f * Physics.G * ft;
        hammer.vz = dz / ft;
        hammer.wy = -15f;
        hammer.wx = 0.6f;
        hammer.savePrev();
        host.sound(Synth.WHOOSH, 0.8f, 1f);
    }

    /**
     * The name flies to pieces at the touch of the hammer, as the first building does: every
     * block near the blow is thrown off it, outward and up and away down the street.
     */
    private void strike(float x, float y, float z) {
        if (struck) return;
        struck = true;
        hitX = x;
        hitY = y;
        hitZ = z;
        host.sound(Synth.thunder(1), 1.1f, 1f);
        host.sound(Synth.CRASH, 1f, 1f);
        host.buzz(140);
        for (Body b : blocks) {
            float dx = b.px - x, dy = b.py - y, dz = b.pz - z;
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.05f;
            float k = Math.max(0f, 1f - d / 3.6f);
            if (k <= 0f) continue;
            // not so hard that it all leaves the picture: the pieces should be seen falling
            float s = 1.2f + 4.2f * k;
            b.asleep = false;
            b.vx += dx / d * s + (rnd.nextFloat() - 0.5f) * 1.2f;
            b.vy += 1.5f + 3.2f * k + rnd.nextFloat() * 1.2f;
            b.vz += 0.8f + 2.2f * k + rnd.nextFloat() * 1.2f;
            b.wx += (rnd.nextFloat() - 0.5f) * 12f * k;
            b.wy += (rnd.nextFloat() - 0.5f) * 12f * k;
            b.wz += (rnd.nextFloat() - 0.5f) * 12f * k;
        }
    }

    /**
     * Where the view is now and what it looks at: x, y, z, then the point looked at. Down
     * from high over the town in an arc that swings out over the promenade, to the player's
     * eyes before the name; after the blow, on in another arc, up a little and over the
     * pedestal's corner, down onto the pieces.
     */
    void view(float[] o) {
        if (t < LAND) {
            float u = Math.max(0f, t / LAND), e = u * u * (3f - 2f * u);
            bez(-30f, 38f, Z - 30f, -24f, 15f, Z - 24f, CAM_X, CAM_Y, CAM_Z, e, o, 0);
            o[3] = 0f;
            o[4] = 0.4f + (LOOK_Y - 0.4f) * e;
            o[5] = Z;
        } else if (t < GLIDE_AT) {
            o[0] = CAM_X;
            o[1] = CAM_Y;
            o[2] = CAM_Z;
            o[3] = 0f;
            o[4] = LOOK_Y;
            o[5] = Z;
        } else {
            float u = Math.min(1f, (t - GLIDE_AT) / (END - GLIDE_AT)), e = u * u * (3f - 2f * u);
            bez(CAM_X, CAM_Y, CAM_Z, 4.2f, 3.8f, Z - 6f, 2.6f, 2.3f, Z - 1.4f, e, o, 0);
            o[3] = shX * e;
            o[4] = LOOK_Y + (Math.min(shY, 0.6f) - LOOK_Y) * e;
            o[5] = Z + (shZ - Z) * e;
        }
    }

    private static void bez(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                            float e, float[] o, int at) {
        float a = (1f - e) * (1f - e), b = 2f * e * (1f - e), c = e * e;
        o[at] = a * ax + b * bx + c * cx;
        o[at + 1] = a * ay + b * by + c * cy;
        o[at + 2] = a * az + b * bz + c * cz;
    }

    /** How far up out of sight the hand with the hammer is: it comes up once the view is down. */
    float handDown() {
        float u = (t - (LAND - 0.15f)) / 0.35f;
        if (u <= 0f) return 1f;
        if (u >= 1f) return 0f;
        return (1f - u) * (1f - u);
    }

    /** The light that carries the opening over into the game: 0 none, 1 the screen all white. */
    float light() {
        if (t < END - BURST) return 0f;
        if (t < END) {
            float u = (t - (END - BURST)) / BURST;
            return u * u;
        }
        float u = Math.min(1f, (t - END) / FADE);
        return (1f - u) * (1f - u);
    }

    boolean fading() {
        return t >= END;
    }

    boolean over() {
        return t >= END + FADE;
    }

    /** A tap: straight on to the burst of light. */
    void skip() {
        if (t < END - BURST) t = END - BURST;
    }
}
