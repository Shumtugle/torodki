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
    static final String VERSION = "0.63";   // shown on screen; keep in step with versionName

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
    static final float FINGER = 34f;         // pixels a ring is lifted to show above a finger
    private int[] bgCol;
    private float[] bgZ;
    private byte[] bgLit;
    private final Backdrop backdrop = new Backdrop();
    // the winter ocean beyond the portal: the levels after the 101st are played there
    static final int SEA_FROM = 101;
    private final Ocean ocean = new Ocean();
    private final Ocean.Mask seaMask = new Ocean.Mask(), zMaskA = new Ocean.Mask(), zMaskB = new Ocean.Mask();
    private Ocean.Mask zSeaMask = zMaskA;
    boolean atSea;
    private boolean bgSea, zoomSea;
    private final float[] callAt3 = new float[3];

    static boolean oceanLevel(int lv) {
        return lv >= SEA_FROM;
    }

    /**
     * Out at sea the iceberg stands nearer than the last buildings of the street, so that
     * what stands on it can be made out and a throw at it lands in time: twenty meters
     * farther than the first plinth, then two more every ten levels, thirty at most.
     */
    static float seaFar(int n) {
        return Math.min(30f, 20f + 2f * (n / 10));
    }
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
    // past the 101st building the plinth holds a ring of stone: a hammer through it opens the way out to sea
    static final int PORTAL = 9;
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
    // the marks in the corner lag behind the gift: they come in once the view is back down
    // from the sky, where the gods were seen to throw it, one after another, each dropping
    // into its place with a little bounce and a glint
    private int heavyShown, marksLanded;
    private float markT = -1f;
    private static final float MARK_FALL = 0.32f, MARK_GAP = 0.24f;
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
    // what a particle is: a speck, a shirt or a postcard fluttering down, a spring
    private static final int SPECK = 0, SHIRT = 1, CARD = 2, SPRING = 3, CHIP = 4;
    // what the golem's shell is made of: stone, darker stone, brick, plank, rusty iron
    private static final int[] SHELL = {0xFFC9BEAD, 0xFFB0A592, 0xFFB4523A, 0xFF9C4632, 0xFFA87444, 0xFF6E3A22};
    private final int[] pKind = new int[MAXP];
    private int pN;
    private final Fliers fliers = new Fliers();
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
        heavyShown = heavyLeft;
        fails = Math.max(0, host.loadFails());
        goldSeen = host.loadGoldSeen();
        cubes = Math.max(0, host.loadInt("cubes"));
        cubesDue = Math.max(0, host.loadInt("cubesDue"));
        // a game saved while the gods still handed over all they held at once: what was
        // handed over that way goes back to them, to be won again figure by figure
        if (host.loadInt("cubeRule") == 0) {
            cubesDue += cubes;
            cubes = 0;
            host.saveInt("cubes", 0);
            host.saveInt("cubesDue", cubesDue);
            host.saveInt("cubeRule", 1);
        }
        atSea = oceanLevel(level);
        if (!Builder.hasWinter()) Builder.readWinter(host.asset("winter.bin"));
        intro = new Intro(host);
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
        ocean.snowFor(W, h);
        // the street is painted first, for the gods' sky to be made from
        paintBg(false);
        sky.build(ras, host.asset("gods.bin"), host.asset("clouds.bin"));
        if (ringCol == null) readPortal(host.asset("portal.bin"));
        if (atSea) paintBg(true);
        paintAlley();
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

    /** The backdrop for the usual camera: the street, or the ocean. */
    private void paintBg(boolean sea) {
        Raster.overcast(sea);
        bgSea = sea;
        if (sea) ocean.paint(normRas, seaMask);
        else backdrop.paint(normRas, 11L);
        bgCol = normRas.col.clone();
        bgZ = normRas.zb.clone();
        bgLit = normRas.lit.clone();
    }

    /** Raises the building for a level: tested for standing still, asleep, ready to fall. */
    void startLevel(int lv, boolean animate) {
        if (alley != null) endAlley();
        // the farthest level reached is kept before the level changes: going back must not lose it
        int far = Math.max(host.loadInt("best"), Math.max(level + 1, lv + 1));
        if (far != host.loadInt("best")) host.saveInt("best", far);
        if (lv != level) {
            fails = 0;
            host.saveFails(0);
        }
        episodeRun(lv);
        level = lv;
        goldOn = fails >= GOLD_AFTER;
        host.saveLevel(level);
        seed = seedOf(lv);
        if (zoomed) setZoomed(false);
        zoomK = 0f;
        atSea = oceanLevel(lv);
        rows = twoRows(lv);
        Builder.PHZ = rows ? Builder.PHZ2 : Builder.PHZ1;
        if (bgSea != atSea && normRas != null) paintBg(atSea);
        Builder.CZ = Builder.BASE_CZ + (atSea ? seaFar(lv - SEA_FROM) : Builder.farther(lv));
        driftT = 0f;
        Builder.CX = drifting(lv) ? driftAt(0f) : 0f;
        if (atSea && skipLevel(lv) && normRas != null) paintSeaAlley();
        prepareZoom();
        if (atSea && drifting(lv) && normRas != null) paintPano();
        phys.clear();
        pieces.clear();
        // out at sea the plinth is a block of old blue ice
        plinth = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ, Body.PLINTH, atSea ? 0xFFB9D4E2 : 0xFFF2E3C6);
        plinth.setPose(Builder.CX, Builder.TOP * 0.5f, Builder.CZ, 1, 0, 0, 0);
        phys.add(plinth);
        golem = null;
        for (int i = 0; i < FLY_MAX; i++) flyState[i] = 0;
        life.park();
        // the parked cars, as solid as the plinth: the hammer and the blocks bounce off them, and dent them
        for (int i = 0; i < cars.length; i++) {
            cars[i] = new Body(0.95f, 0.65f, 2.3f, Body.CAR, 0);
            cars[i].setPose(life.parkX(i), 0.65f, life.parkZ(i), 1, 0, 0, 0);
            // no cars out at sea
            if (atSea) cars[i].setPose(life.parkX(i), -60f, life.parkZ(i), 1, 0, 0, 0);
            else phys.add(cars[i]);
        }
        ArrayList<Body> made = takePrepared(lv);
        if (made == null) made = buildingFor(lv);
        // grown at the first plinth spot: carried along to where the plinth stands now
        float shift = Builder.CZ - Builder.BASE_CZ;
        for (Body b : made) {
            b.homeZ += shift;
            b.homeX += Builder.CX;
        }
        totalMass = 0f;
        rowMass[0] = rowMass[1] = 0f;
        for (Body b : made) {
            b.setPose(b.homeX, b.homeY, b.homeZ, 1, 0, 0, 0);
            b.asleep = true;
            phys.add(b);
            pieces.add(b);
            totalMass += b.mass;
            rowMass[b.row] += b.mass;
        }
        if (atSea) {
            // out at sea a good part of every building is ice, and snow lies on its tops
            java.util.Random ir = new java.util.Random(seed ^ 0x1CEL);
            boolean set = Builder.hasWinter();
            for (Body b : made) {
                boolean block = b.radius == 0f && (b.material == Body.WOOD || b.material == Body.STONE);
                // the winter set brings its own ice; a grown building is iced here
                if (!set && block && ir.nextFloat() < 0.42f) {
                    b.icy = true;
                    b.tint = ir.nextBoolean() ? 0xFFB3D5E5 : 0xFFC6E1EC;
                }
                b.snowy = block && ir.nextFloat() < 0.9f;
            }
        }
        goldCube = null;
        if (goldSeen && lv > 0 && new java.util.Random(seed ^ 0x601DL).nextFloat() < CUBE_CHANCE) crown(made);
        hammer = new Body(0.42f, 0.08f, 0.17f, Body.HAMMER, 0xFFD9A066);
        hammer.ghost = true;
        hammer.asleep = false;
        phys.add(hammer);
        buildFromSky = false;
        lastBuildTime = 0f;
        stars = starsMax();
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
        if (twoRows(lv) && Builder.hasWinter()) {
            ArrayList<Body> both = twoBuildings(lv);
            if (both != null) return both;
        }
        if (oceanLevel(lv) && Builder.hasWinter()) {
            // out at sea, the winter set; one that will not stand this way round may the other,
            // and should neither, a grown one instead
            for (int turn = 0; turn < 2; turn++) {
                ArrayList<Body> made = new Builder().winter(lv - SEA_FROM, seedOf(lv), turn == 1);
                if (standsStill(made)) return made;
            }
        }
        return standingBuilding(new Builder(), lv, seedOf(lv));
    }

    // ---- two rows ----
    // From the 130th on, out at sea, the plinth carries two buildings, one behind the other:
    // a low one in front and a tall one behind it, showing over it. Both have to come down:
    // at one throw, or the front one first and then the one behind.

    static boolean twoRows(int lv) {
        return oceanLevel(lv) && lv - SEA_FROM >= 28 && !skipLevel(lv);
    }

    /** The two buildings, each tested on its own, set before and behind the plinth's middle. */
    static ArrayList<Body> twoBuildings(int lv) {
        int n = lv - SEA_FROM;
        int[] pick = Builder.rowsFor(n);
        long sd = seedOf(lv);
        ArrayList<Body> both = new ArrayList<Body>();
        for (int r = 0; r < 2; r++) {
            ArrayList<Body> one = null;
            // mirrored on alternate rounds of the set, the back row the other way from the front
            boolean mirror = ((n / 50) % 2 == 1) != (r == 1);
            for (int turn = 0; turn < 2 && one == null; turn++) {
                ArrayList<Body> made = new Builder().winterOne(pick[r], sd + r * 104729L, mirror != (turn == 1));
                if (standsStill(made)) one = made;
            }
            if (one == null) return null;
            float dz = r == 0 ? -Builder.ROW : Builder.ROW;
            for (Body b : one) {
                b.homeZ += dz;
                b.row = (byte) r;
                // the row behind stands in the front one's shade: a shade darker, a touch bluer
                if (r == 1) b.tint = Backdrop.mix(b.tint, 0xFF34465A, 0.3f);
            }
            both.addAll(one);
        }
        return both;
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
        Body pl = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ1, Body.PLINTH, 0);
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
            if (Math.abs(b.px) < Builder.PHX + 0.05f && Math.abs(b.pz - Builder.BASE_CZ) < Builder.PHZ1 + 0.05f
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
        Body pl = new Body(Builder.PHX, Builder.TOP * 0.5f, Builder.PHZ1, Body.PLINTH, 0);
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
        float cx = Builder.CX;
        if (g.px < cx - mx || g.px > cx + mx) {
            g.px = Math.max(cx - mx, Math.min(cx + mx, g.px));
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
            if (stillOn(b)) m += b.mass;
        return totalMass > 0f ? m / totalMass : 0f;
    }

    boolean rows;                     // two buildings on the plinth this level
    final float[] rowMass = new float[2];

    private boolean stillOn(Body b) {
        return !b.gone && b != goldCube && Math.abs(b.px - Builder.CX) < Builder.PHX + 0.05f && Math.abs(b.pz - Builder.CZ) < Builder.PHZ + 0.05f
                && b.py > Builder.TOP - 0.05f;
    }

    /** With two rows: the share of one of them still on the plinth, wherever on it. */
    float onPlinth(int row) {
        float m = 0f;
        for (Body b : pieces)
            if (b.row == row && stillOn(b)) m += b.mass;
        return rowMass[row] > 0f ? m / rowMass[row] : 0f;
    }

    /** The share to knock off: of each of two buildings, a little less than of one. */
    float clearShare() {
        return rows ? 0.65f : CLEAR;
    }

    /** Throws for a building: two buildings on the plinth bring two more. */
    int starsMax() {
        return rows ? STARS + 2 : STARS;
    }

    /** What has yet to fall: with two rows, the one that stands more. */
    float standing() {
        return rows ? Math.max(onPlinth(0), onPlinth(1)) : onPlinth();
    }

    // ---- input ----

    void touch(int type, float x, float y) {
        if (splashing()) {
            // a tap goes straight on to the game
            if (type == DOWN) intro.skip();
            return;
        }
        if (bridge != null) {
            bridge.touch(type, x, y);
            return;
        }
        if (alley != null) {
            if (alley.phase == Alley.CUBES) {
                if (type == DOWN && alley.signsReady()) {
                    // the cube under the finger, or the nearest to it
                    int best = -1;
                    float bd = 1e9f;
                    for (int k = 0; k < 5; k++) {
                        Body b = alley.sign[k];
                        if (b == null || b.gone) continue;
                        float vz = alleyRas.project(b.px, b.py, b.pz, scr);
                        float r = Alley.SIGN * alleyRas.f / vz * 1.6f + 6f;
                        float d = (float) Math.hypot(x - scr[0], y - scr[1]);
                        if (d < r && d < bd) {
                            bd = d;
                            best = k;
                        }
                    }
                    if (best >= 0) alley.strike(best);
                }
                return;
            }
            alley.touch(type, x, y, clock);
            return;
        }
        if (atSea && golem == null) {
            // the picture rocks with the floe: the finger means the point in the steady one
            unrock(x, y, scr);
            x = scr[0];
            y = scr[1];
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
            boolean portal = state == PORTAL && portalIn < 0f && stateTime > PORTAL_RISE;
            if (!fight && !portal && (state != PLAY || stars <= 0) || hs != HAND) return;
            // the close view must show the iceberg where it is now, before it closes in
            if (atSea && drifting(level) && !zoomed && zoomK == 0f) {
                followZoom();
                if (Math.abs(Builder.CX - zoomForX) > 0.6f) prepareZoom();
            }
            aiming = true;
            lastX = x;
            lastY = y;
            if (!aimedBefore) buildingCenter();
            else {
                baseX = aimX;
                baseY = aimY;
            }
            // at the drifting iceberg the last throw's spot has long been left behind, and the
            // player puts the finger where he means to aim, ahead of it: the ring starts there.
            // Closing in, the view enlarges that spot away from the finger, so it stays in sight
            if (atSea && drifting(level) && golem == null) {
                baseX = x;
                baseY = zoomed ? y - FINGER : y;
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
            ras.project(Builder.CX, Builder.TOP + 1f, Builder.CZ, scr);
            baseX = scr[0];
            baseY = scr[1];
        }
    }

    void back() {
        if (bridge != null) closeBridge();
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
    /**
     * Seconds in the air over a distance: beyond forty-two meters the arm puts more into it,
     * so the farthest plinths are not reached by a lob that hangs in the sky for ages.
     */
    private static float flight(float dist, float speed) {
        float reach = dist > 42f ? (float) Math.sqrt(dist / 42f) : 1f;
        return Math.max(0.18f, dist / (speed * reach));
    }

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
        if (Builder.CZ == zoomFor && zoomSea == atSea && Builder.CX == zoomForX) return;
        zoomFor = Builder.CZ;
        zoomSea = atSea;
        zoomForX = Builder.CX;
        float lookY = Builder.TOP + 1.4f, dx = Builder.CX - CAM_X, dz = Builder.CZ;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.atan2(dx, dz), pitch = (float) Math.atan2(lookY - CAM_Y, dist);
        zoomZ = Math.max(1.25f, Math.min(4.6f, Builder.CZ / Builder.BASE_CZ * 0.92f));
        // with the iceberg drifting the close view is a little wider: room to aim ahead of it
        if (atSea && drifting(level)) zoomZ *= 0.85f;
        if (zoomRas == null) zoomRas = new Raster(W, h);
        zoomRas.camera(CAM_X, CAM_Y, 0f, yaw, pitch, focal * zoomZ, h * 0.5f);
        zoomPitch = pitch;
        if (atSea) ocean.paint(zoomRas, zSeaMask);
        else backdrop.paint(zoomRas, 11L);
        zCol = zoomRas.col.clone();
        zZ = zoomRas.zb.clone();
        zLit = zoomRas.lit.clone();
        // where the zoomed view's middle sits in the usual view: the closing-in heads there
        normRas.project(Builder.CX, lookY, Builder.CZ, scr);
        zPX = scr[0];
        zPY = scr[1];
    }

    // ---- the close view that follows the iceberg ----
    // At the drifting iceberg the close view turns after it, at seven tenths of its pace:
    // the iceberg still creeps toward the edge, but never out of sight. The sea for it is
    // painted once, three screens wide, and each frame the columns the view now looks along
    // are taken from it. The ring stays on its spot of the world as the view turns.

    private Raster pano;
    private final Ocean.Mask panoMask = new Ocean.Mask();
    private volatile boolean panoDone;
    private float panoCZ, panoYaw, zoomPitch, followYaw, lastTargetYaw;
    private Thread panoPaint;
    private int[] colMap;
    private float[] colDepth;

    private boolean panoReady() {
        return panoDone && panoCZ == Builder.CZ && pano != null;
    }

    private void paintPano() {
        if (zoomZ <= 1f) return;
        panoDone = false;
        if (panoPaint != null) {
            try {
                panoPaint.join();
            } catch (InterruptedException e) {
                return;
            }
        }
        if (pano == null || pano.h != h) pano = new Raster(3 * W, h);
        final Raster r = pano;
        final float cz = Builder.CZ;
        float mid = CAM_X + cz * (float) Math.tan(YAW);
        panoYaw = (float) Math.atan2(mid - CAM_X, cz);
        r.camera(CAM_X, CAM_Y, 0f, panoYaw, zoomPitch, focal * zoomZ, h * 0.5f);
        panoPaint = new Thread(new Runnable() {
            @Override
            public void run() {
                new Ocean().paint(r, panoMask);
                panoCZ = cz;
                panoDone = true;
            }
        });
        panoPaint.setDaemon(true);
        panoPaint.start();
    }

    /** Where the iceberg is, as a turn of the head from the player. */
    private float icebergYaw() {
        return (float) Math.atan2(Builder.CX - CAM_X, Builder.CZ);
    }

    /** The close view's turn, a frame on: it follows the iceberg at seven tenths of its pace. */
    private void follow() {
        if (!atSea || !drifting(level) || !panoReady()) return;
        float target = icebergYaw();
        if (!zoomed && zoomK <= 0f) {
            followYaw = target;
        } else {
            float old = followYaw;
            followYaw += 0.7f * (target - lastTargetYaw);
            float d = followYaw - old;
            if (zoomed && d != 0f) {
                // the ring keeps its place in the world
                float F = focal * zoomZ;
                aimX = Math.max(2f, Math.min(W - 3f, W * 0.5f + F * (float) Math.tan(Math.atan((aimX - W * 0.5f) / F) - d)));
                baseX = W * 0.5f + F * (float) Math.tan(Math.atan((baseX - W * 0.5f) / F) - d);
            }
        }
        lastTargetYaw = target;
        zoomRas.camera(CAM_X, CAM_Y, 0f, followYaw, zoomPitch, focal * zoomZ, h * 0.5f);
        // where the close view's middle is in the usual view, for closing in and out
        float lookY = Builder.TOP + 1.4f, dist = (float) Math.hypot(Builder.CX - CAM_X, Builder.CZ);
        normRas.project(CAM_X + (float) Math.sin(followYaw) * dist, lookY, (float) Math.cos(followYaw) * dist, scr);
        zPX = scr[0];
        zPY = scr[1];
    }

    /** The sea behind the close view, taken column by column from the wide painting. */
    private void panoBackground() {
        int pw = pano.w;
        if (colMap == null || colMap.length != W) {
            colMap = new int[W];
            colDepth = new float[W];
        }
        float F = focal * zoomZ, pcx = pw * 0.5f;
        int lo = pw, hi = 0;
        for (int x = 0; x < W; x++) {
            float ts = (float) Math.atan((x + 0.5f - W * 0.5f) / F);
            float tp = followYaw + ts - panoYaw;
            int xp = (int) (pcx + F * (float) Math.tan(tp));
            if (xp < 0) xp = 0;
            if (xp >= pw) xp = pw - 1;
            colMap[x] = xp;
            // depth along the turned view's own axis
            colDepth[x] = (float) (Math.cos(tp) / Math.cos(ts));
            lo = Math.min(lo, xp);
            hi = Math.max(hi, xp);
        }
        ocean.seaCols(pano, panoMask, clock, lo, hi);
        int[] pc = pano.col;
        float[] pz = pano.zb;
        byte[] pl = pano.lit;
        for (int y = 0; y < h; y++) {
            int rp = y * pw, rs = y * W;
            for (int x = 0; x < W; x++) {
                int k = rp + colMap[x];
                ras.col[rs + x] = pc[k];
                ras.zb[rs + x] = pz[k] * colDepth[x];
                ras.lit[rs + x] = pl[k];
            }
        }
    }

    // the zoomed view follows the drifting iceberg: painted afresh in the background once it
    // has drifted off the middle, and swapped in while the view is wide
    private float zoomForX;
    private Raster zoomSpare;
    private Thread zoomPaint;
    private volatile boolean zoomReady;
    private float zNextX, zNextCZ;
    private int[] zNextCol;
    private float[] zNextZ;
    private byte[] zNextLit;

    private void followZoom() {
        if (!atSea || !drifting(level) || zoomRas == null || zoomZ <= 1f || panoReady()) return;
        if (zoomReady) {
            if (zoomed || zoomK > 0f) return;
            if (zNextCZ != Builder.CZ || Math.abs(zNextX - Builder.CX) > Math.abs(zoomForX - Builder.CX)) {
                // painted for another level: of no use now
                zoomReady = false;
                zoomPaint = null;
                return;
            }
            Raster r = zoomRas;
            zoomRas = zoomSpare;
            zoomSpare = r;
            zCol = zNextCol;
            zZ = zNextZ;
            zLit = zNextLit;
            zSeaMask = zSeaMask == zMaskA ? zMaskB : zMaskA;
            zoomForX = zNextX;
            normRas.project(zNextX, Builder.TOP + 1.4f, Builder.CZ, scr);
            zPX = scr[0];
            zPY = scr[1];
            zoomReady = false;
            zoomPaint = null;
            return;
        }
        if (zoomPaint != null || Math.abs(driftAt(driftT + 0.4f) - zoomForX) < 0.3f) return;
        if (zoomSpare == null) zoomSpare = new Raster(W, h);
        final Raster r = zoomSpare;
        final Ocean.Mask m = zSeaMask == zMaskA ? zMaskB : zMaskA;
        // a little ahead of where it is, the way it is drifting
        final float x = driftAt(driftT + 0.4f), cz = Builder.CZ;
        float lookY = Builder.TOP + 1.4f, dx = x - CAM_X, dz = Builder.CZ;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        r.camera(CAM_X, CAM_Y, 0f, (float) Math.atan2(dx, dz), (float) Math.atan2(lookY - CAM_Y, dist), focal * zoomZ, h * 0.5f);
        zoomPaint = new Thread(new Runnable() {
            @Override
            public void run() {
                new Ocean().paint(r, m);
                zNextCol = r.col.clone();
                zNextZ = r.zb.clone();
                zNextLit = r.lit.clone();
                zNextX = x;
                zNextCZ = cz;
                zoomReady = true;
            }
        });
        zoomPaint.setDaemon(true);
        zoomPaint.start();
    }

    /** Swaps cameras, carrying the aim over so the ring stays on the same spot of the world. */
    private void setZoomed(boolean on) {
        if (on == zoomed) return;
        zoomed = on;
        ras = on ? zoomRas : normRas;
        if (on) {
            // never off the screen: a ring that cannot be seen cannot be aimed
            aimX = Math.max(2f, Math.min(W - 3f, (aimX - zPX) * zoomZ + W * 0.5f));
            aimY = Math.max(2f, Math.min(h - 3f, (aimY - zPY) * zoomZ + h * 0.5f));
            baseX = (baseX - zPX) * zoomZ + W * 0.5f;
            baseY = (baseY - zPY) * zoomZ + h * 0.5f;
            // enlarged, the spot may come to lie under the finger itself: then it shows above it
            if (aiming && atSea && drifting(level) && Math.abs(aimX - lastX) < FINGER * 0.8f && aimY > lastY - FINGER)
                aimY = Math.max(2f, lastY - FINGER);
        } else {
            aimX = (aimX - W * 0.5f) / zoomZ + zPX;
            aimY = (aimY - h * 0.5f) / zoomZ + zPY;
            baseX = (baseX - W * 0.5f) / zoomZ + zPX;
            baseY = (baseY - h * 0.5f) / zoomZ + zPY;
        }
    }

    /** Closes in while a throw is aimed and in flight, opens out once the hammer is back. */
    private void zoomStep(float dt) {
        boolean want = zoomZ > 1f && zoomRas != null && (state == PLAY || state == PORTAL) && (aiming || hs != HAND || portalIn >= 0f);
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
        if (state != PLAY && state != PORTAL || hs != HAND || stars <= 0) return false;
        aimX = sx;
        aimY = sy;
        tremX = tremY = 0f;
        forcedSpeed = speed;
        throwHammer();
        forcedSpeed = -1f;
        return true;
    }

    /**
     * For the stand's bot: a throw at the middle of one row (or of the building), led for the
     * drift as a player would lead it. At the row behind it aims over the front one.
     */
    boolean standThrowRow(int row, float speed) {
        return standThrowRow(row, speed, 0f, 0f);
    }

    /** The same, off by so many meters across and up: a hand that is not perfect. */
    boolean standThrowRow(int row, float speed, float offX, float offY) {
        float mx = 0f, my = 0f, mz = 0f, m = 0f, frontTop = 0f, top = 0f;
        // what still stands, not the rubble lying on the plinth; only rubble left, the rubble
        boolean upright = false;
        for (Body b : pieces) if (stillOn(b) && (!rows || b.row == row) && b.maxY >= Builder.TOP + 0.45f) upright = true;
        for (Body b : pieces) {
            if (!stillOn(b) || upright && b.maxY < Builder.TOP + 0.45f) continue;
            if (rows && b.row == 0) frontTop = Math.max(frontTop, b.maxY);
            if (rows && b.row != row) continue;
            mx += b.px * b.mass;
            my += b.py * b.mass;
            mz += b.pz * b.mass;
            m += b.mass;
            top = Math.max(top, b.maxY);
        }
        if (m == 0f) return false;
        mx /= m;
        my /= m;
        mz /= m;
        if (rows && row == 1 && frontTop + 0.4f < top) my = Math.max(my, frontTop + 0.4f);
        handPoint(tmp);
        float dx = mx - tmp[0], dy = my - tmp[1], dz = mz - tmp[2];
        float t = flight((float) Math.sqrt(dx * dx + dy * dy + dz * dz), speed);
        if (atSea && drifting(level)) mx += driftAt(driftT + t) - Builder.CX;
        float[] o = screenOf(mx + offX, my + offY, mz);
        return throwAt(o[0], o[1], speed);
    }

    /** How badly the parked cars are damaged, all told, for the desktop stand. */
    int carDamage() {
        int n = 0;
        for (int i = 0; i < life.damage.length; i++) n += life.damage[i];
        return n;
    }

    // ---- the call box ----
    // On the lamp post to the right (out at sea, on a buoy) a call box with a red button.
    // Each press rings the dispatcher; three in a row and the bridge opens.

    static final float CALL_Z = 16f, CALL_Y = 1.45f, CALL_X = 4.55f - 0.2f;
    private float tapX, tapY, tapFar;
    private int calls;
    private float callAt = -9f;
    Bridge bridge;                       // the dispatcher's bridge, while it is open

    int best() {
        return Math.max(level + 1, host.loadInt("best"));
    }

    /** Where the call box is now: on the lamp post, or on the buoy as it sways. */
    private float callScreen(Raster r, float[] out) {
        if (atSea) return r.project(callAt3[0], callAt3[1], callAt3[2], out);
        return r.project(CALL_X, CALL_Y, CALL_Z, out);
    }

    private boolean onCallButton(float x, float y) {
        float vz = callScreen(ras, scr);
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
            openBridge();
        }
    }

    void openBridge() {
        if (zoomed) setZoomed(false);
        zoomK = 0f;
        aiming = false;
        bridge = new Bridge(this, host);
        host.sound(Synth.DOOR, 1f, 0.8f);
    }

    private void closeBridge() {
        bridge = null;
        host.sound(Synth.DOOR, 0.8f, 1.15f);
    }

    /** A console monitor or the lever, answered. */
    void bridgeAct(int which) {
        closeBridge();
        if (which == Bridge.ALLEY) startAlley();
        else if (which == Bridge.GOLEM) startBoss(level);
        else if (which == Bridge.SKIP) startSkip();
    }

    /** A screen with its cube, thrown at: to the first level of its episode, or the crossroads. */
    void bridgeEnter(int which) {
        bridge = null;
        if (which == Bridge.HUB) {
            startHub();
            return;
        }
        startLevel(Episode.FIRST[which] - 1, true);
        buildFromSky = false;
        // through the light, as between episodes
        chapterT = CHAPTER_RISE;
    }

    /** A golden cube from the row by the stars, gone into a slot for good. */
    void spendCube() {
        if (cubes <= 0) return;
        cubes--;
        host.saveInt("cubes", cubes);
    }

    /** The button on the post: red, and lit for a moment after each press. */
    private void drawCallButton() {
        if (atSea) ocean.buoy(ras, CALL_X, CALL_Y, CALL_Z, clock, callAt3);
        float vz = callScreen(ras, scr);
        if (vz < 0.5f) return;
        boolean lit = clock - callAt < 1.1f && ((int) ((clock - callAt) * 8f) & 1) == 0;
        float r = Math.max(1.6f, 0.1f * ras.f / vz);
        ras.disc(scr[0], scr[1], r + 0.6f, 1f / (vz - 0.25f), 0xFF3A3C40, 990 << 3);
        ras.disc(scr[0], scr[1], r, 1f / (vz - 0.3f), lit ? 0xFFFF6A4A : 0xFFC8231E, 990 << 3);
        ras.disc(scr[0] - r * 0.3f, scr[1] - r * 0.35f, Math.max(0.6f, r * 0.3f), 1f / (vz - 0.32f), 0xFFFFB0A0, 990 << 3);
    }

    static final String[] SKIP = {
            ".XX..........",
            "XXXX...X...X.",
            ".XX...X.X.X.X",
            ".....X...X...",
            ".............",
            "X.X.X.X.X.X.X",
            ".X.X.X.X.X.X."};

    // pictures for the keys, a cell to a character
    // a ball rolling at a pin
    static final String[] BOWL = {
            "..........X..",
            ".........XXX.",
            "..........X..",
            ".........XXX.",
            ".XXXX...XXXXX",
            "XX.XXX..XXXXX",
            "XXX.XX..XXXXX",
            "XXXXXX..XXXXX",
            ".XXXX....XXX."};
    /** A little picture of cells, centered on a point. */
    static void glyph(Px f, String[] rows, int cx, int cy, int s, int c) {
        int x0 = cx - rows[0].length() * s / 2, y0 = cy - rows.length * s / 2;
        for (int j = 0; j < rows.length; j++)
            for (int i = 0; i < rows[j].length(); i++)
                if (rows[j].charAt(i) == 'X') f.rect(x0 + i * s, y0 + j * s, x0 + (i + 1) * s, y0 + (j + 1) * s, c);
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
        callScreen(ras, o);
        // where it shows on the rocking screen
        if (atSea) {
            float px = o[0] - W * 0.5f + rockX, py = o[1] - h * 0.5f + rockY;
            float c = (float) Math.cos(rockA), sn = (float) Math.sin(rockA);
            o[0] = W * 0.5f + ROCK_S * (c * px - sn * py);
            o[1] = h * 0.5f + ROCK_S * (sn * px + c * py);
        }
        return o;
    }

    /**
     * For the stand: where on the rocking screen a finger would touch to point at the
     * iceberg's middle shifted by dx across and up by dy, meters, in the view now shown.
     */
    float[] standPointAt(float dx, float dy) {
        float[] o = new float[2];
        ras.project(Builder.CX + dx, Builder.TOP + dy, Builder.CZ, o);
        if (atSea) {
            float px = o[0] - W * 0.5f + rockX, py = o[1] - h * 0.5f + rockY;
            float c = (float) Math.cos(rockA), sn = (float) Math.sin(rockA);
            o[0] = W * 0.5f + ROCK_S * (c * px - sn * py);
            o[1] = h * 0.5f + ROCK_S * (sn * px + c * py);
        }
        return o;
    }

    /** For the stand: the world point the ring points at now, on the plane through the plinth. */
    float[] standRingWorld() {
        target(aimX, aimY, tmp);
        return new float[]{tmp[0] - Builder.CX, tmp[1] - Builder.TOP};
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
        if (alley != null) endAlley();
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
        levelPlate(f, W - 6, y + 14);
    }

    /**
     * The level number on a plate of its own, framed like the bar, so the sky never swallows
     * it. The plate warns without a word: pale green while all is well, and with every
     * building in a row that would not fall it ripens through amber to red, as the count
     * nears the tenth, which brings the golden hammer or the golem.
     */
    private void levelPlate(Px f, int right, int top) {
        String lv = String.valueOf(level + 1);
        float risk = Math.min(1f, fails / (float) GOLD_AFTER);
        int bg = risk < 0.5f ? Backdrop.mix(0xFFCFEBB4, 0xFFF2B640, risk * 2f) : Backdrop.mix(0xFFF2B640, 0xFFD02A20, (risk - 0.5f) * 2f);
        int ink = risk < 0.4f ? 0xFF2E3A12 : risk < 0.8f ? 0xFFFFFFFF : 0xFFFFE45A;
        int tw = PixelFont.width(lv, 1), px0 = right - tw - 6, py1 = top + 11;
        f.rect(px0, top, right, py1, risk < 0.8f ? 0xFF8A5A12 : 0xFF5A1208);
        f.rect(px0 + 1, top + 1, right - 1, py1 - 1, bg);
        f.rect(px0 + 1, py1 - 2, right - 1, py1 - 1, Backdrop.scale(bg, 0.86f));
        PixelFont.draw(f, lv, px0 + 3, top + 2, 1, ink);
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
    /** For the stand: every mattress torn at the middle of its front, every suitcase burst. */
    void standTear() {
        for (Body b : pieces) {
            if (b.material == Body.MATTRESS) tearMattress(b, b.px + b.hx * 0.3f, b.py, b.pz - b.hz);
            if (b.material == Body.SUITCASE) b.broke = clock;
        }
    }

    /** For the stand: a flock (1) or a plane (2) right now. */
    void standFliers(int what) {
        if (what == 1) fliers.startFlock();
        else fliers.startPlane();
    }

    /** For the stand: forget the building grown ahead, so the next one grows as asked. */
    void standForget() {
        if (preparing != null) {
            try {
                preparing.join();
            } catch (InterruptedException e) {
                // nothing to do: it is dropped either way
            }
        }
        preparing = null;
        prepared = null;
        preparedFor = -1;
    }

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
        float t = flight(dist, speed);
        hammer.ghost = false;
        hammer.asleep = false;
        float yaw = YAW + (fx.nextFloat() - 0.5f) * 0.6f;
        hammer.setPose(sx, sy, sz, (float) Math.cos(yaw / 2), 0f, (float) Math.sin(yaw / 2), 0f);
        hammer.vx = dx / t;
        hammer.vy = dy / t - 0.5f * Physics.G * t;
        hammer.vz = dz / t;
        spinSign = aimX < W * 0.5f ? 1f : -1f;
        goldThrow = goldOn && state != PORTAL;
        // a throw at the portal costs nothing, not even a heavy one
        heavyThrow = !goldThrow && heavyOn && heavyLeft > 0 && state != PORTAL;
        if (heavyThrow) {
            hammer.scaleMass(HEAVY_MASS);
            heavyLeft--;
            host.saveGifts(heavyLeft);
        }
        hammer.wy = spinSign * (heavyThrow ? 8f : 15f);
        hammer.wx = 0.6f;
        hammer.savePrev();
        flightTime = t;
        if (state != PORTAL) {
            stars--;
            throwsMade++;
        }
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
            best = ras.depth(Builder.CX, CAM_Y, Builder.CZ);
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
        if (intro != null) {
            intro.update(dt);
            if (intro.over()) {
                intro = null;
                introRas = null;
            }
            // the game waits behind the opening, and starts as it fades
            else if (splashing()) return;
        }
        if (bridge != null) {
            bridge.update(dt);
            if (chapterT >= 0f) chapterT = -1f;
            return;
        }
        if (alley != null) {
            clock += dt;
            seaFade = Math.max(0f, seaFade - dt / 1.4f);
            if (alley.sea) {
                ocean.orcaStep(dt);
                ocean.snow(dt);
                rockStep();
            }
            for (int i = 0; i < RIPPLES; i++) if (ripT[i] >= 0f) ripT[i] += dt;
            alley.update(dt, this);
            alley.handUpdate(dt);
            thunderStep(dt);
            if (alley != null && alley.phase == Alley.GODS) {
                sky.update(dt);
                if (sky.bolts != boltsHeard) {
                    boltsHeard = sky.bolts;
                    strikeHeard(sky.boltNear);
                }
            }
            shore.traffic = alley.traffic;
            shore.update(dt);
            particles(dt);
            if (alley != null && alley.phase == Alley.CUBES && alley.next == Alley.MIDDLE && alley.phaseT > 0f
                    && alley.signsReadyToLeave()) {
                // another game: a fresh golem comes round the corner, the view stays where it is
                Alley a = new Alley(host, seed ^ clock2long());
                a.phys.listener = this;
                a.hub = alley.hub;
                a.again();
                alley = a;
                pN = 0;
            }
            if (alley.done()) endAlley();
            return;
        }
        clock += dt;
        stateTime += dt;
        seaFade = Math.max(0f, seaFade - dt / 1.4f);
        thunderStep(dt);
        // fixed physics steps, at most two a frame
        acc += dt;
        int steps = 0;
        while (acc >= 1f / 60f && steps < 2) {
            acc -= 1f / 60f;
            steps++;
            drift(1f / 60f);
            if (state != BUILD && state != VANISH) phys.step(1f / 60f);
            flyingCases();
            bursts();
            keepGold();
            pops();
            blast();
        }
        if (acc > 1f / 30f) acc = 0f;
        particles(dt);
        puddles(dt);
        life.update(dt);
        if (atSea) {
            brace += ((aiming ? 1f : 0f) - brace) * Math.min(1f, dt * 6f);
            followZoom();
            follow();
            ocean.orcaStep(dt);
            ocean.snow(dt);
            rockStep();
            sinking(dt);
            // the swell slaps at the floes now and then, low and heavy
            if ((slapIn -= dt) <= 0f) {
                slapIn = 2.2f + fx.nextFloat() * 3.5f;
                host.sound(Synth.SPLASH, 0.22f + fx.nextFloat() * 0.15f, 0.45f + fx.nextFloat() * 0.15f);
            }
        }
        if (state == PORTAL) portalStep(dt);
        fliers.update(dt, golem == null && (state == PLAY || state == WON || state == LOST || state == BUILD));
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
                strikeHeard(sky.boltNear);
            }
        }
        if (fromSky) {
            backT += dt;
            if (backT >= TILT + 0.2f) fromSky = false;
        }
        marksStep(dt);
        if (chapterT >= 0f) {
            chapterT += dt;
            if (chapterT >= CHAPTER_RISE + CHAPTER_FADE) chapterT = -1f;
        }
        // now and then, before a building, the view turns round to the alley behind
        if (state == PLAY && golem == null && !atSea && alleyLevel(level) && host.loadInt("alley") != level + 1 && stateTime > 0.8f
                && hs == HAND && !aiming && throwsMade == 0) {
            host.saveInt("alley", level + 1);
            startAlley();
            return;
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
                    lookUp(Sky.GLOOM);
                }
                break;
            case SKY_UP:
                if (stateTime >= TILT) setState(SKY_HOLD);
                break;
            case SKY_HOLD:
                // an episode ended with a scene still to come: the light rises first
                if (stateTime >= holdFor && chapterDue && chapterT < 0f) {
                    chapterT = 0f;
                    chapters++;
                    host.sound(Synth.WHOOSH, 0.7f, 0.7f);
                }
                if (stateTime >= holdFor && (chapterT < 0f || chapterT >= CHAPTER_RISE)) {
                    chapterDue = false;
                    if (bossNext) {
                        startBoss(level + nextAfterVanish);
                        buildFromSky = false;
                    } else if (level == SEA_FROM - 1 && nextAfterVanish == 1 && !atSea) {
                        openPortal();
                    } else if (atSea && nextAfterVanish == 1 && skipLevel(level)) {
                        // the ice golem: after the 25th building out here, and after the 50th,
                        // where the portal waits on the iceberg once the game is over
                        if ((level - SEA_FROM) % 50 == 49) openPortal();
                        else startLevel(level + 1, true);
                        buildFromSky = false;
                        startSkip();
                    } else {
                        // the gods have thrown the blocks: the next building falls into place as we look down
                        startLevel(level + nextAfterVanish, true);
                        buildFromSky = true;
                    }
                    fromSky = alley == null;
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
                    || hammer.pz > Builder.CZ + 4f) || hsTime > flightTime + 2.2f || hammer.pz > Math.max(60f, Builder.CZ + 16f))
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
        if (1f - standing() >= clearShare() && state != WON) {
            if (!thundered && throwsMade == 1) {
                thundered = true;
                host.sound(Synth.THUNDER, 1.1f, 1f);
                host.buzz(160);
            }
            wonAt = stars;
            cleared = true;
            earnCube();
            episodeWin();
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
    /** A building that wore the golden cube earns it, once a level; the gods keep it for now. */
    private void earnCube() {
        if (goldCube == null || host.loadInt("cube" + (level + 1)) != 0) return;
        host.saveInt("cube" + (level + 1), 1);
        cubesDue++;
        host.saveInt("cubesDue", cubesDue);
        String k = "ep" + Episode.of(level + 1) + "cubes";
        host.saveInt(k, host.loadInt(k) + 1);
    }

    // ---- episodes ----
    // Each building won adds the stars left to the run through its episode; a run counts
    // only if it began at the episode's first level and went on level after level. The
    // episode's last building won marks it done and keeps the best run. Where an episode
    // ends with a scene still to come, a burst of light stands in for it between the levels.

    static final float CHAPTER_RISE = 0.45f, CHAPTER_FADE = 0.6f;
    private float chapterT = -1f;          // the light between two episodes, running
    private boolean chapterDue;            // the next way down from the sky goes through it
    int chapters;                          // how many have run, for the stand

    private void episodeRun(int lv) {
        int plate = lv + 1;
        if (Episode.first(plate)) {
            host.saveInt("epRun", 0);
            host.saveInt("epRunOk", 1);
        } else if (lv != level && lv != level + 1) host.saveInt("epRunOk", 0);
    }

    private void episodeWin() {
        int plate = level + 1, e = Episode.of(plate);
        int run = host.loadInt("epRun") + Math.max(0, wonAt);
        host.saveInt("epRun", run);
        if (!Episode.last(plate)) return;
        host.saveInt("ep" + e + "done", 1);
        if (host.loadInt("epRunOk") == 1 && run > host.loadInt("ep" + e + "stars")) host.saveInt("ep" + e + "stars", run);
        if (Episode.ENDS[e] == Episode.NEW) chapterDue = true;
    }

    /** For the stand: the ring of the portal raised now, and a throw through its middle. */
    void standPortalOpen() {
        openPortal();
    }

    void standPortalThrow() {
        float[] o = screenOf(portalX(), portalY(), Builder.CZ);
        throwAt(o[0], o[1], 13f);
    }

    /** For the stand: the farthest level reached, as if played that far. */
    void standBest(int plate) {
        host.saveInt("best", plate);
    }

    /** For the stand: where the game is among the episodes. */
    String episodeState() {
        int plate = level + 1, e = Episode.of(plate);
        StringBuilder done = new StringBuilder();
        for (int i = 0; i < Episode.COUNT; i++)
            done.append(host.loadInt("ep" + i + "done") == 1 ? String.valueOf(host.loadInt("ep" + i + "stars")) : ".").append(' ');
        return String.format("plate %d, episode %d (%d-%d), run %d%s, best stars by episode: %s, lights %d",
                plate, e + 1, Episode.FIRST[e], Episode.LAST[e], host.loadInt("epRun"), host.loadInt("epRunOk") == 1 ? "" : " (not whole)",
                done.toString().trim(), chapters);
    }

    private float chapterLight() {
        if (chapterT < 0f) return 0f;
        if (chapterT < CHAPTER_RISE) {
            float u = chapterT / CHAPTER_RISE;
            return u * u;
        }
        float u = Math.min(1f, (chapterT - CHAPTER_RISE) / CHAPTER_FADE);
        return (1f - u) * (1f - u);
    }

    void standWin(int throwsCount) {
        earnCube();
        wonAt = stars;
        episodeWin();
        throwsMade = throwsCount;
        cleared = true;
        setState(WON);
    }

    private void lookUp(int verdict) {
        prepare(level + nextAfterVanish);
        // the first building is the gods' welcome: they all laugh aloud
        sky.begin(verdict, (level == 0 || fromBoss) && verdict == Sky.OVATION);
        giftShown = -1f;
        giftT0 = -1f;
        goldGift = false;
        diamondGift = false;
        bossNext = false;
        boolean afterFight = fromBoss;
        fromBoss = false;
        if (afterFight) {
            // the fight is over: no counting, no golem again at once
        } else if (verdict == Sky.GLOOM) {
            fails++;
            host.saveFails(fails);
            if (fails == GOLD_AFTER) {
                if (!goldSeen) {
                    // the first time a building will not fall, the gods give the golden hammer
                    goldGift = true;
                    goldSeen = true;
                    host.saveGoldSeen(true);
                    giftShown = 0f;
                } else if (!atSea) {
                    // ever after, the golem comes instead; out at sea the golden hammer stays
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
        holdFor = verdict == Sky.GLOOM ? 2.2f : verdict == Sky.OVATION ? 3.2f : 2.4f;
        boltsHeard = sky.bolts;
        setState(SKY_UP);
    }

    /** The gift's marks coming into the corner, once the corner is in sight again. */
    private void marksStep(float dt) {
        if (heavyShown > heavyLeft) heavyShown = heavyLeft;
        if (heavyShown == heavyLeft) {
            markT = -1f;
            return;
        }
        if (markT < 0f) {
            if (state == SKY_UP || state == SKY_HOLD || tilt() >= h / 8) return;
            markT = 0f;
            marksLanded = 0;
        }
        markT += dt;
        int n = heavyLeft - heavyShown;
        while (marksLanded < n && markT >= marksLanded * MARK_GAP + MARK_FALL) {
            host.sound(Synth.CATCH, 0.45f, 1.05f + marksLanded * 0.14f);
            host.buzz(12);
            marksLanded++;
        }
        if (markT >= (n - 1) * MARK_GAP + MARK_FALL + 0.45f) {
            heavyShown = heavyLeft;
            markT = -1f;
        }
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
        // a suitcase that lands hard bursts its latches (one sent flying opens in the air, see
        // flyingCases); a mattress tears under the hammer or a heavy fall
        if (a.material == Body.SUITCASE && !hammerHit && speed > 4.5f) openCase(a);
        if (b != null && b.material == Body.SUITCASE && !hammerHit && speed > 4.5f) openCase(b);
        if (a.material == Body.MATTRESS && (hammerHit && speed > 4.5f || speed > 7f)) tearMattress(a, x, y, z);
        if (b != null && b.material == Body.MATTRESS && (hammerHit && speed > 4.5f || speed > 7f)) tearMattress(b, x, y, z);
        boolean plastic = a.material == Body.WATER || (b != null && b.material == Body.WATER);
        float gain = Math.min(1f, (speed - 1f) / 5f);
        if (hammerHit) gain = Math.min(1.3f, gain * 1.4f + 0.2f);
        int street = voiceOf(a);
        if (street < 0 && b != null) street = voiceOf(b);
        if (street >= 0) {
            if (speed > 1.2f || hammerHit) {
                float rate = street == Synth.PLASTIC ? 1.35f : street == Synth.CLANG ? 0.9f : 1f;
                // a light knock on a mattress is only the whump: the creak needs a real blow
                if (street == Synth.MATTRESS && speed < 3f && !hammerHit) street = Synth.SAND;
                host.sound(street, gain * (street == Synth.CLANG ? 0.85f : 1f), rate * (0.92f + fx.nextFloat() * 0.16f));
            }
        } else if (b == null || other == null) {
            boolean water = atSea && b == null && !Ocean.onFloe(x, z);
            if (speed > 1.6f) host.sound(water ? Synth.SPLASH : Synth.GROUND, gain * 0.9f, 0.9f + fx.nextFloat() * 0.2f);
        } else {
            boolean stone = a.material == Body.STONE || b.material == Body.STONE || other.material == Body.PLINTH
                    || a.material == Body.BALL || b.material == Body.BALL;
            float size = Math.max(other.hx, Math.max(other.hy, other.hz));
            float rate = Math.max(0.6f, Math.min(1.5f, 0.32f / size)) * (0.92f + fx.nextFloat() * 0.16f);
            host.sound(plastic ? Synth.PLASTIC : stone ? Synth.STONE : Synth.WOOD, gain, plastic ? 0.9f + fx.nextFloat() * 0.2f : rate);
        }
        if (hammerHit) host.buzz((int) Math.min(60f, 10f + speed * 3f));
        boolean soft = other != null && (other.material == Body.SUITCASE || other.material == Body.MATTRESS);
        if (soft && speed > 2.2f) {
            // no chips off cloth: a little grey fluff from a mattress, nothing from leather
            if (other.material == Body.MATTRESS)
                for (int i = 0; i < 3; i++) spawn(x, y, z, true, 0xFFC9C5BC, 0.8f);
        } else if (speed > 2.2f && other != null && other.material != Body.WATER) {
            int n = (int) Math.min(14f, speed * 1.2f);
            boolean dusty = other.material == Body.STONE || other.material == Body.PLINTH || other.material == Body.SANDBAG;
            int dust = other.material == Body.SANDBAG ? 0xFFDCC79C : atSea && other.material == Body.PLINTH ? 0xFFE4F0F6 : 0xFFE6DCC8;
            for (int i = 0; i < n; i++)
                spawn(x, y, z, dusty && (i & 1) == 0, dusty ? dust : Backdrop.scale(other.tint, 0.8f), speed * 0.35f);
        } else if (speed > 3f && b == null) {
            for (int i = 0; i < 4; i++) spawn(x, 0.02f, z, true, atSea ? 0xFFE8F0F4 : 0xFFD8CFC0, 1f);
        }
    }

    /** The sound of a street thing knocked about, or -1 for a plain block. */
    private static int voiceOf(Body b) {
        switch (b.material) {
            case Body.BARREL: return Synth.CLANG;
            case Body.TYRE: return Synth.RUBBER;
            case Body.SANDBAG: return Synth.SAND;
            case Body.CONE: return Synth.PLASTIC;
            case Body.SUITCASE: return Synth.LEATHER;
            case Body.MATTRESS: return Synth.MATTRESS;
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
            boolean onTop = Math.abs(b.px - Builder.CX) < Builder.PHX && Math.abs(b.pz - Builder.CZ) < Builder.PHZ
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
        pKind[i] = SPECK;
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
        pKind[i] = SPECK;
    }

    // the shirts packed in old suitcases: loud prints on bright grounds
    private static final int[] SHIRTS = {0xFFE8483C, 0xFF2FB0A6, 0xFFF2C230, 0xFFF27A3A, 0xFFE87AB0, 0xFF3F8FD8, 0xFF5DBE6A};

    /** Something light thrown up into the air to flutter down: a shirt, a postcard, or a spring. */
    private void spawnLight(float x, float y, float z, float vx, float vy, float vz, int kind) {
        if (pN >= MAXP) return;
        int i = pN++;
        pX[i] = x;
        pY[i] = y;
        pZ[i] = z;
        float a = (float) (fx.nextFloat() * Math.PI * 2);
        float sp = kind == SPRING ? 1.5f + fx.nextFloat() * 2.5f : 0.8f + fx.nextFloat() * 2.2f;
        pVx[i] = (float) Math.cos(a) * sp + vx * 0.4f;
        pVz[i] = (float) Math.sin(a) * sp + vz * 0.4f;
        pVy[i] = (kind == SPRING ? 2.5f : 4f) + fx.nextFloat() * 4f + Math.max(0f, vy) * 0.3f;
        pLife[i] = kind == SPRING ? 2.5f + fx.nextFloat() : 5.5f + fx.nextFloat() * 3f;
        pAge[i] = 0f;
        pCol[i] = kind == SHIRT ? SHIRTS[fx.nextInt(SHIRTS.length)] : kind == CARD ? 0xFFF6F3EA : 0xFFB8BAC0;
        pDust[i] = false;
        pKind[i] = kind;
    }

    /** A suitcase thrown through the air faster than a brisk walk gapes open in flight. */
    private void flyingCases() {
        for (Body b : pieces)
            if (b.material == Body.SUITCASE && b.broke < 0f && !b.gone && !b.asleep && b.speed2() > 3.2f * 3.2f) openCase(b);
    }

    /** A suitcase bursts open: the latches snap, the lid gapes, shirts and postcards fly. */
    private void openCase(Body b) {
        if (b.broke >= 0f) return;
        b.broke = clock;
        int shirts = 5 + fx.nextInt(4), cards = 4 + fx.nextInt(5);
        for (int i = 0; i < shirts; i++) spawnLight(b.px, b.py + b.hy, b.pz, b.vx, b.vy, b.vz, SHIRT);
        for (int i = 0; i < cards; i++) spawnLight(b.px, b.py + b.hy, b.pz, b.vx, b.vy, b.vz, CARD);
        host.sound(Synth.LATCH, 0.9f, 0.94f + fx.nextFloat() * 0.12f);
    }

    /** A mattress torn where it was struck: springs jump out of the ticking, grey wadding puffs. */
    private void tearMattress(Body b, float x, float y, float z) {
        if (b.broke >= 0f) return;
        b.broke = clock;
        float dx = x - b.px, dy = y - b.py, dz = z - b.pz;
        float lx = b.r00 * dx + b.r10 * dy + b.r20 * dz;
        float ly = b.r01 * dx + b.r11 * dy + b.r21 * dz;
        float lz = b.r02 * dx + b.r12 * dy + b.r22 * dz;
        // onto the face it is nearest to; the thin sides read best, so the front wins ties
        float ax = Math.abs(lx) / b.hx, ay = Math.abs(ly) / b.hy * 0.7f, az = Math.abs(lz) / b.hz;
        int axis = az >= ax && az >= ay ? 2 : ax >= ay ? 0 : 1;
        float[] l = {lx, ly, lz}, hh = {b.hx, b.hy, b.hz};
        boolean neg = l[axis] < 0f;
        l[axis] = neg ? -hh[axis] : hh[axis];
        for (int k = 0; k < 3; k++) if (k != axis) l[k] = Math.max(-hh[k] * 0.75f, Math.min(hh[k] * 0.75f, l[k]));
        b.markX = l[0];
        b.markY = l[1];
        b.markZ = l[2];
        b.markAxis = axis + (neg ? 3 : 0);
        for (int i = 0; i < 4; i++) spawnLight(x, y, z, b.vx, b.vy, b.vz, SPRING);
        for (int i = 0; i < 10; i++) spawn(x, y, z, true, (i & 1) == 0 ? 0xFFB9B5AD : 0xFFD6D2C8, 1.4f);
        host.sound(Synth.TEAR, 1f, 0.95f + fx.nextFloat() * 0.1f);
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
                pKind[i] = pKind[pN];
                i--;
                continue;
            }
            // a chip of the golem crumbles into dust as it ends
            if (pKind[i] == CHIP && pAge[i] > pLife[i] * 0.7f && fx.nextInt(6) == 0 && pN < MAXP)
                spawn(pX[i], pY[i] + 0.05f, pZ[i], true, 0xFFDCD3C4, 0.6f);
            if (pKind[i] == SHIRT || pKind[i] == CARD) {
                flutter(i, dt);
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
            float floor = Math.abs(pX[i] - Builder.CX) < Builder.PHX && Math.abs(pZ[i] - Builder.CZ) < Builder.PHZ ? Builder.TOP : 0f;
            if (pY[i] < floor && !pDust[i]) {
                pY[i] = floor;
                pVy[i] = -pVy[i] * 0.3f;
                pVx[i] *= 0.6f;
                pVz[i] *= 0.6f;
            }
        }
    }

    /**
     * Cloth and paper in the air: thrown up, caught by the air at once, then sinking slowly,
     * swinging from side to side; once down they lie where they fell.
     */
    private void flutter(int i, float dt) {
        float floor = Math.abs(pX[i] - Builder.CX) < Builder.PHX && Math.abs(pZ[i] - Builder.CZ) < Builder.PHZ ? Builder.TOP : 0f;
        if (pY[i] <= floor + 0.01f && pVy[i] <= 0f) {
            pY[i] = floor + 0.01f;
            pVx[i] = pVy[i] = pVz[i] = 0f;
            return;
        }
        float drag = pKind[i] == SHIRT ? 2.6f : 2.2f;
        pVy[i] += Physics.G * 0.4f * dt;
        float k = Math.max(0f, 1f - drag * dt);
        pVx[i] *= k;
        pVy[i] *= k;
        pVz[i] *= k;
        // the swing: a card side-slips like a leaf, a shirt billows more lazily
        float ph = pAge[i] * (pKind[i] == CARD ? 4.2f : 2.6f) + i * 1.7f;
        float sw = pKind[i] == CARD ? 1.1f : 0.7f;
        pX[i] += (pVx[i] + (float) Math.cos(ph) * sw) * dt;
        pY[i] += pVy[i] * dt;
        pZ[i] += (pVz[i] + (float) Math.sin(ph * 0.7f) * sw * 0.5f) * dt;
        if (pY[i] < floor) pY[i] = floor;
    }

    // ---- drawing ----

    // ---- the opening picture ----
    // ---- the opening ----
    // When the game starts, the game in a few seconds: over the town and down onto a square,
    // the name built of blocks on a pedestal, thrown down from the sky letter by letter, and
    // smashed by the hammer; a burst of light carries it over into the game. It is played
    // and drawn here, the square drawn afresh every frame; a tap goes straight on.

    private Intro intro;
    private Raster introRas;
    private Town town;
    private final float[] introView = new float[6];
    private int[] burstBuf;

    /** For the desktop stand: no opening, or the opening again. */
    void skipSplash() {
        intro = null;
        introRas = null;
    }

    void showSplash() {
        intro = new Intro(host);
    }

    private boolean splashing() {
        return intro != null && !intro.fading();
    }

    private void drawIntro(Px f) {
        if (introRas == null || introRas.h != h) introRas = new Raster(W, h);
        if (town == null) town = new Town();
        Raster keep = ras;
        boolean sea = atSea;
        ras = introRas;
        atSea = false;
        Raster.overcast(false);
        intro.view(introView);
        float[] v = introView;
        float dx = v[3] - v[0], dy = v[4] - v[1], dz = v[5] - v[2];
        ras.camera(v[0], v[1], v[2], (float) Math.atan2(dx, dz), (float) Math.atan2(dy, (float) Math.sqrt(dx * dx + dz * dz)), focal, h * 0.5f);
        town.begin(ras);
        ras.shadowBegin(0f, Intro.Z + 2f, 16f);
        town.build(ras);
        Body p = intro.pedestal;
        drawBody(p, 1f, 0f, 1);
        ras.shadowBox(p.px, p.py, p.pz, 1, 0, 0, 0, 1, 0, 0, 0, 1, p.hx, p.hy, p.hz);
        for (int i = 0; i < intro.blocks.size(); i++) {
            float drop = intro.drop(i);
            if (drop < 0f) continue;
            Body b = intro.blocks.get(i);
            drawBody(b, 1f, drop, i + 2);
            ras.shadowBox(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22, b.hx, b.hy, b.hz);
        }
        town.life(ras, intro.t, 300);
        Body hm = intro.hammer;
        float down = intro.handDown();
        if (!intro.thrown && down < 1f) {
            // in the hand, as in the game, once the view is down on the ground
            eyeX = v[0];
            eyeY = v[1];
            eyeZ = v[2];
            heldPose(0.3f, -0.7f - 0.6f * down + (float) Math.sin(intro.t * 1.7f) * 0.008f, 1.35f, -0.42f, 0.86f, 0.28f, 0.94f, 0.2f, -0.28f);
            pose[0] -= pose[3] * 0.05f;
            pose[1] -= pose[6] * 0.05f;
            pose[2] -= pose[9] * 0.05f;
            eyeX = CAM_X;
            eyeY = CAM_Y;
            eyeZ = 0f;
            drawParts(false, 1f, 900, false);
        } else if (intro.thrown) {
            pose[0] = hm.px;
            pose[1] = hm.py;
            pose[2] = hm.pz;
            pose[3] = hm.r00;
            pose[4] = hm.r01;
            pose[5] = hm.r02;
            pose[6] = hm.r10;
            pose[7] = hm.r11;
            pose[8] = hm.r12;
            pose[9] = hm.r20;
            pose[10] = hm.r21;
            pose[11] = hm.r22;
            ras.shadowBox(hm.px, hm.py, hm.pz, hm.r00, hm.r01, hm.r02, hm.r10, hm.r11, hm.r12, hm.r20, hm.r21, hm.r22, hm.hx, hm.hy * 0.8f, hm.hz);
            drawParts(false, 1f, 900, false);
        }
        town.ground(ras);
        ras.shadowApply(0, 0, W - 1, h - 1);
        ras.outlines(0, 0, W - 1, h - 1, 1);
        int[] c = ras.col;
        int n = Math.min(f.a.length, c.length);
        // out of the dark at the very start
        float in = Math.min(1f, intro.t / 0.35f);
        if (in >= 1f) System.arraycopy(c, 0, f.a, 0, n);
        else for (int k = 0; k < n; k++) f.a[k] = Backdrop.mix(0xFF000000, c[k], in);
        ras = keep;
        atSea = sea;
    }

    /**
     * The light between the opening and the game: the picture rushes toward its middle, as
     * if flown into, and whitens; on the far side the game comes out of the white.
     */
    private void burst(Px f, float light, boolean rush) {
        if (light <= 0f) return;
        int n = W * h;
        if (rush) {
            if (burstBuf == null || burstBuf.length != n) burstBuf = new int[n];
            System.arraycopy(f.a, 0, burstBuf, 0, n);
            float cxp = W * 0.5f, cyp = h * 0.5f, k = 0.12f * light;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < W; x++) {
                    int r = 0, g = 0, b = 0;
                    for (int s = 0; s < 4; s++) {
                        float q = 1f - k * s / 3f;
                        int sx = (int) (cxp + (x - cxp) * q), sy = (int) (cyp + (y - cyp) * q);
                        int col = burstBuf[sy * W + sx];
                        r += (col >> 16) & 0xFF;
                        g += (col >> 8) & 0xFF;
                        b += col & 0xFF;
                    }
                    f.a[y * W + x] = 0xFF000000 | ((r >> 2) << 16) | ((g >> 2) << 8) | (b >> 2);
                }
        }
        for (int i = 0; i < n; i++) f.a[i] = Backdrop.mix(f.a[i], 0xFFFFFCF2, light);
    }

    void draw(Px f) {
        if (ras == null) return;
        if (splashing()) {
            drawIntro(f);
            burst(f, intro.light(), true);
            return;
        }
        if (bridge != null) {
            // the world behind fades as the bridge comes up, and is not drawn once it is up
            if (bridge.t < Bridge.OPEN_T) drawWorld(f);
            bridge.draw(f, Math.min(1f, bridge.t / Bridge.OPEN_T));
            return;
        }
        drawWorld(f);
        if (intro != null) burst(f, intro.light(), false);
        if (chapterT >= 0f) burst(f, chapterLight(), chapterT < CHAPTER_RISE);
    }

    private void drawWorld(Px f) {
        if (alley == null) {
            drawStreet(f);
            if (state == PORTAL && portalIn >= 0f) portalWarp(f);
            if (seaFade > 0f) {
                // back from the sea: the white of the glare clears off the street
                float k = seaFade * seaFade;
                for (int i = 0; i < f.a.length; i++) f.a[i] = Backdrop.mix(f.a[i], 0xFFFFFFFF, k);
            }
            return;
        }
        int ph = alley.phase;
        if (ph == Alley.SEA) {
            drawSea(f);
            return;
        }
        if (ph == Alley.GODS) {
            // the gods look down on the alley too: the view tips up to them between rounds
            if (turnA == null || turnA.a.length != f.a.length) {
                turnA = new Px(f.w, f.h);
                turnB = new Px(f.w, f.h);
            }
            noHud = true;
            drawAlley(turnA);
            noHud = false;
            int off = Math.round(alley.tilt() * h);
            sky.winter = alley.sea;
            sky.compose(turnA.a, f.a, h, off);
            sky.drawClouds(f, off - h);
            float held = alley.phaseT - Alley.TILT_T;
            if (cubesFalling > 0 && alley.verdict != Sky.GLOOM && held > 0.9f && held < alley.godsHold()) {
                glowN = 0;
                drawCubeGift(f, held - 0.9f, cubesFalling);
                for (int i = 0; i < glowN; i++) glow(f.a, h, glowX[i], glowY[i], glowR[i], 9 + i, -1, -1);
                glowN = 0;
            }
            if (off < h / 8) drawAlleyHud(f);
            return;
        }
        if (ph != Alley.TURN_IN && ph != Alley.TURN_OUT) {
            drawAlley(f);
            if (seaFade > 0f) {
                // come through the portal: the white clears off the crossroads
                float k = seaFade * seaFade;
                for (int i = 0; i < f.a.length; i++) f.a[i] = Backdrop.mix(f.a[i], 0xFFFFFFFF, k);
            }
            return;
        }
        // the turn of the head, a quarter of the way and a half, a nod at each
        if (turnA == null || turnA.a.length != f.a.length) {
            turnA = new Px(f.w, f.h);
            turnB = new Px(f.w, f.h);
        }
        float t = alley.phaseT;
        boolean in = ph == Alley.TURN_IN;
        // which leg of the turn: sweeps at 0, 2, 4, nods at 1, 3
        int leg = 0;
        float lt = t;
        float[] len = {Alley.SWEEP, Alley.NOD, Alley.SWEEP, Alley.NOD, Alley.SWEEP};
        while (leg < 4 && lt >= len[leg]) {
            lt -= len[leg];
            leg++;
        }
        float k = Math.min(1f, lt / len[leg]);
        // the views in the order the head passes them
        int a = (leg & 1) == 0 ? leg / 2 : (leg + 1) / 2, b = a + 1;
        if (!in) {
            a = 3 - a;
            b = 3 - b;
        }
        noHud = true;
        int[] from = turnView(a, turnA), to = (leg & 1) == 0 ? turnView(b, turnB) : null;
        noHud = false;
        if ((leg & 1) == 0) whip(f, from, to, k, in ? 1 : -1);
        else nod(f, from, k);
    }

    // the views a turn passes: 0 the street, 1 and 2 painted on the way, 3 the alley
    private int[][] turnCols = new int[4][];

    private int[] turnView(int i, Px buf) {
        if (i == 0) {
            drawStreet(buf);
            return buf.a;
        }
        if (i == 3) {
            drawAlley(buf);
            return buf.a;
        }
        return alley != null && alley.sea ? seaTurn[i] : turnCols[i];
    }

    /** A nod: the head dips and comes up again, the picture rising a little and settling. */
    private void nod(Px f, int[] src, float k) {
        int dy = Math.round((float) Math.sin(k * Math.PI) * h * 0.022f);
        for (int y = 0; y < h; y++) {
            int sy = Math.min(h - 1, y + dy);
            System.arraycopy(src, sy * W, f.a, y * W, W);
        }
    }

    private float seaFade;

    /** Down to the water: the view closes in on the sea and fades into the glare. */
    private void drawSea(Px f) {
        drawAlley(f);
        float t = Math.min(1f, alley.phaseT / Alley.SEA_T);
        float e = t * t * (3f - 2f * t);
        float zk = 1f + 2.2f * e;
        alleyRas.project(alley.wx(0f, 70f), 0.5f, alley.wz(0f, 70f), scr);
        float cx = W * 0.5f + (scr[0] - W * 0.5f) * e, cy = h * 0.5f + (scr[1] - h * 0.5f) * e;
        int[] src = f.a.clone();
        float white = Math.max(0f, (t - 0.55f) / 0.45f);
        for (int y = 0; y < h; y++) {
            int sy = Math.max(0, Math.min(h - 1, (int) (cy + (y + 0.5f - h * 0.5f) / zk)));
            for (int x = 0; x < W; x++) {
                int sx = Math.max(0, Math.min(W - 1, (int) (cx + (x + 0.5f - W * 0.5f) / zk)));
                f.a[y * W + x] = Backdrop.mix(src[sy * W + sx], 0xFFFFFFFF, white);
            }
        }
    }

    private Px turnA, turnB;
    private boolean noHud;

    /**
     * A quick turn of the head: the view sweeps sideways from one picture to the other,
     * smeared along the sweep, fastest in the middle.
     */
    private void whip(Px f, int[] from, int[] to, float t, int dir) {
        float e = t * t * (3f - 2f * t);
        int gap = W / 5, span = W + gap;
        float off = e * span;
        int[] out = f.a;
        for (int y = 0; y < h; y++) {
            int row = y * W;
            for (int x = 0; x < W; x++) {
                int c;
                if (dir > 0) {
                    // turning right: the picture runs off to the left, the next comes in from the right
                    float sx = x + off;
                    if (sx < W) c = from[row + (int) sx];
                    else if (sx < W + gap) c = Backdrop.mix(from[row + W - 1], to[row], (sx - W) / gap);
                    else c = to[row + Math.min(W - 1, (int) (sx - W - gap))];
                } else {
                    float sx = x - off;
                    if (sx >= 0f) c = from[row + (int) sx];
                    else if (sx >= -gap) c = Backdrop.mix(from[row], to[row + W - 1], -sx / gap);
                    else c = to[row + Math.max(0, (int) (sx + W + gap))];
                }
                out[row + x] = c;
            }
        }
        int r = (int) (6f * t * (1f - t) * 13f);
        if (r > 0) for (int y = 0; y < h; y++) smear(out, y * W, r);
    }

    private final int[] smearRow = new int[W];

    /** A running box blur along one row. */
    private void smear(int[] a, int row, int r) {
        System.arraycopy(a, row, smearRow, 0, W);
        int sr = 0, sg = 0, sb = 0, n = 0;
        for (int x = -r; x < W; x++) {
            int in = x + r;
            if (in < W) {
                int c = smearRow[in];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
                n++;
            }
            int outI = x - r - 1;
            if (outI >= 0) {
                int c = smearRow[outI];
                sr -= (c >> 16) & 0xFF;
                sg -= (c >> 8) & 0xFF;
                sb -= c & 0xFF;
                n--;
            }
            if (x >= 0) a[row + x] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
        }
    }

    // ---- the portal ----
    // When the 101st building is down and the gods have looked, no new building comes: a ring
    // of glowing cloud gathers over the plinth, and through it shows raw winter, grey sky and
    // a dark sea in a blizzard, snow streaming out of it. Throws cost nothing now. A hammer
    // through the ring pulls the view in after it, and the next level is out on the ocean.

    static final float PORTAL_H = 3.9f, PORTAL_RISE = 1.6f, PORTAL_IN = 1.5f;
    private static final int PORTAL_ID = 996;
    private float portalIn = -1f;            // seconds since the hammer went through, or -1
    private float prevHX, prevHY, prevHZ;
    // the ring as painted: premultiplied colors, and how much of each pixel is the way through
    private int[] ringCol;
    private byte[] ringIn;
    private int ringW, ringH;
    private float holeU = 0.5f, holeV = 0.42f, holeRU = 0.36f, holeRV = 0.29f;   // as shares of the picture

    private void readPortal(byte[] b) {
        if (b == null || b.length < 4) return;
        int ww = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF), hh = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        if (b.length < 4 + ww * hh * 5) return;
        ringCol = new int[ww * hh];
        ringIn = new byte[ww * hh];
        int x0 = ww, x1 = 0, y0 = hh, y1 = 0;
        for (int k = 0, p = 4; k < ww * hh; k++, p += 5) {
            ringCol[k] = ((b[p + 3] & 0xFF) << 24) | ((b[p] & 0xFF) << 16) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
            ringIn[k] = b[p + 4];
            if ((b[p + 4] & 0xFF) > 128) {
                int x = k % ww, y = k / ww;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
        }
        ringW = ww;
        ringH = hh;
        if (x1 > x0 && y1 > y0) {
            holeU = (x0 + x1 + 1) * 0.5f / ww;
            holeV = (y0 + y1 + 1) * 0.5f / hh;
            holeRU = (x1 - x0 + 1) * 0.5f / ww;
            holeRV = (y1 - y0 + 1) * 0.5f / hh;
        }
    }

    private float portalW() {
        return ringH > 0 ? PORTAL_H * ringW / ringH : PORTAL_H * 0.67f;
    }

    /** The middle of the picture, in the air over the plinth. */
    private float portalMid() {
        return Builder.TOP + 0.05f + PORTAL_H * 0.5f;
    }

    /** The middle of the way through. */
    private float portalY() {
        return portalMid() + (0.5f - holeV) * PORTAL_H;
    }

    private float portalX() {
        return Builder.CX + (holeU - 0.5f) * portalW();
    }

    private void openPortal() {
        for (Body b : pieces)
            if (!b.gone) {
                b.gone = true;
                b.ghost = true;
            }
        if (goldCube != null) {
            goldCube.gone = true;
            goldCube.ghost = true;
        }
        stars = STARS;
        throwsMade = 0;
        portalIn = -1f;
        pN = 0;
        setState(PORTAL);
        // the aim starts in the middle of the ring
        normRas.project(portalX(), portalY(), Builder.CZ, scr);
        aimX = baseX = scr[0];
        aimY = baseY = scr[1];
        aimedBefore = true;
        host.sound(Synth.ROLL, 0.8f, 0.7f);
    }

    private float snowOut;

    private void portalStep(float dt) {
        if (portalIn >= 0f) {
            portalIn += dt;
            if (portalIn >= PORTAL_IN) {
                portalIn = -1f;
                if (atSea && level + 1 >= Episode.LAST[Episode.COUNT - 1]) {
                    // the last episode done: onto the bridge, where two more screens light up
                    host.saveInt("finale", 1);
                    startLevel(level, false);
                    openBridge();
                } else if (atSea) {
                    // out of the ice into the crossroads of five cubes, the next building waiting
                    startLevel(level + 1, true);
                    startHub();
                } else startLevel(SEA_FROM, true);
                seaFade = 1f;
            }
            return;
        }
        // snow blows out of it toward the player
        if (stateTime > PORTAL_RISE && (snowOut -= dt) <= 0f) {
            snowOut = 0.04f;
            float a = fx.nextFloat() * 6.283f, r = fx.nextFloat();
            spawn(portalX() + (float) Math.cos(a) * r * holeRU * portalW(), portalY() + (float) Math.sin(a) * r * holeRV * PORTAL_H,
                    Builder.CZ - 0.2f, true, 0xFFF4F8FA, 2.2f);
        }
        if (hs == FLY) {
            // through the ring: the hammer crossed its plane inside the way through this frame
            float z0 = prevHZ - Builder.CZ, z1 = hammer.pz - Builder.CZ;
            if (hsTime > 0.05f && z0 < 0f && z1 >= 0f) {
                float k = z0 / (z0 - z1);
                float x = (prevHX + (hammer.px - prevHX) * k - portalX()) / (holeRU * portalW());
                float y = (prevHY + (hammer.py - prevHY) * k - portalY()) / (holeRV * PORTAL_H);
                if (x * x + y * y < 0.85f) {
                    portalIn = 0f;
                    hammer.ghost = true;
                    hammer.vx = hammer.vy = hammer.vz = 0f;
                    hammer.px = 0f;
                    hammer.py = -50f;
                    host.sound(Synth.WHOOSH, 1.1f, 0.6f);
                    host.sound(Synth.THUNDER, 0.7f, 1.6f);
                    host.buzz(120);
                }
            }
        }
        prevHX = hammer.px;
        prevHY = hammer.py;
        prevHZ = hammer.pz;
    }

    /** The cloud ring gathering over the plinth, and the winter seen through it. */
    private void drawPortal() {
        if (ringCol == null) return;
        float t = stateTime - 0.5f;
        float open = Math.max(0f, Math.min(1f, t / 1.1f));
        if (open <= 0f) return;
        float e = 1f - (1f - open) * (1f - open) * (1f - open);
        float vz = ras.project(Builder.CX, portalMid(), Builder.CZ, scr);
        if (vz < 0.5f) return;
        float cx = scr[0], cy = scr[1];
        // grows out of a puff, breathes, sways a little
        float size = (0.55f + 0.45f * e) * (1f + 0.018f * (float) Math.sin(clock * 1.3f));
        float px = PORTAL_H / ringH * ras.f / vz * size;            // screen pixels per picture pixel
        float rot = 0.035f * (float) Math.sin(clock * 0.55f), c = (float) Math.cos(rot), sn = (float) Math.sin(rot);
        float half = 0.5f * (float) Math.hypot(ringW, ringH) * px;
        int x0 = Math.max(0, (int) (cx - half)), x1 = Math.min(W - 1, (int) (cx + half));
        int y0 = Math.max(0, (int) (cy - half)), y1 = Math.min(h - 1, (int) (cy + half));
        float iz = 1f / vz;
        int al = (int) (e * 256f);
        float inv = 1f / px;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float u = (c * dx + sn * dy) * inv + ringW * 0.5f, v = (-sn * dx + c * dy) * inv + ringH * 0.5f;
                int iu = (int) u, iv = (int) v;
                if (u < 0f || v < 0f || iu >= ringW || iv >= ringH) continue;
                int k = y * W + x;
                if (iz <= ras.zb[k]) continue;
                int q = iv * ringW + iu;
                int col = ringCol[q], hole = ringIn[q] & 0xFF;
                int base = ras.col[k];
                if (hole > 0) {
                    float hu = (u / ringW - holeU) / holeRU, hv = (v / ringH - holeV) / holeRV;
                    base = Backdrop.mix(base, atSea ? summerAt(hu, hv) : winterAt(hu, hv, x, y), hole / 255f * e);
                }
                if (al < 256) col = premul(col, al);
                ras.col[k] = over(col, base);
                if ((col >>> 24) > 120 || hole > 128) {
                    ras.zb[k] = iz;
                    ras.idb[k] = PORTAL_ID << 3;
                    ras.lit[k] = 0;
                }
            }
    }

    private static int premul(int c, int t) {
        return ((((c >>> 24) * t) >> 8) << 24) | (((((c >> 16) & 0xFF) * t) >> 8) << 16)
                | (((((c >> 8) & 0xFF) * t) >> 8) << 8) | (((c & 0xFF) * t) >> 8);
    }

    private static int over(int c, int d) {
        int k = 255 - (c >>> 24);
        int rr = ((c >> 16) & 0xFF) + ((((d >> 16) & 0xFF) * k) / 255);
        int gg = ((c >> 8) & 0xFF) + ((((d >> 8) & 0xFF) * k) / 255);
        int bb = (c & 0xFF) + (((d & 0xFF) * k) / 255);
        return 0xFF000000 | (Math.min(255, rr) << 16) | (Math.min(255, gg) << 8) | Math.min(255, bb);
    }

    /** Summer through the ring, from the ice: a blue sky, warm light low on one side, the street's warm stone. */
    private int summerAt(float u, float v) {
        float horizon = 0.3f;
        int c = v < horizon ? Backdrop.mix(0xFF3E7CC8, 0xFFB8D4EA, Math.max(0f, (v + 1f) / (horizon + 1f)))
                : Backdrop.mix(0xFFE8C99A, 0xFF9A8670, Math.min(1f, (v - horizon) * 1.6f));
        float sun = Math.max(0f, 1f - (float) Math.hypot(u + 0.45f, v - 0.05f) * 1.6f);
        c = Backdrop.mix(c, 0xFFFFE8B8, sun * sun * 0.8f);
        float cloud = Ocean.noise(u * 2.6f + clock * 0.3f, v * 4f);
        if (v < horizon - 0.1f && cloud > 0.62f) c = Backdrop.mix(c, 0xFFF4EEE6, (cloud - 0.62f) * 2.2f);
        return c;
    }

    /**
     * Winter through the ring, at a point of it (-1..1 across the way through): a raw grey
     * sky, a dark sea under it, fog blowing across, and snow streaming out at the viewer.
     */
    private int winterAt(float u, float v, int sx, int sy) {
        float horizon = 0.28f;
        int c = v < horizon ? Backdrop.mix(0xFF56636F, 0xFFA9B4BD, Math.max(0f, (v + 1f) / (horizon + 1f)))
                : Backdrop.mix(0xFF4A5E6E, 0xFF2A3A48, Math.min(1f, (v - horizon) * 2.2f));
        if (v > horizon && v < horizon + 0.05f) c = Backdrop.mix(c, 0xFFB8C4CC, 0.6f);
        float t = clock;
        float fog = Ocean.noise(u * 2.2f - t * 1.4f, v * 3f + t * 0.2f);
        c = Backdrop.mix(c, 0xFFC4CDD4, Math.max(0f, fog - 0.4f) * 0.8f);
        // snow coming out of it: flakes on rays from the middle, rushing outward
        float r = (float) Math.sqrt(u * u + v * v) + 0.02f;
        float a = (float) Math.atan2(v, u);
        int ring = (int) Math.floor((float) Math.log(r) * 7f - t * 5f);
        int spoke = (int) Math.floor(a * 9f + ring * 0.37f);
        if (Backdrop.hash(spoke, ring) > 236) c = Backdrop.mix(c, 0xFFFFFFFF, Math.min(1f, r * 1.3f));
        // and streaks blown sideways
        if (Backdrop.hash((int) Math.floor(sx * 0.25f - t * 24f), sy) > 247) c = Backdrop.mix(c, 0xFFF4F8FA, 0.7f);
        return c;
    }

    /** Pulled in after the hammer: the picture swirls into the ring and goes white. */
    private void portalWarp(Px f) {
        float t = Math.min(1f, portalIn / PORTAL_IN);
        float e = t * t;
        ras.project(portalX(), portalY(), Builder.CZ, scr);
        float cx = scr[0], cy = scr[1];
        int n = W * h;
        if (rockBuf == null || rockBuf.length != n) rockBuf = new int[n];
        System.arraycopy(f.a, 0, rockBuf, 0, n);
        float zk = 1f + 9f * e, turn = 2.6f * e;
        float white = Math.max(0f, (t - 0.45f) / 0.55f);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < W; x++) {
                float dx = x + 0.5f - W * 0.5f, dy = y + 0.5f - h * 0.5f;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                // nearer the middle, the more it is wound round
                float a = turn * (1f - Math.min(1f, r / (W * 0.9f)));
                float c = (float) Math.cos(a), s = (float) Math.sin(a);
                int sx = (int) (cx + (c * dx - s * dy) / zk), sy = (int) (cy + (s * dx + c * dy) / zk);
                if (sx < 0) sx = 0;
                else if (sx >= W) sx = W - 1;
                if (sy < 0) sy = 0;
                else if (sy >= h) sy = h - 1;
                f.a[y * W + x] = Backdrop.mix(rockBuf[sy * W + sx], 0xFFFFFFFF, white);
            }
    }

    // ---- out on the ice ----

    static final int FLOE_ID = 994;

    /** How far the iceberg rides up or down now, on the swell: a little, it is heavy. */
    private float floeLift() {
        return 0.6f * Ocean.heave(Builder.CX, Builder.CZ, clock);
    }

    /**
     * Where a piece is drawn, up or down, out at sea: on the iceberg it rides with it, the
     * lower it falls the less; in the water it sinks.
     */
    private float seaDrop(Body b, float lift) {
        if (b.sunk > 0f) return -b.sunk + 0.04f * (float) Math.sin(clock * 2f + b.px);
        return lift * Math.max(0f, Math.min(1f, b.minY / Builder.TOP));
    }

    /** Pieces that came down in open water go under, slowly, and are gone. */
    private void sinking(float dt) {
        for (Body b : pieces) {
            if (b.gone || b.material == Body.BALLOON || b == goldCube) continue;
            if (b.sunk <= 0f) {
                if (b.snowy && (b.speed2() > 2.5f || Math.max(Math.abs(b.r10), Math.max(Math.abs(b.r11), Math.abs(b.r12))) < 0.88f)) {
                    // knocked or tipped: the snow flies off
                    b.snowy = false;
                    for (int i = 0; i < 7; i++) spawn(b.px, b.maxY, b.pz, true, 0xFFF4F8FA, 1.4f);
                }
                if (b.minY > 0.25f || Ocean.onFloe(b.px, b.pz)) continue;
                b.sunk = 0.001f;
                for (int i = 0; i < 10; i++) spawn(b.px, 0.05f, b.pz, true, 0xFFE8F0F4, 1.6f);
                continue;
            }
            b.sunk += dt * 0.45f;
            if (b.sunk > b.maxY - b.minY + 0.3f) {
                b.gone = true;
                b.ghost = true;
            }
        }
    }

    private int[] rockBuf;
    private float slapIn = 3f;
    private float rockA, rockX, rockY;
    private static final float ROCK_S = 1.13f;

    /** The floe underfoot rocks: the view leans a little and rises and sinks with it. */
    private void rockStep() {
        // the floe underfoot lies still at first; from the 110th it rocks, harder as it goes
        int n = level - SEA_FROM;
        float k = n < 8 ? 0f : Math.min(1f, 0.55f + (n - 8) * 0.05f);
        // aiming, the player braces his feet: the rocking all but stops while the finger is down
        k *= 1f - 0.88f * brace;
        rockA = k * (0.034f * (float) Math.sin(clock * 0.8f) + 0.012f * (float) Math.sin(clock * 1.9f + 1f));
        rockY = k * (7.5f * (float) Math.sin(clock * 1.05f + 0.4f) + 2f * (float) Math.sin(clock * 2.6f));
        rockX = k * 3f * (float) Math.sin(clock * 0.61f);
    }

    // ---- the drifting iceberg ----
    // From the 120th on, the iceberg does not keep still: it drifts in from the left edge
    // of the view to the right and back again, the building riding on it, so the target
    // moves as well as rocks. Everything resting on it is carried along with it.

    private float driftT, brace;

    static boolean drifting(int lv) {
        return lv - SEA_FROM >= 18;
    }

    // the drift's pace across, meters a second, the same all the way but for a short turn at
    // either end: a steady pace can be foreseen, a pace that keeps changing cannot
    static final float DRIFT_V = 0.5f;
    private static final float DRIFT_K = 0.96f, DRIFT_AK = (float) Math.asin(DRIFT_K);

    /**
     * Where the iceberg is across, at a time into its drift: a zigzag with rounded turns,
     * across the middle of the usual view at that distance and near its edges at both ends.
     * It is under way from the start, near the left edge, heading right.
     */
    private static float driftAt(float t) {
        float hw = Builder.CZ * 0.263f, mid = CAM_X + Builder.CZ * (float) Math.tan(YAW), a = 0.62f * hw;
        float w = DRIFT_V * DRIFT_AK / (a * DRIFT_K);
        return mid + a * (float) Math.asin(DRIFT_K * Math.sin(w * t - 1.2f)) / DRIFT_AK;
    }

    /** How fast the iceberg drifts across now, meters a second, plus to the right. */
    private float driftPace() {
        if (!atSea || !drifting(level)) return 0f;
        return (driftAt(driftT + 0.05f) - driftAt(driftT - 0.05f)) * 10f;
    }

    /** One step of the drift: the plinth moves, and what rests on it moves with it. */
    private void drift(float dt) {
        if (!atSea || !drifting(level) || golem != null) return;
        if (state != PLAY && state != WON && state != LOST) return;
        // it keeps drifting while the player aims: the ring stays where it was put, on the
        // water ahead of the iceberg if need be, and the player must foresee where it will be
        driftT += dt;
        float x = driftAt(driftT), dx = x - Builder.CX;
        if (dx == 0f) return;
        float old = Builder.CX;
        Builder.CX = x;
        plinth.px += dx;
        plinth.ppx += dx;
        // the physics never moves the plinth itself, so its bounds must be moved here: left
        // behind, they would leave the building standing on nothing once it is woken
        plinth.updateR();
        for (Body b : pieces) {
            if (b.gone || b.sunk > 0f) continue;
            if (Math.abs(b.px - old) > Builder.PHX + 0.4f || b.minY < Builder.TOP - 0.3f) continue;
            b.px += dx;
            b.ppx += dx;
            b.homeX += dx;
            b.updateR();
        }
    }

    /** Where a point shown on the rocking screen lies in the steady picture. */
    private void unrock(float x, float y, float[] out) {
        float u = (x - W * 0.5f) / ROCK_S, v = (y - h * 0.5f) / ROCK_S;
        float c = (float) Math.cos(rockA), s = (float) Math.sin(rockA);
        out[0] = W * 0.5f + c * u + s * v - rockX;
        out[1] = h * 0.5f - s * u + c * v - rockY;
    }

    private void rock(Px f) {
        int n = W * h;
        if (rockBuf == null || rockBuf.length != n) rockBuf = new int[n];
        System.arraycopy(f.a, 0, rockBuf, 0, n);
        float c = (float) Math.cos(rockA) / ROCK_S, s = (float) Math.sin(rockA) / ROCK_S;
        for (int y = 0; y < h; y++) {
            float v = y + 0.5f - h * 0.5f, u = 0.5f - W * 0.5f;
            float sx = W * 0.5f + c * u + s * v - rockX, sy = h * 0.5f - s * u + c * v - rockY;
            int row = y * W;
            for (int x = 0; x < W; x++) {
                int ix = (int) sx, iy = (int) sy;
                if (ix < 0) ix = 0;
                else if (ix >= W) ix = W - 1;
                if (iy < 0) iy = 0;
                else if (iy >= h) iy = h - 1;
                f.a[row + x] = rockBuf[iy * W + ix];
                sx += c;
                sy -= s;
            }
        }
    }

    private void drawStreet(Px f) {
        Raster.overcast(atSea);
        glowN = 0;
        boolean panning = zoomed && atSea && drifting(level) && panoReady();
        if (panning) panoBackground();
        else {
            System.arraycopy(zoomed ? zCol : bgCol, 0, ras.col, 0, bgCol.length);
            System.arraycopy(zoomed ? zZ : bgZ, 0, ras.zb, 0, bgZ.length);
            System.arraycopy(zoomed ? zLit : bgLit, 0, ras.lit, 0, bgLit.length);
        }
        java.util.Arrays.fill(ras.idb, 0);

        float lift = 0f;
        if (atSea) {
            if (!panning) ocean.sea(ras, zoomed ? zSeaMask : seaMask, clock);
            ocean.boats(ras, clock);
            ocean.orcas(ras);
            lift = floeLift();
            if (golem == null) {
                // the iceberg is laid out round x = 0: drawn where it has drifted to
                ras.camX -= Builder.CX;
                ocean.wake = golem == null ? driftPace() : 0f;
                ocean.iceberg(ras, Builder.CZ, lift, FLOE_ID << 3, clock);
                ras.camX += Builder.CX;
            }
        } else if (golem == null) drawBody(plinth, 1f, 0f, 1);
        if (state == PORTAL) drawPortal();
        drawCallButton();
        float st = stagger();
        ras.shadowBegin(6f, Builder.CZ + 1f, 12f);
        if (golem == null) {
            if (!atSea) ras.shadowBox(plinth.px, plinth.py, plinth.pz, 1, 0, 0, 0, 1, 0, 0, 0, 1, plinth.hx, plinth.hy, plinth.hz);
        } else drawFight();
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
            if (atSea) drop += seaDrop(b, lift);
            if (b.material == Body.WATER) drawBottle(b, scale, drop, i + 2);
            else if (b.material == Body.BARREL) drawDrum(b, scale, drop, i + 2);
            else if (b.material == Body.CONE) drawCone(b, scale, drop, i + 2);
            else if (b.material == Body.TYRE) drawTyre(b, scale, drop, i + 2);
            else if (b.material == Body.SANDBAG) drawBag(b, scale, drop, i + 2);
            else if (b.material == Body.SUITCASE) drawCase(b, scale, drop, i + 2);
            else if (b.material == Body.MATTRESS) drawMattress(b, scale, drop, i + 2);
            else drawBody(b, scale, drop, i + 2);
            if (b.radius > 0f) ras.shadowBall(b.px, b.py + drop, b.pz, b.radius * scale);
            else ras.shadowBox(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    b.hx * scale, b.hy * scale, b.hz * scale);
        }
        if (!atSea) {
            streetShadows();
            drawPuddles();
            life.draw(ras);
        }
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
        if (atSea) ocean.mist(ras, clock);
        if (golem == null) fliers.draw(ras);
        for (int i = 0; i < glowN; i++) glow(ras.col, h, glowX[i], glowY[i], glowR[i], i * 3, glowLo[i], glowHi[i]);
        glowN = 0;
        if (aiming) drawAim();
        int off = tilt();
        if (off > 0) {
            sky.winter = atSea;
            sky.compose(ras.col, f.a, h, off);
            sky.drawClouds(f, off - h);
            if (atSea) ocean.drawSnow(f);
            if (giftShown >= 0f && state == SKY_HOLD && stateTime > 0.9f) {
                drawGift(f, stateTime - 0.9f);
                for (int i = 0; i < glowN; i++) glow(f.a, h, glowX[i], glowY[i], glowR[i], 7 + i, -1, -1);
                glowN = 0;
            }
            grade(f, off);
            if (off < h / 8) {
                if (golem != null && state == BOSS) drawFightHud(f);
                else drawHud(f);
            }
        } else {
            if (!zoomed && zoomK > 0f) closingIn(f);
            else System.arraycopy(ras.col, 0, f.a, 0, Math.min(f.a.length, ras.col.length));
            if (atSea) {
                rock(f);
                ocean.drawSnow(f);
            }
            if (golem != null) shakeFrame(f);
            grade(f, 0);
            if (golem != null) {
                drawFightHud(f);
                drawFlash(f);
            } else if (!noHud) drawHud(f);
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
            float gz = ras.project(b.px, b.py + drop, b.pz, scr);
            if (gz > 0.3f && scale > 0.5f) glowAt(scr[0], scr[1], b.hx * 2.2f * scale * ras.f / gz, id << 3, (id + 1) << 3);
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
        if (b.look == 1) {
            lifeRing(b, scale, drop, id);
            return;
        }
        if (b.look == 2) {
            shipWheel(b, scale, drop, id);
            return;
        }
        int pat = b.material == Body.STONE ? Raster.PAT_STONE : b.material == Body.PLINTH ? (atSea ? Raster.PAT_FLAT : Raster.PAT_STONE) : Raster.PAT_GRAIN;
        int tint = b.tint;
        if (b.icy) {
            // ice: its own color, smooth
            pat = Raster.PAT_FLAT;
        }
        ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                b.hx * scale, b.hy * scale, b.hz * scale, tint, pat, id << 3);
        if (b.snowy && atSea) snowCap(b, scale, drop, id);
    }

    // ---- things off ships, out at sea ----

    private static final int[] RING_BANDS = {0xFFF2EEE6, 0xFFD23A26};
    private final float[] tq = new float[4], tqy = new float[4], tqz = new float[4];

    /**
     * A ring round the body's own z axis, in its x-y plane: radius R, tube radius tr, in nu
     * pieces round and nv round the tube; the colors go round in bands.
     */
    private void torus(Body b, float drop, float R, float tr, int nu, int nv, int[] cols, int bands, int id) {
        for (int i = 0; i < nu; i++) {
            double a0 = i * Math.PI * 2 / nu, a1 = (i + 1) * Math.PI * 2 / nu, am = (a0 + a1) * 0.5;
            int c0 = cols[(i * bands / nu) % cols.length];
            for (int j = 0; j < nv; j++) {
                double t0 = j * Math.PI * 2 / nv, t1 = (j + 1) * Math.PI * 2 / nv, tm = (t0 + t1) * 0.5;
                float lnx = (float) (Math.cos(tm) * Math.cos(am)), lny = (float) (Math.cos(tm) * Math.sin(am)), lnz = (float) Math.sin(tm);
                float nx = b.r00 * lnx + b.r01 * lny + b.r02 * lnz, ny = b.r10 * lnx + b.r11 * lny + b.r12 * lnz,
                        nz = b.r20 * lnx + b.r21 * lny + b.r22 * lnz;
                for (int k = 0; k < 4; k++) {
                    double a = k == 0 || k == 3 ? a0 : a1, t = k < 2 ? t0 : t1;
                    float rr = R + tr * (float) Math.cos(t);
                    float lx = rr * (float) Math.cos(a), ly = rr * (float) Math.sin(a), lz = tr * (float) Math.sin(t);
                    toWorld(b, lx, ly, lz, drop, wp);
                    tq[k] = wp[0];
                    tqy[k] = wp[1];
                    tqz[k] = wp[2];
                }
                if ((ras.camX - tq[0]) * nx + (ras.camY - tqy[0]) * ny + (ras.camZ - tqz[0]) * nz <= 0f) continue;
                int c = Raster.light(c0, nx, ny, nz, nx * Raster.sunX() + ny * Raster.sunY() + nz * Raster.sunZ());
                ras.poly(tq, tqy, tqz, null, null, 4, c, Raster.PAT_FLAT, id << 3, ny > 0.5f);
            }
        }
    }

    /** A life ring standing on its edge: white, banded red, a line round it. */
    private void lifeRing(Body b, float scale, float drop, int id) {
        float R = Math.min(b.hx, b.hy) * scale * 0.7f, tr = Math.min(b.hx, b.hy) * scale * 0.28f;
        torus(b, drop, R, tr, 16, 6, RING_BANDS, 8, id);
        // the line looped round it, in four swags
        for (int i = 0; i < 4; i++) {
            float a = (float) (i * Math.PI * 0.5 + Math.PI * 0.25);
            float rr = R + tr * 1.02f;
            toWorld(b, (float) Math.cos(a) * rr, (float) Math.sin(a) * rr, 0f, drop, wp);
            ras.box(wp[0], wp[1], wp[2], b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    tr * 0.25f, tr * 0.25f, tr * 1.05f, 0xFFD8C9A0, Raster.PAT_FLAT, id << 3);
        }
    }

    private static final int[] WOOD_RIM = {0xFF8A5A34, 0xFF7A4C2A};

    /** A ship's wheel on its edge: a rim of dark wood, a brass hub, eight spokes with handles. */
    private void shipWheel(Body b, float scale, float drop, int id) {
        float R = Math.min(b.hx, b.hy) * scale * 0.62f, tr = R * 0.09f;
        torus(b, drop, R, tr, 16, 4, WOOD_RIM, 16, id);
        ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                R * 0.18f, R * 0.18f, b.hz * scale * 0.55f, 0xFFC9A44A, Raster.PAT_FLAT, id << 3);
        for (int i = 0; i < 8; i++) {
            float a = (float) (i * Math.PI / 4), c = (float) Math.cos(a), sn = (float) Math.sin(a);
            // the spoke's own frame: along it, across it in the wheel's plane, and the axle
            float ax = b.r00 * c + b.r01 * sn, ay = b.r10 * c + b.r11 * sn, az = b.r20 * c + b.r21 * sn;
            float bx = -b.r00 * sn + b.r01 * c, by = -b.r10 * sn + b.r11 * c, bz = -b.r20 * sn + b.r21 * c;
            toWorld(b, c * R * 0.55f, sn * R * 0.55f, 0f, drop, wp);
            ras.box(wp[0], wp[1], wp[2], ax, bx, b.r02, ay, by, b.r12, az, bz, b.r22,
                    R * 0.5f, tr * 0.7f, tr * 0.7f, 0xFF7A4C2A, Raster.PAT_GRAIN, id << 3);
            // the handle past the rim, turned
            toWorld(b, c * R * 1.22f, sn * R * 1.22f, 0f, drop, wp);
            ras.box(wp[0], wp[1], wp[2], ax, bx, b.r02, ay, by, b.r12, az, bz, b.r22,
                    R * 0.16f, tr * 1.1f, tr * 1.1f, 0xFF9A6A40, Raster.PAT_GRAIN, id << 3);
        }
    }

    /** A cap of snow on whichever face of a block is up, as long as it lies flat. */
    private void snowCap(Body b, float scale, float drop, int id) {
        float ux = Math.abs(b.r10), uy = Math.abs(b.r11), uz = Math.abs(b.r12);
        int axis = ux > uy && ux > uz ? 0 : uy > uz ? 1 : 2;
        float up = axis == 0 ? b.r10 : axis == 1 ? b.r11 : b.r12;
        if (Math.abs(up) < 0.9f) return;
        float sg = up > 0f ? 1f : -1f, t = 0.055f;
        float h0 = (axis == 0 ? b.hx : axis == 1 ? b.hy : b.hz) * scale + t;
        float ax = (axis == 0 ? b.r00 : axis == 1 ? b.r01 : b.r02) * sg * h0;
        float ay = (axis == 0 ? b.r10 : axis == 1 ? b.r11 : b.r12) * sg * h0;
        float az = (axis == 0 ? b.r20 : axis == 1 ? b.r21 : b.r22) * sg * h0;
        float k = 1.03f * scale;
        ras.box(b.px + ax, b.py + drop + ay, b.pz + az, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                axis == 0 ? t : b.hx * k, axis == 1 ? t : b.hy * k, axis == 2 ? t : b.hz * k, 0xFFF4F8FA, Raster.PAT_FLAT, id << 3);
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

    // ---- luggage ----

    /** A point in a body's own frame, out into the world. */
    private static void toWorld(Body b, float lx, float ly, float lz, float drop, float[] out) {
        out[0] = b.px + b.r00 * lx + b.r01 * ly + b.r02 * lz;
        out[1] = b.py + drop + b.r10 * lx + b.r11 * ly + b.r12 * lz;
        out[2] = b.pz + b.r20 * lx + b.r21 * ly + b.r22 * lz;
    }

    private final float[] lid = new float[9], wp = new float[3];

    /**
     * An old suitcase: leather with straps, latches and stickers, a handle at the front.
     * Burst open, its lid swings up on the hinge at the back and the shirts inside show.
     */
    private void drawCase(Body b, float scale, float drop, int id) {
        float hx = b.hx * scale, hy = b.hy * scale, hz = b.hz * scale;
        ras.patSeed = id * 7 + 3;
        if (b.broke < 0f) {
            ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    hx, hy, hz, b.tint, Raster.PAT_CASE, id << 3);
            toWorld(b, 0f, hy * 0.18f, -hz - 0.025f, drop, wp);
            ras.box(wp[0], wp[1], wp[2], b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    hx * 0.17f, hy * 0.16f, 0.025f, 0xFF2A1A10, Raster.PAT_FLAT, id << 3);
            return;
        }
        // the lower half, and the clothes heaped in it
        toWorld(b, 0f, -hy * 0.5f, 0f, drop, wp);
        ras.box(wp[0], wp[1], wp[2], b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                hx, hy * 0.5f, hz, b.tint, Raster.PAT_CASE, id << 3);
        toWorld(b, 0f, 0.012f, 0f, drop, wp);
        ras.patSeed = id * 5 + 1;
        ras.box(wp[0], wp[1], wp[2], b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                hx * 0.9f, 0.014f, hz * 0.86f, SHIRTS[id % SHIRTS.length], Raster.PAT_ALOHA, id << 3);
        // the lid, turned about the hinge along the back edge
        float a = Math.min(1.95f, (clock - b.broke) * 10f);
        float c = (float) Math.cos(a), sn = (float) Math.sin(a);
        float oy = hy * 0.5f * c + hz * sn, oz = hy * 0.5f * sn - hz * c;
        toWorld(b, 0f, oy, hz + oz, drop, wp);
        lid[0] = b.r00;
        lid[3] = b.r10;
        lid[6] = b.r20;
        lid[1] = b.r01 * c + b.r02 * sn;
        lid[4] = b.r11 * c + b.r12 * sn;
        lid[7] = b.r21 * c + b.r22 * sn;
        lid[2] = -b.r01 * sn + b.r02 * c;
        lid[5] = -b.r11 * sn + b.r12 * c;
        lid[8] = -b.r21 * sn + b.r22 * c;
        ras.patSeed = id * 7 + 3;
        ras.box(wp[0], wp[1], wp[2], lid[0], lid[1], lid[2], lid[3], lid[4], lid[5], lid[6], lid[7], lid[8],
                hx, hy * 0.5f, hz, b.tint, Raster.PAT_CASE, id << 3);
        // the lining of the lid, a shirt still caught in it
        float ly = -hy * 0.5f - 0.01f;
        float wx = wp[0] + lid[1] * ly, wy = wp[1] + lid[4] * ly, wz = wp[2] + lid[7] * ly;
        ras.patSeed = id * 5 + 2;
        ras.box(wx, wy, wz, lid[0], lid[1], lid[2], lid[3], lid[4], lid[5], lid[6], lid[7], lid[8],
                hx * 0.88f, 0.01f, hz * 0.84f, SHIRTS[(id + 3) % SHIRTS.length], Raster.PAT_ALOHA, id << 3);
    }

    /**
     * A spring mattress in striped ticking. Torn, it shows a ragged hole with grey wadding
     * round it and twisted springs jumping out of it.
     */
    private void drawMattress(Body b, float scale, float drop, int id) {
        float hx = b.hx * scale, hy = b.hy * scale, hz = b.hz * scale;
        ras.patSeed = id * 11 + 5;
        // a full core and a rounded border round it: the edges of a mattress bulge
        ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                hx, hy * 0.74f, hz, b.tint, Raster.PAT_TICK, id << 3);
        ras.box(b.px, b.py + drop, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                hx * 0.975f, hy, hz * 0.95f, b.tint, Raster.PAT_TICK, id << 3);
        if (b.broke < 0f || scale < 1f) return;
        int axis = b.markAxis % 3;
        float sg = b.markAxis >= 3 ? -1f : 1f;
        // the face's normal and two directions along it, in the body's frame
        float[] n = {0f, 0f, 0f}, e1 = {0f, 0f, 0f}, e2 = {0f, 0f, 0f};
        n[axis] = sg;
        e1[(axis + 1) % 3] = 1f;
        e2[(axis + 2) % 3] = 1f;
        float out = 0.006f;
        float mx = b.markX + n[0] * out, my = b.markY + n[1] * out, mz = b.markZ + n[2] * out;
        // the hole and the wadding round it
        float hole = Math.min(0.12f, Math.min(axis == 1 ? hx : hy, axis == 2 ? hx : hz) * 0.95f);
        toWorld(b, mx, my, mz, drop, wp);
        float vz = ras.project(wp[0], wp[1], wp[2], scr);
        if (vz < 0.3f) return;
        float px = ras.f / vz;
        ras.disc(scr[0], scr[1], Math.max(1.5f, hole * px), 1f / (vz - 0.01f), 0xFF26221F, id << 3);
        for (int k = 0; k < 6; k++) {
            float ang = k * 1.047f + id;
            float r = hole * (0.85f + 0.25f * ((k * 7 + id) % 3));
            float lx = mx + (e1[0] * (float) Math.cos(ang) + e2[0] * (float) Math.sin(ang)) * r + n[0] * 0.02f;
            float ly = my + (e1[1] * (float) Math.cos(ang) + e2[1] * (float) Math.sin(ang)) * r + n[1] * 0.02f;
            float lz = mz + (e1[2] * (float) Math.cos(ang) + e2[2] * (float) Math.sin(ang)) * r + n[2] * 0.02f;
            toWorld(b, lx, ly, lz, drop, wp);
            float z2 = ras.project(wp[0], wp[1], wp[2], scr);
            if (z2 < 0.3f) continue;
            ras.disc(scr[0], scr[1], Math.max(0.8f, 0.04f * ras.f / z2), 1f / (z2 - 0.02f),
                    (k & 1) == 0 ? 0xFFBDB9B1 : 0xFFD9D5CD, id << 3);
        }
        // the springs: coils of wire leaning out of the hole, out to full length in a blink
        float grow = Math.min(1f, (clock - b.broke) / 0.18f);
        for (int sp = 0; sp < 3; sp++) {
            float ang = sp * 2.1f + id * 0.7f, off = hole * 0.45f;
            float bx = mx + (e1[0] * (float) Math.cos(ang) + e2[0] * (float) Math.sin(ang)) * off;
            float by = my + (e1[1] * (float) Math.cos(ang) + e2[1] * (float) Math.sin(ang)) * off;
            float bz = mz + (e1[2] * (float) Math.cos(ang) + e2[2] * (float) Math.sin(ang)) * off;
            // the axis leans out from the normal toward its own side
            float lean = 0.45f + 0.15f * sp;
            float ax = n[0] + (e1[0] * (float) Math.cos(ang) + e2[0] * (float) Math.sin(ang)) * lean;
            float ay = n[1] + (e1[1] * (float) Math.cos(ang) + e2[1] * (float) Math.sin(ang)) * lean;
            float az = n[2] + (e1[2] * (float) Math.cos(ang) + e2[2] * (float) Math.sin(ang)) * lean;
            float len = (0.24f + 0.06f * sp) * grow, rc = 0.055f;
            for (int k = 0; k <= 22; k++) {
                float t = k / 22f, th = t * 2.6f * 6.2832f + sp;
                float cs = (float) Math.cos(th), sn = (float) Math.sin(th);
                float lx = bx + ax * len * t + (e1[0] * cs + e2[0] * sn) * rc;
                float ly = by + ay * len * t + (e1[1] * cs + e2[1] * sn) * rc;
                float lz = bz + az * len * t + (e1[2] * cs + e2[2] * sn) * rc;
                toWorld(b, lx, ly, lz, drop, wp);
                float z2 = ras.project(wp[0], wp[1], wp[2], scr);
                if (z2 < 0.3f) continue;
                ras.disc(scr[0], scr[1], Math.max(0.7f, 0.016f * ras.f / z2), 1f / (z2 - 0.06f),
                        sn > 0f ? 0xFFE8EAEE : 0xFF5E5C60, id << 3);
            }
        }
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
    // where the eye is that holds the hammer: the player's, or the opening's camera
    private float eyeX = CAM_X, eyeY = CAM_Y, eyeZ = 0f;

    private void heldPose(float vx, float vy, float vz, float ux, float uy, float uz, float wx, float wy, float wz) {
        ras.viewToWorld(vx, vy, vz, tmp);
        pose[0] = tmp[0] + eyeX;
        pose[1] = tmp[1] + eyeY;
        pose[2] = tmp[2] + eyeZ;
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

    // ---- the glow of gold ----
    // Golden things are not merely yellow: the hammer and the cube shine with a warm halo
    // that breathes, and sparks twinkle round them. Each frame collects where they are on
    // screen, and the halo is laid over the picture once everything else is drawn.

    private static final int GLOWS = 4;
    private final float[] glowX = new float[GLOWS], glowY = new float[GLOWS], glowR = new float[GLOWS];
    private final int[] glowLo = new int[GLOWS], glowHi = new int[GLOWS];
    private int glowN;

    /** A halo round (x, y); the object's own pixels, ids lo to hi, take only a little of it. */
    private void glowAt(float x, float y, float r, int lo, int hi) {
        if (glowN < GLOWS && r > 0.5f) {
            glowX[glowN] = x;
            glowY[glowN] = y;
            glowR[glowN] = r;
            glowLo[glowN] = lo;
            glowHi[glowN] = hi;
            glowN++;
        }
    }

    private float[] glowD = new float[0];

    /**
     * A warm halo added over a picture W wide and rows tall. For an object on the street
     * (ids lo to hi) the halo hugs its outline, however near or far it is: a few pixels of
     * light all round it, fading outward, and a sheen on the object itself. Without ids it
     * is a round halo about (x, y). Sparks drift round it either way.
     */
    private void glow(int[] buf, int rows, float x, float y, float r, int seed, int lo, int hi) {
        float breathe = 0.62f + 0.18f * (float) Math.sin(clock * 3.6f + seed);
        if (lo < 0) {
            float R = Math.min(34f, r) * 2.1f;
            int x0 = Math.max(0, (int) (x - R)), x1 = Math.min(W - 1, (int) (x + R));
            int y0 = Math.max(0, (int) (y - R)), y1 = Math.min(rows - 1, (int) (y + R));
            for (int py = y0; py <= y1; py++)
                for (int px = x0; px <= x1; px++) {
                    float dx = px + 0.5f - x, dy = py + 0.5f - y;
                    float d = (float) Math.sqrt(dx * dx + dy * dy) / R;
                    if (d < 1f) addLight(buf, py * W + px, (1f - d) * (1f - d) * breathe);
                }
        } else {
            int[] id = ras.idb;
            int bx0 = W, by0 = rows, bx1 = -1, by1 = -1;
            for (int py = 0; py < rows; py++)
                for (int px = 0, i = py * W; px < W; px++, i++)
                    if (id[i] >= lo && id[i] < hi) {
                        if (px < bx0) bx0 = px;
                        if (px > bx1) bx1 = px;
                        if (py < by0) by0 = py;
                        if (py > by1) by1 = py;
                    }
            if (bx1 < 0) return;
            // how far the light reaches past the outline: more for what is near and big
            float reach = Math.max(4f, Math.min(14f, r * 0.8f));
            int g = (int) Math.ceil(reach);
            int x0 = Math.max(0, bx0 - g), x1 = Math.min(W - 1, bx1 + g);
            int y0 = Math.max(0, by0 - g), y1 = Math.min(rows - 1, by1 + g);
            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            if (glowD.length < bw * bh) glowD = new float[bw * bh];
            float[] d = glowD;
            // distance to the object, two chamfer passes over the box round it
            for (int py = 0; py < bh; py++)
                for (int px = 0; px < bw; px++) {
                    int i = (y0 + py) * W + x0 + px;
                    d[py * bw + px] = id[i] >= lo && id[i] < hi ? 0f : 1e6f;
                }
            for (int py = 0; py < bh; py++)
                for (int px = 0; px < bw; px++) {
                    int k = py * bw + px;
                    float v = d[k];
                    if (px > 0) v = Math.min(v, d[k - 1] + 1f);
                    if (py > 0) {
                        v = Math.min(v, d[k - bw] + 1f);
                        if (px > 0) v = Math.min(v, d[k - bw - 1] + 1.414f);
                        if (px < bw - 1) v = Math.min(v, d[k - bw + 1] + 1.414f);
                    }
                    d[k] = v;
                }
            for (int py = bh - 1; py >= 0; py--)
                for (int px = bw - 1; px >= 0; px--) {
                    int k = py * bw + px;
                    float v = d[k];
                    if (px < bw - 1) v = Math.min(v, d[k + 1] + 1f);
                    if (py < bh - 1) {
                        v = Math.min(v, d[k + bw] + 1f);
                        if (px < bw - 1) v = Math.min(v, d[k + bw + 1] + 1.414f);
                        if (px > 0) v = Math.min(v, d[k + bw - 1] + 1.414f);
                    }
                    d[k] = v;
                }
            // a sheen that sweeps across the gold itself
            float sweep = (clock * 0.7f + seed) % 2.2f;
            for (int py = 0; py < bh; py++)
                for (int px = 0; px < bw; px++) {
                    float dist = d[py * bw + px];
                    int i = (y0 + py) * W + x0 + px;
                    if (dist == 0f) {
                        float u = (px + py * 0.6f) / (float) Math.max(1, bw + bh * 0.6f);
                        float band = 1f - Math.min(1f, Math.abs(u - sweep + 0.6f) * 9f);
                        addLight(buf, i, 0.1f + 0.35f * Math.max(0f, band));
                    } else if (dist < reach) {
                        float k = 1f - dist / reach;
                        addLight(buf, i, k * k * (breathe + 0.15f));
                    }
                }
            x = (bx0 + bx1) * 0.5f;
            y = (by0 + by1) * 0.5f;
            r = Math.max(bx1 - bx0, by1 - by0) * 0.5f + reach * 0.5f;
        }
        // sparks drift round it, each flaring and fading in its own time
        for (int s = 0; s < 5; s++) {
            float tw = (float) Math.sin(clock * 6.3f + s * 2.1f + seed);
            if (tw < 0.2f) continue;
            float a = clock * 0.9f + s * 1.2566f + seed;
            float dist = r * (1.05f + 0.35f * (float) Math.sin(clock * 1.7f + s * 3.1f));
            int sx = Math.round(x + (float) Math.cos(a) * dist), sy = Math.round(y + (float) Math.sin(a) * dist * 0.8f);
            int arm = tw > 0.75f ? 2 : 1;
            spark(buf, rows, sx, sy, 0xFFFFFFFF);
            for (int k = 1; k <= arm; k++) {
                int c = k == 1 ? 0xFFFFF2B0 : 0xFFF2C64A;
                spark(buf, rows, sx - k, sy, c);
                spark(buf, rows, sx + k, sy, c);
                spark(buf, rows, sx, sy - k, c);
                spark(buf, rows, sx, sy + k, c);
            }
        }
    }

    private void addLight(int[] buf, int i, float k) {
        int c = buf[i];
        int cr = Math.min(255, ((c >> 16) & 0xFF) + (int) (255f * k));
        int cg = Math.min(255, ((c >> 8) & 0xFF) + (int) (205f * k));
        int cb = Math.min(255, (c & 0xFF) + (int) (95f * k));
        buf[i] = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
    }

    private void spark(int[] buf, int rows, int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < W && y < rows) buf[y * W + x] = c;
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
            if (gold && !diamond && i == 2) {
                float vz = ras.project(cx, cy, cz, scr);
                if (vz > 0.05f) glowAt(scr[0], scr[1], 0.2f * scale * ras.f / vz, id << 3, (id + 10) << 3);
            }
        }
    }

    private void drawParticles() {
        for (int i = 0; i < pN; i++) {
            float vz = ras.project(pX[i], pY[i], pZ[i], scr);
            if (vz < 0.3f) continue;
            float k = pAge[i] / pLife[i];
            if (pKind[i] != SPECK) {
                drawLight(i, vz);
                continue;
            }
            if (pDust[i]) {
                float r = (0.12f + 0.5f * k) * ras.f / vz;
                ras.haze(scr[0], scr[1], r, 1f / vz, pCol[i], (int) (150 * (1f - k)));
            } else {
                float r = Math.max(0.6f, 0.03f * ras.f / vz);
                ras.disc(scr[0], scr[1], r, 1f / vz, pCol[i], 950 << 3);
            }
        }
    }

    /**
     * A shirt, a postcard or a spring on screen. Shirts and cards turn as they fall: seen
     * edge on they narrow to a sliver, and a card shows its cream back between its faces.
     */
    private void drawLight(int i, float vz) {
        float iz = 1f / vz;
        int kind = pKind[i];
        float ph = (float) Math.sin(pAge[i] * (kind == CARD ? 7f : 4.5f) + i * 2.3f);
        float lying = pVx[i] == 0f && pVy[i] == 0f && pVz[i] == 0f ? 1f : Math.abs(ph);
        int sx = Math.round(scr[0]), sy = Math.round(scr[1]);
        if (kind == CHIP) {
            float k = pAge[i] / pLife[i];
            float size = 0.22f * ras.f / vz * (k < 0.7f ? 1f : 1f - (k - 0.7f) / 0.3f);
            int s = Math.max(1, Math.round(size));
            int c = pCol[i];
            for (int y = 0; y < s; y++)
                for (int x = 0; x < s; x++)
                    lightPx(sx - s / 2 + x, sy - s / 2 + y, iz, y == 0 ? Backdrop.scale(c, 1.15f) : x == s - 1 ? Backdrop.scale(c, 0.8f) : c);
            return;
        }
        if (kind == SPRING) {
            int n = Math.max(2, Math.round(0.12f * ras.f / vz));
            for (int y = 0; y < n; y++) {
                int x = sx + ((y & 1) == 0 ? 0 : 1);
                lightPx(x, sy - y, iz, (y & 1) == 0 ? 0xFFE2E4E8 : 0xFF6E6C70);
            }
            return;
        }
        float size = (kind == SHIRT ? 0.2f : 0.12f) * ras.f / vz;
        int w = Math.max(1, Math.round(size * Math.max(0.2f, lying)));
        int hgt = Math.max(1, Math.round(size * (kind == SHIRT ? 0.85f : 0.7f)));
        int c = pCol[i];
        if (kind == CARD && ph < 0f && lying < 1f) c = 0xFFDCD3BE;
        for (int y = 0; y < hgt; y++)
            for (int x = 0; x < w; x++) {
                int cc = c;
                if (kind == SHIRT && w >= 4) {
                    // a shirt's outline: an open collar, sleeves out at the shoulders, a narrower body
                    boolean mid = x == w / 2 || (w % 2 == 0 && x == w / 2 - 1);
                    if (y == 0 && mid) continue;
                    if (y >= hgt / 2 && hgt > 3 && (x == 0 || x == w - 1)) continue;
                    // the print: pale flowers scattered on the ground color
                    int hsh = ((x * 73856093) ^ (y * 19349663) ^ (i * 83492791)) >>> 9 & 15;
                    if (hsh == 3) cc = 0xFFF8F1E2;
                    else if (hsh == 9) cc = 0xFFF2C230;
                    else if (y == 0) cc = Backdrop.scale(c, 0.8f);
                } else if (kind == SHIRT && ((x + y + i) % 3) == 0) cc = 0xFFF8F1E2;
                if (kind == CARD && c != 0xFFDCD3BE && x == w - 1 && y == 0 && w > 1) cc = 0xFFD9505A;
                lightPx(sx - w / 2 + x, sy - hgt / 2 + y, iz, cc);
            }
    }

    private void lightPx(int x, int y, float iz, int c) {
        if (x < 0 || y < 0 || x >= W || y >= h) return;
        int k = y * W + x;
        if (iz <= ras.zb[k]) return;
        ras.zb[k] = iz;
        ras.col[k] = c;
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
        float t = flight(dist, speed);
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

    // ---- the alley behind the player ----

    Alley alley;
    private final Life shore = new Life(true);
    private Raster alleyRas;
    private int[] aCol;
    private float[] aZ;
    private byte[] aLit;
    private Thread alleyPaint;

    /** Before levels 51 and 91, 151 and 191, and so on, the view turns round to the alley. */
    static boolean alleyLevel(int lv) {
        int n = lv + 1;
        return n >= 51 && (n % 100 == 51 || n % 100 == 91);
    }

    /** The street behind is painted once, in the background, while the game goes on. */
    private void paintAlley() {
        final Raster r = new Raster(W, h);
        r.camera(Alley.CAM_X, Alley.CAM_Y, 0f, Alley.YAW, Alley.PITCH, focal, h * 0.5f);
        alleyRas = r;
        aCol = null;
        alleyPaint = new Thread(new Runnable() {
            @Override
            public void run() {
                new Backdrop().paint(r, 11L);
                aZ = r.zb.clone();
                aLit = r.lit.clone();
                aCol = r.col.clone();
                // the two views on the way round, a third and two thirds of the turn
                for (int i = 1; i <= 2; i++) {
                    float k = i / 3f;
                    Raster v = new Raster(W, h);
                    v.camera(CAM_X + (Alley.CAM_X - CAM_X) * k, CAM_Y + (Alley.CAM_Y - CAM_Y) * k, 0f,
                            YAW + (Alley.YAW - YAW) * k, PITCH + (Alley.PITCH - PITCH) * k, focal, h * 0.5f);
                    new Backdrop().paint(v, 11L);
                    if (i == 2) glimpse(v, YAW + (Alley.YAW - YAW) * k, false);
                    turnCols[i] = v.col;
                }
            }
        });
        alleyPaint.setDaemon(true);
        alleyPaint.start();
    }

    void startAlley() {
        if (alley != null || alleyRas == null) return;
        try {
            alleyPaint.join();
        } catch (InterruptedException e) {
            return;
        }
        if (zoomed) setZoomed(false);
        zoomK = 0f;
        aiming = false;
        pN = 0;
        alley = new Alley(host, seed ^ 0xA11EL ^ clock2long());
        alley.phys.listener = this;
        host.sound(Synth.WHOOSH, 0.8f, 0.7f);
    }

    // ---- stone skipping, out at sea ----

    private Raster seaAlleyRas;
    private int[] sCol;
    private float[] sZ;
    private byte[] sLit;
    private final Ocean.Mask seaAlleyMask = new Ocean.Mask(), turnMask = new Ocean.Mask();
    private final int[][] seaTurn = new int[4][];

    /** Sea levels after which the ice golem comes for stone skipping: the 25th and the 50th of each fifty. */
    static boolean skipLevel(int lv) {
        int n = lv - SEA_FROM;
        return n >= 0 && (n % 50 == 24 || n % 50 == 49);
    }

    private Thread seaPaint;

    /**
     * The sea behind the floe, and the views on the way round to it, painted in the
     * background during the level before the game: nothing in it needs the light.
     */
    private void paintSeaAlley() {
        if (seaPaint != null || sCol != null && sCol.length == W * h) return;
        final Raster r = new Raster(W, h);
        r.camera(Alley.CAM_X, Alley.CAM_Y, 0f, Alley.YAW, Alley.PITCH, focal, h * 0.5f);
        seaPaint = new Thread(new Runnable() {
            @Override
            public void run() {
                Ocean o = new Ocean();
                o.paint(r, seaAlleyMask);
                for (int i = 1; i <= 2; i++) {
                    float k = i / 3f;
                    Raster v = new Raster(W, h);
                    v.camera(CAM_X + (Alley.CAM_X - CAM_X) * k, CAM_Y + (Alley.CAM_Y - CAM_Y) * k, 0f,
                            YAW + (Alley.YAW - YAW) * k, PITCH + (Alley.PITCH - PITCH) * k, focal, h * 0.5f);
                    o.paint(v, turnMask);
                    if (i == 2) glimpse(v, YAW + (Alley.YAW - YAW) * k, true);
                    seaTurn[i] = v.col;
                }
                sZ = r.zb.clone();
                sLit = r.lit.clone();
                seaAlleyRas = r;
                sCol = r.col.clone();
            }
        });
        seaPaint.setDaemon(true);
        seaPaint.start();
    }

    /**
     * The golem is never far: in the blink of the turn it stands right there, beside the
     * player, looming over him; on the ice it wades in the sea at the edge of the floe.
     */
    private static void glimpse(Raster v, float yaw, boolean ice) {
        float d = 13f, a = yaw + 0.1f;
        Golem g = new Golem(CAM_X + (float) Math.sin(a) * d, (float) Math.cos(a) * d);
        g.ice = ice;
        if (ice) g.sunk = 0.8f;
        g.update(0.4f, 0f);
        g.draw(v, 700 << 3);
        v.outlines(0, 0, v.w - 1, v.h - 1, 1);
    }

    void startSkip() {
        if (alley != null) return;
        paintSeaAlley();
        if (seaPaint != null) {
            try {
                seaPaint.join();
            } catch (InterruptedException e) {
                return;
            }
            seaPaint = null;
        }
        if (zoomed) setZoomed(false);
        zoomK = 0f;
        aiming = false;
        pN = 0;
        for (int i = 0; i < RIPPLES; i++) ripT[i] = -1f;
        // two games of stones in fifty buildings, each with figures of its own
        int n = (level - SEA_FROM) % 50;
        alley = new Alley(host, seed ^ 0x5C1BL ^ clock2long(), true, n >= 25 && n < 49 ? 0 : 1);
        alley.phys.listener = this;
        host.sound(Synth.WHOOSH, 0.8f, 0.7f);
    }

    /** Through the portal from the ice: the crossroads of five cubes on the summer street. */
    void startHub() {
        if (alley != null || alleyRas == null) return;
        try {
            alleyPaint.join();
        } catch (InterruptedException e) {
            return;
        }
        if (zoomed) setZoomed(false);
        zoomK = 0f;
        aiming = false;
        pN = 0;
        alley = Alley.hub(host, seed ^ 0x40BL ^ clock2long());
        alley.phys.listener = this;
    }

    // rings spreading on the water where a stone skipped or went under
    private static final int RIPPLES = 12;
    private final float[] ripX = new float[RIPPLES], ripZ = new float[RIPPLES], ripS = new float[RIPPLES];
    private final float[] ripT = {-1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f};
    private int ripN;

    void ripple(float x, float z, float size) {
        int i = ripN++ % RIPPLES;
        ripX[i] = x;
        ripZ[i] = z;
        ripS[i] = size;
        ripT[i] = 0f;
    }

    /** A drop of sea thrown up where a stone strikes the water. */
    void splashBit(float x, float y, float z, float vx, float vz) {
        spawnWater(x, y, z, vx, 2.5f + fx.nextFloat() * 2f, vz);
    }

    /** A puff of snow, kicked up off the ice. */
    void snowPuff(float x, float y, float z) {
        spawn(x, y, z, true, 0xFFF2F6F8, 1.4f);
    }

    private void drawRipples() {
        for (int i = 0; i < RIPPLES; i++) {
            float t = ripT[i];
            if (t < 0f || t > 2.2f) continue;
            float rad = ripS[i] + t * 1.3f;
            int a = (int) (170 * (1f - t / 2.2f));
            for (int k = 0; k < 32; k++) {
                double ang = k * Math.PI * 2 / 32;
                float vz = ras.project(ripX[i] + (float) Math.cos(ang) * rad, 0.02f, ripZ[i] + (float) Math.sin(ang) * rad, scr);
                if (vz < 1f) continue;
                ras.haze(scr[0], scr[1], Math.max(0.8f, 0.035f * ras.f / vz), 1f / (vz - 0.2f), 0xFFE6EEF2, a);
            }
        }
    }

    private final float[] dsx = new float[14], dsz = new float[14], dsy = new float[14];

    /** Now and then a corner of an ice block catches the light: a small star, there and gone. */
    private void glint(Body b, float drop, int i) {
        float ph = clock * 1.3f + i * 2.71f;
        float k = (float) Math.sin(ph) * (float) Math.sin(ph * 2.3f + i);
        if (k < 0.82f) return;
        int corner = (int) (Math.floor(ph / Math.PI) + i) & 7;
        toWorld(b, ((corner & 1) == 0 ? -1 : 1) * b.hx, ((corner & 2) == 0 ? 1 : 0.3f) * b.hy, ((corner & 4) == 0 ? -1 : 1) * b.hz, drop, wp);
        float vz = ras.project(wp[0], wp[1], wp[2], scr);
        if (vz < 1f) return;
        float a = (k - 0.82f) / 0.18f, r = 1.2f + 2.2f * a;
        int x = Math.round(scr[0]), y = Math.round(scr[1]);
        float iz = 1f / (vz - 0.3f);
        for (int d = -Math.round(r); d <= Math.round(r); d++) {
            int al = (int) (255 * a * (1f - Math.abs(d) / (r + 0.5f)));
            glintDot(x + d, y, iz, al);
            glintDot(x, y + d, iz, al);
        }
    }

    private void glintDot(int x, int y, float iz, int a) {
        if (x < 0 || y < 0 || x >= W || y >= h || a <= 0) return;
        int k = y * W + x;
        if (ras.zb[k] > iz * 1.05f) return;
        ras.col[k] = Backdrop.mix(ras.col[k], 0xFFFFFFFF, Math.min(1f, a / 255f));
    }

    /** A flat stone: a round slab, grey, thicker in the middle, turning about its middle. */
    private void drawDisc(float x, float y, float z, float spin, float r, int id) {
        int n = 14;
        float hh = r * 0.22f;
        for (int i = 0; i < n; i++) {
            // a pebble is never quite round
            float a = spin + i * (float) Math.PI * 2f / n, rr = r * (0.92f + 0.08f * (float) Math.sin(i * 2.3f));
            dsx[i] = x + (float) Math.cos(a) * rr;
            dsz[i] = z + (float) Math.sin(a) * rr;
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            qx[0] = dsx[i];
            qx[1] = dsx[j];
            qx[2] = dsx[j];
            qx[3] = dsx[i];
            qz[0] = dsz[i];
            qz[1] = dsz[j];
            qz[2] = dsz[j];
            qz[3] = dsz[i];
            qy[0] = qy[1] = y + hh;
            qy[2] = qy[3] = y - hh;
            float nx = (dsx[i] + dsx[j]) * 0.5f - x, nz = (dsz[i] + dsz[j]) * 0.5f - z, l = (float) Math.sqrt(nx * nx + nz * nz);
            nx /= l;
            nz /= l;
            if ((ras.camX - qx[0]) * nx + (ras.camZ - qz[0]) * nz <= 0f) continue;
            ras.poly(qx, qy, qz, null, null, 4, Raster.light(0xFF5E5A54, nx, 0f, nz, nx * Raster.sunX() + nz * Raster.sunZ()),
                    Raster.PAT_FLAT, id << 3, false);
        }
        for (int i = 0; i < n; i++) dsy[i] = y + hh;
        ras.poly(dsx, dsy, dsz, null, null, n, Raster.light(Alley.DISC, 0f, 1f, 0f, Raster.sunY()), Raster.PAT_STONE, id << 3, true);
    }

    private long clock2long() {
        return (long) (clock * 1000f);
    }

    private void endAlley() {
        if (alley.phase == Alley.SEA) seaFade = 1f;
        alley = null;
        pN = 0;
        aiming = false;
    }

    // golden cubes: those the gods have handed over, and those earned on the street that
    // they still hold. They give one for each figure felled in the alley, and keep the rest
    int cubes, cubesDue;
    private int cubesFalling;           // how many are coming down in the gods' hands right now

    /** A figure felled in the alley: the gods hand over one golden cube, if they hold any. */
    void giveCube() {
        cubesFalling = cubesDue > 0 ? 1 : 0;
        if (cubesDue <= 0) return;
        cubes++;
        cubesDue--;
        host.saveInt("cubes", cubes);
        host.saveInt("cubesDue", cubesDue);
    }


    /**
     * The golem's shell bursting off it: pieces of its stone, brick, planks and iron thrown
     * out from all over its body, falling round it, bouncing once and crumbling to dust.
     */
    void chips(Golem g) {
        g.chest(tmp);
        float cx = tmp[0], cy = tmp[1], cz = tmp[2];
        for (int n = 0; n < 46 && pN < MAXP; n++) {
            g.at((fx.nextFloat() - 0.5f) * 3f, 0.3f + fx.nextFloat() * 3.2f, fx.nextFloat() * 1.1f, tmp);
            int i = pN++;
            pX[i] = tmp[0];
            pY[i] = tmp[1];
            pZ[i] = tmp[2];
            float dx = tmp[0] - cx, dy = tmp[1] - cy * 0.6f, dz = tmp[2] - cz;
            float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.3f, sp = 3.5f + fx.nextFloat() * 4f;
            pVx[i] = dx / l * sp;
            pVy[i] = Math.abs(dy / l) * sp * 0.6f + 1.5f + fx.nextFloat() * 2f;
            pVz[i] = dz / l * sp;
            pLife[i] = 1.3f + fx.nextFloat() * 0.8f;
            pAge[i] = 0f;
            pCol[i] = g.ice ? ICE_SHELL[fx.nextInt(ICE_SHELL.length)] : SHELL[fx.nextInt(SHELL.length)];
            pDust[i] = false;
            pKind[i] = CHIP;
        }
    }

    private static final int[] ICE_SHELL = {0xFFDDECF3, 0xFFC4DDEA, 0xFF9CC4D8, 0xFFF2F7FA, 0xFF7FAEC8};

    // thunder on its way: the farther the strike, the later it comes, the lower and softer
    private final float[] thunderIn = {-1f, -1f, -1f, -1f};
    private final int[] thunderId = new int[4];
    private final float[] thunderGain = new float[4], thunderRate = new float[4];

    /** A strike of lightning seen: its thunder is chosen by how near it was, and sent after it. */
    private void strikeHeard(float near) {
        int id;
        if (near > 0.8f) id = Synth.thunder(fx.nextBoolean() ? 1 : 4);
        else if (near > 0.55f) id = Synth.thunder(fx.nextInt(3) == 0 ? 0 : fx.nextBoolean() ? 2 : 5);
        else id = Synth.thunder(3);
        host.buzz((int) (40 + 60 * near));
        for (int i = 0; i < thunderIn.length; i++)
            if (thunderIn[i] < 0f) {
                thunderIn[i] = (1f - near) * 0.9f;
                thunderId[i] = id;
                thunderGain[i] = 0.6f + 0.5f * near;
                thunderRate[i] = 0.88f + 0.14f * near + fx.nextFloat() * 0.08f;
                break;
            }
    }

    private void thunderStep(float dt) {
        for (int i = 0; i < thunderIn.length; i++) {
            if (thunderIn[i] < 0f) continue;
            thunderIn[i] -= dt;
            if (thunderIn[i] < 0f) host.sound(thunderId[i], thunderGain[i], thunderRate[i]);
        }
    }

    /** The gods turn to the alley with a verdict. */
    void godsLook(int verdict) {
        sky.begin(verdict, false);
        boltsHeard = sky.bolts;
    }

    /** A stone cube of the alley broken by the hammer: chips of it everywhere, and dust. */
    void burstCube(Body b) {
        int[] stone = {0xFFE4DACA, 0xFFCFC3AE, 0xFFB0A592};
        for (int n = 0; n < 40 && pN < MAXP; n++) {
            int i = pN++;
            float dx = (fx.nextFloat() - 0.5f) * 2f, dy = fx.nextFloat(), dz = (fx.nextFloat() - 0.5f) * 2f;
            pX[i] = b.px + dx * b.hx;
            pY[i] = b.py + (dy - 0.5f) * 2f * b.hy;
            pZ[i] = b.pz + dz * b.hz;
            float sp = 2.5f + fx.nextFloat() * 3.5f;
            pVx[i] = dx * sp;
            pVy[i] = 1.5f + dy * 4f;
            pVz[i] = dz * sp;
            pLife[i] = 1.2f + fx.nextFloat() * 0.8f;
            pAge[i] = 0f;
            pCol[i] = stone[fx.nextInt(stone.length)];
            pDust[i] = false;
            pKind[i] = CHIP;
        }
        for (int n = 0; n < 12; n++) spawn(b.px, b.py, b.pz, true, 0xFFDCD3C4, 2f);
    }

    /** Sea water thrown up, for the alley. */
    void spray(float x, float y, float z) {
        spawnWater(x + (fx.nextFloat() - 0.5f) * 3f, y, z + (fx.nextFloat() - 0.5f) * 3f, 0f, 6f, 0f);
    }

    /** A puff of street dust, for the alley. */
    void dust(float x, float y, float z) {
        spawn(x, y, z, true, 0xFFDCD3C4, 1.2f);
    }

    private void drawAlley(Px f) {
        Raster keep = ras;
        boolean sea = alley.sea;
        Raster.overcast(sea);
        ras = sea ? seaAlleyRas : alleyRas;
        System.arraycopy(sea ? sCol : aCol, 0, ras.col, 0, ras.col.length);
        System.arraycopy(sea ? sZ : aZ, 0, ras.zb, 0, ras.zb.length);
        System.arraycopy(sea ? sLit : aLit, 0, ras.lit, 0, ras.lit.length);
        java.util.Arrays.fill(ras.idb, 0);
        glowN = 0;
        if (sea) {
            ocean.sea(ras, seaAlleyMask, clock);
            ocean.boats(ras, clock);
            ocean.orcas(ras);
            drawRipples();
        }
        float fv = Alley.figureV(alley.round) + 1f;
        float cx = alley.wx(0f, fv), cz = alley.wz(0f, fv);
        ras.shadowBegin(cx, cz, 9f);
        for (int i = 0; i < alley.blocks.size(); i++) {
            Body b = alley.blocks.get(i);
            if (b.gone) continue;
            // ice floats: on the swell the pillars rise and sink a little, and a fallen one lies low in it
            float bob = sea && b.shown >= 1f ? Ocean.heave(b.px, b.pz, clock) * 0.8f - (Alley.knocked(b) ? 0.12f : 0f) : 0f;
            drawBody(b, b.shown, bob, 40 + i);
            if (sea && b.shown >= 1f) glint(b, bob, i);
            if (Alley.isPin(b) && b.shown >= 1f && !sea)
                for (int k = 0; k < 2; k++) {
                    toWorld(b, 0f, b.hy * (0.32f + k * 0.16f), 0f, 0f, wp);
                    ras.box(wp[0], wp[1], wp[2], b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                            b.hx * 1.04f, b.hy * 0.045f, b.hz * 1.04f, 0xFFD8402A, Raster.PAT_FLAT, (40 + i) << 3);
                }
            ras.shadowBox(b.px, b.py, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22,
                    b.hx * b.shown, b.hy * b.shown, b.hz * b.shown);
        }
        for (int i = 0; i < alley.balls.size(); i++) {
            Body b = alley.balls.get(i);
            if (b.gone) continue;
            if (sea) drawDisc(b.px, b.py, b.pz, (float) Math.atan2(b.r02, b.r00), b.radius * 1.25f, 30 + i);
            else drawBody(b, 1f, 0f, 30 + i);
            ras.shadowBall(b.px, b.py, b.pz, b.radius);
        }
        if (!alley.golem.down) alley.golem.draw(ras, 700 << 3);
        for (int k = 0; k < 5; k++) {
            Body b = alley.sign[k];
            if (b == null || b.gone || b.py < -b.hy * 0.95f) continue;
            drawBody(b, 1f, 0f, 60 + k);
            ras.shadowBox(b.px, b.py, b.pz, b.r00, b.r01, b.r02, b.r10, b.r11, b.r12, b.r20, b.r21, b.r22, b.hx, b.hy, b.hz);
        }
        if (alley.phase == Alley.CUBES && alley.next < 0) alleyHammer();
        if (!sea) shore.draw(ras);
        if (alley.phase == Alley.AIM && sea) {
            // the next stone, flat in the hand, following the finger
            ras.viewToWorld(alley.handX * 0.22f, -0.42f, 1.05f, tmp);
            drawDisc(tmp[0] + Alley.CAM_X, tmp[1] + Alley.CAM_Y, tmp[2], alley.handSpin, 0.075f, 950);
        } else if (alley.phase == Alley.AIM) {
            // the next ball, held low in front, following the finger
            ras.viewToWorld(alley.handX * 0.22f, -0.5f, 1.05f, tmp);
            float bx = tmp[0] + Alley.CAM_X, by = tmp[1] + Alley.CAM_Y, bz = tmp[2];
            float spin = alley.handSpin, c = (float) Math.cos(spin), s = (float) Math.sin(spin);
            int col = alley.handColor;
            ras.ball(bx, by, bz, 0.13f, c, 0, s, 0, 1, 0, -s, 0, c, col, Backdrop.mix(col, 0xFFFFFFFF, 0.28f), 950 << 3);
        }
        float minX = W, minY = h, maxX = 0, maxY = 0;
        for (int i = 0; i < 8; i++) {
            ras.project(cx + ((i & 1) == 0 ? -9f : 9f), (i & 2) == 0 ? 0f : 4f, cz + ((i & 4) == 0 ? -9f : 9f), scr);
            minX = Math.min(minX, scr[0]);
            maxX = Math.max(maxX, scr[0]);
            minY = Math.min(minY, scr[1]);
            maxY = Math.max(maxY, scr[1]);
        }
        ras.shadowApply(Math.max(0, (int) minX), Math.max(0, (int) minY), Math.min(W - 1, (int) maxX + 1), Math.min(h - 1, (int) maxY + 1));
        ras.outlines(0, 0, W - 1, h - 1, 1);
        if (sea) ocean.mist(ras, clock);
        // spray and dust over the outlines, so a speck is never ringed in black
        drawParticles();
        System.arraycopy(ras.col, 0, f.a, 0, Math.min(f.a.length, ras.col.length));
        if (sea) {
            rock(f);
            ocean.drawSnow(f);
        }
        if (alley.flash > 0f) {
            float k = alley.flash * alley.flash * 0.6f;
            for (int i = 0; i < f.a.length; i++) f.a[i] = Backdrop.mix(f.a[i], 0xFFFFFFFF, k);
        }
        if (alley.phase == Alley.CUBES || alley.next >= 0) signArrows(f);
        if (!noHud) drawAlleyHud(f);
        ras = keep;
    }

    /** The hammer in the alley: held ready, or flying at the cube it was thrown at. */
    private void alleyHammer() {
        heldPose(0.3f, -0.7f + (float) Math.sin(clock * 1.7f) * 0.008f, 1.35f, -0.42f, 0.86f, 0.28f, 0.94f, 0.2f, -0.28f);
        // the hammer is held a little lower here: the player stands lower than on the street
        pose[1] -= CAM_Y - Alley.CAM_Y;
        if (alley.chosen >= 0 && alley.hammerT >= 0f) {
            Body b = alley.sign[alley.chosen];
            float k = Math.min(1f, alley.hammerT / Alley.HAMMER_T);
            // to the middle of the face turned to the player, in a low arc, turning over
            float tx = b.px - alley.fx() * b.hx, ty = b.py + b.hy * 0.4f, tz = b.pz - alley.fz() * b.hz;
            float sx = pose[0], sy = pose[1], sz = pose[2];
            pose[0] = sx + (tx - sx) * k;
            pose[1] = sy + (ty - sy) * k + 0.6f * 4f * k * (1f - k);
            pose[2] = sz + (tz - sz) * k;
            float a = k * 9f, ax = alley.rx(), az = alley.rz();
            for (int c = 0; c < 3; c++) {
                // each axis of the hammer turned about the lane's right
                float vx = pose[3 + c], vy = pose[6 + c], vz = pose[9 + c];
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a), d = vx * ax + vz * az;
                // the axis crossed with the vector (the axis lies flat: no up part)
                float cx = -az * vy, cy = az * vx - ax * vz, cz = ax * vy;
                pose[3 + c] = vx * cs + cx * sn + ax * d * (1f - cs);
                pose[6 + c] = vy * cs + cy * sn;
                pose[9 + c] = vz * cs + cz * sn + az * d * (1f - cs);
            }
        }
        drawParts(false, 1f, 900, false);
    }

    // arrows cut in the cubes' faces, pointing up, right, down, left as their way goes
    private static final float[] ARROW_TURN = {0f, 0f, (float) Math.PI * 0.5f, (float) Math.PI, -(float) Math.PI * 0.5f};

    /** On each cube's face: an arrow for the way it means; a ball on the middle one. */
    private void signArrows(Px f) {
        for (int k = 0; k < 5; k++) {
            Body b = alley.sign[k];
            if (b == null || b.gone || b.py < 0f) continue;
            // the arrow sits high on the face, where it shows over the cube in front
            float gy = b.py + b.hy - b.hx * 0.75f;
            float vz = ras.project(b.px - alley.fx() * b.hx * 1.01f, gy, b.pz - alley.fz() * b.hz * 1.01f, scr);
            if (vz < 0.5f) continue;
            float iz = 1f / vz;
            float s = b.hx * ras.f / vz * 0.6f;
            int r = (int) Math.ceil(s) + 1;
            float c = (float) Math.cos(ARROW_TURN[k]), sn = (float) Math.sin(ARROW_TURN[k]);
            for (int y = -r; y <= r; y++)
                for (int x = -r; x <= r; x++) {
                    boolean in;
                    if (k == Alley.MIDDLE) {
                        // a ball with its finger holes
                        float d = (float) Math.sqrt(x * x + y * y);
                        in = d < s * 0.7f && !(Math.hypot(x + s * 0.2f, y + s * 0.25f) < s * 0.12f
                                || Math.hypot(x - s * 0.15f, y + s * 0.3f) < s * 0.12f || Math.hypot(x + s * 0.02f, y + s * 0.02f) < s * 0.12f);
                    } else {
                        // into the arrow's own frame, pointing up
                        float u = x * c + y * sn, v = -x * sn + y * c;
                        boolean head = v >= -s && v <= -s * 0.15f && Math.abs(u) <= s * 0.62f * (v + s) / (s * 0.85f);
                        boolean shaft = v > -s * 0.2f && v <= s * 0.8f && Math.abs(u) <= s * 0.2f;
                        in = head || shaft;
                    }
                    if (!in) continue;
                    int px = Math.round(scr[0]) + x, py = Math.round(scr[1]) + y;
                    if (px < 0 || py < 0 || px >= W || py >= h) continue;
                    // only where this face is what shows: not over a cube in front of it
                    if (ras.zb[py * W + px] > iz * 1.03f) continue;
                    // cut into the stone, dark
                    f.a[py * W + px] = Backdrop.mix(f.a[py * W + px], 0xFF2E2A28, 0.75f);
                }
        }
    }

    /**
     * The alley's corner, without a word: five squares for the five figures, green for
     * those felled and the one bowled at now blinking; under them the balls left; to the
     * right the bar of what still stands, with the line it must fall below, as on the street.
     */
    private void drawAlleyHud(Px f) {
        int x = 6, y = 8;
        for (int i = 0; i < Alley.ROUNDS; i++) {
            int bx = x + i * 17;
            boolean now = i == alley.round && !alley.lost && alley.won <= i;
            boolean done = i < alley.won;
            f.rect(bx, y, bx + 14, y + 14, now && ((int) (clock * 3f) & 1) == 0 ? 0xFFFFC93A : 0xFF5A3A0E);
            int fill = done ? 0xFF6FBF5A : now ? 0xFFF3E9D2 : 0xFF3A3236;
            if (i == alley.round && alley.lost) fill = 0xFFB8301E;
            f.rect(bx + 1, y + 1, bx + 13, y + 13, fill);
        }
        if (alley.phase >= Alley.AIM && alley.phase <= Alley.SETTLE) {
            int left = Alley.BALLS - alley.ball - (alley.phase == Alley.AIM ? 0 : 1);
            for (int i = 0; i < left; i++) {
                int cx = x + 5 + i * 13, cy = y + 23;
                for (int dy = -4; dy <= 4; dy++)
                    for (int dx = -4; dx <= 4; dx++)
                        if (dx * dx + dy * dy <= 17)
                            f.set(cx + dx, cy + dy, alley.sea ? (dx + dy < -3 ? 0xFFA49E96 : 0xFF5E5A54) : dx + dy < -3 ? 0xFF5A70B8 : 0xFF22357E);
            }
        }
        // what stands of the figure, with the line to bring it below
        int bw = 58, bx = W - bw - 7, by = 9;
        float on = alley.standing;
        f.rect(bx - 1, by - 1, bx + bw + 1, by + 7, 0xFF8A5A12);
        f.rect(bx, by, bx + bw, by + 6, 0xFFF3E9D2);
        f.rect(bx, by, bx + Math.round(bw * on), by + 6, on <= 1f - Alley.WIN ? 0xFF6FBF5A : 0xFFE8963C);
        int mark = bx + Math.round(bw * (1f - Alley.WIN));
        f.rect(mark, by - 3, mark + 1, by + 9, 0xFF5A3A0E);
        levelPlate(f, bx + bw + 1, by + 11);
    }

    // ---- HUD ----

    private void drawHud(Px f) {
        int x = 6, y = 8;
        for (int i = 0; i < starsMax(); i++) {
            boolean on = i < stars;
            boolean flash = state == WON && on && ((int) (stateTime * 6) & 1) == 0;
            star(f, x + i * 15 + 6, y + 6, 6.6f, on ? (flash ? 0xFFFFFFFF : 0xFFFFC93A) : 0x00000000, 0xFF8A5A12, on);
        }
        // the heavy throws left: small dark hammers under the stars, ringed in gold when in hand
        for (int i = 0; i < heavyShown; i++) heavyMark(f, x + i * 13 + 1, y + 16, heavyOn);
        // the new ones, dropping in one after another: falling from above the screen's edge,
        // a bounce on landing, ringed in a bright glint for a moment
        if (markT >= 0f)
            for (int i = heavyShown; i < heavyLeft; i++) {
                float tk = markT - (i - heavyShown) * MARK_GAP;
                if (tk <= 0f) continue;
                float yy;
                if (tk < MARK_FALL) {
                    float u = tk / MARK_FALL;
                    yy = y + 16 - 30f * (1f - u * u);
                } else {
                    float b = tk - MARK_FALL;
                    yy = y + 16 - 4f * (float) Math.abs(Math.sin(b * 14f)) * (float) Math.exp(-b * 9f);
                }
                int mx = x + i * 13 + 1, my = Math.round(yy);
                float since = tk - MARK_FALL;
                if (since > 0f && since < 0.3f) {
                    int a = Math.round(2f + 3f * (1f - since / 0.3f));
                    f.rect(mx - a, my - a, mx + 11 + a, my + 8 + a, 0xFFFFF0B0);
                }
                heavyMark(f, mx, my, heavyOn);
            }
        // the golden cubes the gods have handed over, in the row beside the stars
        int cx = x + starsMax() * 15 + 3;
        if (cubes <= 6) for (int i = 0; i < cubes; i++) cubeMark(f, cx + i * 11, y + 2);
        else {
            cubeMark(f, cx, y + 2);
            PixelFont.draw(f, String.valueOf(cubes), cx + 12, y + 3, 1, 0xFFFFE89A);
        }
        // weight still on the plinth, with the line it has to fall below; with two rows, a bar
        // for each: the one behind above, the front one below
        int bw = 58, bx = W - bw - 7, by = 9;
        f.rect(bx - 1, by - 1, bx + bw + 1, by + 7, 0xFF8A5A12);
        f.rect(bx, by, bx + bw, by + 6, 0xFFF3E9D2);
        if (rows) {
            for (int r = 0; r < 2; r++) {
                float on = onPlinth(r);
                int y0 = r == 1 ? by : by + 4;
                f.rect(bx, y0, bx + Math.round(bw * on), y0 + 2, on <= 1f - clearShare() ? 0xFF6FBF5A : 0xFFE8963C);
            }
            f.rect(bx, by + 2, bx + bw, by + 4, 0xFF8A5A12);
        } else {
            float on = onPlinth();
            f.rect(bx, by, bx + Math.round(bw * on), by + 6, on <= 1f - CLEAR ? 0xFF6FBF5A : 0xFFE8963C);
        }
        int mark = bx + Math.round(bw * (1f - clearShare()));
        f.rect(mark, by - 3, mark + 1, by + 9, 0xFF5A3A0E);
        levelPlate(f, bx + bw + 1, by + 11);
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
        if (giftT0 < 0f) planGift();
        float cx = giftX + giftVx * t + giftSway * (float) Math.sin(t * 2.3f + giftPhase);
        float cy = giftY + giftVy * t + 0.5f * giftG * t * t;
        if (cy > h + 40 || cx < -40 || cx > W + 40) return;
        float ang = giftAng + t * giftSpin, s = giftS;
        if (goldGift) {
            float ca0 = (float) Math.cos(ang), sa0 = (float) Math.sin(ang);
            glowAt(cx + ca0 * s * 0.41f, cy + sa0 * s * 0.41f, s * 0.5f, -1, -1);
        }
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

    // the gift's fall, new each time, as no two strokes of lightning are alike: where under
    // the thunderer's cloud it drops from, how big it looks (how near it falls), which way it
    // is tossed, how fast it turns and which way, how it sways in the air
    private float giftT0 = -1f, giftX, giftY, giftVx, giftVy, giftG, giftS, giftAng, giftSpin, giftSway, giftPhase;
    private final java.util.Random giftRnd = new java.util.Random(0x61F7L);

    private void planGift() {
        java.util.Random r = giftRnd;
        giftT0 = 0f;
        sky.godAt(Sky.THUNDERER, 0f, tmp);
        int gw = Math.max(40, sky.godW(Sky.THUNDERER)), gh = Math.max(30, sky.godH(Sky.THUNDERER));
        giftX = tmp[0] + (r.nextFloat() - 0.5f) * gw * 0.6f;
        giftY = tmp[1] + gh * (0.1f + r.nextFloat() * 0.25f);
        // a near one is bigger and falls faster across the screen
        float near = r.nextFloat();
        giftS = 17f + near * 15f;
        giftG = 300f + near * 260f + r.nextFloat() * 80f;
        // tossed toward the middle of the sky more often than away from it, sometimes up first
        float toMid = Math.signum(W * 0.5f - giftX);
        giftVx = (r.nextFloat() < 0.75f ? toMid : -toMid) * (15f + r.nextFloat() * 70f);
        giftVy = -60f + r.nextFloat() * 110f;
        giftAng = r.nextFloat() * 6.28f;
        giftSpin = (r.nextBoolean() ? 1f : -1f) * (3f + r.nextFloat() * 5f);
        giftSway = r.nextFloat() * 9f;
        giftPhase = r.nextFloat() * 6.28f;
    }

    /**
     * The golden cubes coming down from the thunderer's cloud, a fan of them, each turning
     * over as it falls, glowing, a glint on it now and then.
     */
    private float cubeX0, cubeY0, cubeLastT = 1e9f;

    private void drawCubeGift(Px f, float t, int n) {
        // from under the thunderer's cloud, wherever it stands this time
        if (t < cubeLastT) {
            sky.godAt(Sky.THUNDERER, 0f, tmp);
            cubeX0 = tmp[0];
            cubeY0 = tmp[1] + Math.max(30, sky.godH(Sky.THUNDERER)) * 0.25f;
        }
        cubeLastT = t;
        int shown = Math.min(n, 9);
        for (int k = 0; k < shown; k++) {
            float tk = t - k * 0.12f;
            if (tk <= 0f) continue;
            float fall = Math.min(1.7f, tk);
            float cx = cubeX0 + (k - (shown - 1) * 0.5f) * 15f * (0.6f + fall), cy = cubeY0 + 30f * fall + 220f * fall * fall;
            if (cy > h + 20) continue;
            float ang = 0.4f + tk * (3.5f + k * 0.4f), s = 7f;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            int r = (int) (s * 1.5f) + 1;
            for (int y = -r; y <= r; y++)
                for (int x = -r; x <= r; x++) {
                    float u = x * ca + y * sa, v = -x * sa + y * ca;
                    if (Math.abs(u) > s || Math.abs(v) > s) continue;
                    // light on the upper left of the face, an edge darker
                    int c = u + v < -s * 0.6f ? 0xFFFFF0B0 : Math.abs(u) > s - 1.2f || Math.abs(v) > s - 1.2f ? 0xFFB8862A : 0xFFF2C64A;
                    f.set(Math.round(cx) + x, Math.round(cy) + y, c);
                }
            glowAt(cx, cy, s * 1.2f, -1, -1);
            if (((int) (tk * 9f) + k) % 4 == 0) {
                int gx = Math.round(cx - s * 0.4f), gy = Math.round(cy - s * 0.4f);
                f.set(gx, gy, 0xFFFFFFFF);
                f.set(gx - 1, gy, 0xFFFFF4C8);
                f.set(gx + 1, gy, 0xFFFFF4C8);
                f.set(gx, gy - 1, 0xFFFFF4C8);
                f.set(gx, gy + 1, 0xFFFFF4C8);
            }
        }
    }

    /** A golden cube for the corner, nine pixels: a lit top, a face, a shaded side. */
    private static void cubeMark(Px f, int x, int y) {
        f.rect(x, y, x + 9, y + 9, 0xFF8A5A12);
        f.rect(x + 1, y + 1, x + 8, y + 3, 0xFFFFE89A);
        f.rect(x + 1, y + 3, x + 6, y + 8, 0xFFF2C64A);
        f.rect(x + 6, y + 3, x + 8, y + 8, 0xFFC0902C);
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
