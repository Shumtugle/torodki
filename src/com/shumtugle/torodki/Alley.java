package com.shumtugle.torodki;

import java.util.ArrayList;
import java.util.Random;

/**
 * Bowling on the street behind the player, now and then between buildings. The view
 * turns round: the street runs on to the shore road, with the beach and the sea beyond.
 * The golem comes out from behind the corner house in leaps, stops in the road and falls
 * apart, its stones and bricks flying in arcs to stand up as a great figure: a triangle of
 * stone pillars, a wall, a gate, a bending row of slabs, a stepped pyramid. The player has
 * three heavy balls for it; knock most of it down and the stones fly back together into
 * the golem, which falls back down the street and builds itself again, farther off. Five
 * times, and it is driven out of sight: back over the shore road, across the beach and into
 * the sea, and the player walks down to the water. A figure left standing after the third
 * ball sends the player back to the buildings. Each figure felled is a heavy hammer.
 *
 * Plain Java: the world feeds it time and touches, and draws what it holds.
 */
final class Alley {
    // the phases, in the order they come
    static final int TURN_IN = 0, ENTER = 1, SCATTER = 2, AIM = 3, ROLL = 4, SETTLE = 5, GATHER = 6, HOLD = 7,
            SEA = 8, TURN_OUT = 9, SWELL = 10, GODS = 11;
    // the burst: it sinks lower than a crouch, its seams open, it holds a moment, it bursts
    static final float SLUMP_T = 0.22f, SWELL_T = 0.14f, BURST_T = 0.42f;
    static final float FLY_T = 0.55f, BACK_T = 0.5f;
    // the turn of the head: three sweeps, a nod between them
    static final float SWEEP = 0.42f, NOD = 0.34f, TURN_T = 3 * SWEEP + 2 * NOD;
    static final float SEA_T = 2.8f;

    static final int ROUNDS = 5, BALLS = 3;
    static final float WIN = 0.7f;           // share of a figure to knock down to drive the golem back
    // the camera: where the player stands, turned round toward the corner house a little
    static final float CAM_X = World.CAM_X, CAM_Y = 1.7f, YAW = (float) Math.PI - 0.16f, PITCH = -0.1f;
    static final float K = 2f;               // figures are built at this size: they are the golem's stones
    static final float BALL_R = 0.34f;
    static final float BLOCK_MASS = 0.12f, BALL_MASS = 5f;

    /** How far down the lane the figure of a round stands, and the golem behind it. */
    static float figureV(int round) {
        return 12f + 5f * round;
    }

    static float golemV(int round) {
        return figureV(round) + 5.5f;
    }

    int phase = TURN_IN;
    float phaseT;
    int round, ball;
    int won;                                // figures felled so far
    float standing = 1f;                    // share of the figure still standing, for the bar
    float need = WIN;                       // share to fell; the stand may ask for less
    boolean lost;                           // the last figure stood: back to the buildings
    float flash;                            // a figure felled with one ball: a white flash fading
    boolean traffic;                        // cars on the shore road, once the golem has come through
    final Physics phys = new Physics();
    final ArrayList<Body> blocks = new ArrayList<Body>();
    final ArrayList<Body> balls = new ArrayList<Body>();
    Golem golem;

    // the lane's frame in the world: right and forward on the ground
    private final float rX, rZ, fX, fZ;
    private final Random rnd;
    private final int[] order = new int[ROUNDS];
    private final World.Host host;

    // the flight of each block from the golem to its place, or back
    private float[] fromX = new float[0], fromY = new float[0], fromZ = new float[0], delay = new float[0];
    private boolean[] landedAt = new boolean[0];
    private final float[] q = new float[4];

    // the golem's leaps still to go, and what follows them
    private static final int THEN_SCATTER = 0, THEN_SINK = 1;
    private final float[] hopX = new float[4], hopZ = new float[4];
    private int hops, hopAt, then;

    // the swipe
    private boolean touching;
    private float downX, downY, downT, fingerX;
    float handX;                            // where the ball in hand is, -1 left to 1 right
    // the ball in hand: its color and how far it has been turned; the same ball is rolled
    int handColor;
    float handSpin;

    Alley(World.Host host, long seed) {
        this.host = host;
        rnd = new Random(seed);
        rX = (float) Math.cos(YAW);
        rZ = -(float) Math.sin(YAW);
        fX = (float) Math.sin(YAW);
        fZ = (float) Math.cos(YAW);
        phys.sleeping = true;
        handColor = Builder.BALL_COLORS[rnd.nextInt(Builder.BALL_COLORS.length)];
        // the first figure is always the triangle, the rest drawn from the others
        order[0] = 0;
        int[] rest = {1, 2, 3, 4, 5, 6, 7};
        for (int i = rest.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1), t = rest[i];
            rest[i] = rest[j];
            rest[j] = t;
        }
        for (int i = 1; i < ROUNDS; i++) order[i] = rest[i - 1];
        // it comes from the shore road, beyond the corner house: round the corner, up the street
        golem = new Golem(19f, Backdrop.CROSS_Z - 4f);
        golem.holding = true;
        plan(THEN_SCATTER, 8f, Backdrop.CROSS_Z - 3f, wx(0f, golemV(0) + 7f), wz(0f, golemV(0) + 7f),
                wx(0f, golemV(0)), wz(0f, golemV(0)));
    }

    /** A point of the lane, sideways u and forward v from the player's feet, into the world. */
    float wx(float u, float v) {
        return CAM_X + rX * u + fX * v;
    }

    float wz(float u, float v) {
        return rZ * u + fZ * v;
    }

    /** Where a world point is along the lane. */
    float vOf(float x, float z) {
        return (x - CAM_X) * fX + z * fZ;
    }

    private void go(int p) {
        phase = p;
        phaseT = 0f;
    }

    boolean done() {
        return phase == TURN_OUT && phaseT >= TURN_T || phase == SEA && phaseT >= SEA_T;
    }

    /** The golem's next leaps, as world points in pairs, and what it does after the last. */
    private void plan(int after, float... xz) {
        hops = xz.length / 2;
        for (int i = 0; i < hops; i++) {
            hopX[i] = xz[2 * i];
            hopZ[i] = xz[2 * i + 1];
        }
        hopAt = 0;
        then = after;
    }

    // ---- time ----

    void update(float dt, World w) {
        phaseT += dt;
        flash = Math.max(0f, flash - dt * 2.5f);
        golem.update(dt, 0f);
        // put back together, its seams close up
        if (!golem.down && phase != SWELL && golem.swell > 0f) golem.swell = Math.max(0f, golem.swell - dt * 5f);
        if (golem.landings > landed) {
            landed = golem.landings;
            boolean sea = vOf(golem.x, golem.z) > -Backdrop.SEA_Z / Math.abs(fZ) - 0.5f;
            if (sea) {
                // into the sea: a great splash, and it is gone
                golem.down = true;
                host.sound(Synth.SPLASH, 1.3f, 0.55f);
                host.sound(Synth.THUNDER, 0.8f, 0.8f);
                host.buzz(80);
                for (int i = 0; i < 160; i++) w.spray(golem.x, 0.2f, golem.z);
            } else {
                host.sound(Synth.GROUND, 1.1f, 0.7f);
                host.sound(Synth.STONE, 0.6f, 0.6f);
                host.buzz(50);
                for (int i = 0; i < 10; i++)
                    w.dust(golem.x + (rnd.nextFloat() - 0.5f) * 2f, 0.05f, golem.z + (rnd.nextFloat() - 0.5f) * 2f);
            }
        }
        switch (phase) {
            case TURN_IN:
                if (phaseT >= TURN_T) {
                    go(ENTER);
                    host.sound(Synth.ROAR, 0.7f, 1.1f);
                }
                break;
            case ENTER:
                if (golem.down && then == THEN_SINK) {
                    if (phaseT > 1.4f) {
                        go(SEA);
                        w.giveHeavy(2);
                        host.sound(Synth.CATCH, 0.9f, 1.2f);
                    }
                } else if (golem.idle() && (hopAt == 0 && phaseT > 0.5f || hopAt > 0 && golem.actT > 0.2f)) {
                    if (hopAt < hops) {
                        golem.hop(hopX[hopAt], hopZ[hopAt]);
                        hopAt++;
                        phaseT = 0f;
                    } else if (then == THEN_SCATTER && golem.actT > 0.35f) {
                        traffic = true;
                        go(SWELL);
                        host.sound(Synth.STONE, 0.7f, 0.45f);
                    }
                }
                break;
            case SWELL: {
                float s = Math.min(1f, phaseT / SLUMP_T);
                golem.slump = s * s * (3f - 2f * s);
                golem.swell = Math.max(0f, Math.min(1f, (phaseT - SLUMP_T) / SWELL_T));
                if (phaseT >= BURST_T) {
                    // the shell flies off in pieces; the cores become the figure
                    w.chips(golem);
                    for (int i = 0; i < 14; i++)
                        w.dust(golem.x + (rnd.nextFloat() - 0.5f) * 3f, 0.1f, golem.z + (rnd.nextFloat() - 0.5f) * 3f);
                    scatter();
                    golem.slump = golem.swell = 0f;
                }
                break;
            }
            case SCATTER:
                flyBlocks(w, false);
                if (phaseT > lastDelay() + FLY_T + 0.35f) {
                    for (Body b : blocks) {
                        b.setPose(b.homeX, b.homeY, b.homeZ, b.homeQw, b.homeQx, b.homeQy, b.homeQz);
                        b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0f;
                        b.shown = 1f;
                        b.asleep = true;
                        b.savePrev();
                        phys.add(b);
                    }
                    ball = 0;
                    standing = 1f;
                    go(AIM);
                }
                break;
            case AIM:
                break;
            case ROLL: {
                phys.step(dt);
                Body b = balls.get(balls.size() - 1);
                float v = vOf(b.px, b.pz);
                boolean stopped = b.speed2() < 0.09f && phaseT > 0.8f;
                if (v > golemV(round) + 1f) {
                    // gone on down the street, out of the game
                    b.gone = true;
                    b.ghost = true;
                }
                if (stopped || b.gone || phaseT > 8f) go(SETTLE);
                break;
            }
            case SETTLE:
                phys.step(dt);
                standing = 1f - knockedShare();
                if (phaseT > 1.1f && (still() || phaseT > 2.6f)) count(w);
                break;
            case GATHER:
                flyBlocks(w, true);
                if (phaseT > lastDelay() + BACK_T) {
                    blocks.clear();
                    golem.down = false;
                    golem.swell = 1f;
                    host.sound(Synth.STONE, 0.8f, 0.5f);
                    if (lost) {
                        // it stands there whole again, laughing at the player
                        host.sound(Synth.ROAR, 0.9f, 0.9f);
                        go(HOLD);
                    } else if (won >= ROUNDS) {
                        // driven out: back over the shore road, across the beach, into the sea
                        host.sound(Synth.ROAR, 0.8f, 1.3f);
                        plan(THEN_SINK, wx(0.5f, 48f), wz(0.5f, 48f), wx(0f, 64f), wz(0f, 64f));
                        go(ENTER);
                    } else {
                        round++;
                        host.sound(Synth.ROAR, 0.5f, 1.25f);
                        plan(THEN_SCATTER, wx(0f, golemV(round)), wz(0f, golemV(round)));
                        go(ENTER);
                    }
                }
                break;
            case HOLD:
                if (phaseT > 1.8f) go(TURN_OUT);
                break;
            case GODS:
                if (phaseT >= 2f * TILT_T + godsHold()) gather();
                break;
            default:
                break;
        }
    }

    private int landed;

    private boolean still() {
        for (Body b : blocks) if (!b.asleep && b.speed2() > 0.02f) return false;
        return true;
    }

    /** A pin: a tall stone pillar, pale, with red bands round its neck. */
    static boolean isPin(Body b) {
        return b.hy > 2.5f * b.hx && b.hy > 2.5f * b.hz;
    }

    private float lastDelay() {
        float m = 0f;
        for (float d : delay) m = Math.max(m, d);
        return m;
    }

    // where in the golem each stone sits, in its frame (right, up, toward the player) and its
    // units, lowest first, and how far it turns on its way out: shins and thighs, the torso,
    // forearms, upper arms, shoulders, the head
    private static final float[][] SLOTS = {
            {-0.75f, 0.5f, 0.35f, 0.4f}, {0.75f, 0.5f, 0.35f, 0.4f}, {-0.75f, 1.0f, 0.45f, 0.5f}, {0.75f, 1.0f, 0.45f, 0.5f},
            {0f, 1.6f, 0.35f, 0.8f}, {-1.45f, 1.3f, 0.9f, 1.6f}, {1.45f, 1.3f, 0.9f, 1.6f}, {0f, 2.1f, 0.6f, 0.9f},
            {-1.45f, 2.3f, 0.8f, 1.8f}, {1.45f, 2.3f, 0.8f, 1.8f}, {-0.5f, 2.7f, 0.85f, 1.2f}, {0.5f, 2.7f, 0.85f, 1.2f},
            {-1.35f, 3.0f, 0.6f, 3.1f}, {1.35f, 3.0f, 0.6f, 3.1f}, {0f, 3.35f, 1.15f, 2.4f}};
    private float[] turnOut = new float[0];
    private int[] slotOf = new int[0];

    /** The order the stones go in: the lowest first, and of those the nearest the player. */
    private int[] ranks() {
        int n = blocks.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                Body p = blocks.get(a), q = blocks.get(b);
                float ya = Math.round(p.homeY * 4f), yb = Math.round(q.homeY * 4f);
                if (ya != yb) return ya < yb ? -1 : 1;
                float va = vOf(p.homeX, p.homeZ), vb = vOf(q.homeX, q.homeZ);
                return va < vb ? -1 : va > vb ? 1 : 0;
            }
        });
        int[] rank = new int[n];
        for (int r = 0; r < n; r++) rank[idx[r]] = r;
        return rank;
    }

    /** The point of a stone's slot in the golem as it stands now, into from*. */
    private void slotPoint(int i, int slot, float[] out) {
        float[] s = SLOTS[slot % SLOTS.length];
        // a second stone in the same slot sits a little beside the first
        float off = (slot / SLOTS.length) * 0.3f;
        golem.at(s[0] + off, s[1], s[2], out);
    }

    /**
     * The golem bursts, and its stones fly to their places in the figure of the round: each
     * from its own place in the body, the lowest and nearest first, so they come down in a
     * wave from the point of the figure backward.
     */
    private void scatter() {
        blocks.clear();
        phys.clear();
        build(order[round], figureV(round));
        int n = blocks.size();
        fromX = new float[n];
        fromY = new float[n];
        fromZ = new float[n];
        delay = new float[n];
        turnOut = new float[n];
        slotOf = new int[n];
        landedAt = new boolean[n];
        int[] rank = ranks();
        float[] p = new float[3];
        for (int i = 0; i < n; i++) {
            slotOf[i] = rank[i];
            slotPoint(i, slotOf[i], p);
            fromX[i] = p[0];
            fromY[i] = p[1];
            fromZ[i] = p[2];
            turnOut[i] = SLOTS[slotOf[i] % SLOTS.length][3];
            delay[i] = rank[i] * 0.037f;
        }
        golem.down = true;
        host.sound(Synth.THUNDER, 0.5f, 1.5f);
        host.sound(Synth.CRASH, 0.7f, 0.5f);
        host.buzz(60);
        go(SCATTER);
    }

    /** Each block along its arc: out from the golem to its place, or (back) the other way. */
    private void flyBlocks(World w, boolean back) {
        int n = blocks.size(), landedNow = 0;
        for (int i = 0; i < n; i++) {
            Body b = blocks.get(i);
            float t = (phaseT - delay[i]) / (back ? BACK_T : FLY_T);
            float k = Math.max(0f, Math.min(1f, t));
            if (back) k = 1f - k;
            // k = 0 in the golem, 1 at the place: a ballistic arc, quick out of the body
            float x = fromX[i] + (b.homeX - fromX[i]) * k;
            float z = fromZ[i] + (b.homeZ - fromZ[i]) * k;
            float rise = Math.max(1.2f, (fromY[i] - b.homeY) * 0.3f + 1.4f);
            float y = fromY[i] + (b.homeY - fromY[i]) * k + rise * 4f * k * (1f - k);
            // down, it kicks up once and settles
            if (!back && t > 1f && t < 1.6f) {
                float u = (t - 1f) * FLY_T;
                y += 0.07f * K * (float) Math.exp(-u * 16f) * Math.abs((float) Math.sin(u * 26f));
            }
            // turning from the way it lay in the body to upright as it lands
            float spin = (1f - k) * (turnOut.length > i ? turnOut[i] : 1f);
            float c = (float) Math.cos(spin * 0.5f), s = (float) Math.sin(spin * 0.5f);
            float hw = b.homeQw, hx = b.homeQx, hy = b.homeQy, hz = b.homeQz;
            b.setPose(x, y, z, hw * c - hx * s, hx * c + hw * s, hy * c + hz * s, hz * c - hy * s);
            b.shown = back ? 0.7f + 0.3f * k : 1f;
            if (!back && t >= 1f && !landedAt[i]) {
                landedAt[i] = true;
                landedNow++;
                // stone on asphalt, each a little different, the last one the heaviest
                boolean last = true;
                for (boolean l : landedAt) last &= l;
                if (last) {
                    host.sound(Synth.GROUND, 1.1f, 0.65f);
                    host.sound(Synth.STONE, 0.9f, 0.5f);
                    host.buzz(45);
                } else host.sound(Synth.STONE, 0.5f, 0.55f + rnd.nextFloat() * 0.35f);
                for (int d = 0; d < 3; d++) w.dust(b.homeX + (rnd.nextFloat() - 0.5f) * b.hx * 2f, 0.05f, b.homeZ);
            }
        }
    }

    private float knockedShare() {
        int down = 0;
        for (Body b : blocks) if (knocked(b)) down++;
        return blocks.isEmpty() ? 0f : down / (float) blocks.size();
    }

    /** After each ball: felled enough, and the golem is driven back; else the next ball, or back home. */
    private void count(World w) {
        float s = knockedShare();
        standing = 1f - s;
        if (s >= need) {
            won++;
            if (ball == 0) {
                flash = 1f;
                host.sound(Synth.THUNDER, 1f, 1f);
                host.buzz(90);
            } else {
                host.sound(Synth.THUNDER, 0.7f, 1.25f);
                host.buzz(50);
            }
            w.giveHeavy(1);
            // the gods look down on it: delight for one ball, smiles for two, a nod for three
            lookUp(w, ball == 0 ? Sky.OVATION : ball == 1 ? Sky.CHEER : Sky.POLITE);
            return;
        }
        if (ball < BALLS - 1) {
            ball++;
            go(AIM);
            return;
        }
        lost = true;
        lookUp(w, Sky.GLOOM);
    }

    // ---- the gods, between rounds ----

    static final float TILT_T = 1.0f;
    int verdict;

    private void lookUp(World w, int v) {
        verdict = v;
        w.godsLook(v);
        go(GODS);
    }

    /** How long the gods hold the view, as on the street. */
    float godsHold() {
        return verdict == Sky.GLOOM ? 2.2f : verdict == Sky.OVATION ? 3.2f : 2.4f;
    }

    /** How far the view is tipped up into the sky: 0 the alley, 1 the gods. */
    float tilt() {
        if (phase != GODS) return 0f;
        float t = phaseT, k;
        if (t < TILT_T) k = t / TILT_T;
        else if (t < TILT_T + godsHold()) k = 1f;
        else k = Math.max(0f, 1f - (t - TILT_T - godsHold()) / TILT_T);
        return k * k * (3f - 2f * k);
    }

    static boolean knocked(Body b) {
        float dx = b.px - b.homeX, dy = b.py - b.homeY, dz = b.pz - b.homeZ;
        float far = 0.25f * K;
        if (dx * dx + dy * dy + dz * dz > far * far) return true;
        // the up of the block against the up it was set with
        return b.r11 < 0.8f;
    }

    /** The stones fly back together, and the golem stands up from them. */
    private void gather() {
        int n = blocks.size();
        fromX = new float[n];
        fromY = new float[n];
        fromZ = new float[n];
        delay = new float[n];
        float[] p = new float[3];
        if (slotOf.length != n) {
            slotOf = new int[n];
            for (int i = 0; i < n; i++) slotOf[i] = i;
        }
        turnOut = new float[n];
        for (int i = 0; i < n; i++) {
            Body b = blocks.get(i);
            // the flight runs from its slot in the golem (k = 0) to where the block lies now (k = 1)
            slotPoint(i, slotOf[i], p);
            fromX[i] = p[0];
            fromY[i] = p[1];
            fromZ[i] = p[2];
            turnOut[i] = SLOTS[slotOf[i] % SLOTS.length][3];
            b.homeX = b.px;
            b.homeY = b.py;
            b.homeZ = b.pz;
            b.homeQw = b.qw;
            b.homeQx = b.qx;
            b.homeQy = b.qy;
            b.homeQz = b.qz;
            delay[i] = rnd.nextFloat() * 0.25f;
        }
        for (Body b : balls) b.gone = true;
        balls.clear();
        phys.clear();
        host.sound(Synth.WHOOSH, 0.8f, 0.7f);
        go(GATHER);
    }

    // ---- the swipe ----

    void touch(int type, float x, float y, float clock) {
        if (phase != AIM) return;
        if (type == World.DOWN) {
            touching = true;
            downX = x;
            downY = y;
            downT = clock;
            fingerX = x;
        } else if (type == World.MOVE && touching) {
            fingerX = x;
        } else if (type == World.UP && touching) {
            touching = false;
            float dy = downY - y, dx = x - downX, secs = Math.max(0.05f, clock - downT);
            if (dy < 20f || secs > 1.5f) return;
            float pxps = (float) Math.sqrt(dx * dx + dy * dy) / secs;
            float speed = Math.max(5f, Math.min(13f, 4f + pxps / 140f));
            float ang = Math.max(-0.12f, Math.min(0.12f, (float) Math.atan2(dx, dy) * 0.3f));
            roll((downX - World.W * 0.5f) / World.W * 2.4f, speed, ang);
        }
    }

    /** Where the ball in hand is drawn, following the finger before the roll. */
    void handUpdate(float dt) {
        float want = touching ? Math.max(-1f, Math.min(1f, (fingerX - World.W * 0.5f) / (World.W * 0.4f))) : 0f;
        handX += (want - handX) * Math.min(1f, dt * 10f);
        handSpin += dt * 0.6f;
    }

    /** A ball rolled from u (meters right of the lane's middle) at a speed, at an angle. */
    void roll(float u, float speed, float ang) {
        if (phase != AIM) return;
        u = Math.max(-1.2f, Math.min(1.2f, u));
        // the very ball that was in hand, turned as it was held
        Body b = Body.ball(BALL_R, handColor);
        b.scaleMass(BALL_MASS);
        float x = wx(u, 1.6f), z = wz(u, 1.6f);
        b.setPose(x, BALL_R + 0.01f, z, (float) Math.cos(handSpin * 0.5f), 0f, (float) Math.sin(handSpin * 0.5f), 0f);
        // and the next one taken up: another of the set, never the same color twice running
        int c;
        do c = Builder.BALL_COLORS[rnd.nextInt(Builder.BALL_COLORS.length)];
        while (c == handColor);
        handColor = c;
        float dxl = (float) Math.sin(ang), dvl = (float) Math.cos(ang);
        b.vx = (rX * dxl + fX * dvl) * speed;
        b.vz = (rZ * dxl + fZ * dvl) * speed;
        b.vy = 0f;
        b.wx = b.vz / BALL_R;
        b.wz = -b.vx / BALL_R;
        b.asleep = false;
        b.savePrev();
        phys.add(b);
        balls.add(b);
        host.sound(Synth.ROLL, Math.min(1.2f, 0.5f + speed / 12f), 0.8f + speed / 45f);
        host.buzz(15);
        handX = 0f;
        go(ROLL);
    }

    // ---- the figures ----
    // Written small, in lane units: u across, dv along from the figure's place, y up, and
    // built K times bigger, every block set square to the lane. The golem's own stones:
    // pale stone, a darker stone, brick; the pillars of the triangle banded red like pins.

    static final int STONE = 0xFFE4DACA, STONE2 = 0xFFCFC3AE, BRICK = 0xFFCC644C, BRICK2 = 0xFFB4553E, PIN = 0xFFF0EAE0;

    private float v0;

    private void block(float u, float dv, float y, float hu, float hy, float hv, int color, float turn) {
        float x = wx(u * K, v0 + dv * K), z = wz(u * K, v0 + dv * K);
        hu *= K;
        hy *= K;
        hv *= K;
        // the lane's own turn about the up axis, and the block's own on top of it
        float a = YAW + turn;
        q[0] = (float) Math.cos(a * 0.5f);
        q[2] = (float) Math.sin(a * 0.5f);
        Body b = new Body(hu, hy, hv, Body.STONE, color);
        // stone to look at, light enough to knock over, and free to topple
        b.scaleMass(BLOCK_MASS);
        b.trueInertia();
        b.setPose(x, y * K + hy, z, q[0], 0f, q[2], 0f);
        b.homeX = b.px;
        b.homeY = b.py;
        b.homeZ = b.pz;
        b.homeQw = q[0];
        b.homeQy = q[2];
        blocks.add(b);
    }

    private void pin(float u, float dv) {
        block(u, dv, 0f, 0.13f, 0.45f, 0.13f, PIN, 0f);
    }

    private void cube(float u, float dv, float y, float s, int color) {
        block(u, dv, y, s, s, s, color, 0f);
    }

    void build(int kind, float v) {
        v0 = v;
        switch (kind) {
            case 0: // ten pillars in a triangle, its point toward the player
                for (int r = 0; r < 4; r++)
                    for (int i = 0; i <= r; i++) pin((i - r * 0.5f) * 0.56f, r * 0.5f);
                break;
            case 1: { // a brick wall in running bond
                float bw = 0.5f, bh = 0.24f;
                for (int r = 0; r < 4; r++) {
                    int n = (r & 1) == 0 ? 5 : 4;
                    for (int i = 0; i < n; i++)
                        block((i - (n - 1) * 0.5f) * bw, 0f, r * bh, bw * 0.5f - 0.005f, bh * 0.5f, 0.15f,
                                (i + r) % 3 == 0 ? BRICK2 : BRICK, 0f);
                }
                break;
            }
            case 2: { // a gate: two columns, a lintel, three stones on it
                float c = 0.2f;
                for (int i = 0; i < 3; i++) {
                    cube(-0.4f, 0f, i * 2f * c, c, STONE);
                    cube(0.4f, 0f, i * 2f * c, c, STONE2);
                }
                block(0f, 0f, 6f * c, 0.7f, 0.07f, 0.22f, BRICK, 0f);
                for (int i = -1; i <= 1; i++) cube(i * 0.42f, 0f, 6f * c + 0.14f, 0.18f, i == 0 ? STONE2 : STONE);
                break;
            }
            case 3: { // slabs on end in a gentle bend, each one near enough to topple the next
                for (int i = 0; i < 10; i++) {
                    float dv = -1.8f + i * 0.5f;
                    float u = 0.5f * (float) Math.sin(i * 0.55f);
                    float du = 0.55f * (float) Math.cos(i * 0.55f);
                    block(u, dv, 0f, 0.26f, 0.45f, 0.06f, (i & 1) == 0 ? STONE : BRICK, (float) Math.atan(du));
                }
                break;
            }
            case 4: { // a stepped pyramid, one stone deep
                float c = 0.2f;
                for (int r = 0; r < 4; r++) {
                    int n = 4 - r;
                    for (int i = 0; i < n; i++)
                        cube((i - (n - 1) * 0.5f) * 2f * c, 0f, r * 2f * c, c - 0.003f, (r & 1) == 0 ? STONE : STONE2);
                }
                break;
            }
            case 5: // a phalanx: three ranks of pillars, staggered
                for (int r = 0; r < 3; r++) {
                    int n = (r & 1) == 0 ? 4 : 3;
                    for (int i = 0; i < n; i++) pin((i - (n - 1) * 0.5f) * 0.42f, r * 0.5f);
                }
                break;
            case 6: { // two towers bridged by a plank, a little one on top
                float c = 0.17f;
                for (int i = 0; i < 4; i++) {
                    cube(-0.33f, 0f, i * 2f * c, c, (i & 1) == 0 ? STONE : BRICK);
                    cube(0.33f, 0f, i * 2f * c, c, (i & 1) == 0 ? BRICK : STONE);
                }
                block(0f, 0f, 8f * c, 0.6f, 0.06f, 0.2f, STONE2, 0f);
                cube(0f, 0f, 8f * c + 0.12f, c, BRICK2);
                cube(0f, 0f, 10f * c + 0.12f, c * 0.8f, STONE);
                break;
            }
            default: { // three totems in a row, stone and brick by turns
                for (int t = -1; t <= 1; t++) {
                    float y = 0f;
                    for (int i = 0; i < 5; i++) {
                        boolean flat = (i & 1) == 0;
                        float hu = flat ? 0.17f : 0.12f, hy = flat ? 0.11f : 0.16f;
                        block(t * 0.5f, Math.abs(t) * 0.35f, y, hu, hy, 0.14f, flat ? STONE : BRICK, 0f);
                        y += 2f * hy;
                    }
                }
                break;
            }
        }
    }
}
