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
    static final String VERSION = "0.34";   // shown on screen; keep in step with versionName

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
        /** Heavy throws the gods have given and the player has not used yet. */
        void saveGifts(int heavy);
        int loadGifts();
        /** A small number kept between games, by name; 0 when never kept. */
        void saveInt(String key, int value);
        int loadInt(String key);
        /** Whether the player has ever been given the golden hammer. */
        void saveGoldSeen(boolean seen);
        boolean loadGoldSeen();
        /** How many times running the building of the saved level has been lost. */
        void saveFails(int fails);
        int loadFails();
        /** A file shipped with the game, or null. */
        byte[] asset(String name);
    }

    private final Host host;
    int h = 1;                             // current height in game pixels

    // scene
    private Raster ras;                    // the camera being drawn with: the usual one, or the zoomed one
    private Raster normRas, zoomRas;
    // the zoom for far plinths: while the throw is aimed and flies, the view closes in on the
    // building. The zoomed view has its own camera and its own painted street behind
    private int[] zCol;
    private float[] zZ;
    private byte[] zLit;
    private float zoomFor = -1f, zoomZ = 1f, zoomK, zPX, zPY;
    private boolean zoomed;
    static final float ZOOM_T = 0.3f;        // seconds to close in, and back
    private int[] bgCol;
    private float[] bgZ;
    private byte[] bgLit;
    private final Backdrop backdrop = new Backdrop();
    final Physics phys = new Physics();
    private Body plinth;
    private final Body[] cars = new Body[Life.parked()];

    // ---- the golem ----
    // It comes for a player stuck on one building a second time (the first time brings the
    // golden hammer), and at every 25th level. The gods give a diamond hammer for the fight.
    static final int ARMOR = 3;
    Golem golem;
    int armor;
    private boolean bossNext, diamondGift, fromBoss, bossWon;
    private float bossEnd = -1f, throwIn, flash;
    static final int FLY_MAX = 4;
    private final Body[] fly = new Body[FLY_MAX];
    private final int[] flyCar = new int[FLY_MAX], flyState = new int[FLY_MAX];   // 1 lifted, 2 thrown, 3 falling
    private final float[] flyT = new float[FLY_MAX];
    private final float[] carHitAt = new float[Life.parked()];
    final ArrayList<Body> pieces = new ArrayList<Body>();
    float totalMass;

    // camera
    static final float CAM_X = -2.3f, CAM_Y = 1.8f, PITCH = 0.0f;
    // the camera stands in the left lane and looks across at the plinth: the street runs at a slant
    static final float YAW = (float) Math.atan2(-CAM_X, Builder.BASE_CZ);
    private float focal;

    // the game
    static final int BUILD = 0, PLAY = 1, WON = 2, LOST = 3, VANISH = 4, SKY_UP = 5, SKY_HOLD = 6, SKY_DOWN = 7, BOSS = 8;
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
    // the ring starts on the building (or where the last throw went) and follows the finger
    private float lastX, lastY, baseX, baseY;
    // the strength is a wind-up: while the finger rests, the hammer draws back and the throw
    // gathers strength; held past the peak, the arm tires and the ring begins to shake
    static final float CHARGE = 0.9f;        // seconds of stillness to full strength
    private float power, fatigue, tremX, tremY;
    private float anchorX, anchorY, movedAt;
    private boolean fullFelt;

    // the heavy hammer: the gods give a few heavy throws for a building felled at one throw.
    // A tap on the hammer in hand (or on the marks under the stars) takes it up or puts it down
    static final int HEAVY_GIFT = 3;
    static final int HEAVY_MAX = 9;           // gifts add up, as far as the marks fit
    static final float HEAVY_MASS = 2.5f;     // times the light one
    int heavyLeft;
    boolean heavyOn;
    private boolean heavyThrow;               // the throw in flight is a heavy one
    private boolean tapOnHammer;
    private float downAt;
    private float giftShown = -1f;            // when the gift was announced in the sky
    // the golden hammer: lose the same building ten times running and the gods give one
    // throw that scatters it whatever it hits, as the very first building did
    static final int GOLD_AFTER = 10;
    int fails;
    boolean goldOn;
    private boolean goldGift, goldThrow;
    // once the golden hammer has been given, now and then a golden cube crowns a building.
    // It never falls to the street: whatever happens, it comes to rest on the plinth
    boolean goldSeen;
    Body goldCube;
    static final float CUBE_CHANCE = 0.25f;
    private boolean aimedBefore;
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
        if (host.loadInt("best") < level + 1) host.saveInt("best", level + 1);
        heavyLeft = Math.max(0, Math.min(HEAVY_MAX, host.loadGifts()));
        fails = Math.max(0, host.loadFails());
        goldSeen = host.loadGoldSeen();
        phys.listener = this;
    }

    void resize(int height) {
        if (height == h && ras != null) return;
        h = height;
        ras = normRas = new Raster(W, h);
        zoomRas = null;
        zoomFor = -1f;
        zoomed = false;
        zoomK = 0f;
        focal = W * 1.9f;
        ras.camera(CAM_X, CAM_Y, 0f, YAW, PITCH, focal, h * 0.5f);
        backdrop.paint(ras, 11L);
        bgCol = ras.col.clone();
        bgZ = ras.zb.clone();
        bgLit = ras.lit.clone();
        sky.build(ras, host.asset("gods.bin"), host.asset("clouds.bin"));
        if (firstBuild) {
            firstBuild = false;
            startLevel(level, true);
        } else prepareZoom();
    }

    // ---- levels ----

    static long seedOf(int level) {
        return level * 2654435761L ^ 0x2545F4914F6CDD1DL;
    }

    // the next building, grown on a thread of its own while the gods are watching
    private volatile ArrayList<Body> prepared;
    private int preparedFor = -1;
    private Thread preparing;

    private void prepare(final int lv) {
        if (preparedFor == lv) return;
        preparedFor = lv;
        prepared = null;
        preparing = new Thread(new Runnable() {
            @Override
            public void run() {
                prepared = buildingFor(lv);
            }
        });
        preparing.setDaemon(true);
        preparing.start();
    }

    private ArrayList<Body> takePrepared(int lv) {
        if (preparedFor != lv || preparing == null) return null;
        try {
            preparing.join();
        } catch (InterruptedException e) {
            return null;
        }
        ArrayList<Body> r = prepared;
        prepared = null;
        preparing = null;
        preparedFor = -1;
        return r;
    }

    /** Raises the building for a level: tested for standing still, asleep, ready to fall. */
    void startLevel(int lv, boolean animate) {
        // the farthest level reached is kept before the level changes: going back must not lose it
        int far = Math.max(host.loadInt("best"), Math.max(level + 1, lv + 1));
        if (far != host.loadInt("best")) host.saveInt("best", far);
        if (lv != level) {
            fails = 0;
            host.saveFails(0);
        }
        level = lv;
        goldOn = fails >= GOLD_AFTER;
        host.saveLevel(level);
        seed = seedOf(lv);
        if (zoomed) setZoomed(false);
        zoomK = 0f;
        Builder.CZ = Builder.BASE_CZ + Builder.farther(lv);
        prepareZoom();
        phys.clear();
        pieces.clear();
        plinth = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ, Body.PLINTH, 0xFFF2E3C6);
        plinth.setPose(0f, Builder.TOP * 0.5f, Builder.CZ, 1, 0, 0, 0);
        phys.add(plinth);
        golem = null;
        for (int i = 0; i < FLY_MAX; i++) flyState[i] = 0;
        life.park();
        // the parked cars, as solid as the plinth: the hammer and the blocks bounce off them, and dent them
        for (int i = 0; i < cars.length; i++) {
            cars[i] = new Body(0.95f, 0.65f, 2.3f, Body.CAR, 0);
            cars[i].setPose(life.parkX(i), 0.65f, life.parkZ(i), 1, 0, 0, 0);
            phys.add(cars[i]);
        }
        ArrayList<Body> made = takePrepared(lv);
        if (made == null) made = buildingFor(lv);
        // grown at the first plinth spot: carried along to where the plinth stands now
        float shift = Builder.CZ - Builder.BASE_CZ;
        for (Body b : made) b.homeZ += shift;
        totalMass = 0f;
        for (Body b : made) {
            b.setPose(b.homeX, b.homeY, b.homeZ, 1, 0, 0, 0);
            b.asleep = true;
            phys.add(b);
            pieces.add(b);
            totalMass += b.mass;
        }
        goldCube = null;
        if (goldSeen && lv > 0 && new java.util.Random(seed ^ 0x601DL).nextFloat() < CUBE_CHANCE) crown(made);
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
        blasted = false;
        blastNow = false;
        cleared = false;
        hs = HAND;
        heavyThrow = false;
        if (heavyLeft == 0) heavyOn = false;
        pN = 0;
        setState(animate ? BUILD : PLAY);
    }

    /** Tries seeds until the building stands still on its own for a second. */
    /** A building for a level, tested and ready, laid out at the first plinth spot. Thread-safe. */
    static ArrayList<Body> buildingFor(int lv) {
        return standingBuilding(new Builder(), lv, seedOf(lv));
    }

    private static ArrayList<Body> standingBuilding(Builder builder, int lv, long sd) {
        if (Builder.kindOf(lv) == Builder.GROWN) return grownBuilding(builder, lv, sd);
        for (int attempt = 0; attempt < 6; attempt++) {
            ArrayList<Body> made = builder.build(lv, sd + attempt * 7919L);
            if (standsStill(made)) return made;
        }
        return builder.build(0, sd);
    }

    /**
     * How hard a grown building should be: the share of its weight one full throw at its
     * middle should knock off. Early on most of it; later on a fifth.
     */
    static float wantedShare(int lv) {
        return Math.max(0.2f, Math.min(0.75f, 0.85f - 0.012f * (lv + 1)));
    }

    /** Grows candidates until one stands and is about as hard as this level wants. */
    private static ArrayList<Body> grownBuilding(Builder builder, int lv, long sd) {
        float want = wantedShare(lv), bestGap = 9f;
        ArrayList<Body> best = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            ArrayList<Body> made = builder.build(lv, sd + attempt * 7919L);
            if (!standsStill(made)) continue;
            float gap = Math.abs(probe(made) - want);
            if (gap < bestGap) {
                bestGap = gap;
                best = made;
            }
            if (gap < 0.12f) break;
        }
        return best != null ? best : builder.build(0, sd);
    }

    /**
     * One full throw at the middle of a building, played out in a physics of its own:
     * the share of its weight that ends up off the plinth. The building is put back after.
     */
    static float probe(ArrayList<Body> made) {
        Physics p = new Physics();
        p.sleeping = false;
        Body pl = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ, Body.PLINTH, 0);
        pl.setPose(0f, Builder.TOP * 0.5f, Builder.BASE_CZ, 1, 0, 0, 0);
        p.add(pl);
        float mx = 0f, my = 0f, mz = 0f, m = 0f, front = 1e9f;
        for (Body b : made) {
            b.setPose(b.homeX, b.homeY, b.homeZ, 1, 0, 0, 0);
            b.asleep = false;
            p.add(b);
            mx += b.px * b.mass;
            my += b.py * b.mass;
            m += b.mass;
            front = Math.min(front, b.minZ);
        }
        mx /= m;
        my /= m;
        mz = front;
        Body hm = new Body(0.42f, 0.08f, 0.17f, Body.HAMMER, 0);
        float sx = CAM_X + 0.6f, sy = CAM_Y - 0.75f, sz = 1.2f;
        hm.setPose(sx, sy, sz, 1, 0, 0, 0);
        float dx = mx - sx, dy = my - sy, dz = mz - sz;
        float t = Math.max(0.18f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 13.5f);
        hm.vx = dx / t;
        hm.vy = dy / t - 0.5f * Physics.G * t;
        hm.vz = dz / t;
        hm.wy = 15f;
        p.add(hm);
        hm.savePrev();
        int steps = Math.round((t + 1.6f) * 60f);
        for (int i = 0; i < steps; i++) p.step(1f / 60f);
        float on = 0f;
        for (Body b : made)
            if (Math.abs(b.px) < Builder.PHX + 0.05f && Math.abs(b.pz - Builder.BASE_CZ) < Builder.PHZ + 0.05f
                    && b.py > Builder.TOP - 0.05f && b.py < Builder.TOP + 8f) on += b.mass;
        for (Body b : made) {
            b.setPose(b.homeX, b.homeY, b.homeZ, 1, 0, 0, 0);
            b.id = 0;
        }
        return 1f - on / m;
    }

    static boolean standsStill(ArrayList<Body> made) {
        Physics p = new Physics();
        p.sleeping = false;
        Body pl = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ, Body.PLINTH, 0);
        pl.setPose(0f, Builder.TOP * 0.5f, Builder.BASE_CZ, 1, 0, 0, 0);
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
            // settling by a few centimeters is not falling: nothing may tip, nothing may wander far
            if (dx * dx + dy * dy + dz * dz > 0.09f * 0.09f || (b.radius == 0f && Math.abs(b.qw) < 0.995f)) ok = false;
        }
        // the building starts as it settled, so it does not sag once the game begins
        if (ok)
            for (Body b : made) {
                b.homeX = b.px;
                b.homeY = b.py;
                b.homeZ = b.pz;
            }
        for (Body b : made) b.id = 0;
        return ok;
    }

    private void setState(int s) {
        state = s;
        stateTime = 0f;
    }

    /** Sets the golden cube on the highest flat top of the building; it does not count as weight. */
    private void crown(ArrayList<Body> made) {
        Body top = null;
        for (Body b : made)
            if (b.radius == 0f && b.material != Body.WATER && b.hx > 0.16f && b.hz > 0.12f
                    && (top == null || b.maxY > top.maxY)) top = b;
        if (top == null) return;
        float c = 0.19f;
        // nothing may already stand on it
        for (Body b : made)
            if (b != top && b.minY > top.maxY - 0.02f && Math.abs(b.px - top.px) < top.hx + b.hx) return;
        Body g = new Body(c, c, c, Body.GOLD, 0xFFF2C230);
        g.setPose(top.px, top.maxY + c + 0.002f, top.pz, 1, 0, 0, 0);
        g.homeX = g.px;
        g.homeY = g.py;
        g.homeZ = g.pz;
        g.asleep = true;
        phys.add(g);
        pieces.add(g);
        goldCube = g;
    }

    /** The golden cube's one law: it stays over the plinth, so it can only come down onto it. */
    private void keepGold() {
        Body g = goldCube;
        if (g == null) return;
        float mx = Builder.PHX - g.hx * 0.9f, z0 = Builder.CZ - Builder.PHZ + g.hz * 0.9f, z1 = Builder.CZ + Builder.PHZ - g.hz * 0.9f;
        boolean moved = false;
        if (g.px < -mx || g.px > mx) {
            g.px = Math.max(-mx, Math.min(mx, g.px));
            g.vx = -g.vx * 0.2f;
            moved = true;
        }
        if (g.pz < z0 || g.pz > z1) {
            g.pz = Math.max(z0, Math.min(z1, g.pz));
            g.vz = -g.vz * 0.2f;
            moved = true;
        }
        if (g.py < Builder.TOP + g.hy * 0.5f) {
            g.py = Builder.TOP + g.hy;
            g.vy = Math.max(0f, g.vy);
            moved = true;
        }
        if (moved) {
            g.asleep = false;
            g.updateR();
            g.savePrev();
        }
    }

    /** Weight still on the plinth, as a share of the building. */
    float onPlinth() {
        float m = 0f;
        for (Body b : pieces)
            if (!b.gone && b != goldCube && Math.abs(b.px) < Builder.PHX + 0.05f && Math.abs(b.pz - Builder.CZ) < Builder.PHZ + 0.05f
                    && b.py > Builder.TOP - 0.05f) m += b.mass;
        return totalMass > 0f ? m / totalMass : 0f;
    }

    // ---- input ----

    void touch(int type, float x, float y) {
        if (booth >= 0f) {
            boothTouch(type, x, y);
            return;
        }
        if (type == DOWN && state == PLAY && hs == HAND && onCallButton(x, y)) {
            call();
            return;
        }
        if (type == DOWN) {
            tapX = x;
            tapY = y;
            tapFar = 0f;
        } else tapFar = Math.max(tapFar, (float) Math.hypot(x - tapX, y - tapY));
        if (type == DOWN) {
            boolean fight = state == BOSS && bossEnd < 0f;
            if (!fight && (state != PLAY || stars <= 0) || hs != HAND) return;
            aiming = true;
            lastX = x;
            lastY = y;
            if (!aimedBefore) buildingCenter();
            else {
                baseX = aimX;
                baseY = aimY;
            }
            aimX = baseX;
            aimY = baseY;
            downAt = clock;
            tapOnHammer = golem == null && heavyLeft > 0 && onHammer(x, y);
            anchorX = x;
            anchorY = y;
            movedAt = clock;
            power = 0f;
            fatigue = 0f;
            fullFelt = false;
        }
        if (!aiming) return;
        if (type != DOWN) {
            aimX = Math.max(2f, Math.min(W - 3f, aimX + x - lastX));
            aimY = Math.max(2f, Math.min(h - 3f, aimY + y - lastY));
        }
        lastX = x;
        lastY = y;
        // a finger never lies quite still: only a real move breaks the wind-up
        float mx = x - anchorX, my = y - anchorY;
        if (mx * mx + my * my > 2.5f * 2.5f) {
            anchorX = x;
            anchorY = y;
            movedAt = clock;
        }
        if (type == UP) {
            aiming = false;
            // a short tap on the hammer, without a move, swaps hammers instead of throwing
            if (tapOnHammer && clock - downAt < 0.6f && tapFar < 6f) {
                heavyOn = !heavyOn;
                power = 0f;
                host.sound(Synth.CATCH, 0.6f, heavyOn ? 0.7f : 1.2f);
                host.buzz(heavyOn ? 30 : 10);
                return;
            }
            aimedBefore = true;
            if (golem != null) bossThrow();
            else throwHammer();
        }
    }

    /** Where the ring starts on a level's first throw: the middle of what still stands on the plinth. */
    private void buildingCenter() {
        if (golem != null) {
            golem.chest(tmp);
            ras.project(tmp[0], tmp[1], tmp[2], scr);
            baseX = scr[0];
            baseY = scr[1];
            return;
        }
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
        if (booth >= 0f) closeBooth();
        else host.quit();
    }

    /** Where the hammer in hand is on screen, the other one at the lower left, and the gift marks. */
    private boolean onHammer(float x, float y) {
        return (x > W * 0.58f && y > h * 0.7f) || (x < W * 0.34f && y > h * 0.8f) || (x < 130f && y < 32f);
    }

    /**
     * Throw speed in m/s from the wind-up: a quick release lobs, a full one flies flat.
     * The heavy hammer leaves the hand slower and tops out lower, but carries far more weight.
     */
    private float throwSpeed() {
        if (forcedSpeed > 0f) return forcedSpeed;
        return heavyOn ? 7.5f + power * 4.5f : 8f + power * 5.5f;
    }

    /**
     * The zoomed camera for this plinth, if it is far enough to need one: it looks straight
     * at the building, narrower by the ratio of distances, and the street behind is painted
     * for it once (again only when the plinth moves).
     */
    private void prepareZoom() {
        if (normRas == null) return;
        if (Builder.CZ - Builder.BASE_CZ < 2.5f) {
            zoomZ = 1f;
            return;
        }
        if (Builder.CZ == zoomFor) return;
        zoomFor = Builder.CZ;
        float lookY = Builder.TOP + 1.4f, dx = -CAM_X, dz = Builder.CZ;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.atan2(dx, dz), pitch = (float) Math.atan2(lookY - CAM_Y, dist);
        zoomZ = Math.max(1.25f, Math.min(3.4f, Builder.CZ / Builder.BASE_CZ * 0.92f));
        if (zoomRas == null) zoomRas = new Raster(W, h);
        zoomRas.camera(CAM_X, CAM_Y, 0f, yaw, pitch, focal * zoomZ, h * 0.5f);
        backdrop.paint(zoomRas, 11L);
        zCol = zoomRas.col.clone();
        zZ = zoomRas.zb.clone();
        zLit = zoomRas.lit.clone();
        // where the zoomed view's middle sits in the usual view: the closing-in heads there
        normRas.project(0f, lookY, Builder.CZ, scr);
        zPX = scr[0];
        zPY = scr[1];
    }

    /** Swaps cameras, carrying the aim over so the ring stays on the same spot of the world. */
    private void setZoomed(boolean on) {
        if (on == zoomed) return;
        zoomed = on;
        ras = on ? zoomRas : normRas;
        if (on) {
            aimX = (aimX - zPX) * zoomZ + W * 0.5f;
            aimY = (aimY - zPY) * zoomZ + h * 0.5f;
            baseX = (baseX - zPX) * zoomZ + W * 0.5f;
            baseY = (baseY - zPY) * zoomZ + h * 0.5f;
        } else {
            aimX = (aimX - W * 0.5f) / zoomZ + zPX;
            aimY = (aimY - h * 0.5f) / zoomZ + zPY;
            baseX = (baseX - W * 0.5f) / zoomZ + zPX;
            baseY = (baseY - h * 0.5f) / zoomZ + zPY;
        }
    }

    /** Closes in while a throw is aimed and in flight, opens out once the hammer is back. */
    private void zoomStep(float dt) {
        boolean want = zoomZ > 1f && zoomRas != null && state == PLAY && (aiming || hs != HAND);
        zoomK = Math.max(0f, Math.min(1f, zoomK + (want ? dt : -dt) / ZOOM_T));
        if (zoomK >= 1f) setZoomed(true);
        else setZoomed(false);
    }

    /** The usual view, enlarged toward the zoomed one: the way in and out between the two cameras. */
    private void closingIn(Px f) {
        float e = zoomK * zoomK * (3f - 2f * zoomK);
        float zk = 1f + (zoomZ - 1f) * e;
        float cx = W * 0.5f + (zPX - W * 0.5f) * e, cy = h * 0.5f + (zPY - h * 0.5f) * e;
        int[] src = ras.col;
        for (int y = 0; y < h; y++) {
            int sy = (int) (cy + (y + 0.5f - h * 0.5f) / zk);
            if (sy < 0) sy = 0;
            if (sy >= h) sy = h - 1;
            int row = sy * W, out = y * W;
            for (int x = 0; x < W; x++) {
                int sx = (int) (cx + (x + 0.5f - W * 0.5f) / zk);
                if (sx < 0) sx = 0;
                if (sx >= W) sx = W - 1;
                f.a[out + x] = src[row + sx];
            }
        }
    }

    /** The wind-up, frame by frame: strength while the finger rests, then a tiring arm. */
    private void windUp(float dt) {
        if (!aiming) {
            tremX = tremY = 0f;
            return;
        }
        boolean still = clock - movedAt > 0.1f;
        if (still) power = Math.min(1f, power + dt / (heavyOn ? CHARGE * 1.5f : CHARGE));
        else power = Math.max(0f, power - dt * 2f);
        if (power >= 1f) {
            fatigue += dt;
            if (!fullFelt) {
                fullFelt = true;
                host.buzz(12);
            }
        } else {
            fatigue = Math.max(0f, fatigue - dt * 2f);
            if (power < 0.9f) fullFelt = false;
        }
        float amp = Math.max(0f, Math.min(8f, (fatigue - 0.35f) * 5f));
        tremX = amp * ((float) Math.sin(clock * 13f) * 0.6f + (float) Math.sin(clock * 7.3f + 1f) * 0.4f);
        tremY = amp * ((float) Math.sin(clock * 11f + 2f) * 0.6f + (float) Math.sin(clock * 5.9f) * 0.4f);
    }

    private float forcedSpeed = -1f;

    /** A throw without a finger, for the bot: aim at a screen point at a given speed. */
    boolean throwAt(float sx, float sy, float speed) {
        if (state != PLAY || hs != HAND || stars <= 0) return false;
        aimX = sx;
        aimY = sy;
        tremX = tremY = 0f;
        forcedSpeed = speed;
        throwHammer();
        forcedSpeed = -1f;
        return true;
    }

    /** How badly the parked cars are damaged, all told, for the desktop stand. */
    int carDamage() {
        int n = 0;
        for (int i = 0; i < life.damage.length; i++) n += life.damage[i];
        return n;
    }

    // ---- the call box ----
    // On the lamp post to the right a call box with a red button. Each press rings the
    // dispatcher; three in a row and a phone booth opens: inside, a button for every level
    // reached, and one at the bottom that calls the golem.

    static final float CALL_Z = 16f, CALL_Y = 1.45f, CALL_X = 4.55f - 0.2f;
    private float tapX, tapY, tapFar;
    private int calls;
    private float callAt = -9f;
    float booth = -1f;                   // seconds since the booth opened, or -1 when shut

    int best() {
        return Math.max(level + 1, host.loadInt("best"));
    }

    private boolean onCallButton(float x, float y) {
        float vz = ras.project(CALL_X, CALL_Y, CALL_Z, scr);
        if (vz < 0.5f) return false;
        return Math.hypot(x - scr[0], y - scr[1]) < 11f;
    }

    private void call() {
        if (clock - callAt > 6f) calls = 0;
        calls++;
        callAt = clock;
        host.sound(Synth.RING, 0.9f, 1f);
        host.buzz(20);
        if (calls >= 3) {
            calls = 0;
            booth = 0f;
            dialled.setLength(0);
            host.sound(Synth.DOOR, 1f, 1f);
        }
    }

    private void closeBooth() {
        booth = -1f;
        host.sound(Synth.DOOR, 0.8f, 1.15f);
    }

    /** The button on the post: red, and lit for a moment after each press. */
    private void drawCallButton() {
        float vz = ras.project(CALL_X, CALL_Y, CALL_Z, scr);
        if (vz < 0.5f) return;
        boolean lit = clock - callAt < 1.1f && ((int) ((clock - callAt) * 8f) & 1) == 0;
        float r = Math.max(1.6f, 0.1f * ras.f / vz);
        ras.disc(scr[0], scr[1], r + 0.6f, 1f / (vz - 0.25f), 0xFF3A3C40, 990 << 3);
        ras.disc(scr[0], scr[1], r, 1f / (vz - 0.3f), lit ? 0xFFFF6A4A : 0xFFC8231E, 990 << 3);
        ras.disc(scr[0] - r * 0.3f, scr[1] - r * 0.35f, Math.max(0.6f, r * 0.3f), 1f / (vz - 0.32f), 0xFFFFB0A0, 990 << 3);
    }

    // the booth on screen: doors, glass, and a telephone inside. The level is dialled on
    // its keypad and called; the red button below calls the golem; the hook hangs up.
    private static final int BX0 = 18, BX1 = World.W - 18;
    private final StringBuilder dialled = new StringBuilder();
    private float wrongAt = -9f, keyAt = -9f;
    private int keyDown = -1;
    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "CALL"};

    private int boothTop() {
        return 44;
    }

    // the keypad: three columns, four rows
    private static final int KW = 64, KH = 60, KG = 9;

    private int keysX() {
        return (W - (3 * KW + 2 * KG)) / 2;
    }

    private int keysY() {
        return boothTop() + 112;
    }

    private int golemY() {
        return keysY() + 4 * (KH + KG) + 22;
    }

    private void boothTouch(int type, float x, float y) {
        if (booth < 0.6f || type != DOWN) return;
        boothKey(x, y);
    }

    private void boothKey(float x, float y) {
        int kx = keysX(), ky = keysY();
        int col = (int) Math.floor((x - kx) / (KW + KG)), row = (int) Math.floor((y - ky) / (KH + KG));
        if (col >= 0 && col < 3 && row >= 0 && row < 4 && x - kx - col * (KW + KG) < KW && y - ky - row * (KH + KG) < KH) {
            int k = row * 3 + col;
            keyDown = k;
            keyAt = booth;
            host.sound(Synth.DTMF + k, 0.8f, 1f);
            host.buzz(8);
            String key = KEYS[k];
            if (key.equals("C")) dialled.setLength(Math.max(0, dialled.length() - 1));
            else if (key.equals("CALL")) {
                int lv = dialled.length() == 0 ? -1 : Integer.parseInt(dialled.toString());
                if (lv < 2 || lv > best()) {
                    // no such number: the line says so in three rising beeps, the display shakes
                    wrongAt = booth;
                    dialled.setLength(0);
                    host.sound(Synth.BUSY, 0.7f, 1f);
                    host.buzz(30);
                    return;
                }
                dialled.setLength(0);
                booth = -1f;
                host.sound(Synth.RING, 0.7f, 1.1f);
                startLevel(lv - 1, true);
            } else if (dialled.length() < 3) dialled.append(key);
            return;
        }
        int gy = golemY();
        if (y >= gy && y < gy + 34 && x >= BX0 + 14 && x < BX1 - 14) {
            dialled.setLength(0);
            booth = -1f;
            host.sound(Synth.ROAR, 0.8f, 1f);
            startBoss(level);
            return;
        }
        // the hook: hang up and go back to the street
        if (y >= gy + 42 && y < gy + 70) {
            dialled.setLength(0);
            closeBooth();
        }
    }

    // pictures for the keys, a cell to a character
    private static final String[] HANDSET = {
            ".XXXXXXX.",
            "XXXXXXXXX",
            "XXX...XXX",
            "XX.....XX"};
    private static final String[] ERASE = {
            "...X.....",
            "..XX.....",
            ".XXXXXXXX",
            "..XX.....",
            "...X....."};

    /** A little picture of cells, centered on a point. */
    private static void glyph(Px f, String[] rows, int cx, int cy, int s, int c) {
        int x0 = cx - rows[0].length() * s / 2, y0 = cy - rows.length * s / 2;
        for (int j = 0; j < rows.length; j++)
            for (int i = 0; i < rows[j].length(); i++)
                if (rows[j].charAt(i) == 'X') f.rect(x0 + i * s, y0 + j * s, x0 + (i + 1) * s, y0 + (j + 1) * s, c);
    }

    private void drawBooth(Px f) {
        for (int k = 0; k < f.a.length; k++) f.a[k] = Backdrop.mix(f.a[k], 0xFF101418, 0.55f);
        int top = boothTop(), bot = h - 10;
        // the booth: painted iron, a lit sign on the roof
        f.rect(BX0 - 6, top - 22, BX1 + 6, bot, 0xFF5E1612);
        f.rect(BX0 - 10, top - 30, BX1 + 10, top - 20, 0xFF4A100C);
        f.rect(W / 2 - 40, top - 19, W / 2 + 40, top - 7, 0xFF2A2420);
        glyph(f, HANDSET, W / 2, top - 13, 2, 0xFFFFE6A0);
        f.rect(BX0, top, BX1, bot - 6, 0xFF2A2E33);
        // the telephone: a steel case, a small green display
        int cx0 = BX0 + 8, cx1 = BX1 - 8;
        f.rect(cx0, top + 8, cx1, golemY() - 6, 0xFF8E959C);
        f.rect(cx0 + 2, top + 10, cx1 - 2, golemY() - 8, 0xFFA8AFB6);
        int dy = top + 24;
        f.rect(cx0 + 12, dy, cx1 - 12, dy + 52, 0xFF1A1E16);
        f.rect(cx0 + 14, dy + 2, cx1 - 14, dy + 50, 0xFF9DB86A);
        float since = booth - wrongAt;
        boolean wrong = since < 1f;
        int ink = 0xFF243014;
        if (wrong) {
            // a number that is not there: the display blinks dark and shakes its dashes
            boolean dark = ((int) (since * 10f) & 1) == 0;
            if (dark) f.rect(cx0 + 14, dy + 2, cx1 - 14, dy + 50, 0xFF5E7040);
            int sh = Math.round((float) Math.sin(since * 60f) * 4f * (1f - since));
            for (int i = -1; i <= 1; i++) f.rect(W / 2 + i * 22 - 7 + sh, dy + 24, W / 2 + i * 22 + 7 + sh, dy + 28, ink);
        } else {
            if (dialled.length() > 0) {
                String shown = dialled.toString();
                PixelFont.draw(f, shown, W / 2 - PixelFont.width(shown, 3) / 2, dy + 8, 3, ink);
            } else if (((int) (booth * 2f) & 1) == 0) f.rect(W / 2 - 7, dy + 26, W / 2 + 7, dy + 29, ink);
            // the numbers that answer: from the second to the farthest reached
            String up = "2-" + best();
            PixelFont.draw(f, up, W / 2 - PixelFont.width(up, 1) / 2, dy + 38, 1, 0xFF4A5E2A);
        }
        // the keypad: steel keys, the pressed one sunk for a moment
        int kx = keysX(), ky = keysY();
        for (int k = 0; k < 12; k++) {
            int bx = kx + (k % 3) * (KW + KG), by = ky + (k / 3) * (KH + KG);
            boolean pressed = k == keyDown && booth - keyAt < 0.15f;
            boolean call = k == 11, clear = k == 9;
            int face = call ? 0xFF4CA04C : clear ? 0xFFC8B070 : 0xFFDCE0E4;
            if (pressed) face = Backdrop.scale(face, 0.8f);
            f.rect(bx, by + 3, bx + KW, by + KH + 3, 0xFF5A5F64);
            int o = pressed ? 2 : 0;
            f.rect(bx, by + o, bx + KW, by + KH + o, 0xFF6A7076);
            f.rect(bx + 2, by + 2 + o, bx + KW - 2, by + KH - 2 + o, face);
            if (call) glyph(f, HANDSET, bx + KW / 2, by + KH / 2 + o, 4, 0xFFF2F8EC);
            else if (clear) glyph(f, ERASE, bx + KW / 2, by + KH / 2 + o, 4, 0xFF2A2420);
            else PixelFont.draw(f, KEYS[k], bx + KW / 2 - PixelFont.width(KEYS[k], 2) / 2, by + KH / 2 - 6 + o, 2, 0xFF2A2420);
        }
        // the golem's red button, and the hook to hang up on
        int gy = golemY();
        f.rect(BX0 + 14, gy, BX1 - 14, gy + 34, 0xFF3A1A10);
        f.rect(BX0 + 16, gy + 2, BX1 - 16, gy + 32, ((int) (clock * 3f + booth * 3f) & 1) == 0 ? 0xFFD8402A : 0xFFB8301E);
        PixelFont.draw(f, "GOLEM", W / 2 - PixelFont.width("GOLEM", 2) / 2, gy + 10, 2, 0xFFFFE8D8);
        f.rect(W / 2 - 46, gy + 46, W / 2 + 46, gy + 66, 0xFF1E1C1A);
        f.rect(W / 2 - 40, gy + 42, W / 2 + 40, gy + 50, 0xFF2E2C2A);
        PixelFont.draw(f, "HOME", W / 2 - PixelFont.width("HOME", 1) / 2, gy + 54, 1, 0xFFE8E2D6);
        // the doors fold away to the sides as it opens
        float k = Math.min(1f, booth / 0.6f);
        int half = (BX1 - BX0) / 2, open = Math.round(half * k * k * (3f - 2f * k));
        for (int side = 0; side < 2; side++) {
            int a = side == 0 ? BX0 : W / 2 + open, b = side == 0 ? W / 2 - open : BX1;
            if (b <= a) continue;
            f.rect(a, top, b, bot - 6, 0xFF8E2018);
            for (int py = top + 6; py < bot - 12; py += 26)
                for (int px = a + 4; px < b - 4; px += 1)
                    for (int yy = py; yy < Math.min(bot - 12, py + 20); yy++)
                        f.a[yy * W + px] = Backdrop.mix(f.a[yy * W + px], 0xFFAFC8D2, 0.35f);
        }
    }

    // ---- the fight with the golem ----

    /** For the desktop stand's player: the screen point it should throw at now, or null. */
    float[] standPick(boolean cars) {
        if (golem == null || bossEnd >= 0f) return null;
        int best = -1;
        float bz = 1e9f;
        for (int i = 0; i < FLY_MAX; i++)
            if (cars && flyState[i] == 2 && fly[i].pz < bz && flyT[i] > 0.35f) {
                bz = fly[i].pz;
                best = i;
            }
        float[] o = new float[3];
        if (best >= 0) {
            ras.project(fly[best].px, fly[best].py, fly[best].pz, o);
            o[2] = 1f;
            return o;
        }
        if (golem.kneeL > 0) golem.knee(-1, tmp);
        else if (golem.kneeR > 0) golem.knee(1, tmp);
        else golem.headCenter(tmp);
        ras.project(tmp[0], tmp[1], tmp[2], o);
        o[2] = 0f;
        return o;
    }

    /** For the desktop stand: where the call button is on screen. */
    float[] standCallButton() {
        float[] o = new float[2];
        ras.project(CALL_X, CALL_Y, CALL_Z, o);
        return o;
    }

    void standGolemAt(float z) {
        if (golem != null) golem.z = z;
    }

    void standBossThrow(float sx, float sy) {
        if (hs != HAND || golem == null || bossEnd >= 0f) return;
        aimX = sx;
        aimY = sy;
        bossThrow();
    }

    /** For the desktop stand: 0 fighting, 1 won, -1 lost; and how it stands. */
    int bossResult() {
        return golem == null ? (level > 0 ? 1 : 0) : bossEnd < 0f ? 0 : bossWon ? 1 : -1;
    }

    String bossState() {
        return golem == null ? "-" : String.format("golem z %.1f knees %d%d head %d armor %d",
                golem.z, golem.kneeL, golem.kneeR, golem.head, armor);
    }

    /** For the desktop stand: the golem comes at once, for this level. */
    void standBoss() {
        startBoss(level);
    }

    private void startBoss(int lv) {
        bossNext = false;
        level = lv;
        host.saveLevel(lv);
        if (zoomed) setZoomed(false);
        zoomK = 0f;
        Builder.CZ = Builder.BASE_CZ + Builder.farther(lv);
        phys.clear();
        pieces.clear();
        life.park();
        hammer = new Body(0.42f, 0.08f, 0.17f, Body.HAMMER, 0xFFD9A066);
        hammer.ghost = true;
        hammer.asleep = false;
        phys.add(hammer);
        hs = HAND;
        aiming = false;
        aimedBefore = false;
        heavyThrow = false;
        goldThrow = false;
        goldOn = false;
        stars = STARS;
        golem = new Golem(0.4f, START_Z);
        armor = ARMOR;
        throwIn = 2.2f;
        shake = 0f;
        landingsSeen = 0;
        beatIn = 0f;
        host.sound(Synth.ROAR, 1f, 1f);
        flash = 0f;
        bossEnd = -1f;
        bossWon = false;
        for (int i = 0; i < FLY_MAX; i++) flyState[i] = 0;
        pN = 0;
        setState(BOSS);
    }

    static final float START_Z = 46f;
    private float shake, beatIn;
    private int landingsSeen;

    /** How far the golem has come, 0 at its start, 1 at the player. */
    private float progress() {
        return Math.max(0f, Math.min(1f, (START_Z - golem.z) / (START_Z - 8f)));
    }

    /** A thrown car about to arrive: time runs slow, so the player can still answer it. */
    private boolean closeCall() {
        if (golem == null || state != BOSS || bossEnd >= 0f) return false;
        for (int i = 0; i < FLY_MAX; i++) if (flyState[i] == 2 && fly[i].pz < 13f && fly[i].vz < 0f) return true;
        return false;
    }

    private void bossStep(float dt) {
        golem.update(dt, progress());
        flash = Math.max(0f, flash - dt * 2.2f);
        shake = Math.max(0f, shake - dt * 2.5f);
        // it comes down: the street shakes, dust flies
        if (golem.landings != landingsSeen) {
            landingsSeen = golem.landings;
            float near = progress();
            shake = Math.max(shake, 0.5f + near * 0.6f);
            host.sound(Synth.GROUND, 1.4f, 0.45f);
            host.sound(Synth.CRASH, 0.5f + near * 0.4f, 0.45f);
            host.buzz((int) (60 + near * 120));
            for (int k = 0; k < 30; k++) {
                float a = k * 0.2094f;
                spawn(golem.x + (float) Math.cos(a) * 2.5f, 0.15f, golem.z + (float) Math.sin(a) * 2.5f, true, 0xFFD8CFC0, 4f);
            }
            if (fx.nextFloat() < 0.35f) host.sound(Synth.ROAR, 0.8f, 0.9f + fx.nextFloat() * 0.2f);
        }
        // on the last armor the heart is heard
        if (armor == 0 && bossEnd < 0f) {
            beatIn -= dt;
            if (beatIn <= 0f) {
                beatIn = 0.75f;
                host.sound(Synth.BEAT, 1.1f, 1f);
                host.buzz(20);
            }
        }
        boolean holding = false;
        for (int i = 0; i < FLY_MAX; i++) if (flyState[i] == 1) holding = true;
        golem.holding = holding;
        golem.raise = holding ? golem.raise : Math.max(0f, golem.raise - dt * 2f);
        if (bossEnd >= 0f) {
            bossEnd += dt;
            flights(dt);
            if (bossWon) {
                // the pieces jump and the ground trembles, a while after it falls
                rumble -= dt;
                if (rumble <= 0f && bossEnd < 2.4f) {
                    rumble = 0.32f + fx.nextFloat() * 0.15f;
                    shake = Math.max(shake, 0.55f);
                    host.sound(Synth.GROUND, 1.2f, 0.5f + fx.nextFloat() * 0.2f);
                    host.buzz(70);
                    for (Body b : pieces) {
                        if (b.gone || b.py > 3f || fx.nextFloat() < 0.4f) continue;
                        b.vy += 2.5f + fx.nextFloat() * 3f;
                        b.vx += (fx.nextFloat() - 0.5f) * 1.5f;
                        b.wx += (fx.nextFloat() - 0.5f) * 6f;
                        b.asleep = false;
                    }
                }
                if (bossEnd > 2.8f) finishBoss();
            } else if (bossEnd > 1.3f) wakeUp();
            return;
        }
        // throwing: a car at a time, more often the nearer it comes
        if (!holding && golem.stagger <= 0f) {
            throwIn -= dt;
            if (throwIn <= 0f) pickCar();
        }
        flights(dt);
        if (hs == FLY) bossHits();
        if (golem.arrived()) {
            // it has reached the player: a sweep of its arm and the fight is lost
            flash = 1f;
            host.sound(Synth.CRASH, 1f, 0.6f);
            host.buzz(300);
            endBoss(false);
        }
    }

    /** Tears the parked car nearest the golem from its place and lifts it to its hand. */
    private void pickCar() {
        int slot = -1;
        for (int i = 0; i < FLY_MAX; i++) if (flyState[i] == 0) slot = i;
        int best = -1;
        float bd = 1e9f;
        for (int i = 0; i < Life.parked(); i++) {
            if (!life.parkedHere(i)) continue;
            float cz = life.parkZ(i);
            if (cz < 9f || cz > golem.z + 7f || cz < golem.z - 20f) continue;
            float d = Math.abs(cz - golem.z) + Math.abs(life.parkX(i) - golem.x) * 0.5f;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        if (slot < 0 || best < 0) {
            throwIn = 1.5f;
            return;
        }
        life.lift(best);
        Body c = new Body(0.95f, 0.65f, 2.3f, Body.WOOD, life.parkColor(best));
        float yaw = life.parkYaw(best);
        c.setPose(life.parkX(best), 0.65f, life.parkZ(best), (float) Math.cos(yaw / 2), 0f, (float) Math.sin(yaw / 2), 0f);
        c.homeX = c.px;
        c.homeY = c.py;
        c.homeZ = c.pz;
        fly[slot] = c;
        flyCar[slot] = best;
        flyState[slot] = 1;
        flyT[slot] = 0f;
        host.sound(Synth.CRASH, 0.5f, 0.7f);
        host.sound(Synth.ALARM, 0.35f, 0.9f);
        float p = progress();
        throwIn = 4.0f - 2.6f * p;
    }

    static final float LIFT_T = 0.9f;

    private void flights(float dt) {
        for (int i = 0; i < FLY_MAX; i++) {
            Body c = fly[i];
            int st = flyState[i];
            if (st == 0) continue;
            flyT[i] += dt;
            if (st == 1) {
                // up into the golem's raised hand
                float k = Math.min(1f, flyT[i] / LIFT_T);
                float e = k * k * (3f - 2f * k);
                golem.raise = e;
                golem.hand(tmp);
                c.px = c.homeX + (tmp[0] - c.homeX) * e;
                c.py = c.homeY + (tmp[1] + 0.3f - c.homeY) * e + (float) Math.sin(Math.PI * k) * 1.5f;
                c.pz = c.homeZ + (tmp[2] - c.homeZ) * e;
                c.rotateBy(0.8f * dt, 0.3f * dt, 0f);
                c.updateR();
                if (k >= 1f && bossEnd < 0f) {
                    // and away at the player, high and hard
                    float p = progress(), t = 2.4f - 0.8f * p;
                    float tx = CAM_X + 0.3f, ty = 1.4f, tz = 1.0f;
                    c.vx = (tx - c.px) / t;
                    c.vz = (tz - c.pz) / t;
                    c.vy = (ty - c.py) / t - 0.5f * Physics.G * t;
                    c.wx = (fx.nextFloat() - 0.5f) * 3f;
                    c.wy = (fx.nextFloat() - 0.5f) * 3f;
                    c.wz = (fx.nextFloat() - 0.5f) * 2f;
                    flyState[i] = 2;
                    flyT[i] = 0f;
                    host.sound(Synth.WHOOSH, 1f, 0.55f);
                } else if (k >= 1f) {
                    flyState[i] = 3;
                }
                continue;
            }
            c.vy += Physics.G * dt;
            c.px += c.vx * dt;
            c.py += c.vy * dt;
            c.pz += c.vz * dt;
            c.rotateBy(c.wx * dt, c.wy * dt, c.wz * dt);
            c.updateR();
            if (st == 2 && c.pz < 2.6f) {
                // it reaches the player
                flyState[i] = 0;
                life.putDown(flyCar[i], CAM_X - 3.2f, -6f, 0f);
                flash = 1f;
                host.sound(Synth.CRASH, 1.2f, 0.75f);
                host.sound(Synth.GLASS, 0.8f, 0.9f);
                host.buzz(250);
                if (armor == 0 && bossEnd < 0f) endBoss(false);
                else armor = Math.max(0, armor - 1);
                continue;
            }
            if (c.py < 0.65f && c.vy < 0f) {
                // down on the street, a wreck where it fell
                flyState[i] = 0;
                float x = Math.max(-6f, Math.min(6f, c.px));
                life.putDown(flyCar[i], x, c.pz, (float) Math.atan2(c.r02, c.r22));
                host.sound(Synth.CRASH, 0.9f, 0.8f + fx.nextFloat() * 0.2f);
                host.sound(Synth.GROUND, 1f, 0.6f);
                host.buzz(60);
                for (int k = 0; k < 16; k++) spawn(c.px, 0.2f, c.pz, true, 0xFFD8CFC0, 3f);
            }
        }
    }

    /** What the diamond hammer has met: a car in the air, a weak point, or the golem's bulk. */
    private void bossHits() {
        Body h = hammer;
        float hx = h.px + h.r00 * 0.31f, hy = h.py + h.r10 * 0.31f, hz = h.pz + h.r20 * 0.31f;
        for (int i = 0; i < FLY_MAX; i++) {
            if (flyState[i] != 1 && flyState[i] != 2) continue;
            Body c = fly[i];
            float dx = c.px - hx, dy = c.py - hy, dz = c.pz - hz;
            if (dx * dx + dy * dy + dz * dz > 1.6f * 1.6f) continue;
            // knocked out of the air: up, back and toward the kerb
            c.vx = (c.px < 0f ? -1f : 1f) * (2.5f + fx.nextFloat() * 2f);
            c.vy = 5.5f + fx.nextFloat() * 2f;
            c.vz = 3f + fx.nextFloat() * 3f;
            c.wx = (fx.nextFloat() - 0.5f) * 9f;
            c.wz = (fx.nextFloat() - 0.5f) * 9f;
            flyState[i] = 3;
            host.sound(Synth.CRASH, 1f, 1.1f);
            host.sound(Synth.GLASS, 0.6f, 1.2f);
            host.buzz(70);
            for (int k = 0; k < 14; k++) spawn(hx, hy, hz, false, (k & 1) == 0 ? 0xFFCFE2EC : 0xFFFFF2C0, 4f);
            beginReturn();
            return;
        }
        Golem g = golem;
        for (int side = -1; side <= 1; side += 2) {
            if ((side < 0 ? g.kneeL : g.kneeR) == 0) continue;
            g.knee(side, tmp);
            if (near(tmp, hx, hy, hz, Golem.KNEE_HIT + 0.25f)) {
                if (side < 0) g.kneeL = 0;
                else g.kneeR = 0;
                weakHit(hx, hy, hz, 1.1f);
                return;
            }
        }
        g.headCenter(tmp);
        if (near(tmp, hx, hy, hz, Golem.HEAD_HIT + 0.3f)) {
            g.head--;
            weakHit(hx, hy, hz, 0.9f);
            if (g.head == 0) {
                topple();
                endBoss(true);
            }
            return;
        }
        if (g.bodyDist(hx, hy, hz) < Golem.BODY_HIT + 0.2f) {
            // a blow to the bulk: a brick knocked out, a moment's pause
            g.stagger = Math.max(g.stagger, 0.5f);
            g.hurt = 0.6f;
            Body b = new Body(0.22f, 0.1f, 0.22f, Body.WOOD, 0xFFC4864F);
            b.setPose(hx, hy, hz, 1, 0, 0, 0);
            b.vx = (fx.nextFloat() - 0.5f) * 3f;
            b.vy = 2f;
            b.vz = -2f;
            b.wx = 6f;
            phys.add(b);
            pieces.add(b);
            host.sound(Synth.WOOD, 1f, 0.7f);
            host.buzz(40);
            beginReturn();
        }
    }

    private boolean near(float[] p, float x, float y, float z, float r) {
        float dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
        return dx * dx + dy * dy + dz * dz < r * r;
    }

    private void weakHit(float x, float y, float z, float stun) {
        golem.stagger = Math.max(golem.stagger, stun);
        golem.hurt = 1f;
        host.sound(Synth.STONE, 1.3f, 0.5f);
        host.sound(Synth.THUNDER, 0.5f, 1.6f);
        host.buzz(110);
        for (int k = 0; k < 24; k++) spawn(x, y, z, (k & 1) == 0, (k & 1) == 0 ? 0xFFE6DCC8 : 0xFFFFB13A, 5f);
        beginReturn();
    }

    /** The golem falls apart into what it was made of. */
    private void topple() {
        Golem g = golem;
        g.down = true;
        for (int k = 0; k < 4; k++) {
            g.at((k & 1) == 0 ? -0.7f : 0.7f, 0.6f + (k / 2) * 1.2f, 0f, tmp);
            rubble(new Body(0.36f, 0.6f, 0.4f, Body.STONE, 0xFFC9BEAD));
        }
        for (int k = 0; k < 14; k++) {
            g.at((fx.nextFloat() - 0.5f) * 2f, 2.6f + fx.nextFloat() * 2.2f, (fx.nextFloat() - 0.5f) * 0.8f, tmp);
            rubble(new Body(0.29f, 0.14f, 0.3f, Body.WOOD, (k & 1) == 0 ? 0xFFC4864F : 0xFFD39E62));
        }
        for (int k = 0; k < 4; k++) {
            g.at((k & 1) == 0 ? -1.5f : 1.5f, 3.4f + (k / 2) * 0.8f, 0f, tmp);
            rubble(new Body(Builder.BOTTLE_D * 0.5f * Builder.S, Builder.BOTTLE_H * 0.5f * Builder.S,
                    Builder.BOTTLE_D * 0.5f * Builder.S, Body.WATER, 0xFF8FC4EE));
        }
        g.headCenter(tmp);
        rubble(Body.ball(Golem.HEAD_HIT, 0xFF2A2F48));
        host.sound(Synth.THUNDER, 1.2f, 0.8f);
        host.buzz(200);
    }

    private void rubble(Body b) {
        b.setPose(tmp[0], tmp[1], tmp[2], 1, 0, 0, 0);
        b.vx = (fx.nextFloat() - 0.5f) * 5f;
        b.vy = fx.nextFloat() * 3f;
        b.vz = -1f - fx.nextFloat() * 3f;
        b.wx = (fx.nextFloat() - 0.5f) * 6f;
        b.wz = (fx.nextFloat() - 0.5f) * 6f;
        b.homeX = b.px;
        b.homeY = b.py;
        b.homeZ = b.pz;
        phys.add(b);
        pieces.add(b);
    }

    private void endBoss(boolean won) {
        if (bossEnd >= 0f) return;
        bossWon = won;
        bossEnd = 0f;
        aiming = false;
        if (!won) golem.stagger = 99f;
    }

    private float rumble;

    /** The fight is won: up into the sky, from red through gold to day, and the gods rejoice. */
    private void finishBoss() {
        fromBoss = true;
        nextAfterVanish = 1;
        winSky = 0f;
        lookUp(Sky.OVATION);
    }

    /** The fight is lost: the player wakes on the street, before the same building, as from a dream. */
    private void wakeUp() {
        fails = 0;
        host.saveFails(0);
        startLevel(level, false);
        wake = 0f;
        host.sound(Synth.BEAT, 0.8f, 0.9f);
    }

    /**
     * The diamond hammer flies hard and flat. A car in the air near the ring is taken as the
     * target and led by its flight; otherwise the ring's point on the golem, or on the plane
     * it walks in.
     */
    private void bossThrow() {
        handPoint(tmp);
        float sx = tmp[0], sy = tmp[1], sz = tmp[2];
        float ax = aimX, ay = aimY;
        int car = -1;
        float bd = 15f;
        for (int i = 0; i < FLY_MAX; i++) {
            if (flyState[i] != 1 && flyState[i] != 2) continue;
            float vz = ras.project(fly[i].px, fly[i].py, fly[i].pz, scr);
            if (vz < 0.5f) continue;
            float d = (float) Math.hypot(scr[0] - ax, scr[1] - ay);
            if (d < bd) {
                bd = d;
                car = i;
            }
        }
        float tx, ty, tz, speed = 26f;
        if (car >= 0) {
            Body c = fly[car];
            tx = c.px;
            ty = c.py;
            tz = c.pz;
            if (flyState[car] == 2)
                for (int it = 0; it < 4; it++) {
                    float d = (float) Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
                    float t = d / speed;
                    tx = c.px + c.vx * t;
                    ty = c.py + c.vy * t + 0.5f * Physics.G * t * t;
                    tz = c.pz + c.vz * t;
                }
        } else {
            bossTarget(ax, ay, dirA);
            tx = dirA[0];
            ty = dirA[1];
            tz = dirA[2];
            // the golem keeps walking while the hammer flies: meet it where it will be
            if (golem.stagger <= 0f) {
                float d = (float) Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
                float t = d / speed, v = golem.pace();
                tz -= v * t;
            }
        }
        float dx = tx - sx, dy = ty - sy, dz = tz - sz;
        float t = Math.max(0.12f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / speed);
        hammer.ghost = false;
        hammer.asleep = false;
        hammer.setPose(sx, sy, sz, (float) Math.cos(YAW / 2), 0f, (float) Math.sin(YAW / 2), 0f);
        hammer.vx = dx / t;
        hammer.vy = dy / t - 0.5f * Physics.G * t;
        hammer.vz = dz / t;
        spinSign = aimX < W * 0.5f ? 1f : -1f;
        hammer.wy = spinSign * 18f;
        hammer.savePrev();
        flightTime = t;
        hs = FLY;
        hsTime = 0f;
        host.sound(Synth.WHOOSH, 1f, 1.3f);
        host.buzz(12);
    }

    /** The world point under the ring: the golem's head, knees or body, or the plane it walks in. */
    private void bossTarget(float sx, float sy, float[] out) {
        ras.ray(sx, sy, out);
        float dx = out[0], dy = out[1], dz = out[2];
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= l;
        dy /= l;
        dz /= l;
        float best = 1e9f;
        Golem g = golem;
        float[] c = new float[3];
        for (int k = 0; k < 4; k++) {
            float r;
            if (k == 0) {
                g.headCenter(c);
                r = Golem.HEAD_HIT;
            } else if (k == 1) {
                g.chest(c);
                r = Golem.BODY_HIT + 0.2f;
            } else {
                g.knee(k == 2 ? -1 : 1, c);
                r = Golem.KNEE_HIT;
            }
            float ox = CAM_X - c[0], oy = CAM_Y - c[1], oz = -c[2];
            float b = ox * dx + oy * dy + oz * dz, q = ox * ox + oy * oy + oz * oz - r * r;
            float disc = b * b - q;
            if (disc < 0f) continue;
            float t = -b - (float) Math.sqrt(disc);
            if (t > 0f && t < best) best = t;
        }
        if (best == 1e9f) best = Math.max(1f, (g.z - 0f) / Math.max(0.05f, dz));
        out[0] = CAM_X + dx * best;
        out[1] = CAM_Y + dy * best;
        out[2] = dz * best;
    }

    private void drawFight() {
        Golem g = golem;
        // its shadow, long across the street
        if (!g.down) {
            g.chest(tmp);
            float lo = Math.max(0f, tmp[1] - 3f * Golem.G), len = (Golem.HIP + Golem.TORSO) * Golem.G * SH_LEN;
            float k = 1f - Math.min(0.8f, lo / 8f);
            ras.softShadow(g.x + SHX * lo * SH_LEN, g.z + SHZ * lo * SH_LEN, g.x + SHX * (lo * SH_LEN + len),
                    g.z + SHZ * (lo * SH_LEN + len), 2.4f * k, 1.6f * k, 0.75f * k);
        }
        for (int i = 0; i < FLY_MAX; i++) {
            if (flyState[i] == 0) continue;
            Body c = fly[i];
            float lo = Math.max(0f, c.py - 0.6f);
            ras.softShadow(c.px + SHX * lo * SH_LEN, c.pz + SHZ * lo * SH_LEN, c.px + SHX * (lo + 1.2f) * SH_LEN,
                    c.pz + SHZ * (lo + 1.2f) * SH_LEN, 1.1f, 0.9f, 0.6f * (1f - Math.min(1f, lo / 12f)));
        }
        g.draw(ras, 960 << 3);
        for (int i = 0; i < FLY_MAX; i++) if (flyState[i] != 0) drawFlyingCar(fly[i], (970 + i) << 3);
    }

    /** A car turning over in the air: body, cabin, wheels. */
    private void drawFlyingCar(Body c, int id) {
        flyBox(c, 0f, 0.06f, 0f, 0.9f, 0.21f, 2.2f, c.tint, id);
        // the floor, black, with the exhaust under it
        flyBox(c, 0f, -0.2f, 0f, 0.86f, 0.06f, 2.12f, 0xFF1A1918, id);
        flyBox(c, 0.4f, -0.27f, -1.6f, 0.05f, 0.05f, 0.5f, 0xFF4A4642, id);
        flyBox(c, 0f, 0.42f, -0.2f, 0.78f, 0.2f, 1.05f, 0xFF33414E, id);
        flyBox(c, 0f, 0.6f, -0.25f, 0.76f, 0.03f, 0.7f, c.tint, id);
        for (int k = 0; k < 4; k++)
            flyBox(c, (k & 1) == 0 ? -0.86f : 0.86f, -0.28f, (k & 2) == 0 ? -1.38f : 1.38f, 0.12f, 0.3f, 0.3f, 0xFF1C1B1D, id);
    }

    private void flyBox(Body c, float lx, float ly, float lz, float hx, float hy, float hz, int color, int id) {
        float x = c.px + c.r00 * lx + c.r01 * ly + c.r02 * lz;
        float y = c.py + c.r10 * lx + c.r11 * ly + c.r12 * lz;
        float z = c.pz + c.r20 * lx + c.r21 * ly + c.r22 * lz;
        ras.box(x, y, z, c.r00, c.r01, c.r02, c.r10, c.r11, c.r12, c.r20, c.r21, c.r22, hx, hy, hz, color, Raster.PAT_FLAT, id);
    }

    /** The fight's corner: the armor left, and the golem's weak points still whole. */
    private void drawFightHud(Px f) {
        int x = 6, y = 8;
        for (int i = 0; i < ARMOR; i++) shield(f, x + i * 15, y, i < armor);
        int bx = W - 7 - 5 * 11;
        for (int i = 0; i < 5; i++) {
            boolean whole = i < 2 ? (i == 0 ? golem.kneeL : golem.kneeR) > 0 : golem.head > i - 2;
            int cx = bx + i * 11;
            f.rect(cx, y + 1, cx + 9, y + 10, 0xFF5A3A0E);
            f.rect(cx + 1, y + 2, cx + 8, y + 9, whole ? (i < 2 ? 0xFFFFB13A : 0xFFFFE07A) : 0xFF3A3236);
        }
        String lv = String.valueOf(level + 1);
        PixelFont.draw(f, lv, W - 7 - PixelFont.width(lv, 1), y + 15, 1, 0xFF6A4A1A);
    }

    private void shield(Px f, int x, int y, boolean on) {
        for (int r = 0; r < 13; r++) {
            int half = r < 8 ? 6 : 6 - (r - 7);
            for (int c = -half; c <= half; c++) {
                boolean edge = Math.abs(c) == half || r == 0 || r == 12;
                int col = edge ? 0xFF5A4A3A : on ? (c < 0 ? 0xFFB8D8E8 : 0xFF8FB4C8) : 0x00000000;
                if (col != 0) f.set(x + 6 + c, y + r, col);
            }
        }
    }

    private int[] shakeBuf;

    /** The street jolts when the golem comes down: the whole frame jumps a few pixels. */
    private void shakeFrame(Px f) {
        if (shake <= 0.02f) return;
        int a = Math.round(shake * 5f);
        int dx = (int) ((fx.nextFloat() - 0.5f) * 2f * a), dy = (int) ((fx.nextFloat() - 0.5f) * 2f * a);
        if (dx == 0 && dy == 0) return;
        if (shakeBuf == null || shakeBuf.length != f.a.length) shakeBuf = new int[f.a.length];
        System.arraycopy(f.a, 0, shakeBuf, 0, f.a.length);
        for (int y = 0; y < h; y++) {
            int sy = Math.max(0, Math.min(h - 1, y - dy));
            for (int x = 0; x < W; x++) {
                int sx = Math.max(0, Math.min(W - 1, x - dx));
                f.a[y * W + x] = shakeBuf[sy * W + sx];
            }
        }
    }

    /** A red veil at the edges of the frame when a car or the golem's arm strikes home. */
    private void drawFlash(Px f) {
        if (flash <= 0f) return;
        for (int yy = 0; yy < h; yy++)
            for (int xx = 0; xx < W; xx++) {
                float ex = Math.abs(xx - W * 0.5f) / (W * 0.5f), ey = Math.abs(yy - h * 0.5f) / (h * 0.5f);
                float e = Math.max(ex, ey);
                float a = flash * 0.6f * Math.max(0f, (e - 0.45f) / 0.55f);
                if (a <= 0f) continue;
                int k = yy * W + xx;
                f.a[k] = Backdrop.mix(f.a[k], 0xFFC02020, a);
            }
    }

    /** For the desktop stand: drops a bowling ball at a point of the street. */
    void standBall(float x, float y, float z) {
        Body b = Body.ball(Builder.BALL_R * Builder.S, Builder.BALL_COLORS[0]);
        b.setPose(x, y, z, 1, 0, 0, 0);
        b.homeX = x;
        b.homeY = y;
        b.homeZ = z;
        phys.add(b);
        pieces.add(b);
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
        target(aimX + tremX, aimY + tremY, tmp);
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
        goldThrow = goldOn;
        heavyThrow = !goldThrow && heavyOn && heavyLeft > 0;
        if (heavyThrow) {
            hammer.scaleMass(HEAVY_MASS);
            heavyLeft--;
            host.saveGifts(heavyLeft);
        }
        hammer.wy = spinSign * (heavyThrow ? 8f : 15f);
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
        if (booth >= 0f) {
            booth += dt;
            return;
        }
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
            keepGold();
            pops();
            blast();
        }
        if (acc > 1f / 30f) acc = 0f;
        particles(dt);
        puddles(dt);
        life.update(dt);
        trail();

        windUp(dt);
        zoomStep(dt);
        hammerMoves(dt);
        if (winSky >= 0f) {
            winSky += dt;
            if (winSky > 2.4f) winSky = -1f;
        }
        if (wake >= 0f) {
            wake += dt;
            if (wake > 1.8f) wake = -1f;
        }
        if (closeCall()) dt *= 0.4f;
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
                    if (bossNext) {
                        startBoss(level + nextAfterVanish);
                        buildFromSky = false;
                    } else {
                        // the gods have thrown the blocks: the next building falls into place as we look down
                        startLevel(level + nextAfterVanish, true);
                        buildFromSky = true;
                    }
                    fromSky = true;
                    backT = 0f;
                }
                break;
            case BOSS:
                bossStep(dt);
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
        if (hs == FLY && golem != null) {
            if (hsTime > flightTime + 0.35f || hammer.py < 0.25f || hammer.pz > golem.z + 4f) beginReturn();
        } else if (hs == FLY) {
            if (hsTime > flightTime + 0.6f && (hammer.speed2() < 4f || hammer.py < 0.4f
                    || hammer.pz > Builder.CZ + 4f) || hsTime > flightTime + 2.2f || hammer.pz > 60f)
                beginReturn();
        } else if (hs == BACK) {
            float k = Math.min(1f, hsTime / (golem != null ? 0.4f : 0.6f));
            handPoint(tmp);
            float e = k * k * (3f - 2f * k);
            hammer.px = retX + (tmp[0] - retX) * e;
            hammer.py = retY + (tmp[1] - retY) * e + (float) Math.sin(Math.PI * k) * 1.2f;
            hammer.pz = retZ + (tmp[2] - retZ) * e;
            hammer.rotateBy(0f, spinSign * (heavyThrow ? 8f : 14f) * dt, 0f);
            hammer.updateR();
            if (k >= 1f) {
                host.sound(Synth.CATCH, 0.8f, heavyThrow ? 0.75f : 1f);
                host.buzz(heavyThrow ? 45 : 25);
                goldThrow = false;
                if (heavyThrow) {
                    hammer.scaleMass(1f / HEAVY_MASS);
                    heavyThrow = false;
                    if (heavyLeft == 0) heavyOn = false;
                }
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
        prepare(level + nextAfterVanish);
        // the first building is the gods' welcome: they all laugh aloud
        sky.begin(verdict, (level == 0 || fromBoss) && verdict == Sky.OVATION);
        giftShown = -1f;
        goldGift = false;
        diamondGift = false;
        bossNext = false;
        boolean afterFight = fromBoss;
        fromBoss = false;
        if (afterFight) {
            // the fight is over: no counting, no golem again at once
        } else if (verdict == Sky.YAWN) {
            fails++;
            host.saveFails(fails);
            if (fails == GOLD_AFTER) {
                if (!goldSeen) {
                    // the first time a building will not fall, the gods give the golden hammer
                    goldGift = true;
                    goldSeen = true;
                    host.saveGoldSeen(true);
                    giftShown = 0f;
                } else {
                    // ever after, the golem comes instead
                    bossNext = true;
                    fails = 0;
                    host.saveFails(0);
                }
            }
        } else if (fails > 0) {
            fails = 0;
            host.saveFails(0);
        }
        if (verdict == Sky.OVATION) {
            // the gods give the heavy hammer, and put it in the hand for the next building
            heavyLeft = Math.min(HEAVY_MAX, heavyLeft + HEAVY_GIFT);
            heavyOn = true;
            host.saveGifts(heavyLeft);
            giftShown = 0f;
        }
        if (bossNext) {
            diamondGift = true;
            giftShown = 0f;
        }
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
        // a balloon bursts at the touch of the hammer, or at any hard knock
        if (a.material == Body.BALLOON && (hammerHit || speed > 3f)) pop(a);
        if (b != null && b.material == Body.BALLOON && (hammerHit || speed > 3f)) pop(b);
        if ((a.material == Body.CAR || (b != null && b.material == Body.CAR))) {
            carKnock(a.material == Body.CAR ? a : b, speed, x, y, z);
            return;
        }
        // the first building flies to pieces at the first touch of the hammer
        if ((level == 0 || goldThrow) && hammerHit && other != null && other != plinth && other.material != Body.CAR
                && !blasted) {
            if (goldThrow) {
                goldOn = false;
                fails = 0;
                host.saveFails(0);
            }
            blasted = true;
            blastX = x;
            blastY = y;
            blastZ = z;
            blastNow = true;
        }
        // water bottles burst at a hard blow, or a hard landing
        if (a.material == Body.WATER && (speed > 4.2f || hammerHit && speed > 2.4f)) burst(a);
        if (b != null && b.material == Body.WATER && (speed > 4.2f || hammerHit && speed > 2.4f)) burst(b);
        boolean plastic = a.material == Body.WATER || (b != null && b.material == Body.WATER);
        float gain = Math.min(1f, (speed - 1f) / 5f);
        if (hammerHit) gain = Math.min(1.3f, gain * 1.4f + 0.2f);
        int street = voiceOf(a);
        if (street < 0 && b != null) street = voiceOf(b);
        if (street >= 0) {
            if (speed > 1.2f || hammerHit) {
                float rate = street == Synth.PLASTIC ? 1.35f : street == Synth.CLANG ? 0.9f : 1f;
                host.sound(street, gain * (street == Synth.CLANG ? 0.85f : 1f), rate * (0.92f + fx.nextFloat() * 0.16f));
            }
        } else if (b == null || other == null) {
            if (speed > 1.6f) host.sound(Synth.GROUND, gain * 0.9f, 0.9f + fx.nextFloat() * 0.2f);
        } else {
            boolean stone = a.material == Body.STONE || b.material == Body.STONE || other.material == Body.PLINTH
                    || a.material == Body.BALL || b.material == Body.BALL;
            float size = Math.max(other.hx, Math.max(other.hy, other.hz));
            float rate = Math.max(0.6f, Math.min(1.5f, 0.32f / size)) * (0.92f + fx.nextFloat() * 0.16f);
            host.sound(plastic ? Synth.PLASTIC : stone ? Synth.STONE : Synth.WOOD, gain, plastic ? 0.9f + fx.nextFloat() * 0.2f : rate);
        }
        if (hammerHit) host.buzz((int) Math.min(60f, 10f + speed * 3f));
        if (speed > 2.2f && other != null && other.material != Body.WATER) {
            int n = (int) Math.min(14f, speed * 1.2f);
            boolean dusty = other.material == Body.STONE || other.material == Body.PLINTH || other.material == Body.SANDBAG;
            int dust = other.material == Body.SANDBAG ? 0xFFDCC79C : 0xFFE6DCC8;
            for (int i = 0; i < n; i++)
                spawn(x, y, z, dusty && (i & 1) == 0, dusty ? dust : Backdrop.scale(other.tint, 0.8f), speed * 0.35f);
        } else if (speed > 3f && b == null) {
            for (int i = 0; i < 4; i++) spawn(x, 0.02f, z, true, 0xFFD8CFC0, 1f);
        }
    }

    /** The sound of a street thing knocked about, or -1 for a plain block. */
    private static int voiceOf(Body b) {
        switch (b.material) {
            case Body.BARREL: return Synth.CLANG;
            case Body.TYRE: return Synth.RUBBER;
            case Body.SANDBAG: return Synth.SAND;
            case Body.CONE: return Synth.PLASTIC;
            default: return -1;
        }
    }

    /** Something hard against a parked car: crumpled metal, a spray of glass, the alarm. */
    private void carKnock(Body car, float speed, float x, float y, float z) {
        int i = 0;
        while (i < cars.length && cars[i] != car) i++;
        if (i == cars.length) return;
        if (speed < 2.2f) {
            host.sound(Synth.CRASH, 0.25f, 1.3f);
            return;
        }
        if (clock - carHitAt[i] < 0.5f) return;
        carHitAt[i] = clock;
        boolean wasQuiet = life.damage[i] == 0 || clock - carAlarmAt > 1.8f;
        life.hit(i);
        host.sound(Synth.CRASH, Math.min(1f, 0.4f + speed * 0.08f), 0.85f + fx.nextFloat() * 0.3f);
        host.sound(Synth.GLASS, 0.5f, 1.1f + fx.nextFloat() * 0.3f);
        if (wasQuiet) {
            host.sound(Synth.ALARM, 0.45f, 1f);
            carAlarmAt = clock;
        }
        host.buzz(50);
        for (int k = 0; k < 12; k++) spawn(x, y, z, false, (k & 1) == 0 ? 0xFFCFE2EC : 0xFF9FB6C4, speed * 0.4f);
    }

    private float carAlarmAt = -9f;

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

    // the first building's welcome: one blow and it scatters
    private boolean blasted, blastNow;
    private float blastX, blastY, blastZ;

    /** Throws every piece of the building away from the blow, after the step, never inside it. */
    private void blast() {
        if (!blastNow) return;
        blastNow = false;
        for (Body b : pieces) {
            if (b.gone) continue;
            float dx = b.px - blastX, dy = b.py - blastY + 0.6f, dz = b.pz - blastZ + 0.5f;
            float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.2f;
            float v = 6f + fx.nextFloat() * 3.5f;
            b.vx = dx / l * v + (fx.nextFloat() - 0.5f) * 1.5f;
            b.vy = Math.abs(dy / l) * v * 0.8f + 2.5f + fx.nextFloat() * 2f;
            b.vz = dz / l * v + 1.5f;
            b.wx = (fx.nextFloat() - 0.5f) * 14f;
            b.wy = (fx.nextFloat() - 0.5f) * 14f;
            b.wz = (fx.nextFloat() - 0.5f) * 14f;
            b.asleep = false;
            b.savePrev();
            if (b.material == Body.WATER) burst(b);
        }
        for (int i = 0; i < 40; i++)
            spawn(blastX, blastY, blastZ, (i & 1) == 0, (i & 1) == 0 ? 0xFFE6DCC8 : 0xFFD39E62, 5f);
        host.sound(Synth.THUNDER, 0.8f, 1.3f);
        host.buzz(80);
    }

    private final ArrayList<Body> toPop = new ArrayList<Body>();

    private void pop(Body b) {
        if (b.gone || toPop.contains(b)) return;
        toPop.add(b);
    }

    /** Balloons burst this step: gone with a crack and a spray of rubber shreds. */
    private void pops() {
        // a freed balloon drifts away and is gone once it is well up in the sky
        for (Body b : pieces)
            if (b.material == Body.BALLOON && !b.gone && b.py > Builder.TOP + 25f) {
                b.gone = true;
                b.ghost = true;
            }
        if (toPop.isEmpty()) return;
        for (Body b : toPop) {
            b.gone = true;
            b.ghost = true;
            float e = 0.08f;
            for (Body o : pieces)
                if (o.asleep && !(o.maxX + e < b.minX || b.maxX + e < o.minX || o.maxY + e < b.minY
                        || b.maxY + e < o.minY || o.maxZ + e < b.minZ || b.maxZ + e < o.minZ)) phys.wake(o);
            for (int i = 0; i < 14; i++) spawn(b.px, b.py, b.pz, false, Backdrop.scale(b.tint, 0.8f + fx.nextFloat() * 0.4f), 3.5f);
            host.sound(Synth.POP, 1f, 0.9f + fx.nextFloat() * 0.25f);
            host.buzz(20);
        }
        toPop.clear();
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
        System.arraycopy(zoomed ? zCol : bgCol, 0, ras.col, 0, bgCol.length);
        System.arraycopy(zoomed ? zZ : bgZ, 0, ras.zb, 0, bgZ.length);
        System.arraycopy(zoomed ? zLit : bgLit, 0, ras.lit, 0, bgLit.length);
        java.util.Arrays.fill(ras.idb, 0);

        if (golem == null) drawBody(plinth, 1f, 0f, 1);
        drawCallButton();
        float st = stagger();
        ras.shadowBegin(6f, Builder.CZ + 1f, 12f);
        if (golem == null)
            ras.shadowBox(plinth.px, plinth.py, plinth.pz, 1, 0, 0, 0, 1, 0, 0, 0, 1, plinth.hx, plinth.hy, plinth.hz);
        else drawFight();
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
            else if (b.material == Body.BARREL) drawDrum(b, scale, drop, i + 2);
            else if (b.material == Body.CONE) drawCone(b, scale, drop, i + 2);
            else if (b.material == Body.TYRE) drawTyre(b, scale, drop, i + 2);
            else if (b.material == Body.SANDBAG) drawBag(b, scale, drop, i + 2);
            else drawBody(b, scale, drop, i + 2);
            if (b.radius > 0f) ras.shadowBall(b.px, b.py + drop, b.pz, b.radius * scale);
            else ras.shadowBox(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    b.hx * scale, b.hy * scale, b.hz * scale);
        }
        streetShadows();
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
            if (giftShown >= 0f && state == SKY_HOLD && stateTime > 0.9f) drawGift(f, stateTime - 0.9f);
            grade(f, off);
            if (off < h / 8) {
                if (golem != null && state == BOSS) drawFightHud(f);
                else drawHud(f);
            }
        } else {
            if (!zoomed && zoomK > 0f) closingIn(f);
            else System.arraycopy(ras.col, 0, f.a, 0, Math.min(f.a.length, ras.col.length));
            if (golem != null) shakeFrame(f);
            grade(f, 0);
            if (golem != null) {
                drawFightHud(f);
                drawFlash(f);
            } else drawHud(f);
            if (booth >= 0f) drawBooth(f);
        }
        dream(f);
    }

    // ---- the golem's world, seen through a filter ----
    // Its street is red and black, every color still alive under it; the golem itself burns
    // with a golden fire. The way in is down from the sky; a win goes back up into the sky
    // from red through gold to plain day. A loss goes dark, and the player wakes on the street.

    private float winSky = -1f;          // seconds since the sky opened after a won fight
    private float wake = -1f;            // seconds since the player opened his eyes

    /** How red the world is now, and how golden. */
    private float redness() {
        if (winSky >= 0f) return Math.max(0f, 1f - winSky / 0.9f);
        if (golem == null || state != BOSS) return 0f;
        if (state == BOSS && fromSky) return Math.min(1f, backT / TILT);
        if (state == BOSS) return Math.min(1f, stateTime / 0.8f);
        return 1f;
    }

    private float goldness() {
        if (winSky < 0f) return 0f;
        return winSky < 0.9f ? winSky / 0.9f : Math.max(0f, 1f - (winSky - 0.9f) / 1.3f);
    }

    private void grade(Px f, int off) {
        float red = redness(), gold = goldness();
        if (red <= 0.003f && gold <= 0.003f) return;
        boolean fire = golem != null && !golem.down && red > 0f;
        int gid = 960 << 3;
        float flick = clock * 11f;
        for (int y = 0; y < h; y++) {
            int sy = y - off;
            float fl = 0.88f + 0.12f * (float) Math.sin(y * 0.31f + flick) * (float) Math.sin(y * 0.07f - flick * 0.6f);
            for (int x = 0; x < W; x++) {
                int k = y * W + x;
                int c = f.a[k];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                float l = (r * 0.3f + g * 0.55f + b * 0.15f) / 255f;
                float or = r, og = g, ob = b;
                if (fire && sy >= 0 && ras.idb[sy * W + x] == gid) {
                    // golden fire: dark embers in the folds, gold and white heat on the edges
                    float t = Math.min(1f, l * fl * 1.25f);
                    float fr = t < 0.4f ? 60f + t / 0.4f * 170f : 230f + (t - 0.4f) / 0.6f * 25f;
                    float fg = t < 0.4f ? 10f + t / 0.4f * 70f : 80f + (t - 0.4f) / 0.6f * 160f;
                    float fb = t < 0.7f ? 8f + t * 20f : 22f + (t - 0.7f) / 0.3f * 150f;
                    or += (fr - or) * red;
                    og += (fg - og) * red;
                    ob += (fb - ob) * red;
                } else if (red > 0f) {
                    // red and black: the reds kept, the rest sunk, shadows deepened
                    float d = l * l * 1.4f;
                    float rr = Math.min(255f, r * 0.55f + 255f * d * 0.75f), gg = g * 0.22f + 40f * d, bb = b * 0.2f + 30f * d;
                    or += (rr - or) * red * 0.85f;
                    og += (gg - og) * red * 0.85f;
                    ob += (bb - ob) * red * 0.85f;
                }
                if (gold > 0f) {
                    float gr = Math.min(255f, l * 290f + 20f), gg = Math.min(255f, l * 230f + 8f), gb = l * 110f;
                    or += (gr - or) * gold * 0.75f;
                    og += (gg - og) * gold * 0.75f;
                    ob += (gb - ob) * gold * 0.75f;
                }
                f.a[k] = 0xFF000000 | ((int) Math.max(0f, Math.min(255f, or)) << 16)
                        | ((int) Math.max(0f, Math.min(255f, og)) << 8) | (int) Math.max(0f, Math.min(255f, ob));
            }
        }
    }

    private int[] blurA, blurB;

    /** Going dark after a lost fight; opening the eyes again, the world blurred, then clear. */
    private void dream(Px f) {
        float dark = 0f;
        if (golem != null && bossEnd >= 0f && !bossWon) dark = Math.min(1f, bossEnd / 0.9f);
        if (wake >= 0f) {
            float k = Math.min(1f, wake / 1.6f);
            int r = Math.round((1f - k) * 7f);
            if (r > 0) blur(f, r);
            dark = Math.max(dark, Math.max(0f, 1f - wake / 0.5f) * 0.8f);
            // the lids part: dark from above and below, opening
            float open = k * k * (3f - 2f * k);
            int lid = Math.round(h * 0.5f * (1f - open));
            for (int y = 0; y < h; y++) {
                float e = y < lid ? 1f : y >= h - lid ? 1f : 0f;
                int edge = Math.min(Math.abs(y - lid), Math.abs(h - lid - y));
                if (e == 0f && edge < 30) e = (30 - edge) / 30f * (1f - open);
                if (e <= 0f) continue;
                for (int x = 0; x < W; x++) f.a[y * W + x] = Backdrop.mix(f.a[y * W + x], 0xFF000000, Math.min(1f, e));
            }
        }
        if (dark > 0f) for (int k = 0; k < f.a.length; k++) f.a[k] = Backdrop.mix(f.a[k], 0xFF000000, dark);
    }

    private void blur(Px f, int r) {
        int n = f.a.length;
        if (blurA == null || blurA.length != n) {
            blurA = new int[n];
            blurB = new int[n];
        }
        boxPass(f.a, blurA, r, true);
        boxPass(blurA, blurB, r, false);
        boxPass(blurB, blurA, r, true);
        boxPass(blurA, f.a, r, false);
    }

    private void boxPass(int[] in, int[] out, int r, boolean rows) {
        int len = rows ? W : h, lines = rows ? h : W, step = rows ? 1 : W;
        int d = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = rows ? l * W : l;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[base + Math.max(0, Math.min(len - 1, i)) * step];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | ((sr / d) << 16) | ((sg / d) << 8) | (sb / d);
                int ca = in[base + Math.min(len - 1, i + r + 1) * step], cs = in[base + Math.max(0, i - r) * step];
                sr += ((ca >> 16) & 0xFF) - ((cs >> 16) & 0xFF);
                sg += ((ca >> 8) & 0xFF) - ((cs >> 8) & 0xFF);
                sb += (ca & 0xFF) - (cs & 0xFF);
            }
        }
    }

    // the way shadows fall on the street: away from the sun, this long for each meter of height
    private static final float SHX, SHZ, SH_LEN;

    static {
        float hx = -Raster.sunX(), hz = -Raster.sunZ();
        float l = (float) Math.sqrt(hx * hx + hz * hz);
        SHX = hx / l;
        SHZ = hz / l;
        SH_LEN = l / Raster.sunY();
    }

    /**
     * Soft shadows on the street for whatever has fallen off the plinth: the shadow map
     * only covers the plinth and its foot. A ball casts a long soft oval, a block a patch
     * as wide as it lies; the higher it is, the farther and fainter.
     */
    private void streetShadows() {
        for (Body b : pieces) {
            if (b.gone || b.material == Body.BALLOON) continue;
            boolean overPlinth = Math.abs(b.px) < Builder.PHX + 0.3f && Math.abs(b.pz - Builder.CZ) < Builder.PHZ + 0.3f;
            if (overPlinth || b.minY > 2.5f) continue;
            float r = b.radius > 0f ? b.radius : Math.max(0.08f, Math.min(b.hx, Math.min(b.hz, b.hy)) * 1.1f);
            float lo = Math.max(0f, b.minY), hi = b.maxY;
            float ax = b.px + SHX * lo * SH_LEN, az = b.pz + SHZ * lo * SH_LEN;
            float bx = b.px + SHX * hi * SH_LEN, bz = b.pz + SHZ * hi * SH_LEN;
            float fade = 1f - Math.min(1f, lo / 2.5f);
            ras.softShadow(ax, az, bx, bz, r * 1.05f, r * 0.8f, 0.8f * fade);
            // and the dark spot right under it, in sun or in shade
            if (lo < 0.4f) {
                ras.anyLight = true;
                float k = 1f - lo / 0.4f;
                ras.softShadow(b.px - SHX * r * 0.2f, b.pz - SHZ * r * 0.2f, b.px + SHX * r * 0.4f, b.pz + SHZ * r * 0.4f,
                        r * 0.95f, r * 0.8f, 0.55f * k);
                ras.anyLight = false;
            }
        }
    }

    private void drawBody(Body b, float scale, float drop, int id) {
        if (b.material == Body.GOLD) {
            ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    b.hx * scale, b.hy * scale, b.hz * scale, b.tint, Raster.PAT_FLAT, id << 3);
            // a glint that wanders over it
            if (((int) (clock * 3f) % 5) == 0) {
                float vz = ras.project(b.px, b.maxY + drop, b.pz, scr);
                if (vz > 0.3f) ras.disc(scr[0], scr[1], 1.2f, 1f / (vz - 0.3f), 0xFFFFFFF0, id << 3);
            }
            return;
        }
        if (b.radius > 0f) {
            // the swirl in a ball is its own color, lit up
            ras.ball(b.px, b.py + drop, b.pz, b.radius * scale, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12,
                    b.r20, b.r21, b.r22, b.tint, Backdrop.mix(b.tint, 0xFFFFFFFF, 0.28f), id << 3,
                    b.material == Body.BALLOON);
            if (b.material == Body.BALLOON) {
                // the knot under it, tied off in a darker twist of the same rubber
                float kr = b.radius * scale;
                float vz = ras.project(b.px, b.py + drop - kr * 1.02f, b.pz, scr);
                if (vz > 0.3f) ras.disc(scr[0], scr[1], Math.max(0.8f, 0.09f * kr * ras.f / vz), 1f / (vz - kr),
                        Backdrop.scale(b.tint, 0.6f), id << 3);
            }
            return;
        }
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

    // ---- street things: turned shapes, solid, seen from outside ----

    /**
     * A solid of revolution round the body's own up axis, the near facets only: profile
     * heights as a share of the half height, radii as a share of r, a color per band.
     * Inward turns the facets to face the axis, for the inside of a hole. Rust, if any,
     * eats into a few facets; shine adds a sun glint.
     */
    private void lathe(Body b, float r, float hh, float drop, int id, float[] ys, float[] rs, int[] cols,
                       boolean inward, float rust, float shine) {
        float px = b.px, py = b.py + drop, pz = b.pz;
        float out = inward ? -1f : 1f;
        for (int seg = 0; seg < ys.length - 1; seg++) {
            float y0 = ys[seg] * hh, y1 = ys[seg + 1] * hh, r0 = rs[seg] * r, r1 = rs[seg + 1] * r;
            float slope = (r0 - r1) / Math.max(1e-4f, y1 - y0);
            for (int i = 0; i < SIDES; i++) {
                float ca = COS[i], sa = SIN[i], cb = COS[i + 1], sb = SIN[i + 1];
                float mc = (ca + cb) * 0.5f, ms = (sa + sb) * 0.5f;
                float nlx = mc * out, nly = slope * out, nlz = ms * out;
                float nl = (float) Math.sqrt(nlx * nlx + nly * nly + nlz * nlz);
                nlx /= nl;
                nly /= nl;
                nlz /= nl;
                float nx = b.r00 * nlx + b.r01 * nly + b.r02 * nlz;
                float ny = b.r10 * nlx + b.r11 * nly + b.r12 * nlz;
                float nz = b.r20 * nlx + b.r21 * nly + b.r22 * nlz;
                float ym = (y0 + y1) * 0.5f, rm = (r0 + r1) * 0.5f;
                float fxw = px + b.r00 * (mc * rm) + b.r01 * ym + b.r02 * (ms * rm);
                float fyw = py + b.r10 * (mc * rm) + b.r11 * ym + b.r12 * (ms * rm);
                float fzw = pz + b.r20 * (mc * rm) + b.r21 * ym + b.r22 * (ms * rm);
                float vx = ras.camX - fxw, vy = ras.camY - fyw, vz = ras.camZ - fzw;
                if (vx * nx + vy * ny + vz * nz <= 0f) continue;
                float ndl = nx * Raster.sunX() + ny * Raster.sunY() + nz * Raster.sunZ();
                int c = Raster.light(cols[seg], nx, ny, nz, ndl);
                if (shine > 0f) {
                    float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    float hx2 = Raster.sunX() + vx / vl, hy2 = Raster.sunY() + vy / vl, hz2 = Raster.sunZ() + vz / vl;
                    float hl = (float) Math.sqrt(hx2 * hx2 + hy2 * hy2 + hz2 * hz2);
                    float sp = Math.max(0f, (nx * hx2 + ny * hy2 + nz * hz2) / hl);
                    sp = sp * sp * sp * sp;
                    sp = sp * sp * sp;
                    c = Backdrop.mix(c, 0xFFFFFFFF, sp * shine);
                }
                if (rust > 0f) {
                    int h = (i * 7 + seg * 13 + id * 29) & 15;
                    if (h < 4) c = Backdrop.mix(c, 0xFF6E3F22, rust * (0.5f + h * 0.15f));
                }
                for (int k = 0; k < 4; k++) {
                    float cc = k == 0 || k == 3 ? ca : cb, ss = k == 0 || k == 3 ? sa : sb;
                    float rr = k < 2 ? r0 : r1, yy = k < 2 ? y0 : y1;
                    qx[k] = px + b.r00 * cc * rr + b.r01 * yy + b.r02 * ss * rr;
                    qy[k] = py + b.r10 * cc * rr + b.r11 * yy + b.r12 * ss * rr;
                    qz[k] = pz + b.r20 * cc * rr + b.r21 * yy + b.r22 * ss * rr;
                }
                ras.poly(qx, qy, qz, null, null, 4, c, Raster.PAT_FLAT, id << 3, false);
            }
        }
    }

    /**
     * A flat ring across the body's up axis at height y, centered off the axis by (ox, oz),
     * facing up or down; drawn only when it faces the eye. An inner radius of zero makes it
     * a disc.
     */
    private void ring(Body b, float y, float ox, float oz, float rIn, float rOut, float drop, int col, boolean up, int id) {
        float sg = up ? 1f : -1f;
        float nx = b.r01 * sg, ny = b.r11 * sg, nz = b.r21 * sg;
        float cx = b.px + b.r00 * ox + b.r01 * y + b.r02 * oz;
        float cy = b.py + drop + b.r10 * ox + b.r11 * y + b.r12 * oz;
        float cz = b.pz + b.r20 * ox + b.r21 * y + b.r22 * oz;
        if ((ras.camX - cx) * nx + (ras.camY - cy) * ny + (ras.camZ - cz) * nz <= 0f) return;
        int c = Raster.light(col, nx, ny, nz, nx * Raster.sunX() + ny * Raster.sunY() + nz * Raster.sunZ());
        if (rIn <= 0f) {
            for (int i = 0; i < SIDES; i++) {
                float lx = COS[i] * rOut, lz = SIN[i] * rOut;
                qx[i] = cx + b.r00 * lx + b.r02 * lz;
                qy[i] = cy + b.r10 * lx + b.r12 * lz;
                qz[i] = cz + b.r20 * lx + b.r22 * lz;
            }
            ras.poly(qx, qy, qz, null, null, SIDES, c, Raster.PAT_FLAT, id << 3, false);
            return;
        }
        for (int i = 0; i < SIDES; i++) {
            for (int k = 0; k < 4; k++) {
                int j = k == 0 || k == 3 ? i : i + 1;
                float rr = k < 2 ? rOut : rIn;
                float lx = COS[j] * rr, lz = SIN[j] * rr;
                qx[k] = cx + b.r00 * lx + b.r02 * lz;
                qy[k] = cy + b.r10 * lx + b.r12 * lz;
                qz[k] = cz + b.r20 * lx + b.r22 * lz;
            }
            ras.poly(qx, qy, qz, null, null, 4, c, Raster.PAT_FLAT, id << 3, false);
        }
    }

    // a steel drum: the chimes at the ends, two rolling hoops pressed out of the side
    private static final float[] DRUM_Y = {-1f, -0.93f, -0.4f, -0.35f, -0.3f, 0.3f, 0.35f, 0.4f, 0.93f, 1f};
    private static final float[] DRUM_R = {0.96f, 0.97f, 0.97f, 1.04f, 0.97f, 0.97f, 1.04f, 0.97f, 0.97f, 0.96f};
    private final int[] drumCol = new int[9];

    private void drawDrum(Body b, float scale, float drop, int id) {
        float r = b.hx * scale, hh = b.hy * scale;
        int paint = b.tint, rim = Backdrop.scale(paint, 0.72f), hoop = Backdrop.mix(paint, 0xFFFFFFFF, 0.1f);
        for (int k = 0; k < 9; k++) drumCol[k] = k == 0 || k == 8 ? rim : k == 2 || k == 3 || k == 5 || k == 6 ? hoop : paint;
        lathe(b, r, hh, drop, id, DRUM_Y, DRUM_R, drumCol, false, 0.55f, 0.45f);
        // the lid sits a little down inside the top chime; a bung near its edge
        int lid = Backdrop.scale(paint, 0.86f);
        ring(b, hh * 0.95f, 0f, 0f, 0f, r * 0.9f, drop, lid, true, id);
        ring(b, hh, 0f, 0f, r * 0.86f, r * 0.96f, drop, rim, true, id);
        ring(b, hh * 0.955f, r * 0.5f, r * 0.2f, 0f, r * 0.12f, drop, 0xFF3A3A3C, true, id);
        ring(b, -hh, 0f, 0f, 0f, r * 0.96f, drop, rim, false, id);
    }

    // a road cone: a square black foot, the cone in orange with two white bands
    private static final float[] CONE_Y = {-0.8f, -0.12f, 0.12f, 0.36f, 0.56f, 1f};
    private static final float[] CONE_R = new float[CONE_Y.length];
    private static final int[] CONE_COL = {0xFFF0641E, 0xFFF4F2EC, 0xFFF0641E, 0xFFF4F2EC, 0xFFF0641E};

    static {
        for (int i = 0; i < CONE_Y.length; i++) CONE_R[i] = 0.74f - (CONE_Y[i] + 0.8f) / 1.8f * 0.62f;
    }

    private void drawCone(Body b, float scale, float drop, int id) {
        float r = b.hx * scale, hh = b.hy * scale;
        float fy = -hh * 0.9f;
        ras.box(b.px + b.r01 * fy, b.py + drop + b.r11 * fy, b.pz + b.r21 * fy, b.r00, b.r01, b.r02,
                b.r10, b.r11, b.r12, b.r20, b.r21, b.r22, r, hh * 0.1f, b.hz * scale, 0xFF34302E, Raster.PAT_FLAT, id << 3);
        lathe(b, r, hh, drop, id, CONE_Y, CONE_R, CONE_COL, false, 0f, 0.3f);
        ring(b, hh, 0f, 0f, 0f, r * CONE_R[CONE_R.length - 1], drop, 0xFFD85414, true, id);
    }

    // a car tyre lying flat: the tread, the sidewalls round the hole, the inside of the hole
    private static final float[] TYRE_Y = {-1f, -0.62f, -0.12f, 0.12f, 0.62f, 1f};
    private static final float[] TYRE_R = {0.86f, 1f, 1f, 0.97f, 1f, 0.86f};
    private static final float[] HOLE_Y = {-1f, 1f};
    private static final float[] HOLE_R = {0.52f, 0.52f};
    private final int[] tyreCol = new int[5];
    private static final int[] HOLE_COL = {0xFF141415};

    private void drawTyre(Body b, float scale, float drop, int id) {
        float r = b.hx * scale, hh = b.hy * scale;
        int rubber = b.tint, side = Backdrop.scale(rubber, 1.15f);
        // the tread, worn shiny, split by a dark groove round the middle
        tyreCol[0] = side;
        tyreCol[1] = Backdrop.scale(rubber, 0.9f);
        tyreCol[2] = 0xFF121213;
        tyreCol[3] = Backdrop.scale(rubber, 0.9f);
        tyreCol[4] = side;
        lathe(b, r, hh, drop, id, HOLE_Y, HOLE_R, HOLE_COL, true, 0f, 0f);
        lathe(b, r, hh, drop, id, TYRE_Y, TYRE_R, tyreCol, false, 0f, 0.25f);
        ring(b, hh, 0f, 0f, r * 0.52f, r * 0.86f, drop, side, true, id);
        ring(b, -hh, 0f, 0f, r * 0.52f, r * 0.86f, drop, side, false, id);
    }

    /** A bag of sand: a box of sacking rounded off by layers, a seam across one end. */
    private void drawBag(Body b, float scale, float drop, int id) {
        float hx = b.hx * scale, hy = b.hy * scale, hz = b.hz * scale;
        int c = b.tint;
        ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                hx, hy * 0.62f, hz, c, Raster.PAT_GRAIN, id << 3);
        ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                hx * 0.95f, hy * 0.86f, hz * 0.9f, c, Raster.PAT_GRAIN, id << 3);
        ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                hx * 0.86f, hy, hz * 0.76f, Backdrop.mix(c, 0xFFFFFFFF, 0.06f), Raster.PAT_GRAIN, id << 3);
        float sx = hx * 0.8f;
        ras.box(b.px + b.r00 * sx, b.py + drop + b.r10 * sx, b.pz + b.r20 * sx, b.r00, b.r01, b.r02,
                b.r10, b.r11, b.r12, b.r20, b.r21, b.r22, hx * 0.03f, hy * 0.9f, hz * 0.93f,
                Backdrop.scale(c, 0.7f), Raster.PAT_FLAT, id << 3);
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
    // the gift of the gods: a dark wrought head, broader and deeper, bound in bronze, on a stout handle
    private static final float[][] HEAVY_PARTS = {
            {-0.1f, 0f, 0f, 0.31f, 0.038f, 0.038f},
            {-0.36f, 0f, 0f, 0.07f, 0.044f, 0.044f},
            {0.31f, 0f, 0f, 0.13f, 0.1f, 0.2f},
            {0.31f, 0f, 0.15f, 0.134f, 0.104f, 0.02f},
            {0.31f, 0f, -0.15f, 0.134f, 0.104f, 0.02f},
    };
    private static final int[] HEAVY_COL = {0xFF6E4A2C, 0xFF3E2618, 0xFF3F3D44, 0xFFA8783A, 0xFFA8783A};
    private static final int[] HEAVY_PAT = {Raster.PAT_GRAIN, Raster.PAT_FLAT, Raster.PAT_STONE, Raster.PAT_FLAT, Raster.PAT_FLAT};

    private final float[] pose = new float[12];

    /**
     * A hammer held near the camera: at a point given in view space, its handle along one
     * view direction, the faces of its head across another. Fills pose: position, then axes.
     */
    private void heldPose(float vx, float vy, float vz, float ux, float uy, float uz, float wx, float wy, float wz) {
        ras.viewToWorld(vx, vy, vz, tmp);
        pose[0] = tmp[0] + CAM_X;
        pose[1] = tmp[1] + CAM_Y;
        pose[2] = tmp[2];
        ras.viewToWorld(ux, uy, uz, dirA);
        float ax = dirA[0], ay = dirA[1], az = dirA[2];
        float l = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= l;
        ay /= l;
        az /= l;
        ras.viewToWorld(wx, wy, wz, dirA);
        float zx = dirA[0], zy = dirA[1], zz = dirA[2];
        float d = zx * ax + zy * ay + zz * az;
        zx -= d * ax;
        zy -= d * ay;
        zz -= d * az;
        l = (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
        zx /= l;
        zy /= l;
        zz /= l;
        pose[3] = ax;
        pose[6] = ay;
        pose[9] = az;
        pose[4] = zy * az - zz * ay;
        pose[7] = zz * ax - zx * az;
        pose[10] = zx * ay - zy * ax;
        pose[5] = zx;
        pose[8] = zy;
        pose[11] = zz;
    }

    private void drawHammer() {
        if (hs == HAND) {
            float bob = (float) Math.sin(clock * 1.7f) * 0.008f;
            // held upright, head up and to the left, tilted toward the street
            heldPose(0.3f, -0.7f + bob, 1.35f, -0.42f, 0.86f, 0.28f, 0.94f, 0.2f, -0.28f);
            float ax = pose[3], ay = pose[6], az = pose[9];
            if (aiming) pose[1] += 0.06f;
            // the handle runs down off the screen: move the hammer along its own axis
            pose[0] -= ax * 0.05f;
            pose[1] -= ay * 0.05f;
            pose[2] -= az * 0.05f;
            if (aiming) {
                // the wind-up draws it back and down toward the shoulder; a tired arm shakes it
                float k = power * power * (3f - 2f * power);
                ras.viewToWorld(0.06f * k + tremX * 0.002f, -0.06f * k + tremY * 0.002f, -0.07f * k, dirA);
                pose[0] += dirA[0];
                pose[1] += dirA[1];
                pose[2] += dirA[2];
            }
        } else {
            Body b = hammer;
            pose[0] = b.px;
            pose[1] = b.py;
            pose[2] = b.pz;
            pose[3] = b.r00;
            pose[4] = b.r01;
            pose[5] = b.r02;
            pose[6] = b.r10;
            pose[7] = b.r11;
            pose[8] = b.r12;
            pose[9] = b.r20;
            pose[10] = b.r21;
            pose[11] = b.r22;
            ras.shadowBox(b.px, b.py, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    b.hx, b.hy * 0.8f, b.hz);
        }
        boolean heavy = hs == HAND ? heavyOn : heavyThrow;
        boolean gold = hs == HAND ? goldOn : goldThrow;
        drawParts(heavy || gold, 1f, 900, gold);
        if (golem != null) return;
        // the other hammer waits at the lower left, in sight and in reach of a tap
        if (heavyLeft > 0 || heavy) {
            heldPose(-0.5f, -0.96f, 1.65f, 0.5f, 0.8f, 0.2f, 0.94f, -0.2f, 0.28f);
            drawParts(!heavy, 0.62f, 920, false);
        }
    }

    private static final int[] GOLD_COL = {0xFF8A5A32, 0xFF5A3418, 0xFFF2C64A, 0xFFFFF0B0, 0xFFFFF0B0};
    private static final int[] GOLD_PAT = {Raster.PAT_GRAIN, Raster.PAT_FLAT, Raster.PAT_FLAT, Raster.PAT_FLAT, Raster.PAT_FLAT};

    private static final int[] DIAMOND_COL = {0xFF7A5A3A, 0xFF3E2618, 0xFF6CC8EC, 0xFFE8FAFF, 0xFFE8FAFF};

    private void drawParts(boolean heavy, float scale, int id, boolean gold) {
        boolean diamond = golem != null && id == 900;
        if (diamond) heavy = true;
        float[][] parts = heavy ? HEAVY_PARTS : PARTS;
        int[] cols = diamond ? DIAMOND_COL : gold ? GOLD_COL : heavy ? HEAVY_COL : PART_COL;
        int[] pats = diamond || gold ? GOLD_PAT : heavy ? HEAVY_PAT : PART_PAT;
        float px = pose[0], py = pose[1], pz = pose[2];
        float r00 = pose[3], r01 = pose[4], r02 = pose[5], r10 = pose[6], r11 = pose[7], r12 = pose[8];
        float r20 = pose[9], r21 = pose[10], r22 = pose[11];
        for (int i = 0; i < parts.length; i++) {
            float[] p = parts[i];
            float ox = p[0] * scale, oy = p[1] * scale, oz = p[2] * scale;
            float cx = px + r00 * ox + r01 * oy + r02 * oz;
            float cy = py + r10 * ox + r11 * oy + r12 * oz;
            float cz = pz + r20 * ox + r21 * oy + r22 * oz;
            ras.box(cx, cy, cz, r00, r01, r02, r10, r11, r12, r20, r21, r22, p[3] * scale, p[4] * scale, p[5] * scale,
                    cols[i], pats[i], (id + (i == 2 ? 1 : i > 2 ? 2 + i : 0)) << 3);
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
        target(aimX + tremX, aimY + tremY, tmp);
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
        int cx = Math.round(aimX + tremX), cy = Math.round(aimY + tremY);
        ring(cx, cy, 6, 0xFFFFF6D8);
        ring(cx, cy, 5, 0xFFF2B33A);
        // the wind-up: a band round the ring fills clockwise from the top, and glows when full
        boolean full = power >= 1f;
        int band = full ? (((int) (clock * 8f) & 1) == 0 ? 0xFFFFFFFF : 0xFFFFE27A) : 0xFFFFC93A;
        int steps = Math.round(power * 64f);
        for (int a = 0; a < steps; a++) {
            double ang = -Math.PI / 2 + a * Math.PI * 2 / 64;
            for (int r = 8; r <= 9; r++)
                put(cx + (int) Math.round(Math.cos(ang) * r), cy + (int) Math.round(Math.sin(ang) * r), band);
        }
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
        // the heavy throws left: small dark hammers under the stars, ringed in gold when in hand
        for (int i = 0; i < heavyLeft; i++) heavyMark(f, x + i * 13 + 1, y + 16, heavyOn);
        // weight still on the plinth, with the line it has to fall below
        float on = onPlinth();
        int bw = 58, bx = W - bw - 7, by = 9;
        f.rect(bx - 1, by - 1, bx + bw + 1, by + 7, 0xFF8A5A12);
        f.rect(bx, by, bx + bw, by + 6, 0xFFF3E9D2);
        int fill = Math.round(bw * on);
        f.rect(bx, by, bx + fill, by + 6, on <= 1f - CLEAR ? 0xFF6FBF5A : 0xFFE8963C);
        int mark = bx + Math.round(bw * (1f - CLEAR));
        f.rect(mark, by - 3, mark + 1, by + 9, 0xFF5A3A0E);
        String lv = String.valueOf(level + 1);
        PixelFont.draw(f, lv, bx + bw - PixelFont.width(lv, 1), by + 12, 1, 0xFF6A4A1A);
        String v = VERSION;
        PixelFont.draw(f, v, W - PixelFont.width(v, 1) - 3, h - 10, 1, 0xFF8A8478);
    }

    /** A tiny heavy hammer for the HUD, 11 by 8 pixels, from its top left corner. */
    private void heavyMark(Px f, int x, int y, boolean lit) {
        if (lit) f.rect(x - 1, y - 1, x + 12, y + 9, 0xFFFFC93A);
        f.rect(x, y, x + 11, y + 8, 0xFF2E2A28);
        f.rect(x + 1, y + 1, x + 7, y + 5, 0xFF6A676A);   // the head
        f.rect(x + 1, y + 1, x + 7, y + 2, 0xFF8E8A8C);
        f.rect(x + 2, y + 1, x + 3, y + 5, 0xFFC08A3C);   // its bronze bands
        f.rect(x + 5, y + 1, x + 6, y + 5, 0xFFC08A3C);
        f.rect(x + 7, y + 2, x + 10, y + 4, 0xFF8A5A32);  // the handle
    }

    /**
     * The gift, without a word: the heavy hammer drops from the thunderer's cloud and tumbles
     * down past the gods toward the street, a glint on its head.
     */
    private void drawGift(Px f, float t) {
        float fall = Math.min(1.6f, t);
        float cx = W * 0.5f - 30f * fall, cy = h * 0.24f + 40f * fall + 230f * fall * fall;
        if (cy > h + 30) return;
        float ang = 0.6f + t * 5.2f, s = 24f;
        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
        int r = (int) (s * 1.1f) + 2;
        for (int y = -r; y <= r; y++)
            for (int x = -r; x <= r; x++) {
                // into the hammer's own frame: u along the handle, v across
                float u = (x * ca + y * sa) / s, v = (-x * sa + y * ca) / s;
                int c = 0;
                if (u > 0.2f && u < 0.62f && Math.abs(v) < 0.34f) {
                    c = (u > 0.27f && u < 0.32f) || (u > 0.5f && u < 0.55f)
                            ? (diamondGift ? 0xFFFFFFFF : goldGift ? 0xFFFFF0B0 : 0xFFC08A3C)
                            : diamondGift ? 0xFFBFEFFF : goldGift ? 0xFFE8B83A : 0xFF4A484E;
                    if (v < -0.2f) c = Backdrop.scale(c, 1.35f);
                } else if (u > -0.75f && u <= 0.2f && Math.abs(v) < 0.08f) {
                    c = v < 0f ? 0xFF8A5A32 : 0xFF6A4226;
                }
                if (c != 0) f.set(Math.round(cx) + x, Math.round(cy) + y, c);
            }
        // the glint
        if (((int) (t * 10f) & 3) == 0) {
            int gx = Math.round(cx + ca * s * 0.45f), gy = Math.round(cy + sa * s * 0.45f);
            f.set(gx, gy, 0xFFFFFFFF);
            f.set(gx - 1, gy, 0xFFFFF0C0);
            f.set(gx + 1, gy, 0xFFFFF0C0);
            f.set(gx, gy - 1, 0xFFFFF0C0);
            f.set(gx, gy + 1, 0xFFFFF0C0);
        }
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
