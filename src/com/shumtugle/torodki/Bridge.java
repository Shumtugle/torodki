package com.shumtugle.torodki;

import java.util.Random;

/**
 * The dispatcher's bridge: a dark round room, the player in the middle, a ring of screens
 * round him, one for each episode, one for the crossroads of five cubes and one for each of
 * the two games that open after the last episode. A swipe turns the ring, with a run-on and
 * a spring that settles it on the nearest screen with a click. A tap throws the hammer at
 * what is under the finger.
 *
 * The ring is seen from inside: the rails it hangs on and the seams of the wall behind it
 * bow away above and below toward the sides, and the outer edge of a screen turned away is
 * taller than its inner edge. The wall turns slower than the screens. A caged lamp over the
 * middle lights the screen in front; the more slots hold a cube, the warmer the room.
 *
 * Screens take after their world: wooden color sets for the street, round green radar
 * scopes for the sea, black and white monitors for the crossroads and the games. A screen
 * whose episode has been reached shows a still of a level being played; the episode being
 * played shivers like a tube on air; those still ahead are dead grey glass and answer a
 * throw with snow and the busy tone. On the plate under each: the levels, the best stars,
 * the cubes won there.
 *
 * Under each screen a slot: a golden cube set into it opens the screen for good, and from
 * then on a throw at it goes to the episode's first level, as often as the player likes. The
 * first episode's slot holds a cube from the start. A cube goes in once and never comes out.
 *
 * On the console: the alley, the golem and the stones on the water, each a black and white
 * monitor that answers only where the game is (the first two on the street, the last at
 * sea); a dial that shows the whole ring from above; the HOME lever, back to the level being
 * played, whose number glows in the tubes beside it. Leaving, the room goes out like a set
 * switched off.
 *
 * Pure Java; the world runs what is chosen.
 */
final class Bridge {
    static final int SCREENS = Episode.COUNT + 3, HUB = Episode.COUNT, GAME_A = Episode.COUNT + 1, GAME_B = Episode.COUNT + 2;
    static final int ALLEY = 0, GOLEM = 1, SKIP = 2, HOME = 3;
    private static final int AT_SCREEN = 0, AT_SLOT = 1, AT_CONSOLE = 2;
    private static final int SET = 0, SCOPE = 1, MONITOR = 2;
    static final float OPEN_T = 0.3f, THROW_T = 0.42f, INSERT_T = 0.55f;
    static final float LEVER_T = 0.12f, OFF_T = 0.42f, TV_ON_T = 0.3f;
    private static final float CHARGE_T = 0.45f, TEAR_T = 0.1f;

    // the ring: its radius on screen, the turn between screens (shown wider than it is) and
    // the half-width of one screen, both as angles
    private static final float RADIUS = 236f, STEP = (float) (Math.PI * 2 / SCREENS) * 1.6f, HALF = 0.33f;
    // how much taller things grow toward the sides, the bow of the drum seen from inside
    private static final float BOW = 0.17f / (137f * 137f);
    // a screen is drawn flat into a sprite first: rails' hangers above, the case, the slot below
    private static final int SW = 154, CH = 118, TOP = 18, SLOT_GAP = 9, SLOT_W = 34, SLOT_H = 14;
    private static final int SH = TOP + CH + SLOT_GAP + SLOT_H + 20;
    private static final int CONSOLE = 188, CEIL = 34;

    private final World w;
    private final World.Host host;
    private final Random rnd = new Random(0xB21D6EL);
    float t;                                  // seconds since it opened
    float pos, vel;                           // the ring's turn, in screens, and its run-on
    private boolean down, dragged, springing, settled = true;
    private int target;
    private float downX, lastX, lastT;
    private int aimKind = -1, aimWhich;       // the hammer in flight, at what
    private float aimT, aimX, aimY;
    private int inserting = -1;               // a cube on its way into a slot
    private float insertT;
    private final float[] flash = new float[SCREENS], snow = new float[SCREENS], charge = new float[SCREENS],
            slotFlash = new float[SCREENS], tear = new float[SCREENS];
    private final int[] tearDx = new int[4];
    private int thudAt = -1;
    private float thudT, shakeT = 9f, humT;
    private int leaving = -1;                 // what was chosen on the console, while the room goes out
    private float leaveT;
    // what the screens know, read once as the room opens
    private final boolean[] slot = new boolean[SCREENS];
    private final int[] stars = new int[SCREENS], won = new int[SCREENS];
    private final boolean alleyDone, finale;
    // where each screen was drawn this frame, for touches
    private final float[] xl = new float[SCREENS], xr = new float[SCREENS], ang = new float[SCREENS];
    private final boolean[] shown = new boolean[SCREENS];
    private final int[] order = new int[SCREENS];
    private final float[] depth = new float[SCREENS];
    private final Px spr = new Px(SW, SH);
    private float[] poolY;
    private final int[][] cases = new int[SCREENS][];

    // stills of a level being played, one for each episode
    private static int[][] stills;
    private static int stillW, stillH;

    Bridge(World w, World.Host host) {
        this.w = w;
        this.host = host;
        pos = current();
        target = current();
        for (int i = 0; i < SCREENS; i++) {
            slot[i] = i == 0 || host.loadInt("slot" + i) == 1;
            if (i < Episode.COUNT) {
                stars[i] = host.loadInt("ep" + i + "stars");
                won[i] = host.loadInt("ep" + i + "cubes");
            }
        }
        alleyDone = host.loadInt("alley") > 0;
        finale = host.loadInt("finale") == 1;
        if (stills == null) readStills(host.asset("screens.bin"));
    }

    /** count, width, height, then for each still a palette of 256 colors and one byte a pixel. */
    private static void readStills(byte[] b) {
        if (b == null || b.length < 5) return;
        int n = b[0] & 0xFF, sw = ((b[1] & 0xFF) << 8) | (b[2] & 0xFF), sh = ((b[3] & 0xFF) << 8) | (b[4] & 0xFF);
        if (b.length < 5 + n * (768 + sw * sh)) return;
        int[][] s = new int[n][sw * sh];
        int p = 5;
        int[] pal = new int[256];
        for (int k = 0; k < n; k++) {
            for (int c = 0; c < 256; c++, p += 3)
                pal[c] = 0xFF000000 | ((b[p] & 0xFF) << 16) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
            for (int q = 0; q < sw * sh; q++) s[k][q] = pal[b[p++] & 0xFF];
        }
        stillW = sw;
        stillH = sh;
        stills = s;
        // for the scopes: brightness against its own neighbourhood, so the outlines of the
        // iceberg and the building come back like echoes and the flat sky and sea go dark
        echoes = new float[n][];
        for (int k = Episode.SEA; k < n; k++) {
            float[] l = new float[sw * sh], e = new float[sw * sh];
            for (int q = 0; q < sw * sh; q++) {
                int c = s[k][q];
                l[q] = (0.3f * ((c >> 16) & 0xFF) + 0.59f * ((c >> 8) & 0xFF) + 0.11f * (c & 0xFF)) / 255f;
            }
            for (int y = 0; y < sh; y++)
                for (int x = 0; x < sw; x++) {
                    float sum = 0f;
                    int cnt = 0;
                    for (int dy = -4; dy <= 4; dy++)
                        for (int dx = -4; dx <= 4; dx++) {
                            int xx = x + dx, yy = y + dy;
                            if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue;
                            sum += l[yy * sw + xx];
                            cnt++;
                        }
                    float d = l[y * sw + x] - sum / cnt;
                    e[y * sw + x] = Math.max(0f, Math.min(1f, 0.18f + Math.abs(d) * 4.5f + Math.max(0f, l[y * sw + x] - 0.72f) * 1.5f));
                }
            echoes[k] = e;
        }
    }

    private static float[][] echoes;

    // ---- what the screens know ----

    /** The episode being played. */
    int current() {
        return Episode.of(w.level + 1);
    }

    boolean reached(int i) {
        if (i < Episode.COUNT) return Episode.FIRST[i] <= w.best();
        if (i == HUB) return alleyDone;
        return finale;
    }

    boolean open(int i) {
        return slot[i];
    }

    private static int style(int i) {
        if (i >= Episode.COUNT) return MONITOR;
        return i < Episode.SEA ? SET : SCOPE;
    }

    /** Whether a console monitor answers where the game is now. */
    boolean answers(int which) {
        if (which == HOME) return true;
        return which == SKIP ? w.atSea : !w.atSea;
    }

    private int openCount() {
        int n = 0;
        for (int i = 0; i < SCREENS; i++) if (slot[i]) n++;
        return n;
    }

    // ---- time ----

    void update(float dt) {
        t += dt;
        if ((humT -= dt) <= 0f) {
            host.sound(Synth.HUM, 0.45f + 0.3f * openCount() / SCREENS, 1f);
            humT += Synth.HUM_EVERY;
        }
        if (!down) turn(dt);
        for (int i = 0; i < SCREENS; i++) {
            flash[i] = Math.max(0f, flash[i] - dt * 1.6f);
            snow[i] = Math.max(0f, snow[i] - dt * 1.2f);
            slotFlash[i] = Math.max(0f, slotFlash[i] - dt * 2.5f);
            tear[i] = Math.max(0f, tear[i] - dt);
            if (charge[i] > 0f) charge[i] += dt;
            if (charge[i] > CHARGE_T) charge[i] = 0f;
        }
        shakeT += dt;
        if (thudAt >= 0 && (thudT += dt) > 0.4f) thudAt = -1;
        if (aimKind >= 0) {
            aimT += dt;
            if (aimT >= THROW_T) {
                int k = aimKind, which = aimWhich;
                aimKind = -1;
                arrive(k, which);
            }
        }
        if (inserting >= 0) {
            insertT += dt;
            if (insertT >= INSERT_T) {
                // the contactor closes: the room jolts, the slot flares, the tube folds to a
                // line and opens again brighter, the lamp on the case goes green
                int i = inserting;
                host.saveInt("slot" + i, 1);
                slot[i] = true;
                slotFlash[i] = 1f;
                charge[i] = 1e-4f;
                shakeT = 0f;
                host.sound(Synth.LATCH, 1f, 1f);
                host.sound(Synth.CLANG, 0.5f, 0.6f);
                host.sound(Synth.CATCH, 0.7f, 1.3f);
                host.buzz(40);
                inserting = -1;
            }
        }
        if (leaving >= 0 && (leaveT += dt) >= LEVER_T + OFF_T) {
            int k = leaving;
            leaving = -1;
            w.bridgeAct(k);
        }
    }

    /** The ring left to itself: it runs on, then a spring pulls it onto the nearest screen. */
    private void turn(float dt) {
        if (!springing) {
            pos += vel * dt;
            vel *= (float) Math.exp(-dt * 5f);
            if (Math.abs(vel) < 1.5f) {
                springing = true;
                target = Math.round(pos);
                if (!settled) host.sound(Synth.TOCK, 0.35f, 1.8f);
            }
            return;
        }
        // an underdamped spring: it goes a little past and comes back, a detent felt in the hand
        for (int k = 0; k < 4; k++) {
            float h = dt / 4f;
            float acc = -140f * (pos - target) - 9.9f * vel;
            vel += acc * h;
            pos += vel * h;
        }
        if (!settled && Math.abs(pos - target) < 0.003f && Math.abs(vel) < 0.03f) {
            pos = target;
            vel = 0f;
            settled = true;
            // the picture tears for a moment as the screen comes to rest in front
            int i = ((target % SCREENS) + SCREENS) % SCREENS;
            tear[i] = TEAR_T;
            for (int b = 0; b < 4; b++) tearDx[b] = rnd.nextInt(13) - 6;
        }
    }

    /** The hammer has reached what it was thrown at. */
    private void arrive(int kind, int which) {
        if (kind == AT_CONSOLE) {
            if (!answers(which)) {
                thud(-1);
                return;
            }
            host.sound(Synth.CLANG, 0.6f, 1.2f);
            leaving = which;
            leaveT = which == HOME ? 0f : LEVER_T;
            if (which == HOME) host.sound(Synth.LATCH, 0.8f, 0.7f);
            host.sound(Synth.TV_OFF, 0.9f, 1f);
            return;
        }
        if (!reached(which)) {
            // ahead: snow and the busy tone
            snow[which] = 1f;
            host.sound(Synth.BUSY, 0.7f, 1f);
            host.buzz(20);
            return;
        }
        if (!open(which)) {
            if (w.cubes > 0 && inserting < 0) {
                // a cube from the rack flies into the slot and clicks home
                w.spendCube();
                inserting = which;
                insertT = 0f;
                host.sound(Synth.WHOOSH, 0.5f, 1.5f);
            } else thud(which);
            return;
        }
        if (which == GAME_A || which == GAME_B) {
            // the games are not wired in yet: the screen only snows
            snow[which] = 1f;
            host.sound(Synth.BUSY, 0.7f, 1f);
            return;
        }
        flash[which] = 1f;
        host.sound(Synth.CLANG, 0.7f, 1f);
        host.buzz(30);
        w.bridgeEnter(which);
    }

    private void thud(int which) {
        thudAt = which;
        thudT = 0f;
        host.sound(Synth.TOCK, 0.9f, 0.45f);
        host.buzz(15);
    }

    // ---- touch ----

    void touch(int type, float x, float y) {
        if (t < OPEN_T || leaving >= 0) return;
        if (type == World.DOWN) {
            down = true;
            dragged = false;
            downX = lastX = x;
            lastT = t;
            return;
        }
        if (!down) return;
        if (type == World.MOVE) {
            if (Math.abs(x - downX) > 6f && !dragged) {
                dragged = true;
                springing = false;
                settled = false;
                vel = 0f;
            }
            if (dragged) {
                float d = (x - lastX) / 70f;
                pos -= d;
                float dt = Math.max(1e-3f, t - lastT);
                vel = vel * 0.5f + (-d / dt) * 0.5f;
            }
            lastX = x;
            lastT = t;
            return;
        }
        // up: the ring runs on, but not far
        down = false;
        if (dragged) {
            vel = Math.max(-6f, Math.min(6f, vel * 0.6f));
            return;
        }
        if (aimKind >= 0) return;
        int[] hit = hit(x, y);
        if (hit == null) return;
        aimKind = hit[0];
        aimWhich = hit[1];
        aimT = 0f;
        aimX = x;
        aimY = y;
        host.sound(Synth.WHOOSH, 0.6f, 1.2f);
    }

    /** How much taller things are drawn at this column: the bow of the drum. */
    private static float bow(float x) {
        float d = x - World.W * 0.5f;
        return 1f + BOW * d * d;
    }

    private float middle() {
        return w.h * 0.4f;
    }

    /** What is under the finger: the console, then a slot or a screen, front ones first. */
    private int[] hit(float x, float y) {
        int h = w.h;
        for (int k = 0; k < 4; k++) {
            float[] r = consoleRect(k, h);
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return new int[]{AT_CONSOLE, k};
        }
        float r = (y - middle()) / bow(x) + CH * 0.5f;
        for (int o = SCREENS - 1; o >= 0; o--) {
            int i = order[o];
            if (!shown[i] || x < xl[i] || x >= xr[i]) continue;
            if (r >= -4f && r < CH) return new int[]{AT_SCREEN, i};
            if (r >= CH + SLOT_GAP - 7 && r < CH + SLOT_GAP + SLOT_H + 9) return new int[]{AT_SLOT, i};
        }
        return null;
    }

    /** A console item's box: the three monitors in a row, the lever under the middle one. */
    static float[] consoleRect(int k, int h) {
        int W = World.W;
        if (k == HOME) return new float[]{W * 0.5f - 46, h - 92, W * 0.5f + 46, h - 34};
        float cx = W * (0.2f + 0.3f * k);
        return new float[]{cx - 36, h - 172, cx + 36, h - 116};
    }

    // ---- drawing ----

    void draw(Px f, float alpha) {
        int W = World.W, h = w.h;
        int[] keep = alpha < 1f ? f.a.clone() : null;
        float cy = middle();
        float warm = openCount() / (float) SCREENS;
        room(f, h, cy, warm);
        // the ring: the angle of each screen off the middle
        for (int i = 0; i < SCREENS; i++) {
            float d = i - pos;
            d -= SCREENS * (float) Math.floor(d / SCREENS + 0.5f);
            float a = d * STEP;
            ang[i] = a;
            depth[i] = (float) Math.cos(a);
            order[i] = i;
            shown[i] = a - HALF < 0.62f && a + HALF > -0.62f;
            xl[i] = W * 0.5f + RADIUS * (float) Math.sin(Math.max(-1.5f, a - HALF));
            xr[i] = W * 0.5f + RADIUS * (float) Math.sin(Math.min(1.5f, a + HALF));
        }
        // back to front
        for (int a = 1; a < SCREENS; a++)
            for (int b = a; b > 0 && depth[order[b - 1]] > depth[order[b]]; b--) {
                int tmpI = order[b];
                order[b] = order[b - 1];
                order[b - 1] = tmpI;
            }
        for (int o = 0; o < SCREENS; o++) {
            int i = order[o];
            if (!shown[i]) continue;
            paintScreen(i, ang[i] / STEP, warm);
            place(f, i, cy);
        }
        lamp(f, cy);
        console(f, h);
        rack(f);
        // the cube on its way into a slot, growing as it comes near
        if (inserting >= 0) {
            float u = Math.min(1f, insertT / INSERT_T), e = u * u * (3f - 2f * u);
            int i = inserting;
            float xc = slotX(i), yc = slotY(i, cy);
            float x0 = 14 + w.cubes * 11, y0 = 16;
            float x = x0 + (xc - x0) * e, y = y0 + (yc - y0) * e - (float) Math.sin(u * Math.PI) * 44f;
            int s = Math.round(9 + 3 * e);
            cube(f, Math.round(x - s * 0.5f), Math.round(y - s * 0.5f), s);
        }
        // the hammer in flight to what was tapped: from the hand at the lower right, turning
        // end over end, smaller as it goes away
        if (aimKind >= 0) {
            float u = Math.min(1f, aimT / THROW_T), e = u * (2f - u);
            float x0 = W - 20, y0 = h - 40;
            float x = x0 + (aimX - x0) * e, y = y0 + (aimY - y0) * e - (float) Math.sin(u * Math.PI) * 46f;
            hammer(f, x, y, 2.1f + 5.2f * u, 260f - 205f * e);
        }
        // the jolt of a cube going home: down three pixels and back
        if (shakeT < 0.2f) {
            int dy = Math.round(3f * (float) Math.sin(shakeT / 0.2f * Math.PI * 2) * (1f - shakeT / 0.2f));
            shift(f, h, dy);
        }
        if (leaving >= 0) {
            float u = Math.max(0f, leaveT - LEVER_T) / OFF_T;
            switchOff(f, h, u);
        }
        if (keep != null)
            for (int k = 0; k < keep.length; k++) f.a[k] = Backdrop.mix(keep[k], f.a[k], alpha);
    }

    private float slotX(int i) {
        float a = ang[i];
        return World.W * 0.5f + RADIUS * (float) Math.sin(a);
    }

    private float slotY(int i, float cy) {
        return cy + (CH * 0.5f + SLOT_GAP + SLOT_H * 0.5f) * bow(slotX(i));
    }

    private void shift(Px f, int h, int dy) {
        if (dy == 0) return;
        int W = World.W;
        if (dy > 0) {
            System.arraycopy(f.a, 0, f.a, dy * W, (h - dy) * W);
            java.util.Arrays.fill(f.a, 0, dy * W, 0xFF07090B);
        } else {
            System.arraycopy(f.a, -dy * W, f.a, 0, (h + dy) * W);
            java.util.Arrays.fill(f.a, (h + dy) * W, h * W, 0xFF07090B);
        }
    }

    /**
     * A picture tube going out: the picture folds to a bright line, the line to a point, the
     * point fades. u runs 0 to 1. Also used, backwards, as the game's picture comes on.
     */
    static void switchOff(Px f, int h, float u) {
        int W = World.W;
        int[] src = f.a.clone();
        float cy = h * 0.5f;
        if (u < 0.6f) {
            float k = Math.max(0.012f, 1f - u / 0.6f);
            k = k * k;
            float glow = (1f - k) * 0.85f;
            for (int y = 0; y < h; y++) {
                int sy = Math.round(cy + (y - cy) / Math.max(0.012f, k));
                for (int x = 0; x < W; x++) {
                    int c = sy >= 0 && sy < h ? src[sy * W + x] : 0xFF000000;
                    f.a[y * W + x] = sy >= 0 && sy < h ? Backdrop.mix(c, 0xFFF4F8FF, glow) : 0xFF000000;
                }
            }
            return;
        }
        java.util.Arrays.fill(f.a, 0, W * h, 0xFF000000);
        float v = (u - 0.6f) / 0.4f;
        float half = W * 0.5f * (1f - v) * (1f - v);
        int y0 = Math.round(cy);
        for (int x = Math.round(W * 0.5f - half); x < Math.round(W * 0.5f + half); x++)
            if (x >= 0 && x < W) f.a[y0 * W + x] = 0xFFF4F8FF;
        float r = 2.5f * (1f - v * 0.6f);
        int dot = Backdrop.mix(0xFFFFFFFF, 0xFF000000, v * v);
        f.disc(W * 0.5f, cy, r, dot);
    }

    /** The game's picture coming on: a line that opens to the whole frame. u runs 0 to 1. */
    static void switchOn(Px f, int h, float u) {
        int W = World.W;
        int[] src = f.a.clone();
        float cy = h * 0.5f, k = Math.max(0.012f, u * u * (3f - 2f * u));
        float glow = (1f - k) * 0.7f;
        for (int y = 0; y < h; y++) {
            int sy = Math.round(cy + (y - cy) / k);
            for (int x = 0; x < W; x++)
                f.a[y * W + x] = sy >= 0 && sy < h ? Backdrop.mix(src[sy * W + x], 0xFFF4F8FF, glow) : 0xFF000000;
        }
    }

    // ---- the room ----

    /**
     * The visor overhead, the riveted wall going round behind the screens (turning at a
     * quarter of their pace), cable runs, the two rails the ring hangs on, the floor.
     */
    private void room(Px f, int h, float cy, float warm) {
        int W = World.W;
        int bottom = h - CONSOLE;
        float shiftU = pos * 37f;
        float railTop = cy - CH * 0.5f - 12f, railLow = cy + CH * 0.5f + SLOT_GAP + SLOT_H + 13f;
        float floorY = railLow + 10f;
        int heat = 0xFF2A2119;
        int coldA = Backdrop.mix(0xFF10141A, heat, warm * 0.55f), coldB = Backdrop.mix(0xFF13181F, heat, warm * 0.55f);
        int seamC = Backdrop.mix(0xFF080A0D, heat, warm * 0.55f), rivetC = Backdrop.mix(0xFF2A3038, heat, warm * 0.55f);
        int ribA = Backdrop.mix(0xFF0A0C0F, heat, warm * 0.55f), ribB = Backdrop.mix(0xFF1A1E24, heat, warm * 0.55f);
        if (poolY == null || poolY.length != h) poolY = new float[h];
        for (int y = 0; y < h; y++) {
            float ly = (y - cy) / 170f;
            poolY[y] = (float) Math.exp(-ly * ly);
        }
        for (int x = 0; x < W; x++) {
            float s = bow(x + 0.5f), inv = 1f / s;
            float u = (x - W * 0.5f) + shiftU;
            int col = (int) Math.floor(u / 42f);
            float m = u - col * 42f;
            boolean seam = m < 1f, studs = m > 3f && m < 5f || m > 37f && m < 39f;
            boolean cable = ((col % 4) + 4) % 4 == 2 && m > 14f && m < 25f;
            float rib = cable ? 0.75f + 0.6f * (float) Math.sin((m - 14f) / 11f * Math.PI) : 1f;
            int ribLo = Backdrop.scale(ribA, rib), ribHi = Backdrop.scale(ribB, rib);
            int panel = (col & 1) == 0 ? coldA : coldB;
            boolean grate = ((int) (u / 6f) & 1) == 0;
            float lx = (x - W * 0.5f) / 78f, poolX = 0.75f * (float) Math.exp(-lx * lx);
            for (int y = CEIL; y < bottom; y++) {
                float yy = cy + (y - cy) * inv;          // the wall's own height, unbent
                int c;
                if (yy > floorY) {
                    // the floor: a dark grate in rings round the middle
                    float q = (yy - floorY) % 15f;
                    c = q < 1.2f ? 0xFF181D24 : grate ? 0xFF0E1116 : 0xFF0C0F13;
                } else if (cable) {
                    // a thick cable in a ribbed sleeve, running up into the visor
                    c = (((int) (yy + 400f)) & 3) == 0 ? ribLo : ribHi;
                } else {
                    float hm = yy - (cy - 104f), hm2 = yy - (cy + 42f);
                    if (seam || (hm < 0.8f && hm > -0.8f) || (hm2 < 0.8f && hm2 > -0.8f)) c = seamC;
                    else if (studs && (((int) (yy + 400f)) % 13 == 6)) c = rivetC;
                    else c = panel;
                }
                // the lamp's pool of light
                int k = (int) ((0.7f + poolX * poolY[y]) * 256f);
                int rr = Math.min(255, (((c >> 16) & 0xFF) * k) >> 8), gg = Math.min(255, (((c >> 8) & 0xFF) * k) >> 8), bb = Math.min(255, ((c & 0xFF) * k) >> 8);
                f.a[y * W + x] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
            // the rails, pipes bent with the wall
            pipe(f, x, cy + (railTop - cy) * s, s, warm);
            pipe(f, x, cy + (railLow - cy) * s, s, warm);
        }
        // the visor overhead
        for (int y = 0; y < CEIL; y++) {
            int c = y < CEIL - 3 ? Backdrop.mix(0xFF15191E, 0xFF1C2127, y / (float) CEIL) : y == CEIL - 3 ? 0xFF3A424B : 0xFF07090B;
            for (int x = 0; x < W; x++) f.a[y * W + x] = c;
        }
        for (int x = 8; x < W; x += 22) f.rect(x, CEIL - 8, x + 2, CEIL - 6, 0xFF323941);
    }

    /** One column of a steel pipe, about six pixels thick at the middle, with its shine. */
    private static void pipe(Px f, int x, float yc, float s, float warm) {
        float r = 3.2f * s;
        int y0 = (int) Math.floor(yc - r), y1 = (int) Math.ceil(yc + r);
        for (int y = y0; y <= y1; y++) {
            float k = (y - yc) / r;
            if (k < -1f || k > 1f) continue;
            float lit = 0.35f + 0.65f * (float) Math.sqrt(1f - k * k) - 0.25f * k;
            int c = Backdrop.scale(Backdrop.mix(0xFF4A535D, 0xFF6A5A44, warm * 0.5f), lit);
            if (k > -0.62f && k < -0.38f) c = Backdrop.mix(c, 0xFFB8C2CA, 0.45f);
            f.set(x, y, c);
        }
    }

    /** The lamp in its cage over the middle, and the cone of warm light it throws down. */
    private void lamp(Px f, float cy) {
        int W = World.W;
        float lx = W * 0.5f, ly = 54f;
        float yEnd = cy - CH * 0.5f;
        // the cone, faint in the dark air
        for (int y = (int) ly + 6; y < (int) yEnd; y++) {
            float k = (y - ly) / (yEnd - ly);
            float half = 7f + 74f * k;
            for (int x = (int) (lx - half); x <= (int) (lx + half); x++) {
                if (x < 0 || x >= W) continue;
                float e = Math.abs(x - lx) / half;
                float a = 0.11f * (1f - e * e) * (0.5f + 0.5f * k);
                int c = f.a[y * W + x];
                f.a[y * W + x] = Backdrop.mix(c, 0xFFFFD27A, a);
            }
        }
        // the cord, the halo, the bulb, the cage
        f.rect(Math.round(lx), CEIL, Math.round(lx) + 1, (int) ly - 8, 0xFF2A2E33);
        for (int dy = -16; dy <= 16; dy++)
            for (int dx = -16; dx <= 16; dx++) {
                float d = (float) Math.sqrt(dx * dx + dy * dy) / 16f;
                if (d >= 1f) continue;
                int x = Math.round(lx) + dx, y = Math.round(ly) + dy;
                if (x < 0 || x >= W || y < 0) continue;
                f.a[y * W + x] = Backdrop.mix(f.a[y * W + x], 0xFFFFD890, 0.35f * (1f - d) * (1f - d));
            }
        f.disc(lx, ly, 5.5f, 0xFFFFE2A0);
        f.disc(lx - 1.5f, ly - 1.5f, 2f, 0xFFFFFAE6);
        int cage = 0xFF34383C;
        f.rect(Math.round(lx) - 6, (int) ly - 8, Math.round(lx) + 7, (int) ly - 6, cage);
        f.rect(Math.round(lx) - 7, (int) ly + 6, Math.round(lx) + 8, (int) ly + 8, cage);
        for (int k = -6; k <= 6; k += 4) f.rect(Math.round(lx) + k, (int) ly - 7, Math.round(lx) + k + 1, (int) ly + 7, cage);
        f.rect(Math.round(lx) - 7, (int) ly - 1, Math.round(lx) + 8, (int) ly, cage);
    }

    // ---- one screen, flat, into the sprite ----

    private void paintScreen(int i, float d, float warm) {
        int st = style(i);
        // the case does not change while the room is open: painted once, then copied
        if (cases[i] == null) {
            java.util.Arrays.fill(spr.a, 0);
            hangers(i, warm);
            if (st == SET) setCase(i);
            else if (st == SCOPE) scopeCase(i);
            else monitorCase(i);
            cases[i] = spr.a.clone();
        } else System.arraycopy(cases[i], 0, spr.a, 0, spr.a.length);
        if (st == SET) led(i, SW - 18, CH - 14);
        else if (st == SCOPE) led(i, SW / 2 + 66, 66);
        else led(i, SW - 14, CH - 9);
        glass(i, st, d);
        slotPaint(i);
    }

    /** The clamps that hold the case on the rails above and below. */
    private void hangers(int i, float warm) {
        int c = Backdrop.mix(0xFF2E353D, 0xFF4A3E30, warm * 0.4f), hi = 0xFF4E5862;
        for (int x0 : new int[]{22, SW - 28}) {
            spr.rect(x0, 2, x0 + 6, TOP + 2, c);
            spr.rect(x0, 2, x0 + 1, TOP + 2, hi);
            spr.rect(x0 - 2, 4, x0 + 8, 8, c);
            int lowY = TOP + CH;
            spr.rect(x0, lowY, x0 + 6, SH - 4, c);
            spr.rect(x0 - 2, SH - 10, x0 + 8, SH - 6, c);
        }
    }

    /** A wooden color set: a rounded tube behind a bezel, two channel drums, a grille. */
    private void setCase(int i) {
        Random r = new Random(31L * i + 5);
        for (int y = 0; y < CH; y++)
            for (int x = 0; x < SW; x++) {
                if (!inRound(x, y, SW, CH, 7)) continue;
                float g = 0.9f + 0.08f * (float) Math.sin(y * 0.7f + 3f * (float) Math.sin(x * 0.045f + i)) + 0.04f * (float) Math.sin(y * 2.9f + x * 0.02f);
                int c = Backdrop.scale(0xFF6E4428, g);
                if (x < 2 || y < 2 || x >= SW - 2 || y >= CH - 2) c = 0xFF2E1A0E;
                else if (y < 4) c = 0xFF8C5A36;
                spr.a[(TOP + y) * SW + x] = c;
            }
        // the bezel round the tube
        roundRect(5, 5, SW - 35, CH - 15, 12, 0xFF15110D);
        // the side panel: two drums, a grille, the lamp
        int px = SW - 31;
        rect(px, 6, SW - 6, CH - 6, 0xFF3A2616);
        rect(px, 6, px + 1, CH - 6, 0xFF24160C);
        knob(SW - 18, 24, 8, r.nextFloat() * 6f);
        knob(SW - 18, 50, 7, r.nextFloat() * 6f);
        for (int y = 66; y < CH - 22; y += 4) rect(px + 4, y, SW - 10, y + 2, 0xFF1C120A);
        plate(i, 9, CH - 13, SW - 39, 0xFF9C7E44, 0xFF2A1E10, 0xFFB79A5C);
    }

    /** A radar scope: a square steel housing, four bolts, a round green tube. */
    private void scopeCase(int i) {
        for (int y = 0; y < CH; y++)
            for (int x = 0; x < SW; x++) {
                int c = 0xFF46525C;
                if (x < 2 || y < 2 || x >= SW - 2 || y >= CH - 2) c = 0xFF1A2026;
                else if (y < 4) c = 0xFF66727C;
                spr.a[(TOP + y) * SW + x] = c;
            }
        int cx = SW / 2, cyy = 54;
        rect(cx - 52, 3, cx + 52, 107, 0xFF52606A);
        rect(cx - 52, 3, cx + 52, 4, 0xFF7A8690);
        rect(cx - 52, 106, cx + 52, 107, 0xFF2A3238);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2) bolt(cx + sx * 45, cyy + sy * 45);
        // vents on the left, a drum and the lamp on the right
        for (int y = 16; y < 96; y += 6) rect(8, y, cx - 58, y + 2, 0xFF262E34);
        knob(cx + 66, 34, 7, i * 1.3f);
        // the tube's rim
        for (int y = -51; y <= 51; y++)
            for (int x = -51; x <= 51; x++) {
                float d = (float) Math.sqrt(x * x + y * y);
                if (d <= 51f && d > 45.5f) set(cx + x, cyy + y, d > 49f ? 0xFF2A3238 : 0xFF0C1012);
            }
        plate(i, cx - 40, CH - 11, cx + 40, 0xFF0A100C, 0xFF7CE89A, 0xFF1E2A22);
    }

    /** A black and white monitor: a square grey box, a flat tube, a lamp. */
    private void monitorCase(int i) {
        for (int y = 0; y < CH; y++)
            for (int x = 0; x < SW; x++) {
                int c = 0xFF2C3034;
                if (x < 2 || y < 2 || x >= SW - 2 || y >= CH - 2) c = 0xFF121416;
                else if (y < 4) c = 0xFF464C52;
                spr.a[(TOP + y) * SW + x] = c;
            }
        rect(7, 6, SW - 7, CH - 16, 0xFF0E1012);
        plate(i, 10, CH - 13, SW - 24, 0xFF0C0D0E, 0xFFE6ECEE, 0xFF26292C);
    }

    /** The tube's picture: a still, a radar's sweep, a test card, dead glass or snow. */
    private void glass(int i, int st, float d) {
        int gx0, gy0, gx1, gy1;
        if (st == SET) {
            gx0 = 8;
            gy0 = 8;
            gx1 = SW - 38;
            gy1 = CH - 18;
        } else if (st == SCOPE) {
            gx0 = SW / 2 - 46;
            gy0 = 54 - 46;
            gx1 = SW / 2 + 46;
            gy1 = 54 + 46;
        } else {
            gx0 = 9;
            gy0 = 8;
            gx1 = SW - 9;
            gy1 = CH - 18;
        }
        int gw = gx1 - gx0, gh = gy1 - gy0;
        boolean lit = reached(i) && !((i == GAME_A || i == GAME_B) && !finale);
        boolean cur = i < Episode.COUNT && i == current();
        float bright = !lit ? 0f : open(i) ? 1f : 0.72f;
        if (cur) bright *= 0.94f + 0.06f * (float) Math.sin(t * 47f) * (float) Math.sin(t * 3.1f);
        // the tube folding to a line and opening again as a cube goes in
        float fold = 1f, white = 0f;
        if (charge[i] > 0f) {
            float c = charge[i];
            if (c < 0.15f) {
                fold = Math.max(0.03f, 1f - c / 0.15f);
                white = c / 0.15f;
            } else {
                float k = Math.min(1f, (c - 0.15f) / 0.25f);
                fold = Math.max(0.03f, k);
                white = 1f - k;
            }
        }
        float roll = (t * 0.23f) % 1f;
        float glareAt = 0.32f - 0.45f * d;
        float sweep = (float) ((t * Math.PI * 2 / 4.0) % (Math.PI * 2));
        for (int y = gy0; y < gy1; y++) {
            float v = (y - gy0 + 0.5f) / gh;
            float fv = 0.5f + (v - 0.5f) / fold;
            int band = Math.min(3, (int) (v * 4f));
            int dx = tear[i] > 0f ? tearDx[band] : 0;
            for (int x = gx0; x < gx1; x++) {
                float u = (x - gx0 + 0.5f) / gw;
                float cu = u * 2f - 1f, cv = v * 2f - 1f;
                if (st == SCOPE && cu * cu + cv * cv > 1f) continue;
                if (st == SET && !inRound(x - gx0, y - gy0, gw, gh, 10)) continue;
                int c;
                if (fv < 0f || fv > 1f) c = 0xFF050607;
                else if (bright <= 0f) c = dead(st, u, v);
                else {
                    float su = u + dx / (float) gw;
                    if (st == SET) c = setPixel(i, su, fv, cur);
                    else if (st == SCOPE) c = scopePixel(i, su, fv, sweep);
                    else c = monitorPixel(i, su, fv);
                    c = Backdrop.scale(c, bright);
                    if (cur && Math.abs(v - roll) < 0.05f) c = Backdrop.scale(c, 1.12f);
                }
                if (fold < 1f && Math.abs(v - 0.5f) < 0.5f * fold) c = Backdrop.mix(c, 0xFFF6FAFF, white);
                // scan lines
                if ((y & 1) == 0) c = Backdrop.scale(c, st == MONITOR ? 0.8f : st == SET ? 0.88f : 0.93f);
                // the glass bulges: corners darker, a diagonal shine that slides against the turn
                float vig = 1f - 0.22f * (cu * cu * cv * cv) - 0.12f * (cu * cu + cv * cv) * (st == SCOPE ? 0.5f : 1f);
                c = Backdrop.scale(c, vig);
                float g = Math.abs((u + v * 0.6f) - glareAt - 0.3f);
                if (g < 0.09f) c = Backdrop.mix(c, 0xFFFFFFFF, g < 0.04f ? 0.1f : 0.05f);
                if (snow[i] > 0f && rnd.nextFloat() < snow[i] * 0.7f)
                    c = Backdrop.scale(0xFFC8D0D4, 0.3f + rnd.nextFloat() * 0.7f);
                if (flash[i] > 0f) c = Backdrop.mix(c, 0xFFFFFFF4, flash[i]);
                spr.a[(TOP + y) * SW + x] = c;
            }
        }
    }

    /** Dead glass: grey, a faint reflection of the room in its bulge. */
    private static int dead(int st, float u, float v) {
        float k = Math.max(0f, 1f - (float) Math.hypot(u - 0.3f, v - 0.25f) * 1.6f);
        int base = st == SCOPE ? 0xFF0B120E : 0xFF171B1E;
        return Backdrop.mix(base, 0xFF353C42, k * 0.5f);
    }

    private static int still(int e, float u, float v) {
        if (stills == null || e >= stills.length) return 0xFF5E7A6A;
        int x = Math.min(stillW - 1, Math.max(0, (int) (u * stillW))), y = Math.min(stillH - 1, Math.max(0, (int) (v * stillH)));
        return stills[e][y * stillW + x];
    }

    /** A color set: the still through a bulging tube, colors a little apart on the one on air. */
    private int setPixel(int i, float u, float v, boolean cur) {
        float cu = u * 2f - 1f, cv = v * 2f - 1f;
        float bu = 0.5f + 0.5f * cu * (0.94f + 0.06f * cv * cv), bv = 0.5f + 0.5f * cv * (0.94f + 0.06f * cu * cu);
        int c = still(i, bu, bv);
        if (cur) {
            int l = still(i, bu - 1.2f / 110f, bv), r = still(i, bu + 1.2f / 110f, bv);
            c = (c & 0xFF00FF00) | (l & 0x00FF0000) | (r & 0x000000FF);
        }
        // warm, rich phosphor
        int rr = Math.min(255, (int) (((c >> 16) & 0xFF) * 1.08f)), gg = (c >> 8) & 0xFF, bb = (int) ((c & 0xFF) * 0.92f);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    /** A radar scope: the still in green, rings and a cross, a beam going round once in four seconds. */
    private int scopePixel(int i, float u, float v, float sweep) {
        float lum = 0.3f;
        if (echoes != null && i < echoes.length && echoes[i] != null) {
            int x = Math.min(stillW - 1, Math.max(0, (int) ((0.125f + u * 0.75f) * stillW))), y = Math.min(stillH - 1, Math.max(0, (int) (v * stillH)));
            lum = echoes[i][y * stillW + x];
        }
        float cu = u * 2f - 1f, cv = v * 2f - 1f;
        float r = (float) Math.sqrt(cu * cu + cv * cv);
        // the beam and the afterglow it leaves behind
        float a = (float) Math.atan2(cv, cu);
        float behind = sweep - a;
        behind -= (float) (Math.PI * 2) * (float) Math.floor(behind / (Math.PI * 2));
        float trail = (float) Math.exp(-behind * 2.4f);
        float k = lum * (0.5f + 0.9f * trail);
        if (Math.abs(r - 0.33f) < 0.012f || Math.abs(r - 0.66f) < 0.012f || Math.abs(cu) < 0.012f || Math.abs(cv) < 0.012f)
            k += 0.12f;
        if (behind < 0.035f) k += 0.6f;
        k = Math.min(1.25f, k);
        int g = Math.min(255, (int) (30 + 225 * k)), rr = Math.min(255, (int) (8 + 110 * k * k)), bb = Math.min(255, (int) (14 + 120 * k * k));
        return 0xFF000000 | (rr << 16) | (g << 8) | bb;
    }

    /** Black and white: five cubes in a cross for the crossroads; a test card for the games. */
    private int monitorPixel(int i, float u, float v) {
        float x = (u - 0.5f) * 1.33f, y = v - 0.5f;
        if (i == HUB) {
            float q = 0.075f;
            boolean on = box(x, y, 0, 0, q) || box(x, y, 0, -0.27f, q) || box(x, y, 0, 0.27f, q) || box(x, y, -0.27f, 0, q) || box(x, y, 0.27f, 0, q);
            return on ? 0xFFE6ECEE : 0xFF23282C;
        }
        // the test card: a grid, a circle, a ramp of greys
        float r = (float) Math.sqrt(x * x + y * y);
        if (Math.abs(r - 0.32f) < 0.02f) return 0xFFE6ECEE;
        if (y > 0.08f && y < 0.2f && Math.abs(x) < 0.3f) return Backdrop.mix(0xFF101214, 0xFFE6ECEE, (x + 0.3f) / 0.6f);
        boolean line = Math.abs(((x + 2f) * 10f) % 1f) < 0.08f || Math.abs(((y + 2f) * 10f) % 1f) < 0.08f;
        return line ? 0xFF8A9298 : 0xFF2C3236;
    }

    private static boolean box(float x, float y, float cx, float cy, float q) {
        return Math.abs(x - cx) < q && Math.abs(y - cy) < q;
    }

    /** The plate under the tube: the episode's levels, its best stars and cubes won there. */
    private void plate(int i, int x0, int y0, int x1, int bg, int ink, int edge) {
        rect(x0 - 1, y0 - 1, x1 + 1, y0 + 10, edge);
        rect(x0, y0, x1, y0 + 9, bg);
        boolean lit = reached(i);
        int dim = lit ? ink : Backdrop.mix(bg, ink, 0.45f);
        if (i < Episode.COUNT) {
            String s = Episode.FIRST[i] + "-" + Episode.LAST[i];
            PixelFont.draw(spr, s, x0 + 3, y0 + 1 + TOP, 1, dim);
            int x = x1 - 3;
            if (won[i] > 0) {
                String n = String.valueOf(won[i]);
                x -= 7;
                cube(spr, x, y0 + 1 + TOP, 6);
                x -= PixelFont.width(n, 1) + 1;
                PixelFont.draw(spr, n, x, y0 + 1 + TOP, 1, dim);
                x -= 4;
            }
            if (stars[i] > 0) {
                String n = String.valueOf(stars[i]);
                x -= 6;
                star(spr, x + 3, y0 + 4 + TOP, dim);
                x -= PixelFont.width(n, 1) + 1;
                PixelFont.draw(spr, n, x, y0 + 1 + TOP, 1, dim);
            }
        } else if (i == HUB) {
            for (int k = -2; k <= 2; k++) rect((x0 + x1) / 2 + k * 6 - 2, y0 + 3, (x0 + x1) / 2 + k * 6 + 2, y0 + 7, dim);
        }
    }

    /** The little lamp on the case: dead grey, green once a cube is in the slot. */
    private void led(int i, int cx, int cy) {
        boolean on = open(i) && charge[i] == 0f || charge[i] > 0.15f;
        if (on) {
            for (int dy = -3; dy <= 3; dy++)
                for (int dx = -3; dx <= 3; dx++)
                    if (dx * dx + dy * dy <= 9) {
                        int p = (TOP + cy + dy) * SW + cx + dx;
                        if (p >= 0 && p < spr.a.length && spr.a[p] != 0) spr.a[p] = Backdrop.mix(spr.a[p], 0xFF3CFF7A, 0.25f);
                    }
        }
        rect(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF0A0C0E);
        rect(cx - 1, cy - 1, cx + 2, cy + 2, on ? 0xFF3CFF7A : 0xFF4A4E52);
        if (on) set(cx - 1, cy - 1, 0xFFC8FFD8);
    }

    private void knob(int cx, int cy, int r, float turn) {
        for (int y = -r; y <= r; y++)
            for (int x = -r; x <= r; x++) {
                float d = (float) Math.sqrt(x * x + y * y);
                if (d > r + 0.3f) continue;
                int c = d > r - 1.5f ? 0xFF1C1C1A : Backdrop.scale(0xFFB4AEA2, 1.1f - 0.35f * (x + y) / (2f * r));
                float a = (float) Math.atan2(y, x);
                if (d > r - 3f && d <= r - 1.5f && ((int) Math.floor((a + Math.PI) / (Math.PI / 6))) % 2 == 0) c = Backdrop.scale(c, 0.75f);
                set(cx + x, cy + y, c);
            }
        for (int k = 1; k < r - 1; k++)
            set(cx + Math.round((float) Math.cos(turn) * k), cy + Math.round((float) Math.sin(turn) * k), 0xFF2A2622);
    }

    private void bolt(int cx, int cy) {
        for (int y = -3; y <= 3; y++)
            for (int x = -3; x <= 3; x++)
                if (x * x + y * y <= 9) set(cx + x, cy + y, x + y < -1 ? 0xFFA8B2BA : x + y > 2 ? 0xFF262E34 : 0xFF6E7A84);
        set(cx - 1, cy + 1, 0xFF262E34);
        set(cx + 1, cy - 1, 0xFF262E34);
    }

    /**
     * The slot: a shaft sunk into the console rail, deep shadow on top and left, a bright edge
     * bottom and right, two dull copper contacts on the floor of it; a cube once one is in,
     * with a soft amber glow round the mouth.
     */
    private void slotPaint(int i) {
        int x0 = SW / 2 - SLOT_W / 2, y0 = TOP + CH + SLOT_GAP, x1 = x0 + SLOT_W, y1 = y0 + SLOT_H;
        if (thudAt == i && thudT < 0.3f) {
            int s = Math.round((float) Math.sin(thudT * 70f) * 2f);
            x0 += s;
            x1 += s;
        }
        boolean in = open(i) && inserting != i;
        if (in) {
            float g = 0.55f + 0.45f * slotFlash[i];
            for (int y = y0 - 5; y < y1 + 5; y++)
                for (int x = x0 - 5; x < x1 + 5; x++) {
                    float dx = Math.max(0, Math.max(x0 - x, x - x1 + 1)), dy = Math.max(0, Math.max(y0 - y, y - y1 + 1));
                    float dd = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dd <= 0f || dd > 5f) continue;
                    float a = g * 0.8f * (1f - dd / 5f);
                    int p = y * SW + x;
                    if (p < 0 || p >= spr.a.length) continue;
                    spr.a[p] = spr.a[p] == 0 ? Backdrop.mix(0xFF0E1013, 0xFFFFB040, a) : Backdrop.mix(spr.a[p], 0xFFFFB040, a);
                }
        }
        // the plate the slot is cut in
        rawRect(x0 - 3, y0 - 3, x1 + 3, y1 + 3, 0xFF2C333B);
        rawRect(x0 - 3, y0 - 3, x1 + 3, y0 - 2, 0xFF4A535D);
        rawRect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF1A1F25);
        rawRect(x0, y0, x1, y1, 0xFF0B0D10);
        rawRect(x0, y0, x1, y0 + 3, 0xFF020304);
        rawRect(x0, y0, x0 + 3, y1, 0xFF020304);
        rawRect(x0, y1 - 1, x1, y1, 0xFF7A828A);
        rawRect(x1 - 1, y0, x1, y1, 0xFF7A828A);
        rawRect(x0 + 6, y1 - 4, x0 + 14, y1 - 3, 0xFF6A4A2A);
        rawRect(x1 - 14, y1 - 4, x1 - 6, y1 - 3, 0xFF6A4A2A);
        if (in) {
            int q = SLOT_H - 4;
            cube(spr, (x0 + x1) / 2 - q / 2, y0 + 2, q);
        }
        if (slotFlash[i] > 0f)
            for (int y = y0 - 3; y < y1 + 3; y++)
                for (int x = x0 - 3; x < x1 + 3; x++) {
                    int p = y * SW + x;
                    if (p >= 0 && p < spr.a.length && spr.a[p] != 0) spr.a[p] = Backdrop.mix(spr.a[p], 0xFFFFF6C8, slotFlash[i] * slotFlash[i] * 0.7f);
                }
    }

    // sprite helpers, in case coordinates (y from the case's top) or raw ones
    private void rect(int x0, int y0, int x1, int y1, int c) {
        spr.rect(x0, TOP + y0, x1, TOP + y1, c);
    }

    private void rawRect(int x0, int y0, int x1, int y1, int c) {
        spr.rect(x0, y0, x1, y1, c);
    }

    private void set(int x, int y, int c) {
        spr.set(x, TOP + y, c);
    }

    private void roundRect(int x0, int y0, int x1, int y1, int r, int c) {
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if (inRound(x - x0, y - y0, x1 - x0, y1 - y0, r)) set(x, y, c);
    }

    private static boolean inRound(int x, int y, int w, int h, int r) {
        int dx = x < r ? r - x : x >= w - r ? x - (w - r - 1) : 0, dy = y < r ? r - y : y >= h - r ? y - (h - r - 1) : 0;
        return dx * dx + dy * dy <= r * r;
    }

    /**
     * The flat sprite onto the curved wall: each column of the frame looks along its own
     * angle, so the side of a screen turned away is narrower, and is drawn taller by the bow
     * of the drum; the far ones lie in the dark outside the lamp.
     */
    private void place(Px f, int i, float cy) {
        int W = World.W;
        float a = ang[i], c = (float) Math.cos(a);
        float dim = 0.3f + 0.7f * c * c, lampK = (float) Math.pow(Math.max(0f, c), 10);
        float kr = dim * (1f + 0.08f * lampK), kg = dim, kb = dim * (1f - 0.05f * lampK);
        int x0 = Math.max(0, (int) Math.floor(xl[i])), x1 = Math.min(W, (int) Math.ceil(xr[i]));
        for (int x = x0; x < x1; x++) {
            float th = (float) Math.asin((x + 0.5f - W * 0.5f) / RADIUS);
            float u = (th - (a - HALF)) / (2f * HALF);
            if (u < 0f || u >= 1f) continue;
            int sx = Math.min(SW - 1, (int) (u * SW));
            float s = bow(x + 0.5f);
            int ya = (int) Math.floor(cy + (-TOP - CH * 0.5f) * s), yb = (int) Math.ceil(cy + (SH - TOP - CH * 0.5f) * s);
            for (int y = Math.max(0, ya); y < Math.min(w.h, yb); y++) {
                int r = (int) Math.floor((y + 0.5f - cy) / s + CH * 0.5f + TOP);
                if (r < 0 || r >= SH) continue;
                int p = spr.a[r * SW + sx];
                if (p == 0) continue;
                int rr = Math.min(255, (int) (((p >> 16) & 0xFF) * kr)), gg = Math.min(255, (int) (((p >> 8) & 0xFF) * kg)), bb = Math.min(255, (int) ((p & 0xFF) * kb));
                f.a[y * W + x] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        }
    }

    // ---- the console ----

    private void console(Px f, int h) {
        int W = World.W;
        int top = h - CONSOLE;
        // a sloping steel desk with worn edges and rivets
        for (int y = top; y < h; y++) {
            int c = Backdrop.mix(0xFF262C33, 0xFF14181C, (y - top) / (float) CONSOLE);
            for (int x = 0; x < W; x++) f.a[y * W + x] = c;
        }
        f.rect(0, top, W, top + 3, 0xFF3A424B);
        f.rect(0, top, W, top + 1, 0xFF6A747E);
        for (int x = 12; x < W; x += 36) {
            f.rect(x, top + 6, x + 2, top + 8, 0xFF464E57);
            f.rect(x, h - 8, x + 2, h - 6, 0xFF2E353C);
        }
        for (int k = 0; k < 3; k++) {
            float[] r = consoleRect(k, h);
            int x0 = Math.round(r[0]), y0 = Math.round(r[1]), x1 = Math.round(r[2]), y1 = Math.round(r[3]);
            boolean on = answers(k);
            f.rect(x0 - 3, y0 - 3, x1 + 3, y1 + 3, 0xFF0A0C0E);
            f.rect(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xFF3A4048);
            f.rect(x0 - 2, y0 - 2, x1 + 2, y0 - 1, 0xFF5A626B);
            f.rect(x0, y0, x1, y1, 0xFF0E1012);
            int g = on ? 0xFFC8CEC8 : 0xFF262A2C;
            for (int y = y0 + 3; y < y1 - 3; y++)
                f.rect(x0 + 3, y, x1 - 3, y + 1, (y & 1) == 0 ? g : Backdrop.scale(g, on ? 0.86f : 0.9f));
            int ink = on ? 0xFF16181A : 0xFF3A3E42;
            int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
            if (k == GOLEM) PixelFont.draw(f, "GOLEM", cx - PixelFont.width("GOLEM", 1) / 2, cy - 3, 1, ink);
            else if (k == ALLEY) World.glyph(f, BOWLING, cx, cy, 2, ink);
            else {
                // the stones' wave runs slowly along the monitor
                World.glyph(f, World.SKIP, cx, cy, 2, ink);
                if (on) {
                    int ph = (int) (t * 10f) % 4;
                    for (int x = x0 + 4 + ph; x < x1 - 4; x += 4) f.rect(x, y1 - 7, x + 1, y1 - 6, ink);
                }
            }
        }
        lever(f, h);
        dial(f, h);
        tubes(f, h);
    }

    /** A pin and a ball, for the alley. */
    static final String[] BOWLING = {
            "...........XXX.",
            "..........XXXXX",
            "..........XXXXX",
            "...........XXX.",
            "............X..",
            "...........XXX.",
            "..XXXX....XXXXX",
            ".XXXXXX..XXXXXX",
            "XX.XXXXX.XXXXXX",
            "X.XXXXXX.XXXXXX",
            "XXXXXXXX..XXXXX",
            ".XXXXXX...XXXX.",
            "..XXXX.....XX.."};

    /** The HOME lever: thrown, it swings from right to left in a fraction of a second. */
    private void lever(Px f, int h) {
        float[] r = consoleRect(HOME, h);
        int x0 = Math.round(r[0]), x1 = Math.round(r[2]), y1 = Math.round(r[3]);
        float k = leaving == HOME ? Math.min(1f, leaveT / LEVER_T) : 0f;
        k = k * k * (3f - 2f * k);
        float ang = 0.75f - 1.5f * k;
        int lx = (x0 + x1) / 2, py = y1 - 14;
        // the base with its slot, and the shadow of the arm on it
        f.rect(x0 + 8, y1 - 16, x1 - 8, y1 - 2, 0xFF2A2E33);
        f.rect(x0 + 8, y1 - 16, x1 - 8, y1 - 15, 0xFF4A525A);
        f.rect(lx - 22, py - 1, lx + 22, py + 2, 0xFF0A0C0E);
        float sx = (float) Math.sin(ang), sy = -(float) Math.cos(ang);
        for (int q = 4; q < 22; q++) f.set(Math.round(lx - sx * q), py + 3, 0xFF15181B);
        for (int q = 0; q < 32; q++) {
            int x = Math.round(lx + sx * q), y = Math.round(py + sy * q);
            f.rect(x - 1, y - 1, x + 2, y + 1, 0xFF8A9096);
            f.set(x - 1, y - 1, 0xFFB8BEC4);
        }
        float kx = lx + sx * 35f, ky = py + sy * 35f;
        f.disc(kx, ky, 6.5f, 0xFF7A1410);
        f.disc(kx, ky, 5.5f, 0xFFC8231E);
        f.disc(kx - 2f, ky - 2f, 1.6f, 0xFFFF9A8A);
        PixelFont.draw(f, "HOME", lx - PixelFont.width("HOME", 1) / 2, y1 - 10, 1, 0xFFE8E2D6);
    }

    /** The whole ring from above: a mark for each screen, the one in front under the pointer. */
    private void dial(Px f, int h) {
        float cx = 44f, cy = h - 63f, r = 22f;
        f.disc(cx, cy, r + 4f, 0xFF0A0C0E);
        f.disc(cx, cy, r + 3f, 0xFF2C333B);
        f.disc(cx, cy, r + 1f, 0xFF0C1013);
        float step = (float) (Math.PI * 2 / SCREENS);
        int cur = current();
        for (int i = 0; i < SCREENS; i++) {
            float a = (i - pos) * step;
            float x = cx + (float) Math.sin(a) * (r - 4f), y = cy - (float) Math.cos(a) * (r - 4f);
            int c;
            if (open(i)) c = 0xFFF2C64A;
            else if (reached(i)) c = 0xFF6EA884;
            else c = 0xFF3A4048;
            if (i < Episode.COUNT && i == cur && ((int) (t * 3f) & 1) == 0) c = 0xFFE6FFEE;
            f.rect(Math.round(x) - 1, Math.round(y) - 1, Math.round(x) + 2, Math.round(y) + 2, c);
        }
        // the pointer, and the player in the middle
        f.rect(Math.round(cx) - 1, Math.round(cy - r - 4), Math.round(cx) + 2, Math.round(cy - r + 2), 0xFFC8231E);
        f.disc(cx, cy, 2f, 0xFF8A9096);
    }

    /** Three glow tubes beside the lever: the number of the level it goes back to. */
    private void tubes(Px f, int h) {
        int n = Math.min(999, w.level + 1);
        int x0 = World.W - 86, y0 = h - 86;
        String s = String.valueOf(n);
        for (int k = 0; k < 3; k++) {
            int tx = x0 + k * 25;
            // the glass, the anode mesh behind
            f.rect(tx, y0, tx + 21, y0 + 36, 0xFF0A0C0E);
            f.rect(tx + 1, y0 + 1, tx + 20, y0 + 35, 0xFF15110F);
            for (int y = y0 + 4; y < y0 + 33; y += 3)
                for (int x = tx + 3; x < tx + 18; x += 3) f.set(x, y, 0xFF241C16);
            f.rect(tx + 3, y0 + 2, tx + 5, y0 + 30, 0xFF201A16);
            int idx = k - (3 - s.length());
            if (idx < 0) continue;
            String dch = s.substring(idx, idx + 1);
            int dx = tx + 3, dy = y0 + 7;
            // the glow round the digit, then the digit
            for (int oy = -1; oy <= 1; oy++)
                for (int ox = -1; ox <= 1; ox++)
                    if (ox != 0 || oy != 0) PixelFont.draw(f, dch, dx + ox, dy + oy, 3, 0xFF5A2A10);
            PixelFont.draw(f, dch, dx, dy, 3, 0xFFFF8A2E);
        }
    }

    /** The golden cubes in hand, in a rack set into the corner of the visor, like fuel rods. */
    private void rack(Px f) {
        int n = w.cubes, slots = 10;
        int x0 = 6, y0 = 5;
        f.rect(x0 - 2, y0 - 2, x0 + slots * 11 + 3, y0 + 17, 0xFF07090B);
        f.rect(x0 - 1, y0 - 1, x0 + slots * 11 + 2, y0 + 16, 0xFF2E353D);
        f.rect(x0 - 1, y0 - 1, x0 + slots * 11 + 2, y0, 0xFF56606A);
        for (int i = 0; i < slots; i++) {
            int x = x0 + 3 + i * 11, y = y0 + 3;
            boolean full = i < n;
            if (full) {
                // the warm light a cube throws on the steel round it
                f.rect(x - 2, y - 2, x + 11, y + 11, Backdrop.mix(0xFF2E353D, 0xFFFFB040, 0.28f));
            }
            f.rect(x - 1, y - 1, x + 10, y + 10, 0xFF0A0C0E);
            f.rect(x, y, x + 9, y + 9, 0xFF14181C);
            if (full) cube(f, x, y, 9);
        }
        if (n > slots) PixelFont.draw(f, "+" + (n - slots), x0 + slots * 11 + 6, y0 + 5, 1, 0xFFFFE89A);
    }

    static void cube(Px f, int x, int y, int s) {
        f.rect(x, y, x + s, y + s, 0xFFB8862A);
        f.rect(x + 1, y + 1, x + s - 1, y + s - 1, 0xFFF2C64A);
        f.rect(x + 1, y + 1, x + Math.max(2, s / 2), y + Math.max(2, s / 3), 0xFFFFF0B0);
    }

    private static void star(Px f, int cx, int cy, int c) {
        f.rect(cx - 1, cy - 3, cx + 2, cy + 4, c);
        f.rect(cx - 3, cy - 1, cx + 4, cy + 2, c);
    }

    // the hammer, as in the game: a handle, a darker grip, a grained wooden head bound with
    // two iron hoops near its ends; boxes by their middle and half sizes, in meters, the
    // handle along x, the head's long side along z
    private static final float[][] PARTS = {
            {-0.1f, 0f, 0f, 0.31f, 0.03f, 0.03f},
            {-0.36f, 0f, 0f, 0.07f, 0.036f, 0.036f},
            {0.31f, 0f, 0f, 0.1f, 0.078f, 0.17f},
            {0.31f, 0f, 0.12f, 0.104f, 0.082f, 0.016f},
            {0.31f, 0f, -0.12f, 0.104f, 0.082f, 0.016f}};
    private static final int[] PART_COL = {0xFF9A6638, 0xFF6E3F27, 0xFFDFA868, 0xFF55524F, 0xFF55524F};

    /**
     * The hammer in the air at (cx, cy), so many pixels to a meter: turned in the plane of
     * the frame by spin (radians), and a little toward the eye so the head shows its depth.
     * Each box is drawn face by face, far ones first, lit from the upper left.
     */
    private static void hammer(Px f, float cx, float cy, float spin, float ppm) {
        // the hammer's own axes in view space, before the turn: the handle across the frame and
        // a little toward the eye, the head's long side upright and leaning back, so it reads
        // as a T with the depth of its faces showing
        float[] ax = norm(1f, 0f, 0.32f), az = norm(0f, 1f, -0.42f);
        float d = ax[0] * az[0] + ax[1] * az[1] + ax[2] * az[2];
        az = norm(az[0] - d * ax[0], az[1] - d * ax[1], az[2] - d * ax[2]);
        float[] ay = {az[1] * ax[2] - az[2] * ax[1], az[2] * ax[0] - az[0] * ax[2], az[0] * ax[1] - az[1] * ax[0]};
        float[][] m = {{ax[0], ay[0], az[0]}, {ax[1], ay[1], az[1]}, {ax[2], ay[2], az[2]}};
        float cs = (float) Math.cos(spin), sn = (float) Math.sin(spin);
        float[][] r = new float[3][3];
        for (int c = 0; c < 3; c++) {
            r[0][c] = cs * m[0][c] - sn * m[1][c];
            r[1][c] = sn * m[0][c] + cs * m[1][c];
            r[2][c] = m[2][c];
        }
        float lx = -0.45f, ly = 0.6f, lz = 0.66f;
        // far parts first; the hoops wrap the head, so they always go on after it
        float zh = r[2][0] * PARTS[0][0], zg = r[2][0] * PARTS[1][0], zd = r[2][0] * PARTS[2][0];
        int[] ord = zd < Math.min(zh, zg) ? new int[]{2, 3, 4, zh < zg ? 0 : 1, zh < zg ? 1 : 0}
                : zd > Math.max(zh, zg) ? new int[]{zh < zg ? 0 : 1, zh < zg ? 1 : 0, 2, 3, 4}
                : new int[]{zh < zg ? 0 : 1, 2, 3, 4, zh < zg ? 1 : 0};
        for (int o = 0; o < 5; o++) {
            int i = ord[o];
            float[] p = PARTS[i];
            float px = r[0][0] * p[0] + r[0][1] * p[1] + r[0][2] * p[2];
            float py = r[1][0] * p[0] + r[1][1] * p[1] + r[1][2] * p[2];
            // the six faces: an axis k, a side; visible when the outward normal faces the eye
            for (int k = 0; k < 3; k++)
                for (int sd = -1; sd <= 1; sd += 2) {
                    float nx = r[0][k] * sd, ny = r[1][k] * sd, nz = r[2][k] * sd;
                    if (nz <= 0.02f) continue;
                    int a1 = (k + 1) % 3, a2 = (k + 2) % 3;
                    float h0 = p[3 + k] * sd, h1 = p[3 + a1], h2 = p[3 + a2];
                    // the face's middle and its two half edges, on screen (y down)
                    float mx = cx + (px + r[0][k] * h0) * ppm, my = cy - (py + r[1][k] * h0) * ppm;
                    float e1x = r[0][a1] * h1 * ppm, e1y = -r[1][a1] * h1 * ppm;
                    float e2x = r[0][a2] * h2 * ppm, e2y = -r[1][a2] * h2 * ppm;
                    float lit = 0.62f + 0.48f * Math.max(0f, nx * lx + ny * ly + nz * lz);
                    // the grain runs along the box's longest side
                    int along = p[3] >= p[4] && p[3] >= p[5] ? 0 : p[5] >= p[4] ? 2 : 1;
                    boolean grainA = along == a1, grainB = along == a2;
                    face(f, mx, my, e1x, e1y, e2x, e2y, PART_COL[i], lit, i == 0 || i == 2 ? (grainA ? 2 : grainB ? 1 : 0) : 0, i * 7 + k * 3 + sd);
                }
        }
    }

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    /**
     * A parallelogram filled pixel by pixel: middle (mx, my), half edges e1 and e2. grain 1
     * draws faint stripes across e1 (running along e2), grain 2 across e2.
     */
    private static void face(Px f, float mx, float my, float e1x, float e1y, float e2x, float e2y, int col, float lit, int grain, int seed) {
        float det = e1x * e2y - e1y * e2x;
        if (Math.abs(det) < 0.05f) return;
        float ext = Math.abs(e1x) + Math.abs(e2x), eyt = Math.abs(e1y) + Math.abs(e2y);
        int x0 = (int) Math.floor(mx - ext), x1 = (int) Math.ceil(mx + ext), y0 = (int) Math.floor(my - eyt), y1 = (int) Math.ceil(my + eyt);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - mx, dy = y + 0.5f - my;
                float a = (dx * e2y - dy * e2x) / det, b = (e1x * dy - e1y * dx) / det;
                if (a < -1f || a > 1f || b < -1f || b > 1f) continue;
                float k = lit;
                if (grain == 1) k *= 0.93f + 0.07f * (float) Math.sin(a * 9.5f + seed) + 0.04f * (float) Math.sin(a * 23f);
                else if (grain == 2) k *= 0.93f + 0.07f * (float) Math.sin(b * 9.5f + seed) + 0.04f * (float) Math.sin(b * 23f);
                // edges a shade darker, so the box keeps its shape when small
                if (Math.abs(a) > 0.9f || Math.abs(b) > 0.9f) k *= 0.86f;
                f.set(x, y, Backdrop.scale(col, k));
            }
    }
}
