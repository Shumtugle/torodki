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
 * ball sends the player back to the buildings. No hammers are given here; but for each
 * figure felled the gods hand over one of the golden cubes the player has earned on the
 * street, and keep the rest for figures to come.
 *
 * Out on the ice the same game is played on the water, with stones: the view turns round
 * to the open sea behind the floe, an ice golem leaps up out of the swell and bursts into
 * pillars of ice standing on the waves themselves, for ice floats; the player skips flat
 * stones at them over the water. A stone that skips all the way to the pillars knocks into
 * them; one that runs out of speed on the way goes under. Driven back five times, the
 * golem dives and is gone.
 *
 * Plain Java: the world feeds it time and touches, and draws what it holds.
 */
final class Alley {
    // the phases, in the order they come
    static final int TURN_IN = 0, ENTER = 1, SCATTER = 2, AIM = 3, ROLL = 4, SETTLE = 5, GATHER = 6, HOLD = 7,
            SEA = 8, TURN_OUT = 9, SWELL = 10, GODS = 11, CUBES = 12;
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
    // the stones skipped at sea: flat, heavy for their size
    static final float DISC_R = 0.22f, DISC_MASS = 8f;
    static final int DISC = 0xFF6E6A64;

    /** How far down the lane the figure of a round stands, and the golem behind it. */
    static float figureV(int round) {
        return 12f + 5f * round;
    }

    static float golemV(int round) {
        return figureV(round) + 5.5f;
    }

    final boolean sea;                      // stone skipping on the water, with the ice golem
    final int figures;                      // at sea: which five ice figures, of the two sets
    boolean hub;                            // the crossroads of five cubes, come to through the portal
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
        this(host, seed, false);
    }

    Alley(World.Host host, long seed, boolean sea) {
        this(host, seed, sea, 0);
    }

    Alley(World.Host host, long seed, boolean sea, int figures) {
        this.host = host;
        this.sea = sea;
        this.figures = figures;
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
        // the first figure grows while the head turns and the golem comes
        if (host != null) prepare(0);
        if (sea) {
            // under the water out beyond the first floe, ready to spring up onto it
            golem = new Golem(wx(0.5f, golemV(0) + 9f), wz(0.5f, golemV(0) + 9f));
            golem.ice = true;
            golem.sunk = 6.5f;
            golem.wade = 0.8f;
            golem.holding = true;
            plan(THEN_SCATTER, wx(0f, golemV(0)), wz(0f, golemV(0)));
            emerging = true;
            return;
        }
        // it comes from the shore road, beyond the corner house: round the corner, up the street
        golem = new Golem(19f, Backdrop.CROSS_Z - 4f);
        golem.holding = true;
        plan(THEN_SCATTER, 8f, Backdrop.CROSS_Z - 3f, wx(0f, golemV(0) + 7f), wz(0f, golemV(0) + 7f),
                wx(0f, golemV(0)), wz(0f, golemV(0)));
    }

    /** The crossroads alone: the five cubes rising, the golem long gone. */
    static Alley hub(World.Host host, long seed) {
        Alley a = new Alley(host, seed, false);
        a.hub = true;
        a.golem.down = true;
        a.traffic = true;
        a.raiseSigns();
        return a;
    }

    private boolean emerging, skipping;
    int skips;

    /** How near the pillars of a round a stone must come before it strikes among them. */
    static float landV(int round) {
        return figureV(round) - 3.2f;
    }

    // the pillars standing on the water rise and sink a little with the swell, and a fallen
    // one floats; each falls into the water once, with a splash
    private boolean[] splashed = new boolean[0];

    /** Back to the buildings: the head turns round; from the crossroads, a walk into the white. */
    private void leave() {
        go(hub ? SEA : TURN_OUT);
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
        if (sea && emerging && golem.flying()) {
            // up out of the swell, the sea pouring off it
            emerging = false;
            host.sound(Synth.SPLASH, 1.3f, 0.5f);
            host.sound(Synth.ROAR, 0.8f, 0.85f);
            host.buzz(70);
            for (int i = 0; i < 140; i++) w.spray(golem.x, 0.2f, golem.z);
        }
        if (golem.landings > landed) {
            landed = golem.landings;
            boolean water = sea ? golem.sunk > 2.5f : vOf(golem.x, golem.z) > -Backdrop.SEA_Z / Math.abs(fZ) - 0.5f;
            if (water) {
                // into the sea: a great splash, and it is gone
                golem.down = true;
                host.sound(Synth.SPLASH, 1.3f, 0.55f);
                host.sound(Synth.THUNDER, 0.8f, 0.8f);
                host.buzz(80);
                for (int i = 0; i < 160; i++) w.spray(golem.x, 0.2f, golem.z);
            } else {
                host.sound(Synth.GROUND, 1.1f, 0.7f);
                host.sound(Synth.STONE, 0.6f, sea ? 0.9f : 0.6f);
                host.buzz(50);
                if (sea) {
                    // down into the swell to the thighs: the sea thrown up round it
                    host.sound(Synth.SPLASH, 1f, 0.6f);
                    for (int i = 0; i < 60; i++) w.spray(golem.x, 0.2f, golem.z);
                }
                for (int i = 0; i < 10; i++)
                    if (sea) w.ripple(golem.x + (rnd.nextFloat() - 0.5f) * 3f, golem.z + (rnd.nextFloat() - 0.5f) * 3f, 0.8f);
                    else w.dust(golem.x + (rnd.nextFloat() - 0.5f) * 2f, 0.05f, golem.z + (rnd.nextFloat() - 0.5f) * 2f);
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
                    // gone: at sea the head turns back to the floe; on the street the cubes rise
                    if (phaseT > 1.4f) {
                        if (sea) go(TURN_OUT);
                        else raiseSigns();
                    }
                } else if (golem.idle() && (hopAt == 0 && phaseT > 0.5f || hopAt > 0 && golem.actT > 0.2f)) {
                    if (hopAt < hops) {
                        // at sea the last leap of all goes down into the water
                        if (sea && then == THEN_SINK && hopAt == hops - 1) golem.diveNext = true;
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
                        if (sea) w.spray(golem.x + (rnd.nextFloat() - 0.5f) * 3f, 0.1f, golem.z + (rnd.nextFloat() - 0.5f) * 3f);
                        else w.dust(golem.x + (rnd.nextFloat() - 0.5f) * 3f, 0.1f, golem.z + (rnd.nextFloat() - 0.5f) * 3f);
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
                Body b = balls.get(balls.size() - 1);
                if (sea && skipping) {
                    skip(b, dt, w);
                    if (b.gone || phaseT > 8f) go(SETTLE);
                    break;
                }
                phys.step(dt);
                float v = vOf(b.px, b.pz);
                if (sea && !b.gone && b.speed2() < 1.5f) {
                    // spent among the pillars: down it goes
                    b.gone = true;
                    b.ghost = true;
                    w.ripple(b.px, b.pz, 0.5f);
                    host.sound(Synth.SPLASH, 0.4f, 1.1f);
                }
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
                if (sea) splashes(w);
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
                        // driven out: back over the shore road, across the beach, into the sea;
                        // at sea, off its floe and down into the water
                        host.sound(Synth.ROAR, 0.8f, 1.3f);
                        if (sea) plan(THEN_SINK, wx(0.4f, golemV(round) + 7f), wz(0.4f, golemV(round) + 7f));
                        else plan(THEN_SINK, wx(0.5f, 48f), wz(0.5f, 48f), wx(0f, 64f), wz(0f, 64f));
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
                if (phaseT > 1.8f) leave();
                break;
            case GODS:
                if (phaseT >= 2f * TILT_T + godsHold()) gather();
                break;
            case CUBES:
                signs(dt, w);
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
        ArrayList<Body> grown = takeGrown(round);
        if (grown != null) blocks.addAll(grown);
        else build(sea ? 0 : order[round], figureV(round));
        prepare(round + 1);
        int n = blocks.size();
        fromX = new float[n];
        fromY = new float[n];
        fromZ = new float[n];
        delay = new float[n];
        turnOut = new float[n];
        slotOf = new int[n];
        landedAt = new boolean[n];
        splashed = new boolean[n];
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
                if (sea) {
                    // down onto the water: it floats, the sea splashing up round it
                    w.ripple(b.homeX, b.homeZ, 0.5f);
                    for (int d = 0; d < 3; d++) w.splashBit(b.homeX, 0.05f, b.homeZ, 0f, 0f);
                } else for (int d = 0; d < 3; d++) w.dust(b.homeX + (rnd.nextFloat() - 0.5f) * b.hx * 2f, 0.05f, b.homeZ);
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
                host.sound(Synth.thunder(1 + rnd.nextInt(Synth.THUNDERS - 1)), 1f, 1f);
                host.buzz(90);
            } else {
                host.sound(Synth.thunder(rnd.nextInt(Synth.THUNDERS)), 0.7f, 1.2f);
                host.buzz(50);
            }
            // the gods look down on it: delight for one ball, smiles for two, a nod for three;
            // and now they hand over one of the golden cubes the player has earned on the street
            w.giveCube();
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

    // ---- the way on: five stone cubes ----
    // With the golem gone, five cubes of stone rise out of the road in a cross, arrows cut
    // in their faces: forward, right, back, left, and the one in the middle without an arrow
    // but with a ball. A hammer is in hand again; one throw picks the way. The middle one
    // brings the golem back for another game; back goes home to the buildings; forward goes
    // down to the sea. Right and left are not open yet: the hammer rings off them.

    static final int MIDDLE = 0, FORWARD = 1, RIGHT = 2, BACK = 3, LEFT = 4;
    static final float SIGN = 0.55f;                       // half the width of a cube
    // where each stands (across, along) and how tall it is, half: the far ones taller, so
    // every face shows over the one in front of it
    private static final float[][] SIGN_AT = {{0f, 10f, 0.55f}, {0f, 12.8f, 0.95f}, {1.9f, 10f, 0.55f}, {0f, 7.5f, 0.34f},
            {-1.9f, 10f, 0.55f}};
    final Body[] sign = new Body[5];
    final float[] signShake = new float[5];
    int chosen = -1;                                       // the cube the hammer is flying at
    float hammerT = -1f;                                   // seconds since the throw
    static final float HAMMER_T = 0.45f;
    private float riseT, leaveT;
    private boolean riseHeard;
    int next = -1;                                         // what the player chose, once it is settled

    private void raiseSigns() {
        for (int k = 0; k < 5; k++) {
            float x = wx(SIGN_AT[k][0], SIGN_AT[k][1]), z = wz(SIGN_AT[k][0], SIGN_AT[k][1]);
            float hh = SIGN_AT[k][2];
            Body b = new Body(SIGN, hh, SIGN, Body.STONE, k == MIDDLE ? STONE2 : STONE);
            q[0] = (float) Math.cos(YAW * 0.5f);
            q[2] = (float) Math.sin(YAW * 0.5f);
            b.setPose(x, -hh, z, q[0], 0f, q[2], 0f);
            b.homeX = x;
            b.homeY = hh;
            b.homeZ = z;
            sign[k] = b;
        }
        riseT = 0f;
        riseHeard = false;
        chosen = -1;
        hammerT = -1f;
        go(CUBES);
    }

    /** True once the cubes are up and the hammer may be thrown. */
    boolean signsReady() {
        return phase == CUBES && riseT > 1.1f && chosen < 0;
    }

    private void signs(float dt, World w) {
        riseT += dt;
        // up out of the asphalt, one after another, grinding
        for (int k = 0; k < 5; k++) {
            Body b = sign[k];
            if (b == null || b.gone) continue;
            float t = Math.max(0f, Math.min(1f, (riseT - k * 0.12f) / 0.7f));
            float e = 1f - (1f - t) * (1f - t);
            float shake = signShake[k] > 0f ? (float) Math.sin(signShake[k] * 70f) * 0.06f * signShake[k] : 0f;
            b.setPose(b.homeX + shake, -b.hy + 2f * b.hy * e, b.homeZ, b.qw, b.qx, b.qy, b.qz);
            signShake[k] = Math.max(0f, signShake[k] - dt * 2f);
            if (t > 0f && t < 1f && ((int) (riseT * 30f) & 3) == 0) w.dust(b.px + (rnd.nextFloat() - 0.5f) * 1.2f, 0.05f, b.pz);
        }
        if (!riseHeard) {
            riseHeard = true;
            host.sound(Synth.ROAR, 0.6f, 0.6f);
            host.sound(Synth.GROUND, 1f, 0.5f);
        }
        if (chosen >= 0 && hammerT >= 0f) {
            hammerT += dt;
            if (hammerT >= HAMMER_T && next < 0) {
                Body b = sign[chosen];
                if (chosen == LEFT || chosen == RIGHT) {
                    // not open yet: the hammer rings off it, the cube shudders, nothing more
                    host.sound(Synth.CLANG, 0.9f, 0.6f);
                    host.buzz(30);
                    signShake[chosen] = 1f;
                    chosen = -1;
                    hammerT = -1f;
                } else {
                    host.sound(Synth.CRASH, 0.9f, 0.6f);
                    host.sound(Synth.STONE, 1f, 0.5f);
                    host.buzz(70);
                    w.burstCube(b);
                    b.gone = true;
                    next = chosen;
                    hammerT = HAMMER_T;
                    leaveT = 0f;
                }
            }
        }
        if (next >= 0) leaveT += dt;
        if (next >= 0 && leaveT > 0.8f) {
            if (next == BACK) leave();
            else if (next == FORWARD) go(SEA);
            // the middle one: the world starts the game again from the corner
        }
    }

    /** The middle cube has been broken and the dust has settled: time for another game. */
    boolean signsReadyToLeave() {
        return next == MIDDLE && leaveT > 0.8f;
    }

    /** The lane's forward and right, for the world drawing on it. */
    float fx() {
        return fX;
    }

    float fz() {
        return fZ;
    }

    float rx() {
        return rX;
    }

    float rz() {
        return rZ;
    }

    /** The player threw the hammer at a cube. */
    void strike(int k) {
        if (!signsReady() || sign[k] == null || sign[k].gone) return;
        chosen = k;
        hammerT = 0f;
        host.sound(Synth.WHOOSH, 0.9f, 1f);
        host.buzz(12);
    }

    /** Back at the corner for another game: the golem comes round it again. */
    void again() {
        phase = ENTER;
        phaseT = 0f;
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
            float speed = sea ? Math.max(7f, Math.min(18f, 6f + pxps / 110f)) : Math.max(5f, Math.min(13f, 4f + pxps / 140f));
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
        if (sea) {
            throwDisc(u, speed, ang);
            return;
        }
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

    // ---- the skipping stone ----

    private float discSpin;

    /** A flat stone thrown low from the hand, spinning, to skip over the water. */
    private void throwDisc(float u, float speed, float ang) {
        Body b = Body.ball(DISC_R, DISC);
        b.scaleMass(DISC_MASS);
        b.setPose(wx(u * 0.5f, 1.4f), 1.15f, wz(u * 0.5f, 1.4f), 1, 0, 0, 0);
        float dxl = (float) Math.sin(ang), dvl = (float) Math.cos(ang);
        b.vx = (rX * dxl + fX * dvl) * speed;
        b.vz = (rZ * dxl + fZ * dvl) * speed;
        b.vy = -1.0f - speed * 0.04f;
        b.asleep = false;
        b.ghost = false;
        balls.add(b);
        skipping = true;
        skips = 0;
        discSpin = 0f;
        host.sound(Synth.WHOOSH, 0.8f, 1.2f + speed / 40f);
        host.buzz(15);
        handX = 0f;
        go(ROLL);
    }

    /**
     * The stone in flight over the water: each time it comes down flat and fast enough it
     * skips up again, a little slower; on the floe it comes down to slide; too slow, it sinks.
     */
    private void skip(Body b, float dt, World w) {
        b.vy += Physics.G * dt;
        b.px += b.vx * dt;
        b.py += b.vy * dt;
        b.pz += b.vz * dt;
        discSpin += dt * 26f;
        float v = vOf(b.px, b.pz);
        if (v >= landV(round) && v <= golemV(round) + 1f) {
            // among the pillars: from here on it is a stone that strikes them, falls and rolls
            if (b.py < DISC_R + 0.005f) {
                b.py = DISC_R + 0.005f;
                b.vy = 0f;
            }
            b.wx = b.vz / DISC_R;
            b.wz = -b.vx / DISC_R;
            b.savePrev();
            phys.add(b);
            skipping = false;
            if (b.py < DISC_R + 0.05f) {
                w.ripple(b.px, b.pz, 0.35f);
                host.sound(Synth.SPLASH, 0.35f, 1.3f);
            }
        } else if (b.py <= DISC_R * 0.6f && b.vy < 0f) {
            float hs = (float) Math.sqrt(b.vx * b.vx + b.vz * b.vz);
            if (hs > 3.2f) {
                skips++;
                b.py = DISC_R * 0.6f;
                b.vy = -b.vy * 0.35f + 1.3f;
                b.vx *= 0.88f;
                b.vz *= 0.88f;
                w.ripple(b.px, b.pz, 0.35f);
                for (int i = 0; i < 4; i++) w.splashBit(b.px, 0.05f, b.pz, b.vx * 0.3f, b.vz * 0.3f);
                host.sound(Synth.SPLASH, 0.32f, 1.45f + 0.07f * Math.min(skips, 8));
                host.buzz(8);
            } else {
                // out of speed: under it goes
                b.gone = true;
                b.ghost = true;
                w.ripple(b.px, b.pz, 0.8f);
                for (int i = 0; i < 10; i++) w.splashBit(b.px, 0.05f, b.pz, 0f, 0f);
                host.sound(Synth.SPLASH, 0.6f, 0.95f);
            }
        }
        // turned as it spins; setting the pose stops a body, so its speed is kept aside
        float vx = b.vx, vy = b.vy, vz = b.vz;
        float c = (float) Math.cos(discSpin * 0.5f), sn = (float) Math.sin(discSpin * 0.5f);
        if (skipping) {
            b.setPose(b.px, b.py, b.pz, c, 0f, sn, 0f);
            b.vx = vx;
            b.vy = vy;
            b.vz = vz;
        }
    }

    /** A pillar that has fallen into the water throws it up, once. */
    private void splashes(World w) {
        if (splashed.length != blocks.size()) splashed = new boolean[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            Body b = blocks.get(i);
            if (splashed[i] || !knocked(b) || b.minY > 0.3f) continue;
            splashed[i] = true;
            w.ripple(b.px, b.pz, 0.6f);
            for (int k = 0; k < 8; k++) w.splashBit(b.px, 0.1f, b.pz, b.vx * 0.2f, b.vz * 0.2f);
            host.sound(Synth.SPLASH, 0.5f, 0.9f + rnd.nextFloat() * 0.3f);
        }
    }

    // ---- the grower ----
    // Every figure is grown fresh, from the golem's stones, storey by storey: ranks of
    // pillars, courses of brick, portals with a lintel, towers of blocks, a bending row of
    // slabs, steps, a totem. A flat top may carry another storey, narrower; some are capped
    // with a block or a pillar. Each candidate must stand by itself, and one ball rolled at
    // its middle must bring down some of it but not all: of six candidates the one nearest
    // the share wanted is kept. It grows in the background, one figure ahead.

    private Thread growing;
    private volatile ArrayList<Body> grownFig;
    private int grownFor = -1;

    private void prepare(final int r) {
        if (r >= ROUNDS || grownFor == r) return;
        grownFor = r;
        grownFig = null;
        final long sd = rnd.nextLong();
        growing = new Thread(new Runnable() {
            @Override
            public void run() {
                grownFig = new Alley(null, sd, sea, figures).growFigure(r, new Random(sd));
            }
        });
        growing.setDaemon(true);
        growing.start();
    }

    private ArrayList<Body> takeGrown(int r) {
        if (grownFor != r || growing == null) return null;
        try {
            growing.join();
        } catch (InterruptedException e) {
            return null;
        }
        growing = null;
        grownFor = -1;
        return grownFig;
    }

    /** Six candidates, the one that stands and falls nearest the share wanted; null if none stood. */
    ArrayList<Body> growFigure(int r, Random g) {
        float want = 0.55f - 0.05f * r, bestGap = 9f;
        ArrayList<Body> best = null;
        for (int attempt = 0; attempt < (sea ? 8 : 6); attempt++) {
            if (sea && attempt == 6 && best != null) break; // an ice figure stood: keep it
            blocks.clear();
            // at sea: the ice figure of the round, a little different each try; the grower
            // only if none of them will stand
            if (sea && attempt < 6) iceFigure(ICE_ORDER[figures & 1][r], g, figureV(r));
            else grow(g, r, figureV(r));
            if (blocks.size() < 6 || !standsAlone()) continue;
            float s = probe();
            // a figure of ice may fall easily or hard; a grown one must be neither too easy nor too hard
            boolean made = sea && attempt < 6;
            if (s < (made ? 0.05f : 0.12f) || s > (made ? 1f : 0.9f)) continue;
            float gap = Math.abs(s - want);
            if (gap < bestGap) {
                bestGap = gap;
                best = new ArrayList<Body>(blocks);
            }
            if (gap < 0.1f) break;
        }
        return best;
    }

    /** Let it stand a second: nothing may slide or lean. It starts the game as it settled. */
    boolean standsAlone() {
        Physics p = new Physics();
        p.sleeping = false;
        for (Body b : blocks) {
            b.setPose(b.homeX, b.homeY, b.homeZ, b.homeQw, b.homeQx, b.homeQy, b.homeQz);
            b.asleep = false;
            b.savePrev();
            p.add(b);
        }
        for (int i = 0; i < 60; i++) p.step(1f / 60f);
        boolean ok = true;
        for (Body b : blocks) {
            float dx = b.px - b.homeX, dy = b.py - b.homeY, dz = b.pz - b.homeZ;
            if (dx * dx + dy * dy + dz * dz > 0.06f * K * 0.06f * K || b.r11 < 0.98f) ok = false;
        }
        for (Body b : blocks) {
            if (ok) {
                b.homeX = b.px;
                b.homeY = b.py;
                b.homeZ = b.pz;
            }
            b.setPose(b.homeX, b.homeY, b.homeZ, b.homeQw, b.homeQx, b.homeQy, b.homeQz);
            b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0f;
            b.id = 0;
        }
        return ok;
    }

    /** One ball, fair and square at the middle: the share it brings down. Everything put back after. */
    float probe() {
        Physics p = new Physics();
        p.sleeping = true;
        for (Body b : blocks) {
            b.asleep = true;
            b.savePrev();
            p.add(b);
        }
        Body ball = Body.ball(sea ? DISC_R : BALL_R, 0);
        ball.scaleMass(sea ? DISC_MASS : BALL_MASS);
        // at sea the stone comes down on the near edge of the floe, slower after its skips
        float start = sea ? v0 - 3.0f : 1.6f, rr = sea ? DISC_R : BALL_R;
        ball.setPose(wx(0f, start), rr + 0.01f, wz(0f, start), 1, 0, 0, 0);
        float speed = sea ? 8f : 10f;
        ball.vx = fX * speed;
        ball.vz = fZ * speed;
        ball.wx = ball.vz / rr;
        ball.wz = -ball.vx / rr;
        ball.asleep = false;
        ball.savePrev();
        p.add(ball);
        for (int i = 0; i < 60 * 4; i++) p.step(1f / 60f);
        float s = knockedShare();
        for (Body b : blocks) {
            b.setPose(b.homeX, b.homeY, b.homeZ, b.homeQw, b.homeQx, b.homeQy, b.homeQz);
            b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0f;
            b.asleep = true;
            b.id = 0;
        }
        return s;
    }

    private static final int PINS = 0, WALL = 1, PORTAL = 2, TOWERS = 3, SLABS = 4, STEPS = 5, TOTEM = 6;

    /** A figure grown storey by storey, in lane units, at a distance v. */
    void grow(Random g, int r, float v) {
        v0 = v;
        float hw = 0.7f + g.nextFloat() * 0.4f + r * 0.04f;
        float y = 0f;
        int storeys = 2 + g.nextInt(2) + (r >= 2 && g.nextBoolean() ? 1 : 0);
        boolean flat = false;
        for (int s = 0; s < storeys; s++) {
            boolean ground = s == 0;
            // what may stand here: everything on the ground, on a flat top only what is narrow-footed
            // flat tops come more often low down, so the figure has storeys to grow
            boolean last = s == storeys - 1;
            float[] w = {last ? 1.4f : 0.5f, 1.4f, 1.8f, last ? 1.0f : 0.3f, ground ? 0.5f : 0f, ground ? 0.5f : 0f, last ? 0.6f : 0.2f};
            // at sea: pillars, towers of blocks, totems; nothing else
            if (sea) w = new float[]{2.4f, 0f, 0f, 1.0f, 0f, 0f, 0.7f};
            float sum = 0f;
            for (float x : w) sum += x;
            float pick = g.nextFloat() * sum;
            int m = 0;
            while (m < w.length - 1 && (pick -= w[m]) > 0f) m++;
            int c = (s & 1) == 0 ? STONE : STONE2;
            switch (m) {
                case PINS: {
                    int n = Math.max(2, Math.min(5, Math.round(2f * hw / 0.46f)));
                    float gap = Math.min(0.56f, 2f * hw / n);
                    int ranks = ground ? 1 + g.nextInt(3) : 1;
                    for (int k = 0; k < ranks; k++) {
                        int nk = n - (k & 1);
                        for (int i = 0; i < nk; i++)
                            block((i - (nk - 1) * 0.5f) * gap, k * 0.5f, y, 0.13f, 0.45f, 0.13f, PIN, 0f);
                    }
                    flat = false;
                    s = storeys;
                    break;
                }
                case WALL: {
                    int n = Math.max(2, Math.round(2f * hw / 0.5f));
                    float bw = 2f * hw / n, bh = 0.24f;
                    int courses = 2 + g.nextInt(2);
                    for (int k = 0; k < courses; k++) {
                        if ((k & 1) == 0)
                            for (int i = 0; i < n; i++)
                                block(-hw + bw * (i + 0.5f), 0f, y, bw * 0.5f - 0.005f, bh * 0.5f, 0.15f, (i + k) % 3 == 0 ? BRICK2 : BRICK, 0f);
                        else {
                            block(-hw + bw * 0.25f, 0f, y, bw * 0.25f - 0.005f, bh * 0.5f, 0.15f, BRICK, 0f);
                            for (int i = 0; i < n - 1; i++)
                                block(-hw + bw * (i + 1f), 0f, y, bw * 0.5f - 0.005f, bh * 0.5f, 0.15f, (i + k) % 3 == 0 ? BRICK2 : BRICK, 0f);
                            block(hw - bw * 0.25f, 0f, y, bw * 0.25f - 0.005f, bh * 0.5f, 0.15f, BRICK, 0f);
                        }
                        y += bh;
                    }
                    flat = true;
                    break;
                }
                case PORTAL: {
                    float cs = 0.17f;
                    int high = 2 + g.nextInt(2);
                    boolean three = hw > 0.75f;
                    for (int k = 0; k < high; k++) {
                        cube(-hw + cs, 0f, y + k * 2f * cs, cs, (k & 1) == 0 ? c : BRICK);
                        cube(hw - cs, 0f, y + k * 2f * cs, cs, (k & 1) == 0 ? BRICK : c);
                        if (three) cube(0f, 0f, y + k * 2f * cs, cs, c);
                    }
                    y += high * 2f * cs;
                    block(0f, 0f, y, hw + 0.04f, 0.06f, 0.2f, STONE2, 0f);
                    y += 0.12f;
                    flat = true;
                    break;
                }
                case TOWERS: {
                    int n = 2 + (hw > 0.7f ? g.nextInt(2) : 0), high = 2 + g.nextInt(3);
                    float cs = 0.15f + g.nextFloat() * 0.04f;
                    for (int t = 0; t < n; t++) {
                        float u = n == 1 ? 0f : -hw + cs + t * (2f * hw - 2f * cs) / (n - 1);
                        for (int k = 0; k < high; k++) cube(u, 0f, y + k * 2f * cs, cs, ((k + t) & 1) == 0 ? c : BRICK);
                    }
                    flat = false;
                    s = storeys;
                    break;
                }
                case SLABS: {
                    int n = 6 + g.nextInt(5);
                    float bend = (g.nextFloat() - 0.5f) * 1.2f;
                    for (int i = 0; i < n; i++) {
                        float a = i * 0.55f;
                        float u = bend * (float) Math.sin(a), du = bend * 0.55f / 0.5f * (float) Math.cos(a);
                        block(u, -1.8f + i * 0.5f, 0f, 0.26f, 0.45f, 0.06f, (i & 1) == 0 ? STONE : BRICK, (float) Math.atan(du));
                    }
                    flat = false;
                    s = storeys;
                    break;
                }
                case STEPS: {
                    float cs = 0.2f;
                    int base = Math.max(2, Math.min(4, Math.round(hw / cs)));
                    for (int k = 0; k < base; k++) {
                        int n = base - k;
                        for (int i = 0; i < n; i++) cube((i - (n - 1) * 0.5f) * 2f * cs, 0f, y + k * 2f * cs, cs - 0.003f, (k & 1) == 0 ? STONE : STONE2);
                    }
                    flat = false;
                    s = storeys;
                    break;
                }
                default: { // a totem in the middle, stone and brick by turns
                    int n = 3 + g.nextInt(3);
                    for (int i = 0; i < n; i++) {
                        boolean wide = (i & 1) == 0;
                        float hu = wide ? 0.17f : 0.12f, hy = wide ? 0.11f : 0.16f;
                        block(0f, 0f, y, hu, hy, 0.14f, wide ? c : BRICK, 0f);
                        y += 2f * hy;
                    }
                    flat = false;
                    s = storeys;
                    break;
                }
            }
            hw *= 0.65f + g.nextFloat() * 0.25f;
            if (hw < 0.28f) break;
        }
        // a flat top may wear a cap
        if (flat && g.nextFloat() < 0.7f) {
            if (g.nextBoolean()) block(0f, 0f, y, 0.13f, 0.45f, 0.13f, PIN, 0f);
            else for (int i = -1; i <= 1; i += 2) cube(i * Math.min(0.3f, hw), 0f, y, 0.15f, BRICK2);
        }
    }

    // ---- the ice figures ----
    // Ten figures of ice for the two games of stones out at sea, five to each: a gate of two
    // towers, a crown of icicles round a spire, a stepped ziggurat, a colonnade, a frozen
    // ship; a ring of stacked blocks like an igloo's, three obelisks, a spiral of standing
    // slabs that fall one into the next, a forest of icicles, a castle with towers and keep.
    // Pale ice, blue ice, deep glacier blue and green, caps of snow.

    // bright, for the grey light out there takes a good deal off them
    static final int ICE_A = 0xFFF0FAFF, ICE_B = 0xFFAEE2FA, ICE_C = 0xFF74C6F0, ICE_D = 0xFF62E8DC, ICE_E = 0xFF4A98E0,
            SNOW = 0xFFFFFFFF;

    // the order the figures come in, the readiest to fall first: the ziggurat, the gate, the
    // crown, the ship, the colonnade; the spiral, the henge, the castle, the icicles, the obelisks
    private static final int[][] ICE_ORDER = {{2, 0, 1, 4, 3}, {7, 5, 9, 8, 6}};

    private float jit;

    private float j(Random g) {
        return (g.nextFloat() - 0.5f) * jit;
    }

    /** A stack of cubes from y, each a little smaller; returns the height of its top. */
    private float stack(float u, float dv, float y, int n, float s, float shrink, int c1, int c2) {
        for (int i = 0; i < n; i++) {
            cube(u, dv, y, s, (i & 1) == 0 ? c1 : c2);
            y += 2f * s;
            s *= shrink;
        }
        return y;
    }

    void iceFigure(int kind, Random g, float v) {
        v0 = v;
        jit = 0.04f;
        switch (kind % 10) {
            case 0: { // the gate: two towers, a lintel, a spire on it, two guards before it
                float s = 0.17f, x = 0.25f + j(g) * 0.2f;
                float top = Math.max(stack(-x, 0f, 0f, 3, s, 1f, ICE_B, ICE_C), stack(x, 0f, 0f, 3, s, 1f, ICE_C, ICE_B));
                block(0f, 0f, top, x + s + 0.02f, 0.06f, 0.2f, ICE_A, 0f);
                top += 0.12f;
                block(0f, 0f, top, 0.1f, 0.32f, 0.1f, ICE_D, 0f);
                cube(-x, 0f, top, 0.12f, SNOW);
                cube(x, 0f, top, 0.12f, SNOW);
                for (int i = -1; i <= 1; i += 2) block(i * 0.52f + j(g), -0.3f, 0f, 0.1f, 0.34f, 0.1f, ICE_E, 0f);
                break;
            }
            case 1: { // the crown: a ring of icicles round a spire of blocks
                int n = 9;
                float rad = 0.62f + j(g);
                for (int i = 0; i < n; i++) {
                    double a = i * Math.PI * 2 / n;
                    float hy = 0.3f + 0.12f * (i % 3);
                    block((float) Math.cos(a) * rad, (float) Math.sin(a) * rad, 0f, 0.1f, hy, 0.1f, (i & 1) == 0 ? ICE_B : ICE_D, (float) a);
                }
                float top = stack(0f, 0f, 0f, 3, 0.16f, 0.85f, ICE_C, ICE_A);
                block(0f, 0f, top, 0.07f, 0.28f, 0.07f, SNOW, 0f);
                break;
            }
            case 2: { // the ziggurat: four tiers, a spire
                float s = 0.14f, y = 0f;
                for (int t = 0; t < 4; t++) {
                    int n = 4 - t;
                    for (int i = 0; i < n; i++)
                        cube((i - (n - 1) * 0.5f) * 2f * s, 0f, y, s - 0.004f, t == 0 ? ICE_E : t == 1 ? ICE_C : t == 2 ? ICE_B : ICE_A);
                    y += 2f * s;
                }
                block(0f, 0f, y, 0.08f, 0.3f, 0.08f, ICE_D, 0f);
                break;
            }
            case 3: { // the colonnade: two rows of columns, slabs across, a row of blocks on them
                int n = 5;
                float gap = 0.42f + j(g);
                for (int i = 0; i < n; i++) {
                    float u = (i - (n - 1) * 0.5f) * gap;
                    block(u, -0.22f, 0f, 0.09f, 0.4f, 0.09f, ICE_B, 0f);
                    block(u, 0.22f, 0f, 0.09f, 0.4f, 0.09f, ICE_C, 0f);
                    block(u, 0f, 0.8f, 0.14f, 0.05f, 0.34f, ICE_A, 0f);
                    cube(u, 0f, 0.9f, 0.12f, (i & 1) == 0 ? ICE_D : SNOW);
                }
                break;
            }
            case 4: { // the frozen ship: a hull of blocks, raised bow and stern, a mast and its yard
                float s = 0.14f;
                for (int i = -3; i <= 3; i++) cube(i * 2f * s, 0f, 0f, s - 0.004f, ICE_E);
                for (int i = -2; i <= 2; i++) block(i * 2f * s, 0f, 2f * s, s - 0.004f, 0.05f, s * 1.2f, ICE_B, 0f);
                stack(-6f * s, 0f, 2f * s, 2, s, 0.85f, ICE_C, ICE_A);
                stack(6f * s, 0f, 2f * s, 1, s, 1f, ICE_C, ICE_A);
                float deck = 2f * s + 0.1f;
                block(0f, 0f, deck, 0.06f, 0.55f, 0.06f, ICE_A, 0f);
                block(0f, 0f, deck + 1.1f, 0.42f, 0.035f, 0.05f, SNOW, 0f);
                block(-0.55f, 0f, deck, 0.05f, 0.3f, 0.05f, ICE_D, 0f);
                break;
            }
            case 5: { // the henge: a ring of short stacks, lintels across pairs of them, a spire within
                int n = 8;
                float s = 0.13f, rad = 0.6f;
                float[] px = new float[n], pz = new float[n];
                for (int i = 0; i < n; i++) {
                    double a = i * Math.PI * 2 / n;
                    px[i] = (float) Math.cos(a) * rad;
                    pz[i] = (float) Math.sin(a) * rad;
                    stack(px[i], pz[i], 0f, 2, s, 1f, ICE_C, ICE_B);
                }
                for (int i = 0; i < n; i += 2) {
                    float mx = (px[i] + px[i + 1]) * 0.5f, mz = (pz[i] + pz[i + 1]) * 0.5f;
                    float dx = px[i + 1] - px[i], dz = pz[i + 1] - pz[i], len = (float) Math.sqrt(dx * dx + dz * dz);
                    block(mx, mz, 4f * s, len * 0.5f + 0.1f, 0.05f, 0.11f, (i & 2) == 0 ? ICE_A : SNOW, -(float) Math.atan2(dz, dx));
                }
                block(0f, 0f, 0f, 0.08f, 0.5f, 0.08f, ICE_D, 0f);
                break;
            }
            case 6: { // three obelisks, of different heights, a point of snow on each
                float[][] at = {{-0.5f, 0f, 4}, {0.5f, 0.05f, 5}, {0f, 0.45f, 6}};
                for (float[] o : at) {
                    float top = stack(o[0] + j(g), o[1], 0f, (int) o[2], 0.15f, 0.9f, ICE_C, ICE_B);
                    block(o[0], o[1], top, 0.05f, 0.14f, 0.05f, SNOW, 0f);
                }
                break;
            }
            case 7: { // a spiral of standing slabs, each a little taller: knock the first and they fall one into the next
                int n = 15;
                for (int i = 0; i < n; i++) {
                    float a = 0.4f + i * 0.42f, rad = 0.35f + i * 0.045f;
                    float u = (float) Math.cos(a) * rad, dv = (float) Math.sin(a) * rad - 0.1f;
                    block(u, dv, 0f, 0.12f, 0.16f + i * 0.026f, 0.045f, i % 3 == 0 ? ICE_D : (i & 1) == 0 ? ICE_B : ICE_A, -a);
                }
                break;
            }
            case 8: { // a forest of icicles, staggered, taller toward the back
                for (int row = 0; row < 4; row++) {
                    int n = 5 - (row & 1);
                    for (int i = 0; i < n; i++) {
                        float u = (i - (n - 1) * 0.5f) * 0.36f + j(g), hy = 0.26f + row * 0.07f + g.nextFloat() * 0.08f;
                        block(u, row * 0.3f - 0.4f, 0f, 0.07f, hy, 0.07f, row == 0 ? ICE_A : row == 1 ? ICE_B : row == 2 ? ICE_C : ICE_E, 0f);
                    }
                }
                break;
            }
            default: { // the castle: a crenellated wall, two corner towers, a keep behind
                float s = 0.13f;
                for (int i = -2; i <= 2; i++) {
                    cube(i * 2f * s, 0f, 0f, s - 0.004f, ICE_B);
                    cube(i * 2f * s, 0f, 2f * s, s - 0.004f, ICE_A);
                    if ((i & 1) == 0) cube(i * 2f * s, 0f, 4f * s, s * 0.8f, SNOW);
                }
                for (int side = -1; side <= 1; side += 2) {
                    float top = stack(side * 0.82f, 0f, 0f, 4, 0.15f, 0.95f, ICE_C, ICE_B);
                    block(side * 0.82f, 0f, top, 0.06f, 0.18f, 0.06f, ICE_D, 0f);
                }
                float top = stack(0f, 0.5f, 0f, 5, 0.16f, 0.92f, ICE_E, ICE_C);
                block(0f, 0.5f, top, 0.07f, 0.25f, 0.07f, SNOW, 0f);
                break;
            }
        }
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
        // at sea the golem's stone and brick are ice
        if (sea) color = color == STONE ? ICE_B : color == STONE2 ? ICE_C : color == BRICK ? ICE_E
                : color == BRICK2 ? ICE_C : color == PIN ? ICE_A : color;
        Body b = new Body(hu, hy, hv, Body.STONE, color);
        // stone to look at, light enough to knock over, and free to topple
        b.scaleMass(BLOCK_MASS);
        if (sea) {
            // the ice golem's: old blue ice, snow on top
            b.icy = true;
            b.snowy = true;
        }
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
