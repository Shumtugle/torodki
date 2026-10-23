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
    static final float CZ = 11.5f;           // plinth center along the street
    static final float PHX = 1.42f * S, PHZ = 0.78f * S;

    // in the order they first appear: the first ones fall easily, the pyramid is the stubborn one
    static final String[] KINDS = {"TOWER", "SEESAW", "RACK", "WALL", "WELL", "GATE", "PRESS", "STEPS"};

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
        else if (k.equals("RACK")) rack(size);
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

    // ---- recipes ----

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
            if (ph > BOTTLE_H + 0.03f && rnd.nextInt(3) != 0) bottle(0, y, 0);
            y += ph;
            y = put(0, y, 0, w + 0.1f, 0.1f, w + 0.1f, rnd.nextFloat() < 0.5f);
            w *= 0.84f;
        }
        if (rnd.nextBoolean()) bottle(0, y, 0);
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
            if (rnd.nextInt(3) != 0) bottle(side * (len / 2 - 0.2f), yy, 0);
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
        bottle(0, y, 0);
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
        bottle(0, y, 0);
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
        if (h * c > BOTTLE_H + 0.05f) bottle(0, 0f, 0);
        float span = gap + 2 * c + 0.2f;
        y = put(0, y, 0, span, 0.14f, 0.4f, rnd.nextBoolean());
        int n = 4;
        for (int r = 0; r < 3 && n > 0; r++, n--) {
            for (int i = 0; i < n; i++) put((i - (n - 1) / 2f) * 0.36f, y, 0, 0.34f, 0.24f, 0.34f, false);
            y += 0.24f;
        }
    }
}
