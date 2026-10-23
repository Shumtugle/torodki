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
    static final float TOP = 0.75f;          // plinth top
    static final float CZ = 11.5f;           // plinth center along the street
    static final float PHX = 1.42f, PHZ = 0.78f;

    // in the order they first appear: the first ones fall easily, the pyramid is the stubborn one
    static final String[] KINDS = {"TOWER", "SEESAW", "WALL", "WELL", "GATE", "PRESS", "STEPS"};

    static final int[] WOODS = {0xFFE3B47C, 0xFFD39E62, 0xFFC4864F, 0xFFE8C08C};
    static final int[] PAINT = {0xFF8FCBB2, 0xFFEE9272, 0xFFF2D06A, 0xFF8EBDE3, 0xFFF0A9B9};
    static final int[] STONES = {0xFFE0D3B8, 0xFFC9BEAD, 0xFFD8C6A6};

    private final ArrayList<Body> out = new ArrayList<Body>();
    private Random rnd;
    private float paintChance;

    /** The pieces of one building. */
    ArrayList<Body> build(int level, long seed) {
        out.clear();
        rnd = new Random(seed);
        int kind = kindOf(level);
        int size = Math.min(4, level / KINDS.length);
        paintChance = 0.15f + 0.1f * rnd.nextFloat();
        String k = KINDS[kind];
        if (k.equals("WALL")) wall(size);
        else if (k.equals("TOWER")) tower(size);
        else if (k.equals("SEESAW")) seesaw(size);
        else if (k.equals("PRESS")) press(size);
        else if (k.equals("WELL")) well(size);
        else if (k.equals("STEPS")) steps(size);
        else gate(size);
        return new ArrayList<Body>(out);
    }

    static int kindOf(int level) {
        return level % KINDS.length;
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

    /** A piece by its bottom-center point. */
    private float put(float x, float bottom, float z, float sx, float sy, float sz, boolean stone) {
        piece(x, bottom + sy * 0.5f, CZ + z, sx, sy, sz, stone);
        return bottom + sy;
    }

    // ---- recipes ----

    /** Bricks in running bond. */
    private void wall(int size) {
        int cols = 4 + Math.min(2, size);
        int rows = 5 + size + rnd.nextInt(3);
        float bw = 0.42f, bh = 0.2f, bd = 0.24f + rnd.nextFloat() * 0.06f;
        boolean stoneRows = rnd.nextBoolean();
        float width = cols * bw;
        float y = TOP;
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
        float y = TOP;
        float post = 0.15f;
        for (int s = 0; s < storeys; s++) {
            float ph = 0.5f + rnd.nextFloat() * 0.15f;
            float o = w / 2 - post / 2;
            for (int i = 0; i < 4; i++)
                put((i & 1) == 0 ? -o : o, y, (i & 2) == 0 ? -o : o, post, ph, post, false);
            y += ph;
            y = put(0, y, 0, w + 0.1f, 0.1f, w + 0.1f, rnd.nextFloat() < 0.5f);
            w *= 0.84f;
        }
        put(0, y, 0, 0.3f, 0.3f, 0.3f, false);
    }

    /** A pillar with a long beam balanced on it and loads at both ends. */
    private void seesaw(int size) {
        float y = TOP;
        int blocks = 2 + rnd.nextInt(2);
        for (int i = 0; i < blocks; i++) y = put(0, y, 0, 0.42f, 0.38f, 0.42f, true);
        float len = 2.4f + 0.2f * Math.min(2, size);
        y = put(0, y, 0, len, 0.14f, 0.34f, false);
        int load = 2 + Math.min(2, size) + rnd.nextInt(2);
        for (int side = -1; side <= 1; side += 2) {
            float yy = y;
            for (int i = 0; i < load; i++) yy = put(side * (len / 2 - 0.2f), yy, 0, 0.3f, 0.3f, 0.3f, i == 0 && rnd.nextBoolean());
        }
        put(0, y, 0, 0.3f, 0.3f, 0.3f, false);
    }

    /** A heavy cube of small blocks on one thin stone leg. */
    private void press(int size) {
        float y = TOP;
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
    }

    /** A well: crossed logs, layer on layer. */
    private void well(int size) {
        int layers = 6 + Math.min(4, size * 2) + rnd.nextInt(3);
        float len = 1.45f, t = 0.16f, o = len / 2 - 0.14f;
        float y = TOP;
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
        float y = TOP;
        for (int r = 0; r < base; r++) {
            int n = base - r;
            for (int i = 0; i < n; i++) {
                float x = (i - (n - 1) / 2f) * bw;
                boolean stone = r == n - 1 && rnd.nextBoolean();
                put(x, y, 0f, bw, bh, bd, stone);
            }
            y += bh;
        }
    }

    /** Two columns, a lintel, and a little roof of blocks on top. */
    private void gate(int size) {
        int h = 3 + Math.min(2, size) + rnd.nextInt(2);
        float c = 0.34f, gap = 1.1f;
        float y = TOP;
        for (int i = 0; i < h; i++) {
            put(-gap / 2 - c / 2, y, 0, c, c, c, i == 0);
            put(gap / 2 + c / 2, y, 0, c, c, c, i == 0);
            y += c;
        }
        float span = gap + 2 * c + 0.2f;
        y = put(0, y, 0, span, 0.14f, 0.4f, rnd.nextBoolean());
        int n = 4;
        for (int r = 0; r < 3 && n > 0; r++, n--) {
            for (int i = 0; i < n; i++) put((i - (n - 1) / 2f) * 0.36f, y, 0, 0.34f, 0.24f, 0.34f, false);
            y += 0.24f;
        }
    }
}
