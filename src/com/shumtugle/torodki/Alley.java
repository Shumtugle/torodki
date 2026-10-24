package com.shumtugle.torodki;

import java.util.ArrayList;
import java.util.Random;

/**
 * Bowling on the street behind the player, now and then between buildings. The view
 * turns round: the street runs on to the shore road, with the beach and the sea beyond.
 * The golem comes out from behind the corner house in leaps, stops in the middle of the
 * road and falls apart, its stones and bricks flying in arcs to stand up as a figure: a
 * triangle of pins, a wall, a gate, a bending row of slabs. The player rolls heavy balls at
 * it, two to a figure; whatever falls or is pushed off its spot counts. Then the stones
 * fly back together into the golem, it leaps, and falls apart into the next figure. After
 * five figures the view turns back to the building that waits, and the gods give heavy
 * hammers for what was knocked down. Nothing is lost here: it is a feast, not a trial.
 *
 * Plain Java: the world feeds it time and touches, and draws what it holds.
 */
final class Alley {
    // the phases, in the order they come
    static final int TURN_IN = 0, ENTER = 1, SCATTER = 2, AIM = 3, ROLL = 4, SETTLE = 5, GATHER = 6, FINALE = 7, TURN_OUT = 8;
    static final float TURN_T = 1.1f;

    static final int FRAMES = 5, BALLS = 2;
    // the camera: where the player stands, turned round toward the corner house a little
    static final float CAM_X = World.CAM_X, CAM_Y = 1.7f, YAW = (float) Math.PI - 0.16f, PITCH = -0.1f;
    // how far down the lane the figures stand, and the golem behind them
    static final float FIGURE_V = 12f, GOLEM_V = 16f;
    static final float BALL_R = 0.24f;
    static final float BLOCK_MASS = 0.3f, BALL_MASS = 2f;

    int phase = TURN_IN;
    float phaseT;
    int frame, ball;
    final float[] share = new float[FRAMES];
    final boolean[] strike = new boolean[FRAMES];
    int reward = -1;                        // heavy hammers given at the end, once counted
    float flash;                            // the strike: a white flash fading
    final Physics phys = new Physics();
    final ArrayList<Body> blocks = new ArrayList<Body>();
    final ArrayList<Body> balls = new ArrayList<Body>();
    Golem golem;

    // the lane's frame in the world: right and forward on the ground
    private final float rX, rZ, fX, fZ;
    private final Random rnd;
    private final int[] order = new int[FRAMES];
    private final World.Host host;

    // the flight of each block from the golem to its place, or back
    private float[] fromX = new float[0], fromY = new float[0], fromZ = new float[0], delay = new float[0];
    private boolean[] landedAt = new boolean[0];
    private final float[] q = new float[4];

    // the swipe
    private boolean touching;
    private float downX, downY, downT, fingerX;
    float handX;                            // where the ball in hand is, -1 left to 1 right

    Alley(World.Host host, long seed) {
        this.host = host;
        rnd = new Random(seed);
        rX = (float) Math.cos(YAW);
        rZ = -(float) Math.sin(YAW);
        fX = (float) Math.sin(YAW);
        fZ = (float) Math.cos(YAW);
        phys.sleeping = true;
        // the first figure is always the classic triangle, the rest drawn from the others
        order[0] = 0;
        int[] rest = {1, 2, 3, 4, 5, 6, 7};
        for (int i = rest.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1), t = rest[i];
            rest[i] = rest[j];
            rest[j] = t;
        }
        for (int i = 1; i < FRAMES; i++) order[i] = rest[i - 1];
        // it comes from the shore road, beyond the corner house
        golem = new Golem(19f, Backdrop.CROSS_Z - 4f);
        golem.holding = true;
    }

    /** A point of the lane, sideways u and forward v from the player's feet, into the world. */
    float wx(float u, float v) {
        return CAM_X + rX * u + fX * v;
    }

    float wz(float u, float v) {
        return rZ * u + fZ * v;
    }

    private void go(int p) {
        phase = p;
        phaseT = 0f;
    }

    boolean done() {
        return phase == TURN_OUT && phaseT >= TURN_T;
    }

    // ---- time ----

    void update(float dt, World w) {
        phaseT += dt;
        flash = Math.max(0f, flash - dt * 2.5f);
        golem.update(dt, 0f);
        if (golem.landings > landed) {
            landed = golem.landings;
            host.sound(Synth.GROUND, 1.1f, 0.7f);
            host.sound(Synth.STONE, 0.6f, 0.6f);
            host.buzz(50);
            for (int i = 0; i < 10; i++) w.dust(golem.x + (rnd.nextFloat() - 0.5f) * 2f, 0.05f, golem.z + (rnd.nextFloat() - 0.5f) * 2f);
        }
        switch (phase) {
            case TURN_IN:
                if (phaseT >= TURN_T) {
                    go(ENTER);
                    host.sound(Synth.ROAR, 0.7f, 1.1f);
                }
                break;
            case ENTER:
                // round the corner and up the street in three leaps
                if (golem.idle() && (leaps == 0 || golem.actT > 0.2f)) {
                    if (leaps == 0 && phaseT > 0.5f) hopTo(8f, Backdrop.CROSS_Z - 3f);
                    else if (leaps == 1) hopTo(wx(0f, GOLEM_V + 7f), wz(0f, GOLEM_V + 7f));
                    else if (leaps == 2) hopTo(wx(0f, GOLEM_V), wz(0f, GOLEM_V));
                    else if (leaps == 3 && golem.actT > 0.5f) scatter();
                }
                break;
            case SCATTER:
                flyBlocks(w, false);
                if (phaseT > lastDelay() + 0.65f) {
                    for (Body b : blocks) {
                        b.setPose(b.homeX, b.homeY, b.homeZ, b.homeQw, b.homeQx, b.homeQy, b.homeQz);
                        b.vx = b.vy = b.vz = b.wx = b.wy = b.wz = 0f;
                        b.shown = 1f;
                        b.asleep = true;
                        b.savePrev();
                        phys.add(b);
                    }
                    ball = 0;
                    go(AIM);
                }
                break;
            case AIM:
                break;
            case ROLL: {
                phys.step(dt);
                Body b = balls.get(balls.size() - 1);
                float v = (b.px - CAM_X) * fX + b.pz * fZ;
                boolean stopped = b.speed2() < 0.09f && phaseT > 0.8f;
                if (v > GOLEM_V + 1f) {
                    // gone on down the street, out of the game
                    b.gone = true;
                    b.ghost = true;
                }
                if (stopped || b.gone || phaseT > 7f) go(SETTLE);
                break;
            }
            case SETTLE:
                phys.step(dt);
                if (phaseT > 1.1f && (still() || phaseT > 2.6f)) count(w);
                break;
            case GATHER:
                flyBlocks(w, true);
                if (phaseT > lastDelay() + 0.55f) {
                    blocks.clear();
                    golem.down = false;
                    host.sound(Synth.ROAR, 0.5f, 1.25f);
                    frame++;
                    leaps = 2;
                    hopTo(wx(0f, GOLEM_V), wz(0f, GOLEM_V));
                    go(ENTER);
                }
                break;
            case FINALE:
                phys.step(dt);
                if (reward < 0 && phaseT > 0.8f) {
                    float sum = 0f;
                    for (float s : share) sum += s;
                    sum /= FRAMES;
                    reward = sum >= 0.9f ? 3 : sum >= 0.65f ? 2 : sum >= 0.35f ? 1 : 0;
                    w.giveHeavy(reward);
                    for (int i = 0; i < reward; i++) host.sound(Synth.CATCH, 0.9f, 0.9f + i * 0.12f);
                }
                if (phaseT > 2.6f) go(TURN_OUT);
                break;
            default:
                break;
        }
    }

    private int leaps, landed;

    private boolean still() {
        for (Body b : blocks) if (!b.asleep && b.speed2() > 0.02f) return false;
        return true;
    }

    /** A pin: tall, pale, with red bands round its neck. */
    static boolean isPin(Body b) {
        return b.hy > 0.4f && b.hx < 0.15f;
    }

    private void hopTo(float x, float z) {
        golem.hop(x, z);
        leaps++;
    }

    private float lastDelay() {
        float m = 0f;
        for (float d : delay) m = Math.max(m, d);
        return m;
    }

    /** The golem falls apart, and its stones fly to their places in the next figure. */
    private void scatter() {
        golem.down = true;
        blocks.clear();
        phys.clear();
        build(order[frame]);
        int n = blocks.size();
        fromX = new float[n];
        fromY = new float[n];
        fromZ = new float[n];
        delay = new float[n];
        landedAt = new boolean[n];
        float cx = golem.x, cz = golem.z;
        for (int i = 0; i < n; i++) {
            fromX[i] = cx + (rnd.nextFloat() - 0.5f) * 2.4f;
            fromY[i] = 1.5f + rnd.nextFloat() * 4f;
            fromZ[i] = cz + (rnd.nextFloat() - 0.5f) * 1.6f;
            delay[i] = i * 0.045f + rnd.nextFloat() * 0.05f;
        }
        host.sound(Synth.THUNDER, 0.45f, 1.5f);
        host.sound(Synth.WHOOSH, 0.9f, 0.8f);
        go(SCATTER);
    }

    /** Each block along its arc: out from the golem to its place, or (back) the other way. */
    private void flyBlocks(World w, boolean back) {
        for (int i = 0; i < blocks.size(); i++) {
            Body b = blocks.get(i);
            float t = (phaseT - delay[i]) / (back ? 0.5f : 0.6f);
            float k = Math.max(0f, Math.min(1f, t));
            if (back) k = 1f - k;
            // k = 0 at the golem, 1 at the place
            float e = k * k * (3f - 2f * k);
            float x = fromX[i] + (b.homeX - fromX[i]) * e;
            float z = fromZ[i] + (b.homeZ - fromZ[i]) * e;
            float y = fromY[i] + (b.homeY - fromY[i]) * e + 2.2f * 4f * e * (1f - e);
            float spin = (1f - e) * 6f + i;
            float c = (float) Math.cos(spin * 0.5f), s = (float) Math.sin(spin * 0.5f);
            // a tumble about its own across-axis while it flies, none once it is down
            float hw = b.homeQw, hx = b.homeQx, hy = b.homeQy, hz = b.homeQz;
            b.setPose(x, y, z, hw * c - hx * s, hx * c + hw * s, hy * c + hz * s, hz * c - hy * s);
            b.shown = back ? 0.35f + 0.65f * e : Math.min(1f, 0.35f + e);
            if (!back && t >= 1f && !landedAt[i]) {
                landedAt[i] = true;
                if ((i & 1) == 0) host.sound(Synth.STONE, 0.35f, 0.8f + rnd.nextFloat() * 0.3f);
                w.dust(b.homeX, 0.05f, b.homeZ);
            }
        }
    }

    /** What the ball has knocked: a block counts once it lies or has been pushed off its spot. */
    private void count(World w) {
        int down = 0;
        for (Body b : blocks) if (knocked(b)) down++;
        float s = blocks.isEmpty() ? 0f : down / (float) blocks.size();
        if (ball == 0 && s >= 0.999f) {
            strike[frame] = true;
            flash = 1f;
            host.sound(Synth.THUNDER, 1f, 1f);
            host.buzz(90);
        } else if (ball == 1 && s >= 0.999f) {
            host.sound(Synth.THUNDER, 0.6f, 1.3f);
            host.buzz(50);
        }
        if (ball == 0 && s < 0.999f) {
            ball = 1;
            go(AIM);
            return;
        }
        share[frame] = s;
        if (frame + 1 >= FRAMES) {
            go(FINALE);
            return;
        }
        gather();
    }

    static boolean knocked(Body b) {
        float dx = b.px - b.homeX, dy = b.py - b.homeY, dz = b.pz - b.homeZ;
        if (dx * dx + dy * dy + dz * dz > 0.25f * 0.25f) return true;
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
        for (int i = 0; i < n; i++) {
            Body b = blocks.get(i);
            // the flight runs from the golem (k = 0) to where the block lies now (k = 1)
            fromX[i] = golem.x + (rnd.nextFloat() - 0.5f) * 2f;
            fromY[i] = 1.5f + rnd.nextFloat() * 4f;
            fromZ[i] = golem.z + (rnd.nextFloat() - 0.5f) * 1.4f;
            b.homeX = b.px;
            b.homeY = b.py;
            b.homeZ = b.pz;
            b.homeQw = b.qw;
            b.homeQx = b.qx;
            b.homeQy = b.qy;
            b.homeQz = b.qz;
            delay[i] = rnd.nextFloat() * 0.3f;
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
            float speed = Math.max(4f, Math.min(11f, 3f + pxps / 160f));
            float ang = Math.max(-0.12f, Math.min(0.12f, (float) Math.atan2(dx, dy) * 0.3f));
            roll((downX - World.W * 0.5f) / World.W * 1.8f, speed, ang);
        }
    }

    /** Where the ball in hand is drawn, following the finger before the roll. */
    void handUpdate(float dt) {
        float want = touching ? Math.max(-1f, Math.min(1f, (fingerX - World.W * 0.5f) / (World.W * 0.4f))) : 0f;
        handX += (want - handX) * Math.min(1f, dt * 10f);
    }

    /** A ball rolled from u (meters right of the lane's middle) at a speed, at an angle. */
    void roll(float u, float speed, float ang) {
        if (phase != AIM) return;
        u = Math.max(-0.9f, Math.min(0.9f, u));
        Body b = Body.ball(BALL_R, Builder.BALL_COLORS[rnd.nextInt(Builder.BALL_COLORS.length)]);
        b.scaleMass(BALL_MASS);
        float x = wx(u, 1.4f), z = wz(u, 1.4f);
        b.setPose(x, BALL_R + 0.01f, z, 1, 0, 0, 0);
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
        host.sound(Synth.ROLL, Math.min(1.2f, 0.5f + speed / 12f), 0.85f + speed / 40f);
        host.buzz(15);
        handX = 0f;
        go(ROLL);
    }

    // ---- the figures ----
    // In lane units: u across, v along from the player, y up; every block is set square to
    // the lane. Pins are stone, walls brick, the rest whatever the golem is made of.

    static final int STONE = 0xFFE4DACA, STONE2 = 0xFFCFC3AE, BRICK = 0xFFCC644C, BRICK2 = 0xFFB4553E, PIN = 0xFFF0EAE0;

    private void block(float u, float v, float y, float hu, float hy, float hv, int color, float turn) {
        float x = wx(u, v), z = wz(u, v);
        // the lane's own turn about the up axis, and the block's own on top of it
        float a = YAW + turn;
        q[0] = (float) Math.cos(a * 0.5f);
        q[2] = (float) Math.sin(a * 0.5f);
        Body b = new Body(hu, hy, hv, Body.STONE, color);
        // stone to look at, light as pins to knock over, and free to topple
        b.scaleMass(BLOCK_MASS);
        b.trueInertia();
        b.setPose(x, y + hy, z, q[0], 0f, q[2], 0f);
        b.homeX = b.px;
        b.homeY = b.py;
        b.homeZ = b.pz;
        b.homeQw = q[0];
        b.homeQy = q[2];
        blocks.add(b);
    }

    private void pin(float u, float v) {
        block(u, v, 0f, 0.13f, 0.45f, 0.13f, PIN, 0f);
    }

    private void cube(float u, float v, float y, float s, int color) {
        block(u, v, y, s, s, s, color, 0f);
    }

    void build(int kind) {
        float v0 = FIGURE_V;
        switch (kind) {
            case 0: // ten pins in a triangle, its point toward the player
                for (int r = 0; r < 4; r++)
                    for (int i = 0; i <= r; i++) pin((i - r * 0.5f) * 0.56f, v0 + r * 0.5f);
                break;
            case 1: { // a brick wall in running bond
                float bw = 0.5f, bh = 0.24f;
                for (int r = 0; r < 4; r++) {
                    int n = (r & 1) == 0 ? 5 : 4;
                    for (int i = 0; i < n; i++)
                        block((i - (n - 1) * 0.5f) * bw, v0, r * bh, bw * 0.5f - 0.005f, bh * 0.5f, 0.15f,
                                (i + r) % 3 == 0 ? BRICK2 : BRICK, 0f);
                }
                break;
            }
            case 2: { // a gate: two columns, a lintel, three stones on it
                float c = 0.2f;
                for (int i = 0; i < 3; i++) {
                    cube(-0.4f, v0, i * 2f * c, c, STONE);
                    cube(0.4f, v0, i * 2f * c, c, STONE2);
                }
                block(0f, v0, 6f * c, 0.7f, 0.07f, 0.22f, BRICK, 0f);
                for (int i = -1; i <= 1; i++) cube(i * 0.42f, v0, 6f * c + 0.14f, 0.18f, i == 0 ? STONE2 : STONE);
                break;
            }
            case 3: { // slabs on end in a gentle bend, each one near enough to topple the next
                for (int i = 0; i < 10; i++) {
                    float v = v0 - 1.8f + i * 0.5f;
                    float u = 0.5f * (float) Math.sin(i * 0.55f);
                    float du = 0.5f * 0.55f * (float) Math.cos(i * 0.55f) / 0.5f;
                    float turn = (float) Math.atan(du);
                    block(u, v, 0f, 0.26f, 0.45f, 0.06f, (i & 1) == 0 ? STONE : BRICK, turn);
                }
                break;
            }
            case 4: { // a stepped pyramid, one stone deep
                float c = 0.2f;
                for (int r = 0; r < 4; r++) {
                    int n = 4 - r;
                    for (int i = 0; i < n; i++) cube((i - (n - 1) * 0.5f) * 2f * c, v0, r * 2f * c, c - 0.003f, (r & 1) == 0 ? STONE : STONE2);
                }
                break;
            }
            case 5: // a phalanx: three ranks of pins, staggered
                for (int r = 0; r < 3; r++) {
                    int n = (r & 1) == 0 ? 5 : 4;
                    for (int i = 0; i < n; i++) pin((i - (n - 1) * 0.5f) * 0.5f, v0 + r * 0.55f);
                }
                break;
            case 6: { // two towers bridged by a plank, a little one on top
                float c = 0.17f;
                for (int i = 0; i < 4; i++) {
                    cube(-0.33f, v0, i * 2f * c, c, (i & 1) == 0 ? STONE : BRICK);
                    cube(0.33f, v0, i * 2f * c, c, (i & 1) == 0 ? BRICK : STONE);
                }
                block(0f, v0, 8f * c, 0.6f, 0.06f, 0.2f, STONE2, 0f);
                cube(0f, v0, 8f * c + 0.12f, c, BRICK2);
                cube(0f, v0, 10f * c + 0.12f, c * 0.8f, STONE);
                break;
            }
            default: { // three totems in a row, stone and brick by turns
                for (int t = -1; t <= 1; t++) {
                    float y = 0f;
                    for (int i = 0; i < 5; i++) {
                        boolean flat = (i & 1) == 0;
                        float hu = flat ? 0.17f : 0.12f, hy = flat ? 0.11f : 0.16f;
                        block(t * 0.75f, v0 + Math.abs(t) * 0.4f, y, hu, hy, 0.14f, flat ? STONE : BRICK, 0f);
                        y += 2f * hy;
                    }
                }
                break;
            }
        }
    }
}
