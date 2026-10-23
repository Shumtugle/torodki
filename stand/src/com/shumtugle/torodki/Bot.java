package com.shumtugle.torodki;

import java.util.ArrayList;

/**
 * A player without hands: for each level it tries throws at every piece it can see,
 * keeps the one that knocks the most weight off the plinth, and goes on until the
 * level is cleared or the stars run out. The physics is deterministic, so a position
 * is reached again by replaying the throws that led to it.
 *
 *   java ... Bot [first level] [last level] [aim jitter in pixels]
 */
public final class Bot implements World.Host {
    public void buzz(int ms) { }
    public void quit() { }
    public void sound(int id, float gain, float rate) { }
    public void saveLevel(int level) { }
    public int loadLevel() { return 0; }
    public byte[] asset(String name) { return null; }
    public void saveGifts(int heavy) { }
    public int loadGifts() { return 0; }
    public void saveFails(int fails) { }
    public int loadFails() { return 0; }
    public void saveInt(String key, int value) { }
    public int loadInt(String key) { return 0; }
    public void saveGoldSeen(boolean seen) { }
    public boolean loadGoldSeen() { return false; }

    static final float DT = 1f / 60f;
    /** A casual player throws at a random piece instead of the best one. */
    static boolean casual;
    static final float[] SPEEDS = {13.5f, 9f};

    public static void main(String[] args) {
        int a = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int b = args.length > 1 ? Integer.parseInt(args[1]) : 13;
        float jitter = args.length > 2 ? Float.parseFloat(args[2]) : 0f;
        casual = args.length > 3 && args[3].equals("casual");
        int wins = 0;
        for (int lv = a; lv <= b; lv++) {
            String r = play(lv, jitter);
            System.out.println(r);
            if (!r.contains("FAIL")) wins++;
        }
        System.out.println("cleared " + wins + " of " + (b - a + 1));
    }

    private static boolean won(World w, int lv) {
        return w.level != lv || w.cleared;
    }

    private static World fresh(int lv) {
        World w = new World(new Bot());
        w.resize(640);
        w.startLevel(lv, false);
        return w;
    }

    /** Plays a throw list from the start; returns the world after things settle. */
    private static World replay(int lv, ArrayList<float[]> throwsList) {
        World w = fresh(lv);
        for (float[] t : throwsList) {
            settle(w);
            w.throwAt(t[0], t[1], t[2]);
            run(w, 0.4f);
        }
        settle(w);
        return w;
    }

    private static void run(World w, float sec) {
        for (int i = 0; i < Math.round(sec / DT); i++) w.update(DT);
    }

    private static void settle(World w) {
        for (int i = 0; i < 600; i++) {
            if (w.hs == World.HAND && (w.phys.settled() || i > 300) && i > 30) break;
            w.update(DT);
        }
    }

    private static String play(int lv, float jitter) {
        ArrayList<float[]> chosen = new ArrayList<float[]>();
        java.util.Random rnd = new java.util.Random(lv);
        StringBuilder log = new StringBuilder();
        World base = fresh(lv);
        log.append(String.format("level %2d %-7s %2d pieces:", lv + 1, Builder.KINDS[Builder.kindOf(lv)], base.pieces.size()));
        for (int k = 0; k < World.STARS; k++) {
            World now = replay(lv, chosen);
            if (won(now, lv)) {
                log.append("  WIN in ").append(k);
                return log.toString();
            }
            float best = -1f;
            float[] pick = null;
            ArrayList<float[]> cands = new ArrayList<float[]>();
            for (Body p : now.pieces) {
                if (p.gone || Math.abs(p.px) > Builder.PHX + 0.05f || p.py < Builder.TOP) continue;
                float[] s = now.screenOf(p.px, p.py, p.pz - p.hz);
                for (float sp : SPEEDS) cands.add(new float[]{s[0], s[1], sp});
            }
            java.util.Collections.shuffle(cands, rnd);
            int limit = Math.min(cands.size(), casual ? 1 : 36);
            for (int c = 0; c < limit; c++) {
                float[] t = cands.get(c);
                ArrayList<float[]> trial = new ArrayList<float[]>(chosen);
                float jx = (rnd.nextFloat() - 0.5f) * 2f * jitter, jy = (rnd.nextFloat() - 0.5f) * 2f * jitter;
                trial.add(new float[]{t[0] + jx, t[1] + jy, t[2]});
                World w = replay(lv, trial);
                float off = won(w, lv) ? 1f : 1f - w.onPlinth();
                if (off > best) {
                    best = off;
                    pick = trial.get(trial.size() - 1);
                }
            }
            if (pick == null) break;
            chosen.add(pick);
            log.append(String.format("  %.0f%%", best * 100));
        }
        World end = replay(lv, chosen);
        if (won(end, lv)) return log.append("  WIN in 5").toString();
        return log.append("  FAIL").toString();
    }
}
