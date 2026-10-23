package com.shumtugle.torodki;

import java.util.ArrayList;
import java.util.Random;

/**
 * The whole game: state, rules and drawing. Plain Java with no platform classes,
 * so the same code runs on the phone and on the desktop stand.
 *
 * A building stands on a plinth in the middle of a summer street. The player
 * throws a wooden hammer at it by dragging a finger to the spot to hit and letting
 * go; the faster the flick, the harder and flatter the throw. Every throw burns one
 * of five stars in the corner. Knock three quarters of the building's weight off the
 * plinth before the stars run out and the next one rises; otherwise the same one
 * stands again, at once, for another try.
 *
 * The shell calls, always from one thread: touch() for each queued touch,
 * update(dt), then draw(frame). Coordinates are game pixels, W wide.
 */
final class World implements Physics.Listener {
    static final int W = 274;              // game pixels across; the height follows the screen
    static final String VERSION = "0.12";   // shown on screen; keep in step with versionName

    static final int DOWN = 0, MOVE = 1, UP = 2;

    static final int STARS = 5;
    static final float CLEAR = 0.75f;      // share of the weight to knock off the plinth

    /** What the game may ask of the phone. The stand answers with nothing. */
    interface Host {
        void buzz(int ms);
        void quit();
        void sound(int id, float gain, float rate);
        void saveLevel(int level);
        int loadLevel();
        /** A file shipped with the game, or null. */
        byte[] asset(String name);
    }

    private final Host host;
    int h = 1;                             // current height in game pixels

    // scene
    private Raster ras;
    private int[] bgCol;
    private float[] bgZ;
    private byte[] bgLit;
    private final Backdrop backdrop = new Backdrop();
    final Physics phys = new Physics();
    private Body plinth;
    final ArrayList<Body> pieces = new ArrayList<Body>();
    private final Builder builder = new Builder();
    float totalMass;

    // camera
    static final float CAM_X = -2.3f, CAM_Y = 1.8f, PITCH = 0.0f;
    // the camera stands in the left lane and looks across at the plinth: the street runs at a slant
    static final float YAW = (float) Math.atan2(-CAM_X, Builder.CZ);
    private float focal;

    // the game
    static final int BUILD = 0, PLAY = 1, WON = 2, LOST = 3, VANISH = 4, SKY_UP = 5, SKY_HOLD = 6, SKY_DOWN = 7;
    static final float TILT = 1.0f;          // seconds to tip up into the sky, and back
    static final int HAND = 0, FLY = 1, BACK = 2;
    int state = VANISH;
    int hs = HAND;                         // where the hammer is
    float hsTime;
    boolean aiming;
    float stateTime;
    int level;
    long seed;
    int stars = STARS;
    int throwsMade;
    float clock;
    private float acc;
    private int nextAfterVanish;           // 1 next level, 0 same again
    private boolean firstBuild = true;

    // the hammer
    Body hammer;
    private float aimX, aimY;
    // the aim moves with the finger but starts away from it, so the finger never hides the target:
    // the ring starts on the building (or where the last throw went) and follows the finger's moves
    private float downX, downY, baseX, baseY;
    private boolean aimedBefore;
    private final float[] dragT = new float[16], dragX = new float[16], dragY = new float[16];
    private int dragN;
    private float flightTime;
    private float retX, retY, retZ;
    private float offAtThrow;
    private boolean thundered;
    private float spinSign = 1f;

    // particles
    private static final int MAXP = 500;
    private final float[] pX = new float[MAXP], pY = new float[MAXP], pZ = new float[MAXP];
    private final float[] pVx = new float[MAXP], pVy = new float[MAXP], pVz = new float[MAXP];
    private final float[] pLife = new float[MAXP], pAge = new float[MAXP];
    private final int[] pCol = new int[MAXP];
    private final boolean[] pDust = new boolean[MAXP];
    private int pN;
    private final Random fx = new Random(99);

    private final float[] tmp = new float[3], scr = new float[2], dirA = new float[3];

    private final Life life;
    private final Sky sky = new Sky();
    private float holdFor;
    private boolean fromSky;          // looking back down from the sky at a building in the making
    private float backT;
    private int boltsHeard;
    private boolean buildFromSky;

    World(Host host) {
        this.host = host;
        life = new Life();
        level = Math.max(0, host.loadLevel());
        phys.listener = this;
    }

    void resize(int height) {
        if (height == h && ras != null) return;
        h = height;
        ras = new Raster(W, h);
        focal = W * 1.9f;
        ras.camera(CAM_X, CAM_Y, 0f, YAW, PITCH, focal, h * 0.5f);
        backdrop.paint(ras, 11L);
        bgCol = ras.col.clone();
        bgZ = ras.zb.clone();
        bgLit = ras.lit.clone();
        sky.build(ras, host.asset("gods.bin"));
        if (firstBuild) {
            firstBuild = false;
            startLevel(level, true);
        }
    }

    // ---- levels ----

    static long seedOf(int level) {
        return level * 2654435761L ^ 0x2545F4914F6CDD1DL;
    }

    /** Raises the building for a level: tested for standing still, asleep, ready to fall. */
    void startLevel(int lv, boolean animate) {
        level = lv;
        host.saveLevel(level);
        seed = seedOf(lv);
        phys.clear();
        pieces.clear();
        plinth = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ, Body.PLINTH, 0xFFF2E3C6);
        plinth.setPose(0f, Builder.TOP * 0.5f, Builder.CZ, 1, 0, 0, 0);
        phys.add(plinth);
        ArrayList<Body> made = standingBuilding(lv, seed);
        totalMass = 0f;
        for (Body b : made) {
            b.setPose(b.homeX, b.homeY, b.homeZ, 1, 0, 0, 0);
            b.asleep = true;
            phys.add(b);
            pieces.add(b);
            totalMass += b.mass;
        }
        hammer = new Body(0.42f, 0.08f, 0.17f, Body.HAMMER, 0xFFD9A066);
        hammer.ghost = true;
        hammer.asleep = false;
        phys.add(hammer);
        buildFromSky = false;
        lastBuildTime = 0f;
        stars = STARS;
        throwsMade = 0;
        thundered = false;
        aiming = false;
        aimedBefore = false;
        cleared = false;
        hs = HAND;
        pN = 0;
        setState(animate ? BUILD : PLAY);
    }

    /** Tries seeds until the building stands still on its own for a second. */
    private ArrayList<Body> standingBuilding(int lv, long sd) {
        for (int attempt = 0; attempt < 6; attempt++) {
            ArrayList<Body> made = builder.build(lv, sd + attempt * 7919L);
            if (standsStill(made)) return made;
        }
        return builder.build(0, sd);
    }

    static boolean standsStill(ArrayList<Body> made) {
        Physics p = new Physics();
        p.sleeping = false;
        Body pl = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ, Body.PLINTH, 0);
        pl.setPose(0f, Builder.TOP * 0.5f, Builder.CZ, 1, 0, 0, 0);
        p.add(pl);
        for (Body b : made) {
            b.setPose(b.homeX, b.homeY, b.homeZ, 1, 0, 0, 0);
            b.asleep = false;
            p.add(b);
        }
        for (int i = 0; i < 60; i++) p.step(1f / 60f);
        boolean ok = true;
        for (Body b : made) {
            float dx = b.px - b.homeX, dy = b.py - b.homeY, dz = b.pz - b.homeZ;
            if (dx * dx + dy * dy + dz * dz > 0.045f * 0.045f || Math.abs(b.qw) < 0.995f) ok = false;
        }
        for (Body b : made) b.id = 0;
        return ok;
    }

    private void setState(int s) {
        state = s;
        stateTime = 0f;
    }

    /** Weight still on the plinth, as a share of the building. */
    float onPlinth() {
        float m = 0f;
        for (Body b : pieces)
            if (!b.gone && Math.abs(b.px) < Builder.PHX + 0.05f && Math.abs(b.pz - Builder.CZ) < Builder.PHZ + 0.05f
                    && b.py > Builder.TOP - 0.05f) m += b.mass;
        return totalMass > 0f ? m / totalMass : 0f;
    }

    // ---- input ----

    void touch(int type, float x, float y) {
        if (type == DOWN) {
            if (state != PLAY || hs != HAND || stars <= 0) return;
            aiming = true;
            dragN = 0;
            downX = x;
            downY = y;
            if (!aimedBefore) buildingCenter();
            else {
                baseX = aimX;
                baseY = aimY;
            }
        }
        if (!aiming) return;
        if (dragN == dragT.length) {
            System.arraycopy(dragT, 1, dragT, 0, dragN - 1);
            System.arraycopy(dragX, 1, dragX, 0, dragN - 1);
            System.arraycopy(dragY, 1, dragY, 0, dragN - 1);
            dragN--;
        }
        dragT[dragN] = clock;
        dragX[dragN] = x;
        dragY[dragN] = y;
        dragN++;
        aimX = Math.max(2f, Math.min(W - 3f, baseX + x - downX));
        aimY = Math.max(2f, Math.min(h - 3f, baseY + y - downY));
        if (type == UP) {
            aiming = false;
            aimedBefore = true;
            throwHammer();
        }
    }

    /** Where the ring starts on a level's first throw: the middle of what still stands on the plinth. */
    private void buildingCenter() {
        float sx = 0f, sy = 0f, m = 0f;
        for (Body b : pieces) {
            if (b.gone) continue;
            ras.project(b.px, b.py, b.pz, scr);
            sx += scr[0] * b.mass;
            sy += scr[1] * b.mass;
            m += b.mass;
        }
        if (m > 0f) {
            baseX = sx / m;
            baseY = sy / m;
        } else {
            ras.project(0f, Builder.TOP + 1f, Builder.CZ, scr);
            baseX = scr[0];
            baseY = scr[1];
        }
    }

    void back() {
        host.quit();
    }

    /** Flick speed at release, in game pixels per second. */
    private float flick() {
        if (dragN < 2) return 0f;
        int i = dragN - 1, j = i;
        while (j > 0 && dragT[i] - dragT[j - 1] < 0.09f) j--;
        if (j == i) j = i - 1;
        float dt = Math.max(1f / 120f, dragT[i] - dragT[j]);
        float dx = dragX[i] - dragX[j], dy = dragY[i] - dragY[j];
        return (float) Math.sqrt(dx * dx + dy * dy) / dt;
    }

    /** Throw speed in m/s from the flick: a lazy release lobs, a sharp one flies flat. */
    private float throwSpeed() {
        if (forcedSpeed > 0f) return forcedSpeed;
        return 8f + Math.min(1f, flick() / 650f) * 5.5f;
    }

    private float forcedSpeed = -1f;

    /** A throw without a finger, for the bot: aim at a screen point at a given speed. */
    boolean throwAt(float sx, float sy, float speed) {
        if (state != PLAY || hs != HAND || stars <= 0) return false;
        aimX = sx;
        aimY = sy;
        forcedSpeed = speed;
        throwHammer();
        forcedSpeed = -1f;
        return true;
    }

    /** Where the ring is now, for the desktop stand. */
    float[] aimPoint() {
        return new float[]{aimX, aimY};
    }

    /** Screen point of a world point, for the bot. */
    float[] screenOf(float x, float y, float z) {
        float[] o = new float[2];
        ras.project(x, y, z, o);
        return o;
    }

    private void throwHammer() {
        float speed = throwSpeed();
        target(aimX, aimY, tmp);
        float tx = tmp[0], ty = tmp[1], tz = tmp[2];
        handPoint(tmp);
        float sx = tmp[0], sy = tmp[1], sz = tmp[2];
        float dx = tx - sx, dy = ty - sy, dz = tz - sz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float t = Math.max(0.18f, dist / speed);
        hammer.ghost = false;
        hammer.asleep = false;
        float yaw = YAW + (fx.nextFloat() - 0.5f) * 0.6f;
        hammer.setPose(sx, sy, sz, (float) Math.cos(yaw / 2), 0f, (float) Math.sin(yaw / 2), 0f);
        hammer.vx = dx / t;
        hammer.vy = dy / t - 0.5f * Physics.G * t;
        hammer.vz = dz / t;
        spinSign = aimX < W * 0.5f ? 1f : -1f;
        hammer.wy = spinSign * 15f;
        hammer.wx = 0.6f;
        hammer.savePrev();
        flightTime = t;
        stars--;
        throwsMade++;
        offAtThrow = 1f - onPlinth();
        thundered = false;
        hs = FLY;
        hsTime = 0f;
        host.sound(Synth.WHOOSH, 0.9f, 0.9f + speed / 60f);
        host.buzz(12);
    }

    /** The world point under a screen point: the nearest piece, or the plane through the plinth. */
    void target(float sx, float sy, float[] out) {
        ras.ray(sx, sy, out);
        float dx = out[0], dy = out[1], dz = out[2];
        float best = 1e9f;
        for (Body b : pieces) {
            if (b.gone) continue;
            float t = rayBox(b, CAM_X, CAM_Y, 0f, dx, dy, dz);
            if (t > 0f && t < best) best = t;
        }
        if (best > 1e8f) {
            float t = rayBox(plinth, CAM_X, CAM_Y, 0f, dx, dy, dz);
            if (t > 0f) best = t;
        }
        if (best > 1e8f) {
            // the vertical plane through the plinth, a little short of the far end
            best = ras.depth(0f, CAM_Y, Builder.CZ);
            if (dy * best + CAM_Y < 0f && dy < 0f) best = -CAM_Y / dy;
        }
        out[0] = CAM_X + dx * best;
        out[1] = CAM_Y + dy * best;
        out[2] = dz * best;
    }

    /** Ray against an oriented box; distance along the ray or -1. */
    static float rayBox(Body b, float ox, float oy, float oz, float dx, float dy, float dz) {
        float px = ox - b.px, py = oy - b.py, pz = oz - b.pz;
        float lox = b.r00 * px + b.r10 * py + b.r20 * pz;
        float loy = b.r01 * px + b.r11 * py + b.r21 * pz;
        float loz = b.r02 * px + b.r12 * py + b.r22 * pz;
        float ldx = b.r00 * dx + b.r10 * dy + b.r20 * dz;
        float ldy = b.r01 * dx + b.r11 * dy + b.r21 * dz;
        float ldz = b.r02 * dx + b.r12 * dy + b.r22 * dz;
        float t0 = -1e9f, t1 = 1e9f;
        float[] o = {lox, loy, loz}, d = {ldx, ldy, ldz}, hh = {b.hx, b.hy, b.hz};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9f) {
                if (Math.abs(o[i]) > hh[i]) return -1f;
                continue;
            }
            float a = (-hh[i] - o[i]) / d[i], c = (hh[i] - o[i]) / d[i];
            if (a > c) {
                float s = a;
                a = c;
                c = s;
            }
            t0 = Math.max(t0, a);
            t1 = Math.min(t1, c);
            if (t0 > t1) return -1f;
        }
        return t0 > 0f ? t0 : (t1 > 0f ? 0.001f : -1f);
    }

    /** Where the hand holds the hammer, in the world. */
    private void handPoint(float[] out) {
        float vx = 0.3f, vy = -0.7f, vz = 1.35f;
        float bob = (float) Math.sin(clock * 1.7f) * 0.008f;
        vy += bob;
        ras.viewToWorld(vx, vy, vz, out);
        out[0] += CAM_X;
        out[1] += CAM_Y;
    }

    // ---- simulation ----

    void update(float dt) {
        clock += dt;
        stateTime += dt;
        // fixed physics steps, at most two a frame
        acc += dt;
        int steps = 0;
        while (acc >= 1f / 60f && steps < 2) {
            acc -= 1f / 60f;
            steps++;
            if (state != BUILD && state != VANISH) phys.step(1f / 60f);
            bursts();
        }
        if (acc > 1f / 30f) acc = 0f;
        particles(dt);
        puddles(dt);
        life.update(dt);
        trail();

        hammerMoves(dt);
        if (state == SKY_UP || state == SKY_HOLD || fromSky) {
            sky.update(dt);
            if (sky.bolts != boltsHeard) {
                boltsHeard = sky.bolts;
                host.sound(Synth.THUNDER, 1.0f, 0.9f + fx.nextFloat() * 0.2f);
                host.buzz(90);
            }
        }
        if (fromSky) {
            backT += dt;
            if (backT >= TILT + 0.2f) fromSky = false;
        }
        switch (state) {
            case BUILD:
                if (stateTime > buildDuration() + 0.15f) setState(PLAY);
                break;
            case PLAY:
                checkWin();
                if (state == PLAY && stars == 0 && hs == HAND && !aiming && (phys.settled() || stateTime > 3f))
                    setState(LOST);
                break;
            case WON:
                if (stateTime > 2.2f && hs == HAND && (phys.settled() || stateTime > 4.5f)) {
                    nextAfterVanish = 1;
                    lookUp(throwsMade <= 1 ? Sky.OVATION : throwsMade <= 3 ? Sky.CHEER : Sky.POLITE);
                }
                break;
            case LOST:
                if (stateTime > 0.7f) {
                    nextAfterVanish = 0;
                    lookUp(Sky.YAWN);
                }
                break;
            case SKY_UP:
                if (stateTime >= TILT) setState(SKY_HOLD);
                break;
            case SKY_HOLD:
                if (stateTime >= holdFor) {
                    // the gods have thrown the blocks: the next building falls into place as we look down
                    startLevel(level + nextAfterVanish, true);
                    fromSky = true;
                    buildFromSky = true;
                    backT = 0f;
                }
                break;
            case SKY_DOWN:
                sky.update(dt);
                if (stateTime >= TILT) setState(PLAY);
                break;
            case VANISH:
                if (stateTime > 0.5f) startLevel(level + nextAfterVanish, true);
                break;
            default:
                break;
        }
        if (state == BUILD) buildSounds();
    }

    private void hammerMoves(float dt) {
        hsTime += dt;
        if (hs == FLY) {
            if (hsTime > flightTime + 0.6f && (hammer.speed2() < 4f || hammer.py < 0.4f
                    || hammer.pz > Builder.CZ + 4f) || hsTime > flightTime + 2.2f || hammer.pz > 60f)
                beginReturn();
        } else if (hs == BACK) {
            float k = Math.min(1f, hsTime / 0.6f);
            handPoint(tmp);
            float e = k * k * (3f - 2f * k);
            hammer.px = retX + (tmp[0] - retX) * e;
            hammer.py = retY + (tmp[1] - retY) * e + (float) Math.sin(Math.PI * k) * 1.2f;
            hammer.pz = retZ + (tmp[2] - retZ) * e;
            hammer.rotateBy(0f, spinSign * 14f * dt, 0f);
            hammer.updateR();
            if (k >= 1f) {
                host.sound(Synth.CATCH, 0.8f, 1f);
                host.buzz(25);
                hs = HAND;
                hsTime = 0f;
                if (state == PLAY) stateTime = 0f;
            }
        }
    }

    // the hammer's wake: where its head has been over the last frames
    private static final int TRAIL = 14;
    private final float[] tX = new float[TRAIL], tY = new float[TRAIL], tZ = new float[TRAIL];
    private int tN;

    private void trail() {
        if (hs == HAND) {
            tN = 0;
            return;
        }
        if (tN == TRAIL) {
            System.arraycopy(tX, 1, tX, 0, TRAIL - 1);
            System.arraycopy(tY, 1, tY, 0, TRAIL - 1);
            System.arraycopy(tZ, 1, tZ, 0, TRAIL - 1);
            tN--;
        }
        Body b = hammer;
        tX[tN] = b.px + b.r00 * 0.31f;
        tY[tN] = b.py + b.r10 * 0.31f;
        tZ[tN] = b.pz + b.r20 * 0.31f;
        tN++;
    }

    private void drawTrail() {
        float moving = hs == FLY ? (float) Math.sqrt(hammer.speed2()) : 8f;
        if (moving < 3f) return;
        for (int i = 0; i < tN - 1; i++) {
            float vz = ras.project(tX[i], tY[i], tZ[i], scr);
            if (vz < 0.4f) continue;
            float k = (i + 1) / (float) tN;
            ras.haze(scr[0], scr[1], (0.1f + 0.16f * k) * ras.f / vz, 1f / vz, 0xFFFFF2D2, (int) (190 * k * k));
        }
    }

    private void beginReturn() {
        hammer.ghost = true;
        hammer.vx = hammer.vy = hammer.vz = hammer.wx = hammer.wy = hammer.wz = 0f;
        retX = hammer.px;
        retY = hammer.py;
        retZ = hammer.pz;
        hs = BACK;
        hsTime = 0f;
        host.sound(Synth.WHOOSH, 0.45f, 1.25f);
    }

    private void checkWin() {
        float off = 1f - onPlinth();
        if (!thundered && throwsMade > 0 && off - offAtThrow >= 0.5f) {
            thundered = true;
            host.sound(Synth.THUNDER, 1.1f, 1f);
            host.buzz(160);
        }
        if (off >= CLEAR && state != WON) {
            if (!thundered && throwsMade == 1) {
                thundered = true;
                host.sound(Synth.THUNDER, 1.1f, 1f);
                host.buzz(160);
            }
            wonAt = stars;
            cleared = true;
            setState(WON);
        }
    }

    int wonAt = -1;
    boolean cleared;            // this building has been cleared

    private void vanish() {
        for (Body b : pieces) {
            if (b.gone) continue;
            for (int i = 0; i < 3; i++) spawn(b.px, b.py, b.pz, true, 0xFFF4EFE4, 0.6f);
        }
        setState(VANISH);
    }

    /** For the desktop stand: the building counts as cleared after this many throws. */
    void standWin(int throwsCount) {
        throwsMade = throwsCount;
        cleared = true;
        setState(WON);
    }

    private void lookUp(int verdict) {
        sky.begin(verdict);
        holdFor = verdict == Sky.YAWN ? 2.2f : verdict == Sky.OVATION ? 3.2f : 2.4f;
        boltsHeard = sky.bolts;
        setState(SKY_UP);
    }

    /** How far the view is tipped up into the sky, in rows (0 = looking at the street). */
    private int tilt() {
        float k;
        if (state == SKY_UP) k = Math.min(1f, stateTime / TILT);
        else if (state == SKY_HOLD) k = 1f;
        else if (fromSky) k = 1f - Math.min(1f, backT / TILT);
        else return 0;
        k = k * k * (3f - 2f * k);
        return Math.round(k * h);
    }

    private float buildDuration() {
        int n = pieces.size();
        if (buildFromSky) {
            float end = 0f;
            for (int i = 0; i < n; i++) end = Math.max(end, skyAppear(i) + skyFall(i));
            return end;
        }
        return Math.min(0.05f, 1.7f / Math.max(1, n)) * n + 0.4f;
    }

    // pieces sent down by the gods: they appear 15, 10 or 5 meters up and fall under a brisk gravity
    private static final float SKY_G = 26f;
    private static final float[] SKY_H = {15f, 10f, 5f};

    private float skyHeight(int i) {
        return SKY_H[i % 3];
    }

    private float skyFall(int i) {
        return (float) Math.sqrt(2f * skyHeight(i) / SKY_G);
    }

    private float skyAppear(int i) {
        return 0.25f + i * Math.min(0.07f, 1.6f / Math.max(1, pieces.size()));
    }

    private float lastBuildTime;

    private float stagger() {
        return Math.min(0.05f, 1.7f / Math.max(1, pieces.size()));
    }

    private int lastLanded = -1;

    private void buildSounds() {
        if (buildFromSky) {
            for (int i = 0; i < pieces.size(); i++) {
                float land = skyAppear(i) + skyFall(i);
                if (land > lastBuildTime && land <= stateTime)
                    host.sound(Synth.TOCK, 0.18f + skyHeight(i) * 0.02f, 0.75f + fx.nextFloat() * 0.5f);
            }
            lastBuildTime = stateTime;
            return;
        }
        float st = stagger();
        int landed = (int) ((stateTime - 0.4f) / st);
        if (stateTime < 0.4f) landed = -1;
        landed = Math.min(landed, pieces.size() - 1);
        if (landed > lastLanded) {
            for (int i = lastLanded + 1; i <= landed; i++)
                if (i >= 0 && (i & 1) == 0) host.sound(Synth.TOCK, 0.22f, 0.8f + fx.nextFloat() * 0.5f);
            lastLanded = landed;
        }
        if (stateTime < 0.05f) lastLanded = -1;
    }

    @Override
    public void knock(Body a, Body b, float speed, float x, float y, float z) {
        boolean hammerHit = a == hammer || b == hammer;
        Body other = a == hammer ? b : a;
        // water bottles burst at a hard blow, or a hard landing
        if (a.material == Body.WATER && (speed > 4.2f || hammerHit && speed > 2.4f)) burst(a);
        if (b != null && b.material == Body.WATER && (speed > 4.2f || hammerHit && speed > 2.4f)) burst(b);
        boolean plastic = a.material == Body.WATER || (b != null && b.material == Body.WATER);
        float gain = Math.min(1f, (speed - 1f) / 5f);
        if (hammerHit) gain = Math.min(1.3f, gain * 1.4f + 0.2f);
        if (b == null || other == null) {
            if (speed > 1.6f) host.sound(Synth.GROUND, gain * 0.9f, 0.9f + fx.nextFloat() * 0.2f);
        } else {
            boolean stone = a.material == Body.STONE || b.material == Body.STONE || other.material == Body.PLINTH;
            float size = Math.max(other.hx, Math.max(other.hy, other.hz));
            float rate = Math.max(0.6f, Math.min(1.5f, 0.32f / size)) * (0.92f + fx.nextFloat() * 0.16f);
            host.sound(plastic ? Synth.PLASTIC : stone ? Synth.STONE : Synth.WOOD, gain, plastic ? 0.9f + fx.nextFloat() * 0.2f : rate);
        }
        if (hammerHit) host.buzz((int) Math.min(60f, 10f + speed * 3f));
        if (speed > 2.2f && other != null && other.material != Body.WATER) {
            int n = (int) Math.min(14f, speed * 1.2f);
            boolean dusty = other.material == Body.STONE || other.material == Body.PLINTH;
            for (int i = 0; i < n; i++)
                spawn(x, y, z, dusty && (i & 1) == 0, dusty ? 0xFFE6DCC8 : Backdrop.scale(other.tint, 0.8f), speed * 0.35f);
        } else if (speed > 3f && b == null) {
            for (int i = 0; i < 4; i++) spawn(x, 0.02f, z, true, 0xFFD8CFC0, 1f);
        }
    }

    // ---- water ----

    private final ArrayList<Body> toBurst = new ArrayList<Body>();
    private static final int PUDDLES = 24;
    private final float[] pdX = new float[PUDDLES], pdY = new float[PUDDLES], pdZ = new float[PUDDLES];
    private final float[] pdR = new float[PUDDLES], pdAge = new float[PUDDLES];
    private final int[] pdSeed = new int[PUDDLES];
    private final boolean[] pdWall = new boolean[PUDDLES];
    private int pdN;

    private void burst(Body b) {
        if (b.gone || toBurst.contains(b)) return;
        toBurst.add(b);
    }

    /** Bottles hit hard enough this step: gone in a splash, leaving a puddle. */
    private void bursts() {
        if (toBurst.isEmpty()) return;
        for (Body b : toBurst) {
            b.gone = true;
            b.ghost = true;
            // whatever leaned on it must notice it is gone
            float e = 0.05f;
            for (Body o : pieces)
                if (o.asleep && !(o.maxX + e < b.minX || b.maxX + e < o.minX || o.maxY + e < b.minY
                        || b.maxY + e < o.minY || o.maxZ + e < b.minZ || b.maxZ + e < o.minZ)) phys.wake(o);
            for (int i = 0; i < 120; i++) spawnWater(b.px, b.py, b.pz, b.vx, b.vy, b.vz);
            for (int i = 0; i < 6; i++) spawn(b.px, b.py, b.pz, true, 0xFFF2F8FF, 1.6f);
            boolean onTop = Math.abs(b.px) < Builder.PHX && Math.abs(b.pz - Builder.CZ) < Builder.PHZ
                    && b.py > Builder.TOP - 0.2f;
            int top = addPuddle(b.px, onTop ? Builder.TOP : 0.002f, b.pz, 1.5f + fx.nextFloat() * 0.6f);
            if (onTop) {
                // it pours over the front edge, runs down the face and pools at its foot
                pdWall[top] = true;
                int foot = addPuddle(b.px + (fx.nextFloat() - 0.5f) * 0.6f, 0.002f, Builder.CZ - Builder.PHZ - 0.7f,
                        1.3f + fx.nextFloat() * 0.5f);
                pdAge[foot] = -0.5f;
            }
            host.sound(Synth.SPLASH, 1.1f, 0.9f + fx.nextFloat() * 0.2f);
            host.buzz(40);
        }
        toBurst.clear();
    }

    private int addPuddle(float x, float y, float z, float r) {
        int i = pdN < PUDDLES ? pdN++ : fx.nextInt(PUDDLES);
        pdX[i] = x;
        pdY[i] = y;
        pdZ[i] = z;
        pdR[i] = r;
        pdAge[i] = 0f;
        pdSeed[i] = fx.nextInt(1000);
        pdWall[i] = false;
        return i;
    }

    private void puddles(float dt) {
        for (int i = 0; i < pdN; i++) {
            pdAge[i] += dt;
            if (pdAge[i] > 32f) {
                pdN--;
                pdX[i] = pdX[pdN];
                pdY[i] = pdY[pdN];
                pdZ[i] = pdZ[pdN];
                pdR[i] = pdR[pdN];
                pdAge[i] = pdAge[pdN];
                pdSeed[i] = pdSeed[pdN];
                pdWall[i] = pdWall[pdN];
                i--;
            }
        }
    }

    private void drawPuddles() {
        for (int i = 0; i < pdN; i++) {
            if (pdAge[i] < 0f) continue;
            float grow = Math.min(1f, pdAge[i] / 0.6f);
            grow = 1f - (1f - grow) * (1f - grow);
            float fade = pdAge[i] < 22f ? 1f : 1f - (pdAge[i] - 22f) / 10f;
            ras.puddle(pdX[i], pdY[i], pdZ[i], pdR[i] * (0.3f + 0.7f * grow), 0.9f * fade, pdSeed[i]);
            if (pdWall[i]) ras.wetWall(pdX[i], Builder.CZ - Builder.PHZ - 0.001f, Builder.TOP, pdR[i] * 0.55f,
                    Builder.TOP * Math.min(1f, pdAge[i] / 0.8f), 0.85f * fade, pdSeed[i]);
        }
    }

    private void spawnWater(float x, float y, float z, float vx, float vy, float vz) {
        if (pN >= MAXP) return;
        int i = pN++;
        float a = (float) (fx.nextFloat() * Math.PI * 2);
        float s = 1.5f + fx.nextFloat() * 4.2f;
        pX[i] = x + (float) Math.cos(a) * 0.15f;
        pY[i] = y;
        pZ[i] = z + (float) Math.sin(a) * 0.15f;
        pVx[i] = (float) Math.cos(a) * s + vx * 0.4f;
        pVz[i] = (float) Math.sin(a) * s + vz * 0.4f;
        pVy[i] = 1.8f + fx.nextFloat() * 4.6f + vy * 0.3f;
        pLife[i] = 0.7f + fx.nextFloat() * 0.7f;
        pAge[i] = 0f;
        pCol[i] = fx.nextInt(4) == 0 ? 0xFFFFFFFF : 0xFFBFE2F7;
        pDust[i] = false;
    }

    // ---- particles ----

    private void spawn(float x, float y, float z, boolean dust, int color, float kick) {
        if (pN >= MAXP) return;
        int i = pN++;
        pX[i] = x;
        pY[i] = y;
        pZ[i] = z;
        float a = (float) (fx.nextFloat() * Math.PI * 2);
        float s = kick * (0.4f + fx.nextFloat());
        pVx[i] = (float) Math.cos(a) * s;
        pVz[i] = (float) Math.sin(a) * s;
        pVy[i] = dust ? 0.3f + fx.nextFloat() * 0.4f : kick * (0.5f + fx.nextFloat());
        pLife[i] = dust ? 0.9f + fx.nextFloat() * 0.6f : 1.4f + fx.nextFloat();
        pAge[i] = 0f;
        pCol[i] = color;
        pDust[i] = dust;
    }

    private void particles(float dt) {
        for (int i = 0; i < pN; i++) {
            pAge[i] += dt;
            if (pAge[i] >= pLife[i]) {
                pN--;
                pX[i] = pX[pN];
                pY[i] = pY[pN];
                pZ[i] = pZ[pN];
                pVx[i] = pVx[pN];
                pVy[i] = pVy[pN];
                pVz[i] = pVz[pN];
                pLife[i] = pLife[pN];
                pAge[i] = pAge[pN];
                pCol[i] = pCol[pN];
                pDust[i] = pDust[pN];
                i--;
                continue;
            }
            if (pDust[i]) {
                pVx[i] *= 1f - 2f * dt;
                pVz[i] *= 1f - 2f * dt;
                pVy[i] *= 1f - 1.5f * dt;
            } else {
                pVy[i] += Physics.G * dt;
            }
            pX[i] += pVx[i] * dt;
            pY[i] += pVy[i] * dt;
            pZ[i] += pVz[i] * dt;
            float floor = Math.abs(pX[i]) < Builder.PHX && Math.abs(pZ[i] - Builder.CZ) < Builder.PHZ ? Builder.TOP : 0f;
            if (pY[i] < floor && !pDust[i]) {
                pY[i] = floor;
                pVy[i] = -pVy[i] * 0.3f;
                pVx[i] *= 0.6f;
                pVz[i] *= 0.6f;
            }
        }
    }

    // ---- drawing ----

    void draw(Px f) {
        if (ras == null) return;
        System.arraycopy(bgCol, 0, ras.col, 0, bgCol.length);
        System.arraycopy(bgZ, 0, ras.zb, 0, bgZ.length);
        System.arraycopy(bgLit, 0, ras.lit, 0, bgLit.length);
        java.util.Arrays.fill(ras.idb, 0);

        drawBody(plinth, 1f, 0f, 1);
        float st = stagger();
        ras.shadowBegin(6f, Builder.CZ + 1f, 12f);
        ras.shadowBox(plinth.px, plinth.py, plinth.pz, 1, 0, 0, 0, 1, 0, 0, 0, 1, plinth.hx, plinth.hy, plinth.hz);
        for (int i = 0; i < pieces.size(); i++) {
            Body b = pieces.get(i);
            float scale = 1f, drop = 0f;
            if (state == BUILD && buildFromSky) {
                // out of nothing, high above, and down into place
                float t = stateTime - skyAppear(i);
                if (t < 0f) continue;
                float fall = skyFall(i);
                if (t < fall) {
                    drop = skyHeight(i) - 0.5f * SKY_G * t * t;
                    scale = Math.min(1f, t / 0.12f);
                }
            } else if (state == BUILD) {
                float t = stateTime - 0.4f - i * st;
                if (t < -0.35f) continue;
                if (t < 0f) {
                    float k = (t + 0.35f) / 0.35f;
                    drop = (1f - k * k) * 4f;
                }
            } else if (state == VANISH) {
                float k = Math.min(1f, stateTime / 0.35f);
                scale = (1f - k) * (1f - k);
                if (scale < 0.02f) continue;
            }
            if (b.gone) continue;
            if (b.material == Body.WATER) drawBottle(b, scale, drop, i + 2);
            else drawBody(b, scale, drop, i + 2);
            ras.shadowBox(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    b.hx * scale, b.hy * scale, b.hz * scale);
        }
        drawPuddles();
        life.draw(ras);
        drawTrail();
        drawHammer();
        drawParticles();
        // shadows fall only near the plinth: work on that part of the screen
        float minX = W, minY = h, maxX = 0, maxY = 0;
        for (int i = 0; i < 8; i++) {
            ras.project((i & 1) == 0 ? -4f : 17f, (i & 2) == 0 ? 0f : 7f,
                    Builder.CZ + ((i & 4) == 0 ? -4f : 7f), scr);
            minX = Math.min(minX, scr[0]);
            maxX = Math.max(maxX, scr[0]);
            minY = Math.min(minY, scr[1]);
            maxY = Math.max(maxY, scr[1]);
        }
        ras.shadowApply((int) minX, (int) minY, (int) maxX + 1, (int) maxY + 1);
        ras.outlines(0, (int) Math.max(0, minY - 40), W - 1, h - 1, 1);
        if (aiming) drawAim();
        int off = tilt();
        if (off > 0) {
            sky.compose(ras.col, f.a, h, off);
            sky.drawClouds(f, off - h);
            if (off < h / 8) drawHud(f);
        } else {
            System.arraycopy(ras.col, 0, f.a, 0, Math.min(f.a.length, ras.col.length));
            drawHud(f);
        }
    }

    private void drawBody(Body b, float scale, float drop, int id) {
        int pat = b.material == Body.STONE ? Raster.PAT_STONE : b.material == Body.PLINTH ? Raster.PAT_STONE : Raster.PAT_GRAIN;
        int tint = b.tint;
        for (int k = 0; k < 1; k++)
            ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    b.hx * scale, b.hy * scale, b.hz * scale, tint, pat, id << 3);
    }

    // ---- a water bottle: see-through plastic round a column of water ----

    private static final int SIDES = 12;
    private static final float[] COS = new float[SIDES + 1], SIN = new float[SIDES + 1];

    static {
        for (int i = 0; i <= SIDES; i++) {
            COS[i] = (float) Math.cos(i * Math.PI * 2 / SIDES);
            SIN[i] = (float) Math.sin(i * Math.PI * 2 / SIDES);
        }
    }

    private final float[] qx = new float[SIDES], qy = new float[SIDES], qz = new float[SIDES];
    private final float[] fLx = new float[4], fLy = new float[4], fLz = new float[4];
    private static final float[] BY = {-1f, -0.92f, 0.42f, 0.74f, 0.78f, 0.9f, 1f};
    private static final float[] BR = {0.92f, 1f, 1f, 0.38f, 0.36f, 0.42f, 0.42f};
    private static final int[] BCOL = {0xFF7FB9E6, 0xFF8CC6EE, 0xFF9FD0F0, 0xFFA8D6F2, 0xFF2F7FD0, 0xFF2F7FD0};
    private static final int[] BALPHA = {150, 105, 120, 130, 255, 255};

    private void drawBottle(Body b, float scale, float drop, int id) {
        float r = b.hx * scale, hh = b.hy * scale;
        // the profile: (height as a share of the half-height, radius as a share of r)
        float[] ys = BY, rs = BR;
        int[] cols = BCOL, alpha = BALPHA;
        float px = b.px, py = b.py + drop, pz = b.pz;
        for (int pass = 0; pass < 2; pass++) {
            for (int seg = 0; seg < ys.length - 1; seg++) {
                for (int i = 0; i < SIDES; i++) {
                    float ca = COS[i], sa = SIN[i], cb = COS[i + 1], sb = SIN[i + 1];
                    float y0 = ys[seg] * hh, y1 = ys[seg + 1] * hh, r0 = rs[seg] * r, r1 = rs[seg + 1] * r;
                    // the outward normal at the middle of the facet, in the body frame
                    float mc = (ca + cb) * 0.5f, ms = (sa + sb) * 0.5f;
                    float slope = (r0 - r1) / Math.max(1e-4f, y1 - y0);
                    float nlx = mc, nly = slope * 0.7f, nlz = ms;
                    float nl = (float) Math.sqrt(nlx * nlx + nly * nly + nlz * nlz);
                    nlx /= nl;
                    nly /= nl;
                    nlz /= nl;
                    float nx = b.r00 * nlx + b.r01 * nly + b.r02 * nlz;
                    float ny = b.r10 * nlx + b.r11 * nly + b.r12 * nlz;
                    float nz = b.r20 * nlx + b.r21 * nly + b.r22 * nlz;
                    float fxw = px + b.r00 * (mc * r0) + b.r01 * y0 + b.r02 * (ms * r0);
                    float fyw = py + b.r10 * (mc * r0) + b.r11 * y0 + b.r12 * (ms * r0);
                    float fzw = pz + b.r20 * (mc * r0) + b.r21 * y0 + b.r22 * (ms * r0);
                    float vx = ras.camX - fxw, vy = ras.camY - fyw, vz = ras.camZ - fzw;
                    boolean front = vx * nx + vy * ny + vz * nz > 0f;
                    // back facets first, through the plastic; then the near ones on top
                    if (front != (pass == 1)) continue;
                    float ndl = nx * Raster.sunX() + ny * Raster.sunY() + nz * Raster.sunZ();
                    int c = Raster.light(cols[seg], nx, ny, nz, ndl);
                    // a glossy streak where the sun glances off toward the eye
                    float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    float hx2 = Raster.sunX() + vx / vl, hy2 = Raster.sunY() + vy / vl, hz2 = Raster.sunZ() + vz / vl;
                    float hl = (float) Math.sqrt(hx2 * hx2 + hy2 * hy2 + hz2 * hz2);
                    float spec = Math.max(0f, (nx * hx2 + ny * hy2 + nz * hz2) / hl);
                    spec = spec * spec * spec * spec * spec * spec * spec * spec;
                    c = Backdrop.mix(c, 0xFFFFFFFF, spec * 0.85f);
                    // ribs on the body: a little darker every few centimeters
                    if (seg == 2 && (i & 1) == 0) c = Backdrop.scale(c, 0.94f);
                    float[] lx = fLx, ly = fLy, lz = fLz;
                    lx[0] = ca * r0;
                    lx[1] = cb * r0;
                    lx[2] = cb * r1;
                    lx[3] = ca * r1;
                    ly[0] = y0;
                    ly[1] = y0;
                    ly[2] = y1;
                    ly[3] = y1;
                    lz[0] = sa * r0;
                    lz[1] = sb * r0;
                    lz[2] = sb * r1;
                    lz[3] = sa * r1;
                    for (int k = 0; k < 4; k++) {
                        qx[k] = px + b.r00 * lx[k] + b.r01 * ly[k] + b.r02 * lz[k];
                        qy[k] = py + b.r10 * lx[k] + b.r11 * ly[k] + b.r12 * lz[k];
                        qz[k] = pz + b.r20 * lx[k] + b.r21 * ly[k] + b.r22 * lz[k];
                    }
                    ras.alpha = front ? alpha[seg] : alpha[seg] * 3 / 4;
                    ras.depthWrite = front;
                    ras.poly(qx, qy, qz, null, null, 4, c, Raster.PAT_FLAT, id << 3, false);
                }
            }
        }
        // the cap's top
        ras.alpha = 255;
        ras.depthWrite = true;
        float yTop = hh, rc = 0.42f * r;
        for (int i = 0; i < SIDES; i++) {
            qx[i] = px + b.r00 * COS[i] * rc + b.r01 * yTop + b.r02 * SIN[i] * rc;
            qy[i] = py + b.r10 * COS[i] * rc + b.r11 * yTop + b.r12 * SIN[i] * rc;
            qz[i] = pz + b.r20 * COS[i] * rc + b.r21 * yTop + b.r22 * SIN[i] * rc;
        }
        ras.poly(qx, qy, qz, null, null, SIDES, Raster.light(0xFF3C8AD8, b.r01, b.r11, b.r21,
                b.r01 * Raster.sunX() + b.r11 * Raster.sunY() + b.r21 * Raster.sunZ()), Raster.PAT_FLAT, id << 3, false);
    }

    // hammer parts in the hammer's own frame: center, half sizes, color, pattern
    private static final float[][] PARTS = {
            {-0.1f, 0f, 0f, 0.31f, 0.03f, 0.03f},       // handle
            {-0.36f, 0f, 0f, 0.07f, 0.036f, 0.036f},    // grip
            {0.31f, 0f, 0f, 0.1f, 0.078f, 0.17f},       // head
            {0.31f, 0f, 0.12f, 0.104f, 0.082f, 0.016f}, // hoops
            {0.31f, 0f, -0.12f, 0.104f, 0.082f, 0.016f},
    };
    private static final int[] PART_COL = {0xFF9A6638, 0xFF6E3F27, 0xFFDFA868, 0xFF55524F, 0xFF55524F};
    private static final int[] PART_PAT = {Raster.PAT_GRAIN, Raster.PAT_FLAT, Raster.PAT_GRAIN, Raster.PAT_FLAT, Raster.PAT_FLAT};

    private void drawHammer() {
        float px, py, pz;
        float r00, r01, r02, r10, r11, r12, r20, r21, r22;
        if (hs == HAND) {
            handPoint(tmp);
            px = tmp[0];
            py = tmp[1];
            pz = tmp[2];
            // held upright, head up and to the left, tilted toward the street
            float lift = aiming ? 0.06f : 0f;
            py += lift;
            ras.viewToWorld(-0.42f, 0.86f, 0.28f, dirA);
            float ax = dirA[0], ay = dirA[1], az = dirA[2];
            float l = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            ax /= l;
            ay /= l;
            az /= l;
            // z axis of the head: across the view
            ras.viewToWorld(0.94f, 0.2f, -0.28f, dirA);
            float zx = dirA[0], zy = dirA[1], zz = dirA[2];
            float d = zx * ax + zy * ay + zz * az;
            zx -= d * ax;
            zy -= d * ay;
            zz -= d * az;
            l = (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
            zx /= l;
            zy /= l;
            zz /= l;
            float yx = zy * az - zz * ay, yy = zz * ax - zx * az, yz = zx * ay - zy * ax;
            r00 = ax;
            r10 = ay;
            r20 = az;
            r01 = yx;
            r11 = yy;
            r21 = yz;
            r02 = zx;
            r12 = zy;
            r22 = zz;
            // the handle runs down off the screen: move the hammer along its own axis
            px -= ax * 0.05f;
            py -= ay * 0.05f;
            pz -= az * 0.05f;
        } else {
            Body b = hammer;
            px = b.px;
            py = b.py;
            pz = b.pz;
            r00 = b.r00;
            r01 = b.r01;
            r02 = b.r02;
            r10 = b.r10;
            r11 = b.r11;
            r12 = b.r12;
            r20 = b.r20;
            r21 = b.r21;
            r22 = b.r22;
            ras.shadowBox(px, py, pz, r00, r01, r02, r10, r11, r12, r20, r21, r22, b.hx, b.hy * 0.8f, b.hz);
        }
        for (int i = 0; i < PARTS.length; i++) {
            float[] p = PARTS[i];
            float cx = px + r00 * p[0] + r01 * p[1] + r02 * p[2];
            float cy = py + r10 * p[0] + r11 * p[1] + r12 * p[2];
            float cz = pz + r20 * p[0] + r21 * p[1] + r22 * p[2];
            ras.box(cx, cy, cz, r00, r01, r02, r10, r11, r12, r20, r21, r22, p[3], p[4], p[5], PART_COL[i], PART_PAT[i],
                    (900 + (i == 2 ? 1 : i > 2 ? 2 + i : 0)) << 3);
        }
    }

    private void drawParticles() {
        for (int i = 0; i < pN; i++) {
            float vz = ras.project(pX[i], pY[i], pZ[i], scr);
            if (vz < 0.3f) continue;
            float k = pAge[i] / pLife[i];
            if (pDust[i]) {
                float r = (0.12f + 0.5f * k) * ras.f / vz;
                ras.haze(scr[0], scr[1], r, 1f / vz, pCol[i], (int) (150 * (1f - k)));
            } else {
                float r = Math.max(0.6f, 0.03f * ras.f / vz);
                ras.disc(scr[0], scr[1], r, 1f / vz, pCol[i], 950 << 3);
            }
        }
    }

    /** The aim: a ring where the hammer will land and the arc it will take. */
    private void drawAim() {
        float speed = throwSpeed();
        target(aimX, aimY, tmp);
        float tx = tmp[0], ty = tmp[1], tz = tmp[2];
        handPoint(tmp);
        float sx = tmp[0], sy = tmp[1], sz = tmp[2];
        float dx = tx - sx, dy = ty - sy, dz = tz - sz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float t = Math.max(0.18f, dist / speed);
        float vx = dx / t, vy = dy / t - 0.5f * Physics.G * t, vzv = dz / t;
        for (int i = 3; i < 22; i++) {
            float q = t * i / 22f;
            float x = sx + vx * q, y = sy + vy * q + 0.5f * Physics.G * q * q, z = sz + vzv * q;
            float vz = ras.project(x, y, z, scr);
            if (vz < 0.2f) continue;
            dot(Math.round(scr[0]), Math.round(scr[1]), (i & 1) == 0 ? 0xFFFFF6D8 : 0xFFF2C14E);
        }
        int cx = Math.round(aimX), cy = Math.round(aimY);
        ring(cx, cy, 6, 0xFFFFF6D8);
        ring(cx, cy, 5, 0xFFF2B33A);
    }

    private void dot(int x, int y, int c) {
        for (int j = -1; j <= 0; j++)
            for (int i = -1; i <= 0; i++) put(x + i, y + j, c);
    }

    private void ring(int cx, int cy, int r, int c) {
        for (int a = 0; a < 48; a++) {
            double t = a * Math.PI * 2 / 48;
            put(cx + (int) Math.round(Math.cos(t) * r), cy + (int) Math.round(Math.sin(t) * r), c);
        }
    }

    private void put(int x, int y, int c) {
        if (x < 0 || y < 0 || x >= W || y >= h) return;
        ras.col[y * W + x] = c;
    }

    // ---- HUD ----

    private void drawHud(Px f) {
        int x = 6, y = 8;
        for (int i = 0; i < STARS; i++) {
            boolean on = i < stars;
            boolean flash = state == WON && on && ((int) (stateTime * 6) & 1) == 0;
            star(f, x + i * 15 + 6, y + 6, 6.6f, on ? (flash ? 0xFFFFFFFF : 0xFFFFC93A) : 0x00000000, 0xFF8A5A12, on);
        }
        // weight still on the plinth, with the line it has to fall below
        float on = onPlinth();
        int bw = 58, bx = W - bw - 7, by = 9;
        f.rect(bx - 1, by - 1, bx + bw + 1, by + 7, 0xFF8A5A12);
        f.rect(bx, by, bx + bw, by + 6, 0xFFF3E9D2);
        int fill = Math.round(bw * on);
        f.rect(bx, by, bx + fill, by + 6, on <= 1f - CLEAR ? 0xFF6FBF5A : 0xFFE8963C);
        int mark = bx + Math.round(bw * (1f - CLEAR));
        f.rect(mark, by - 3, mark + 1, by + 9, 0xFF5A3A0E);
        String lv = "LEVEL " + (level + 1);
        PixelFont.draw(f, lv, bx + bw - PixelFont.width(lv, 1), by + 12, 1, 0xFF6A4A1A);
        String v = "V" + VERSION;
        PixelFont.draw(f, v, W - PixelFont.width(v, 1) - 3, h - 10, 1, 0xFF8A8478);
    }

    /** A five-pointed star; hollow when spent. */
    private void star(Px f, int cx, int cy, float r, int fill, int edge, boolean full) {
        int R = (int) Math.ceil(r) + 1;
        for (int y = -R; y <= R; y++)
            for (int x = -R; x <= R; x++) {
                float d = starDist(x + 0.0f, y + 0.0f, r);
                if (d < -1.1f) {
                    if (full) f.set(cx + x, cy + y, shadeStar(fill, x, y, r));
                } else if (d < 0.2f) {
                    f.set(cx + x, cy + y, edge);
                }
            }
    }

    private static int shadeStar(int c, int x, int y, float r) {
        float k = 1.08f - 0.25f * (x + y) / (2f * r);
        return Backdrop.scale(c, k);
    }

    /** Signed distance-like value for a star outline: negative inside. */
    private static float starDist(float x, float y, float r) {
        double a = Math.atan2(x, -y);
        double seg = Math.PI * 2 / 5;
        double m = ((a % seg) + seg) % seg - seg / 2;
        double inner = r * 0.48;
        // radius of the star outline at this angle, linear between tip and notch
        double t = Math.abs(m) / (seg / 2);
        double edge = r + (inner - r) * t;
        return (float) (Math.sqrt(x * x + y * y) - edge);
    }
}
