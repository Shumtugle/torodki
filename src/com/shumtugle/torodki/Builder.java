package com.shumtugle.torodki;

import java.util.ArrayList;
import java.util.Random;

/**
 * Raises a building on the plinth from a level number and a seed. Each kind of
 * building is a recipe with a few dials (storeys, width, which pieces are stone);
 * the level turns the dials up slowly and the seed varies the rest. Every piece is
 * placed exactly on what carries it; whether the result really stands is for the
 * caller to test.
 */
final class Builder {
    static final float S = 1.5f;             // recipe units to meters
    static final float TOP = 1.0f;           // plinth top
    static final float BASE_CZ = 11.5f;      // plinth center along the street, early levels
    static float CZ = BASE_CZ;               // where it stands for the level being played

    /**
     * How much farther down the street the plinth stands for a level (counted from 0):
     * the very first stands close, as the first plinth of the game did; from the 21st on,
     * three meters at once, then two more every five levels, ten meters at most; from the
     * 40th, twelve meters and again two more every five levels, twenty at most; from the
     * 50th, twenty-four, and two more every five levels, thirty at most; from the 80th,
     * thirty-six, and two more every five levels, forty-six at most.
     */
    static float farther(int level) {
        int n = level + 1;
        if (n == 1) return -1.9f;
        if (n < 21) return 0f;
        if (n < 40) return Math.min(10f, 3f + 2f * ((n - 21) / 5));
        if (n < 50) return Math.min(20f, 12f + 2f * ((n - 40) / 5));
        if (n < 80) return Math.min(30f, 24f + 2f * ((n - 50) / 5));
        return Math.min(46f, 36f + 2f * ((n - 80) / 5));
    }
    static final float PHX = 1.42f * S, PHZ = 0.78f * S;

    // in the order they first appear: the first ones fall easily, the pyramid is the stubborn one;
    // the last three carry heavy polished balls and come in from the 15th level
    static final String[] KINDS = {"TOWER", "SEESAW", "RACK", "WALL", "WELL", "GATE", "PRESS", "STEPS",
            "PILE", "CRADLE", "CROWN", "GROWN"};
    static final int FIRST_KINDS = 8, RECIPES = 11, GROWN = 11;
    static final int BALLS_FROM = 14;        // level, counted from 0
    static final int STREET_FROM = 9;        // drums, tyres, cones and sandbags, counted from 0
    static final int LUGGAGE_FROM = 11;      // suitcases and mattresses, counted from 0
    // the stand may ask for things to be grown into every building: 1 suitcases, 2 mattresses
    static volatile int force;
    // the recipes after the first eight: every other one carries balls
    private static final int[] LATE = {8, 0, 9, 1, 10, 2, 8, 3, 9, 4, 10, 5, 8, 6, 9, 7, 10};

    static final int[] WOODS = {0xFFE3B47C, 0xFFD39E62, 0xFFC4864F, 0xFFE8C08C};
    static final int[] PAINT = {0xFF8FCBB2, 0xFFEE9272, 0xFFF2D06A, 0xFF8EBDE3, 0xFFF0A9B9};
    static final int[] STONES = {0xFFE0D3B8, 0xFFC9BEAD, 0xFFD8C6A6};

    private final ArrayList<Body> out = new ArrayList<Body>();
    private Random rnd;
    private float paintChance;
    private boolean water;           // this building has water bottles in it

    /**
     * The pieces of one building, laid out on a plinth at the first plinth spot; the game
     * moves them along with the plinth. Building in one place keeps the generator free of
     * the game's state, so the next building can grow while this one is played.
     */
    ArrayList<Body> build(int level, long seed) {
        out.clear();
        rnd = new Random(seed);
        int kind = kindOf(level);
        int size = Math.min(4, level / FIRST_KINDS);
        paintChance = 0.15f + 0.1f * rnd.nextFloat();
        String k = KINDS[kind];
        // bottles are a treat, not a rule: the rack always, the rest now and then
        water = k.equals("RACK") || rnd.nextFloat() < 0.3f;
        if (k.equals("WALL")) wall(size);
        else if (k.equals("TOWER")) tower(size);
        else if (k.equals("SEESAW")) seesaw(size);
        else if (k.equals("PRESS")) press(size);
        else if (k.equals("WELL")) well(size);
        else if (k.equals("STEPS")) steps(size);
        else if (k.equals("RACK")) rack(size);
        else if (k.equals("PILE")) pile(size);
        else if (k.equals("CRADLE")) cradle(size);
        else if (k.equals("CROWN")) crown(size);
        else if (k.equals("GROWN")) grown(level);
        else gate(size);
        return new ArrayList<Body>(out);
    }

    /**
     * The first eight levels show the eight first recipes, one each. From then on two
     * buildings of three are grown fresh, and every third is a recipe, as a landmark.
     */
    static int kindOf(int level) {
        if (level < FIRST_KINDS) return level;
        int i = level - FIRST_KINDS;
        if (i % 3 != 2) return GROWN;
        int r = i / 3;
        if (level < BALLS_FROM) return r % FIRST_KINDS;
        return LATE[r % LATE.length];
    }

    // ---- the winter set ----
    // Out at sea the buildings are not grown: they come from a set of fifty laid out in
    // advance, flat in depth, of wood, stone and ice, from easy ones to stubborn ones.
    // Each is a list of blocks: material (0 wood, 1 stone, 2 ice), middle across, bottom
    // and size, in millimeters above the plinth top. After fifty they come round again,
    // mirrored on every other pass.

    static final float WINTER_DEPTH = 0.24f;      // half the depth of every block, meters
    private static volatile short[][] winter;

    static void readWinter(byte[] b) {
        if (b == null || b.length < 2) return;
        int n = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF), p = 2;
        short[][] set = new short[n][];
        for (int i = 0; i < n; i++) {
            if (p + 3 > b.length) return;
            int m = b[p + 2] & 0xFF;
            p += 3;
            if (p + m * 9 > b.length) return;
            short[] l = new short[m * 5];
            for (int k = 0; k < m; k++, p += 9) {
                l[k * 5] = b[p];
                for (int j = 0; j < 4; j++) l[k * 5 + 1 + j] = (short) (((b[p + 1 + j * 2] & 0xFF) << 8) | (b[p + 2 + j * 2] & 0xFF));
            }
            set[i] = l;
        }
        winter = set;
    }

    static boolean hasWinter() {
        return winter != null && winter.length > 0;
    }

    /** The n-th building of the winter set, counted from the first level at sea. */
    ArrayList<Body> winter(int n, long seed, boolean turn) {
        out.clear();
        rnd = new Random(seed);
        paintChance = 0.1f;
        short[][] set = winter;
        short[] l = set[n % set.length];
        float flip = ((n / set.length) % 2 == 1) != turn ? -1f : 1f;
        for (int k = 0; k < l.length / 5; k++) {
            int m = l[k * 5];
            float x = l[k * 5 + 1] * 0.001f * flip, y = l[k * 5 + 2] * 0.001f, w = l[k * 5 + 3] * 0.001f, h = l[k * 5 + 4] * 0.001f;
            Body b = piece(x, TOP + y + h * 0.5f, BASE_CZ, w, h, WINTER_DEPTH * 2f, m == 1);
            b.icy = m == 2;
        }
        return new ArrayList<Body>(out);
    }

    // ---- pieces ----

    private int wood() {
        return rnd.nextFloat() < paintChance ? PAINT[rnd.nextInt(PAINT.length)] : WOODS[rnd.nextInt(WOODS.length)];
    }

    private Body piece(float x, float y, float z, float sx, float sy, float sz, boolean stone) {
        Body b = new Body(sx * 0.5f, sy * 0.5f, sz * 0.5f, stone ? Body.STONE : Body.WOOD,
                stone ? STONES[rnd.nextInt(STONES.length)] : wood());
        b.setPose(x, y, z, 1, 0, 0, 0);
        b.homeX = x;
        b.homeY = y;
        b.homeZ = z;
        out.add(b);
        return b;
    }

    // a big water bottle, in recipe units: it bursts when hit hard
    static final float BOTTLE_D = 0.3f, BOTTLE_H = 0.5f;

    /** A water bottle standing at a bottom-center point; returns the height of its top. */
    private float bottle(float x, float bottom, float z) {
        float wxp = x * S, wyp = TOP + (bottom + BOTTLE_H * 0.5f) * S, wzp = BASE_CZ + z * S;
        Body b = new Body(BOTTLE_D * 0.5f * S, BOTTLE_H * 0.5f * S, BOTTLE_D * 0.5f * S, Body.WATER, 0xFF8FC4EE);
        b.setPose(wxp, wyp, wzp, 1, 0, 0, 0);
        b.homeX = wxp;
        b.homeY = wyp;
        b.homeZ = wzp;
        out.add(b);
        return bottom + BOTTLE_H;
    }

    /** A piece by its bottom-center point, in recipe units above the plinth top. */
    private float put(float x, float bottom, float z, float sx, float sy, float sz, boolean stone) {
        // recipes are written at a modest size; the world builds them bigger
        piece(x * S, TOP + (bottom + sy * 0.5f) * S, BASE_CZ + z * S, sx * S, sy * S, sz * S, stone);
        return bottom + sy;
    }

    // a bowling ball, in recipe units, and the deep glossy colors such balls come in
    static final float BALL_R = 0.17f;
    static final int[] BALL_COLORS = {0xFF22357E, 0xFF6E1A2A, 0xFF34204A, 0xFF17604A, 0xFFB24A18, 0xFF1E1E26};

    // ---- street things, in recipe units ----

    static final float DRUM_D = 0.3f, DRUM_H = 0.44f, TYRE_D = 0.4f, TYRE_H = 0.13f, CONE_W = 0.3f, CONE_H = 0.46f;
    static final float BAG_H = 0.13f;
    static final int[] DRUM_COLORS = {0xFF2F5D8C, 0xFFB23A2E, 0xFF3F6B45, 0xFFD9A62B, 0xFF6B6E72, 0xFF2B2F36};
    static final int[] BAG_COLORS = {0xFFC9AE7C, 0xFFBFA06A, 0xFFD2BA8A, 0xFF9C9A6A};
    static final int TYRE_COLOR = 0xFF2E2D2F;
    // old suitcases: browns, mustard, burgundy
    static final int[] CASE_COLORS = {0xFF7A4A2A, 0xFF8E5A34, 0xFF6A3E24, 0xFFC79A2E, 0xFFB08424, 0xFF7A2430, 0xFF8C2F3A};
    static final float CASE_H = 0.14f, MAT_H = 0.17f, SLAB_H = 0.08f;
    static final int[] CONCRETE = {0xFFB8B4AA, 0xFFAEAAA2, 0xFFC2BEB4};
    static final int MAT_COLOR = 0xFFE9E6DC;

    /** Any body by its bottom-center point; returns the height of its top. */
    private float thing(float x, float bottom, float z, float sx, float sy, float sz, int material, int tint) {
        float wx = x * S, wy = TOP + (bottom + sy * 0.5f) * S, wz = BASE_CZ + z * S;
        Body b = new Body(sx * 0.5f * S, sy * 0.5f * S, sz * 0.5f * S, material, tint);
        b.setPose(wx, wy, wz, 1, 0, 0, 0);
        b.homeX = wx;
        b.homeY = wy;
        b.homeZ = wz;
        out.add(b);
        return bottom + sy;
    }

    /** An empty steel drum standing on end. */
    private float drum(float x, float bottom, float z) {
        return thing(x, bottom, z, DRUM_D, DRUM_H, DRUM_D, Body.BARREL, DRUM_COLORS[rnd.nextInt(DRUM_COLORS.length)]);
    }

    /** Old car tyres lying flat, one on another. */
    private float tyres(float x, float bottom, float z, int n) {
        for (int i = 0; i < n; i++) bottom = thing(x, bottom, z, TYRE_D, TYRE_H, TYRE_D, Body.TYRE, TYRE_COLOR);
        return bottom;
    }

    /** A road cone on its square foot. */
    private float cone(float x, float bottom, float z) {
        return thing(x, bottom, z, CONE_W, CONE_H, CONE_W, Body.CONE, 0xFFF0641E);
    }

    /**
     * Old suitcases lying flat, one on another, each a little different in size and set a
     * little askew; the biggest at the bottom. Returns the height of the top one.
     */
    private float cases(float x, float bottom, float z, int n, float maxW) {
        float w = Math.min(maxW, 0.4f + rnd.nextFloat() * 0.06f);
        for (int i = 0; i < n; i++) {
            float cw = w * (1f - 0.07f * i - rnd.nextFloat() * 0.05f);
            float h = CASE_H * (0.9f + rnd.nextFloat() * 0.2f);
            float dz = 0.26f + rnd.nextFloat() * 0.04f;
            float off = (rnd.nextFloat() - 0.5f) * 0.04f;
            bottom = thing(x + off, bottom, z, cw, h, dz, Body.SUITCASE, CASE_COLORS[rnd.nextInt(CASE_COLORS.length)]);
        }
        return bottom;
    }

    /**
     * A spring mattress pressed between two concrete slabs: the slab above sinks into it,
     * and whatever stands on top sways on it instead of falling straight.
     */
    private float padded(float cx, float hw, float y, float d) {
        int c = CONCRETE[rnd.nextInt(CONCRETE.length)];
        y = thing(cx, y, 0, 2f * hw + 0.04f, SLAB_H, d + 0.04f, Body.STONE, c);
        y = thing(cx, y, 0, 2f * hw + 0.02f, MAT_H, d + 0.06f, Body.MATTRESS, MAT_COLOR);
        return thing(cx, y, 0, 2f * hw + 0.06f, SLAB_H, d + 0.04f, Body.STONE, c);
    }

    /** Sandbags laid like bricks, two or three courses: heavy, dead, hard to shift. */
    private float bags(float cx, float hw, float y, float d) {
        int n = Math.max(2, Math.round(2f * hw / 0.44f));
        float bw = 2f * hw / n;
        int rows = 2 + rnd.nextInt(2);
        int tint = BAG_COLORS[rnd.nextInt(BAG_COLORS.length)];
        for (int r = 0; r < rows; r++) {
            if ((r & 1) == 0) {
                for (int c = 0; c < n; c++) thing(cx - hw + bw * (c + 0.5f), y, 0, bw - 0.01f, BAG_H, d, Body.SANDBAG, tint);
            } else {
                thing(cx - hw + bw * 0.25f, y, 0, bw * 0.5f - 0.01f, BAG_H, d, Body.SANDBAG, tint);
                for (int c = 0; c < n - 1; c++) thing(cx - hw + bw * (c + 1f), y, 0, bw - 0.01f, BAG_H, d, Body.SANDBAG, tint);
                thing(cx + hw - bw * 0.25f, y, 0, bw * 0.5f - 0.01f, BAG_H, d, Body.SANDBAG, tint);
            }
            y += BAG_H;
        }
        return y;
    }

    /** A ball with its center at a point in recipe units above the plinth top. */
    private void ballAt(float x, float cy, float z) {
        float wx = x * S, wy = TOP + cy * S, wz = BASE_CZ + z * S;
        Body b = Body.ball(BALL_R * S, BALL_COLORS[rnd.nextInt(BALL_COLORS.length)]);
        b.setPose(wx, wy, wz, 1, 0, 0, 0);
        b.homeX = wx;
        b.homeY = wy;
        b.homeZ = wz;
        out.add(b);
    }

    /**
     * A ball on a little stone pad, with two low stops at its sides that it does not quite
     * touch: it stays where it is put and rolls the moment anything tips it. Returns its top.
     */
    private float nested(float x, float bottom, float z) {
        float pad = 0.05f, stop = 0.06f, sw = 0.07f;
        float top = put(x, bottom, z, 0.22f, pad, 0.22f, true);
        float reach = (float) Math.sqrt(BALL_R * BALL_R - (BALL_R - stop) * (BALL_R - stop)) + 0.012f;
        put(x - reach - sw / 2, bottom, z, sw, pad + stop, 0.2f, false);
        put(x + reach + sw / 2, bottom, z, sw, pad + stop, 0.2f, false);
        ballAt(x, top + BALL_R, z);
        return top + 2f * BALL_R;
    }

    // ---- the grower ----
    //
    // A building is grown from the plinth up, storey by storey. Each storey is one of a few
    // ways to carry weight: posts under a slab, a brick wall, bottles under a slab, balls or
    // balloons under a slab, a stack of cubes. Above it the tower goes on as wide, narrows,
    // or splits in two, often mirrored. Whatever stands on something is placed over its
    // support, so most of what grows stands; the physics test throws out the rest.

    private int budget;
    private float maxH;
    private boolean useBottles, useBalls, useBalloons;
    private boolean useDrums, useTyres, useBags, useCones, useCases, useMats;
    private static final int[] BALLOON_COLORS = {0xFFE8483C, 0xFFF2C230, 0xFF3F8FD8, 0xFF5DBE6A, 0xFFE87AB0, 0xFFF29A3A};

    private void grown(int level) {
        budget = 12 + Math.min(32, level * 3 / 4);
        maxH = 2.6f + Math.min(0.9f, level * 0.025f);
        useBottles = rnd.nextFloat() < (water ? 0.8f : 0.3f);
        useBalls = level >= BALLS_FROM && rnd.nextFloat() < 0.45f;
        useBalloons = level >= BALLS_FROM + 2 && rnd.nextFloat() < 0.45f;
        boolean street = level >= STREET_FROM;
        useDrums = street && rnd.nextFloat() < 0.4f;
        useTyres = street && rnd.nextFloat() < 0.4f;
        useBags = street && rnd.nextFloat() < 0.35f;
        useCones = street && rnd.nextFloat() < 0.4f;
        boolean luggage = level >= LUGGAGE_FROM;
        useCases = luggage && rnd.nextFloat() < 0.4f || (force & 1) != 0;
        useMats = luggage && rnd.nextFloat() < 0.5f || (force & 2) != 0;
        float hw = 0.95f + rnd.nextFloat() * 0.35f;
        float d = 0.34f + rnd.nextInt(3) * 0.08f;
        if (useBalls || useBalloons) d = Math.max(d, 0.46f);
        grow(0f, hw, 0f, d, 0, 1f, true);
    }

    private void balloonAt(float x, float cy, float z, float r) {
        float wx = x * S, wy = TOP + cy * S, wz = BASE_CZ + z * S;
        Body b = Body.balloon(r * S, BALLOON_COLORS[rnd.nextInt(BALLOON_COLORS.length)]);
        b.setPose(wx, wy, wz, 1, 0, 0, 0);
        b.homeX = wx;
        b.homeY = wy;
        b.homeZ = wz;
        out.add(b);
    }

    /**
     * Grows a tower over [cx - hw, cx + hw] from height y; sign mirrors anything lopsided.
     * Returns the height it reached; with capIt false the top is left bare for a bridge.
     */
    private float grow(float cx, float hw, float y, float d, int storey, float sign, boolean capIt) {
        if (y > maxH || out.size() >= budget || hw < 0.16f) {
            if (capIt) cap(cx, hw, y, d);
            return y;
        }
        boolean stone = storey == 0 ? rnd.nextFloat() < 0.5f : rnd.nextFloat() < 0.2f;
        float top;
        float nhw = hw;
        if (hw < 0.3f) {
            top = column(cx, hw, y, stone);
            nhw = Math.min(hw, 0.17f);
        } else {
            // each way of carrying weight by its own chance, among those this building may use
            boolean deep = d >= 0.42f && hw >= 0.4f;
            float wBalloon = useBalloons && deep ? 1.2f : 0f, wBall = useBalls && deep ? 1.2f : 0f;
            float wBottle = useBottles ? 1.4f : 0f, wSteps = storey == 0 && hw > 0.6f ? 0.5f : 0f;
            float wWall = 1.1f, wPortal = 1.6f, wFence = hw > 0.45f ? 0.9f : 0f, wDeck = storey > 0 && hw > 0.5f ? 0.5f : 0f;
            float wDrum = useDrums ? 1.3f : 0f, wTyre = useTyres ? 1.2f : 0f, wBags = useBags && storey < 2 ? 1.2f : 0f;
            // suitcases ride the upper storeys; a mattress may lie anywhere but the very top
            float wCases = useCases && storey >= 1 ? ((force & 1) != 0 ? 6f : 1.4f) : 0f;
            float wMat = useMats && storey < 3 && y < maxH - 0.5f ? ((force & 2) != 0 ? 6f : 2.6f) : 0f;
            float r = rnd.nextFloat() * (wBalloon + wBall + wBottle + wSteps + wWall + wPortal + wFence + wDeck
                    + wDrum + wTyre + wBags + wCases + wMat);
            if ((r -= wCases) < 0f) top = carried(cx, hw, y, d, 5);
            else if ((r -= wMat) < 0f) top = padded(cx, hw, y, d);
            else if ((r -= wDrum) < 0f) top = carried(cx, hw, y, d, 3);
            else if ((r -= wTyre) < 0f) top = carried(cx, hw, y, d, 4);
            else if ((r -= wBags) < 0f) top = bags(cx, hw, y, d);
            else if ((r -= wBalloon) < 0f) top = carried(cx, hw, y, d, 2);
            else if ((r -= wBall) < 0f) top = carried(cx, hw, y, d, 1);
            else if ((r -= wBottle) < 0f) top = carried(cx, hw, y, d, 0);
            else if ((r -= wSteps) < 0f) {
                float[] t = steps(cx, hw, y, d, stone);
                top = t[0];
                nhw = t[1];
            } else if ((r -= wWall) < 0f) top = wall(cx, hw, y, d, stone);
            else if ((r -= wFence) < 0f) top = fence(cx, hw, y, d, stone);
            else if ((r -= wDeck) < 0f) {
                top = deck(cx, hw, y, d, stone);
                nhw = Math.min(hw * 1.35f, hw + 0.3f);
            } else top = portal(cx, hw, y, d, stone);
        }
        float next = rnd.nextFloat();
        if (nhw > 0.72f && next < 0.38f && out.size() < budget - 6) {
            // two towers side by side, usually mirror images
            float gap = 0.12f + rnd.nextFloat() * 0.25f;
            float shw = (nhw - gap / 2f) / 2f;
            long seed = rnd.nextLong();
            boolean mirror = rnd.nextFloat() < 0.75f;
            // twin towers of one height may be bridged by a plank, and the tower goes on over it
            boolean bridge = mirror && rnd.nextFloat() < 0.45f && out.size() < budget - 10;
            Random keep = rnd;
            rnd = new Random(seed);
            float hl = grow(cx - sign * (shw + gap / 2f), shw, top, d, storey + 1, -sign, !bridge);
            if (mirror) rnd = new Random(seed);
            float hr = grow(cx + sign * (shw + gap / 2f), shw, top, d, storey + 1, sign, !bridge);
            rnd = keep;
            if (bridge && Math.abs(hl - hr) < 0.005f) {
                float bt = put(cx, hl, 0, 2f * nhw + 0.04f, 0.1f, d + 0.04f, rnd.nextFloat() < 0.4f);
                return grow(cx, nhw * (0.45f + rnd.nextFloat() * 0.3f), bt, d, storey + 2, sign, true);
            }
            if (bridge) {
                cap(cx - sign * (shw + gap / 2f), shw, hl, d);
                cap(cx + sign * (shw + gap / 2f), shw, hr, d);
            }
            return Math.max(hl, hr);
        } else if (next < 0.75f) {
            // narrower, in the middle or toward one side
            float k = 0.5f + rnd.nextFloat() * 0.35f;
            float shift = rnd.nextFloat() < 0.6f ? 0f : sign * (nhw - nhw * k) * rnd.nextFloat();
            return grow(cx + shift, nhw * k, top, Math.max(0.3f, d - (rnd.nextFloat() < 0.3f ? 0.08f : 0f)), storey + 1, sign, capIt);
        }
        return grow(cx, nhw, top, d, storey + 1, sign, capIt);
    }

    /** A palisade: a row of upright posts, a hand's breadth apart, under one plank. */
    private float fence(float cx, float hw, float y, float d, boolean stone) {
        float p = 0.12f, gap = 0.1f + rnd.nextFloat() * 0.06f, h = 0.45f + rnd.nextFloat() * 0.3f;
        int n = Math.max(3, Math.min(6, (int) ((2f * hw + gap) / (p + gap))));
        float span = n * p + (n - 1) * gap;
        for (int i = 0; i < n; i++) put(cx - span / 2f + p / 2f + i * (p + gap), y, 0, p, h, d, stone);
        return put(cx, y + h, 0, 2f * hw + 0.06f, 0.1f, d + 0.04f, false);
    }

    /** Top-heavy: a wide deck on two close posts in the middle, overhanging both sides. */
    private float deck(float cx, float hw, float y, float d, boolean stone) {
        float p = 0.16f, h = 0.4f + rnd.nextFloat() * 0.2f, o = hw * 0.35f;
        put(cx - o, y, 0, p, h, d, stone);
        put(cx + o, y, 0, p, h, d, stone);
        float w = Math.min(2f * hw * 1.35f, 2f * hw + 0.6f);
        return put(cx, y + h, 0, w, 0.1f, d + 0.04f, false);
    }

    /** Posts under a slab: two at the ends, a third in the middle of a wide one, four for a deep one. */
    private float portal(float cx, float hw, float y, float d, boolean stone) {
        float p = 0.14f + rnd.nextFloat() * 0.06f, h = 0.4f + rnd.nextFloat() * 0.25f;
        boolean four = d >= 0.5f && rnd.nextFloat() < 0.4f;
        float o = hw - p / 2f;
        for (int s = -1; s <= 1; s += 2) {
            if (four) {
                put(cx + s * o, y, -(d / 2f - p / 2f), p, h, p, stone);
                put(cx + s * o, y, d / 2f - p / 2f, p, h, p, stone);
            } else put(cx + s * o, y, 0, p, h, d, stone);
        }
        if (hw > 0.9f) put(cx, y, 0, p, h, four ? p : d, stone);
        return put(cx, y + h, 0, 2f * hw + 0.06f, 0.1f, d + 0.04f, rnd.nextFloat() < 0.3f);
    }

    /** Bricks in running bond, two to four courses. */
    private float wall(float cx, float hw, float y, float d, boolean stone) {
        int n = Math.max(2, Math.round(2f * hw / 0.42f));
        float bw = 2f * hw / n, bh = 0.2f;
        int rows = 2 + rnd.nextInt(3);
        for (int r = 0; r < rows; r++) {
            boolean st = stone && r == 0;
            if ((r & 1) == 0) {
                for (int c = 0; c < n; c++) put(cx - hw + bw * (c + 0.5f), y, 0, bw, bh, d, st);
            } else {
                put(cx - hw + bw * 0.25f, y, 0, bw * 0.5f, bh, d, st);
                for (int c = 0; c < n - 1; c++) put(cx - hw + bw * (c + 1f), y, 0, bw, bh, d, st);
                put(cx + hw - bw * 0.25f, y, 0, bw * 0.5f, bh, d, st);
            }
            y += bh;
        }
        return y;
    }

    /** Cubes stacked into a little column; or a stack of tyres, a drum, or suitcases. */
    private float column(float cx, float hw, float y, boolean stone) {
        if (useCases && rnd.nextFloat() < 0.3f) return cases(cx, y, 0, 2 + rnd.nextInt(2), Math.max(0.3f, 2f * hw + 0.1f));
        if (useTyres && rnd.nextFloat() < 0.4f) return tyres(cx, y, 0, 2 + rnd.nextInt(3));
        if (useDrums && rnd.nextFloat() < 0.35f) return drum(cx, y, 0);
        float c = Math.min(2f * hw, 0.34f);
        int n = 1 + rnd.nextInt(3);
        for (int i = 0; i < n; i++) y = put(cx, y, 0, c, c, c, stone && i == 0);
        return y;
    }

    /** A low stepped pyramid; returns its top and the half width of its top block. */
    private float[] steps(float cx, float hw, float y, float d, boolean stone) {
        int base = Math.max(3, Math.min(5, Math.round(2f * hw / 0.45f)));
        float bw = 2f * hw / base, bh = 0.26f;
        int rows = Math.min(base, 2 + rnd.nextInt(2));
        for (int r = 0; r < rows; r++) {
            int n = base - r;
            for (int i = 0; i < n; i++) put(cx + (i - (n - 1) / 2f) * bw, y, 0, bw, bh, d, stone && r == 0);
            y += bh;
        }
        return new float[]{y, (base - rows + 1) * bw / 2f};
    }

    /**
     * A slab carried by something that gives way: water bottles (0) that burst, polished
     * balls on pads (1) that roll out, balloons (2) that pop and fly off, empty steel
     * drums (3) that ring and roll, stacks of old tyres (4) that bounce away, or stacks of
     * old suitcases (5) that soak up a blow.
     */
    private float carried(float cx, float hw, float y, float d, int what) {
        float top;
        float o = Math.max(0.2f, hw - 0.18f);
        if (what == 0 || what == 3) {
            int n = hw > 0.8f ? 3 : 2;
            top = y;
            for (int i = 0; i < n; i++) {
                float x = cx - o + 2f * o * i / (n - 1);
                top = Math.max(top, what == 0 ? bottle(x, y, 0) : drum(x, y, 0));
            }
        } else if (what == 5) {
            top = y;
            float cw = Math.min(0.44f, Math.max(0.3f, hw - 0.05f));
            float co = Math.max(0.16f, hw - cw / 2f);
            int n = 2 + rnd.nextInt(2);
            // every stack from the same draw, so they come out one height and the slab lies flat
            long seed = rnd.nextLong();
            Random keep = rnd;
            for (int i = -1; i <= 1; i += 2) {
                rnd = new Random(seed);
                top = Math.max(top, cases(cx + i * co, y, 0, n, cw));
            }
            if (hw > 0.85f) {
                rnd = new Random(seed);
                top = Math.max(top, cases(cx, y, 0, n, cw));
            }
            rnd = keep;
        } else if (what == 4) {
            int n = 3 + rnd.nextInt(2);
            boolean four = d >= 0.5f;
            float zo = d / 2f - TYRE_D / 2f + 0.03f;
            top = y;
            for (int i = -1; i <= 1; i += 2) {
                if (four) {
                    tyres(cx + i * o, y, -zo, n);
                    top = Math.max(top, tyres(cx + i * o, y, zo, n));
                } else top = Math.max(top, tyres(cx + i * o, y, 0, n));
            }
        } else {
            float zo = d / 2f - 0.15f;
            top = y;
            for (int i = -1; i <= 1; i += 2)
                for (int j = -1; j <= 1; j += 2) {
                    if (what == 1) top = Math.max(top, nested(cx + i * o, y, j * Math.max(0.17f, zo)));
                    else {
                        float r = 0.2f;
                        balloonAt(cx + i * o, y + r, j * Math.max(0.2f, zo), r);
                        top = Math.max(top, y + 2f * r);
                    }
                }
        }
        return put(cx, top, 0, 2f * hw + 0.06f, 0.1f, d + 0.04f, false);
    }

    /** What crowns a tower: a cube, a ball, a bottle, three cubes, road cones, or nothing. */
    private void cap(float cx, float hw, float y, float d) {
        float r = rnd.nextFloat();
        if (out.size() >= budget + 4 || r < 0.15f) return;
        if (useCases && hw >= 0.18f && rnd.nextFloat() < 0.45f) {
            int n = 2 + rnd.nextInt(2);
            if (hw >= 0.6f) {
                cases(cx - hw * 0.5f, y, 0, n, hw * 0.9f);
                cases(cx + hw * 0.5f, y, 0, 2 + rnd.nextInt(2), hw * 0.9f);
            } else cases(cx, y, 0, n, Math.max(0.3f, 2f * hw + 0.12f));
            return;
        }
        if (useCones && rnd.nextFloat() < 0.5f) {
            int n = hw >= 0.5f ? 2 + rnd.nextInt(2) : 1;
            float step = Math.min(0.34f, 2f * hw / n);
            for (int i = 0; i < n; i++) cone(cx + (i - (n - 1) / 2f) * step, y, 0);
            return;
        }
        if (useBalls && r < 0.35f && hw >= 0.2f) nested(cx, y, 0);
        else if (r < 0.42f && hw >= 0.2f) {
            // a balanced T: a plank on one narrow post, a weight at each end
            float t = put(cx, y, 0, 0.14f, 0.36f, 0.14f, true);
            float half = 0.45f + rnd.nextFloat() * 0.2f;
            t = put(cx, t, 0, 2f * half, 0.08f, 0.3f, false);
            put(cx - half + 0.12f, t, 0, 0.22f, 0.22f, 0.22f, false);
            put(cx + half - 0.12f, t, 0, 0.22f, 0.22f, 0.22f, false);
        }
        else if (useBottles && r < 0.5f) bottle(cx, y, 0);
        else if (hw >= 0.45f && r < 0.75f) {
            float c = 0.3f;
            put(cx - c / 2f - 0.01f, y, 0, c, c, c, false);
            float t = put(cx + c / 2f + 0.01f, y, 0, c, c, c, false);
            put(cx, t, 0, c, c, c, false);
        } else put(cx, y, 0, Math.min(0.3f, 2f * hw), Math.min(0.3f, 2f * hw), Math.min(0.3f, 2f * hw), false);
    }

    // ---- recipes ----

    /** Balls piled like cannonballs, nine, four and one, in a low wooden frame. */
    private void pile(int size) {
        float d = BALL_R * 2f + 0.004f, inner = 3f * d, t = 0.1f, fh = 0.14f;
        put(0, 0, -(inner / 2 + t / 2), inner + 2 * t, fh, t, true);
        put(0, 0, inner / 2 + t / 2, inner + 2 * t, fh, t, true);
        put(-(inner / 2 + t / 2), 0, 0, t, fh, inner, true);
        put(inner / 2 + t / 2, 0, 0, t, fh, inner, true);
        float up = d / (float) Math.sqrt(2.0);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) ballAt((i - 1) * d, BALL_R, (j - 1) * d);
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++) ballAt((i - 0.5f) * d, BALL_R + up, (j - 0.5f) * d);
        ballAt(0, BALL_R + 2 * up, 0);
    }

    /** A beam on two stone pillars, a row of balls along it, each in its little cradle. */
    private void cradle(int size) {
        float y = 0f;
        int blocks = 1 + rnd.nextInt(2) + (size > 2 ? 1 : 0);
        for (int i = 0; i < blocks; i++) {
            put(-0.75f, y, 0, 0.3f, 0.4f, 0.3f, true);
            y = put(0.75f, y, 0, 0.3f, 0.4f, 0.3f, true);
        }
        y = put(0, y, 0, 2.3f, 0.12f, 0.42f, false);
        int n = 3 + (size >= 2 ? 1 : 0);
        for (int i = 0; i < n; i++) nested(-0.85f + 1.7f * i / (n - 1), y, 0);
    }

    /** Storeys of four posts and a slab, a ball in a cradle on every floor, one on the top. */
    private void crown(int size) {
        int storeys = 2 + Math.min(1, size) + rnd.nextInt(2);
        float w = 1.3f, y = 0f, post = 0.22f;
        for (int s = 0; s < storeys; s++) {
            float ph = 0.46f;
            float o = w / 2 - post / 2;
            for (int i = 0; i < 4; i++)
                put((i & 1) == 0 ? -o : o, y, (i & 2) == 0 ? -o : o, post, ph, post, false);
            if (s > 0) nested(0, y, 0);
            y += ph;
            y = put(0, y, 0, w + 0.1f, 0.1f, w + 0.1f, rnd.nextFloat() < 0.4f);
            w *= 0.86f;
        }
        nested(0, y, 0);
    }

    /** Bricks in running bond. */
    private void wall(int size) {
        int cols = 4 + Math.min(2, size);
        int rows = 5 + size + rnd.nextInt(3);
        float bw = 0.42f, bh = 0.2f, bd = 0.24f + rnd.nextFloat() * 0.06f;
        boolean stoneRows = rnd.nextBoolean();
        float width = cols * bw;
        float y = 0f;
        for (int r = 0; r < rows; r++) {
            boolean stone = stoneRows && r % 3 == 0;
            if ((r & 1) == 0) {
                for (int c = 0; c < cols; c++) put(-width / 2 + bw * (c + 0.5f), y, 0, bw, bh, bd, stone);
            } else {
                put(-width / 2 + bw * 0.25f, y, 0, bw * 0.5f, bh, bd, stone);
                for (int c = 0; c < cols - 1; c++) put(-width / 2 + bw * (c + 1f), y, 0, bw, bh, bd, stone);
                put(width / 2 - bw * 0.25f, y, 0, bw * 0.5f, bh, bd, stone);
            }
            y += bh;
        }
    }

    /** Storeys of four posts and a slab, narrowing upward. */
    private void tower(int size) {
        int storeys = 2 + Math.min(2, size) + rnd.nextInt(2);
        float w = 1.25f + rnd.nextFloat() * 0.2f;
        float y = 0f;
        float post = 0.15f;
        for (int s = 0; s < storeys; s++) {
            float ph = 0.5f + rnd.nextFloat() * 0.15f;
            float o = w / 2 - post / 2;
            for (int i = 0; i < 4; i++)
                put((i & 1) == 0 ? -o : o, y, (i & 2) == 0 ? -o : o, post, ph, post, false);
            if (water && ph > BOTTLE_H + 0.03f && rnd.nextInt(3) != 0) bottle(0, y, 0);
            y += ph;
            y = put(0, y, 0, w + 0.1f, 0.1f, w + 0.1f, rnd.nextFloat() < 0.5f);
            w *= 0.84f;
        }
        if (water && rnd.nextBoolean()) bottle(0, y, 0);
        else put(0, y, 0, 0.3f, 0.3f, 0.3f, false);
    }

    /** A pillar with a long beam balanced on it and loads at both ends. */
    private void seesaw(int size) {
        float y = 0f;
        int blocks = 2 + rnd.nextInt(2);
        for (int i = 0; i < blocks; i++) y = put(0, y, 0, 0.42f, 0.38f, 0.42f, true);
        float len = 2.4f + 0.2f * Math.min(2, size);
        y = put(0, y, 0, len, 0.14f, 0.34f, false);
        int load = 2 + Math.min(2, size) + rnd.nextInt(2);
        for (int side = -1; side <= 1; side += 2) {
            float yy = y;
            for (int i = 0; i < load; i++) yy = put(side * (len / 2 - 0.2f), yy, 0, 0.3f, 0.3f, 0.3f, i == 0 && rnd.nextBoolean());
            if (water && rnd.nextInt(3) != 0) bottle(side * (len / 2 - 0.2f), yy, 0);
        }
        put(0, y, 0, 0.3f, 0.3f, 0.3f, false);
    }

    /** A heavy cube of small blocks on one thin stone leg. */
    private void press(int size) {
        float y = 0f;
        int legs = 1 + rnd.nextInt(2);
        for (int i = 0; i < legs; i++) y = put(0, y, 0, 0.3f, 0.55f, 0.3f, true);
        y = put(0, y, 0, 1.3f, 0.1f, 1.0f, true);
        int n = 3, layers = 2 + Math.min(2, size);
        float c = 0.32f;
        for (int l = 0; l < layers; l++) {
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    put((i - 1) * c, y, (j - 1) * c, c, c, c, false);
            y += c;
        }
        if (water) bottle(0, y, 0);
    }

    /** A well: crossed logs, layer on layer. */
    private void well(int size) {
        int layers = 6 + Math.min(4, size * 2) + rnd.nextInt(3);
        float len = 1.45f, t = 0.16f, o = len / 2 - 0.14f;
        float y = 0f;
        for (int l = 0; l < layers; l++) {
            boolean alongX = (l & 1) == 0;
            for (int s = -1; s <= 1; s += 2) {
                if (alongX) put(0, y, s * o, len, t, t, false);
                else put(s * o, y, 0, t, t, len, false);
            }
            y += t;
        }
        put(0, y, 0, len + 0.1f, 0.08f, len + 0.1f, false);
    }

    /** A stepped pyramid, one block deep, laid like bricks. */
    private void steps(int size) {
        int base = 4 + Math.min(2, size);
        float bw = 2.6f / base, bh = 0.3f, bd = 0.42f;
        float y = 0f;
        for (int r = 0; r < base; r++) {
            int n = base - r;
            for (int i = 0; i < n; i++) {
                float x = (i - (n - 1) / 2f) * bw;
                boolean stone = r == n - 1 && rnd.nextBoolean();
                put(x, y, 0f, bw, bh, bd, stone);
            }
            y += bh;
        }
        if (water) bottle(0, y, 0);
    }

    /** Shelves on posts, tier on tier, every shelf lined with water bottles. */
    private void rack(int size) {
        int tiers = 2 + Math.min(1, size) + (size > 2 ? 1 : 0);
        int perShelf = 3 + Math.min(1, size);
        float width = perShelf * 0.38f + 0.2f, depth = 0.5f, post = 0.12f, gapH = BOTTLE_H + 0.06f;
        float y = 0f;
        float ox = width / 2 - post / 2, oz = depth / 2 - post / 2;
        for (int t = 0; t < tiers; t++) {
            // bottles stand on the shelf below (or on the plinth), between the posts
            for (int i = 0; i < perShelf; i++) bottle((i - (perShelf - 1) / 2f) * 0.38f, y, 0f);
            for (int i = 0; i < 4; i++) put((i & 1) == 0 ? -ox : ox, y, (i & 2) == 0 ? -oz : oz, post, gapH, post, false);
            y += gapH;
            y = put(0, y, 0, width + 0.06f, 0.08f, depth + 0.06f, t == 0 && rnd.nextBoolean());
        }
        put(0, y, 0, 0.3f, 0.3f, 0.3f, false);
    }

    /** Two columns, a lintel, and a little roof of blocks on top. */
    private void gate(int size) {
        int h = 3 + Math.min(2, size) + rnd.nextInt(2);
        float c = 0.34f, gap = 1.1f;
        float y = 0f;
        for (int i = 0; i < h; i++) {
            put(-gap / 2 - c / 2, y, 0, c, c, c, i == 0);
            put(gap / 2 + c / 2, y, 0, c, c, c, i == 0);
            y += c;
        }
        if (water && h * c > BOTTLE_H + 0.05f) bottle(0, 0f, 0);
        float span = gap + 2 * c + 0.2f;
        y = put(0, y, 0, span, 0.14f, 0.4f, rnd.nextBoolean());
        int n = 4;
        for (int r = 0; r < 3 && n > 0; r++, n--) {
            for (int i = 0; i < n; i++) put((i - (n - 1) / 2f) * 0.36f, y, 0, 0.34f, 0.24f, 0.34f, false);
            y += 0.24f;
        }
    }
}
