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
     * three meters at once, then two more every five levels, ten meters at most.
     */
    static float farther(int level) {
        int n = level + 1;
        if (n == 1) return -1.9f;
        if (n < 21) return 0f;
        return Math.min(10f, 3f + 2f * ((n - 21) / 5));
    }
    static final float PHX = 1.42f * S, PHZ = 0.78f * S;

    // in the order they first appear: the first ones fall easily, the pyramid is the stubborn one;
    // the last three carry heavy polished balls and come in from the 15th level
    static final String[] KINDS = {"TOWER", "SEESAW", "RACK", "WALL", "WELL", "GATE", "PRESS", "STEPS",
            "PILE", "CRADLE", "CROWN"};
    static final int FIRST_KINDS = 8;
    static final int BALLS_FROM = 14;        // level, counted from 0
    // from then on: every other building is one of the new ones, the old ones go round between
    private static final int[] LATE = {8, 0, 9, 1, 10, 2, 8, 3, 9, 4, 10, 5, 8, 6, 9, 7, 10};

    static final int[] WOODS = {0xFFE3B47C, 0xFFD39E62, 0xFFC4864F, 0xFFE8C08C};
    static final int[] PAINT = {0xFF8FCBB2, 0xFFEE9272, 0xFFF2D06A, 0xFF8EBDE3, 0xFFF0A9B9};
    static final int[] STONES = {0xFFE0D3B8, 0xFFC9BEAD, 0xFFD8C6A6};

    private final ArrayList<Body> out = new ArrayList<Body>();
    private Random rnd;
    private float paintChance;
    private boolean water;           // this building has water bottles in it

    /** The pieces of one building. */
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
        else gate(size);
        return new ArrayList<Body>(out);
    }

    static int kindOf(int level) {
        if (level < BALLS_FROM) return level % FIRST_KINDS;
        return LATE[(level - BALLS_FROM) % LATE.length];
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
        float wxp = x * S, wyp = TOP + (bottom + BOTTLE_H * 0.5f) * S, wzp = CZ + z * S;
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
        piece(x * S, TOP + (bottom + sy * 0.5f) * S, CZ + z * S, sx * S, sy * S, sz * S, stone);
        return bottom + sy;
    }

    // a bowling ball, in recipe units, and the deep glossy colors such balls come in
    static final float BALL_R = 0.17f;
    static final int[] BALL_COLORS = {0xFF22357E, 0xFF6E1A2A, 0xFF34204A, 0xFF17604A, 0xFFB24A18, 0xFF1E1E26};

    /** A ball with its center at a point in recipe units above the plinth top. */
    private void ballAt(float x, float cy, float z) {
        float wx = x * S, wy = TOP + cy * S, wz = CZ + z * S;
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
