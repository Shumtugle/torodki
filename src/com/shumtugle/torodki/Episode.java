package com.shumtugle.torodki;

/**
 * The game's levels gathered into episodes, by the numbers on the plate in the corner.
 * An episode opens with something new and ends where the game turns: the alley before the
 * 51st and the 91st, the portal after the 101st, the stones on the water after the 126th and
 * the 151st; the others end with a scene of their own. One table, read by the game and by
 * the bridge alike.
 */
final class Episode {
    static final int NEW = 0, ALLEY = 1, PORTAL = 2, SKIP = 3;
    static final int[] FIRST = {1, 10, 21, 40, 51, 66, 80, 91, 102, 120, 127, 140};
    static final int[] LAST = {9, 20, 39, 50, 65, 79, 90, 101, 119, 126, 139, 151};
    // how each ends: a scene still to come, or a turn the game already takes there
    static final int[] ENDS = {NEW, NEW, NEW, ALLEY, NEW, NEW, ALLEY, PORTAL, NEW, SKIP, NEW, SKIP};
    static final int COUNT = FIRST.length;

    private Episode() {
    }

    /** The episode a level belongs to, by its plate number; past the last, the last. */
    static int of(int plate) {
        for (int i = 0; i < COUNT; i++) if (plate <= LAST[i]) return i;
        return COUNT - 1;
    }

    /** Whether this plate is the last of its episode. */
    static boolean last(int plate) {
        return plate == LAST[of(plate)];
    }

    static boolean first(int plate) {
        return plate == FIRST[of(plate)];
    }
}
