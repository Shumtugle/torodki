package com.shumtugle.torodki;

import java.util.Random;

/**
 * The dispatcher's bridge: a dark room, the player in the middle, a ring of screens round
 * him, one for each episode, one for the crossroads of five cubes and one for each of the
 * two games that open after the last episode. A swipe turns the ring, with a run-on and a
 * settle on the nearest screen. A tap throws the hammer at what is under the finger.
 *
 * A screen lights up once its episode has been reached; the one being played flickers;
 * those still ahead stay dark and answer a throw with snow and the busy tone. Under each
 * screen a slot: a golden cube set into it opens the screen for good, and from then on a
 * throw at it goes to the episode's first level, as often as the player likes. The first
 * episode's slot holds a cube from the start. A cube goes in once and never comes out.
 *
 * On the console below: the alley, the golem and the stones on the water, each a black and
 * white monitor that answers only where the game is (the first two on the street, the last
 * at sea), and the HOME lever, back to the level being played.
 *
 * Pure Java; the world runs what is chosen.
 */
final class Bridge {
    static final int SCREENS = Episode.COUNT + 3, HUB = Episode.COUNT, GAME_A = Episode.COUNT + 1, GAME_B = Episode.COUNT + 2;
    static final int ALLEY = 0, GOLEM = 1, SKIP = 2, HOME = 3;
    private static final int AT_SCREEN = 0, AT_SLOT = 1, AT_CONSOLE = 2;
    static final float OPEN_T = 0.3f, THROW_T = 0.32f, INSERT_T = 0.55f;

    private final World w;
    private final World.Host host;
    private final Random rnd = new Random(0xB21D6EL);
    float t;                                  // seconds since it opened
    float pos, vel;                           // the ring's turn, in screens, and its run-on
    private boolean down, dragged;
    private float downX, downY, lastX, lastT;
    private int aimKind = -1, aimWhich;       // the hammer in flight, at what
    private float aimT, aimX, aimY;
    private int inserting = -1;               // a cube on its way into a slot
    private float insertT;
    private final float[] flash = new float[SCREENS], snow = new float[SCREENS];
    private int thudAt = -1;
    private float thudT;
    // where each screen was drawn this frame, for touches
    private final float[] bx = new float[SCREENS], by = new float[SCREENS], bw = new float[SCREENS], bh = new float[SCREENS];
    private final boolean[] shown = new boolean[SCREENS];
    private final int[] order = new int[SCREENS];
    private final float[] depth = new float[SCREENS];

    Bridge(World w, World.Host host) {
        this.w = w;
        this.host = host;
        pos = current();
    }

    // ---- what the screens know ----

    /** The episode being played. */
    int current() {
        return Episode.of(w.level + 1);
    }

    boolean reached(int i) {
        if (i < Episode.COUNT) return Episode.FIRST[i] <= w.best();
        if (i == HUB) return host.loadInt("alley") > 0;
        return host.loadInt("finale") == 1;
    }

    boolean open(int i) {
        return i == 0 || host.loadInt("slot" + i) == 1;
    }

    /** Whether a console monitor answers where the game is now. */
    boolean answers(int which) {
        if (which == HOME) return true;
        return which == SKIP ? w.atSea : !w.atSea;
    }

    // ---- time ----

    void update(float dt) {
        t += dt;
        if (!down) {
            // the run-on dies away, then the ring settles on the nearest screen
            pos += vel * dt;
            vel *= (float) Math.exp(-dt * 5f);
            if (Math.abs(vel) < 1.2f) {
                float target = Math.round(pos);
                pos += (target - pos) * Math.min(1f, dt * 9f);
                vel *= 0.8f;
            }
        }
        for (int i = 0; i < SCREENS; i++) {
            flash[i] = Math.max(0f, flash[i] - dt * 1.6f);
            snow[i] = Math.max(0f, snow[i] - dt * 1.2f);
        }
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
                host.saveInt("slot" + inserting, 1);
                flash[inserting] = 1f;
                host.sound(Synth.LATCH, 1f, 1f);
                host.sound(Synth.CATCH, 0.7f, 1.3f);
                host.buzz(40);
                inserting = -1;
            }
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
            w.bridgeAct(which);
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
                // a cube from the row by the stars flies into the slot and clicks home
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
        if (t < OPEN_T) return;
        if (type == World.DOWN) {
            down = true;
            dragged = false;
            downX = lastX = x;
            downY = y;
            lastT = t;
            vel = 0f;
            return;
        }
        if (!down) return;
        if (type == World.MOVE) {
            if (Math.abs(x - downX) > 6f) dragged = true;
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
        vel = Math.max(-6f, Math.min(6f, vel * 0.6f));
        if (dragged || aimKind >= 0) return;
        int[] hit = hit(x, y);
        if (hit == null) return;
        aimKind = hit[0];
        aimWhich = hit[1];
        aimT = 0f;
        aimX = x;
        aimY = y;
        host.sound(Synth.WHOOSH, 0.6f, 1.2f);
    }

    /** What is under the finger: a slot, a screen, front ones first, or the console. */
    private int[] hit(float x, float y) {
        int h = w.h;
        for (int k = 0; k < 4; k++) {
            float[] r = consoleRect(k, h);
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return new int[]{AT_CONSOLE, k};
        }
        for (int o = SCREENS - 1; o >= 0; o--) {
            int i = order[o];
            if (!shown[i]) continue;
            float sx0 = bx[i] - bw[i] * 0.5f, sx1 = bx[i] + bw[i] * 0.5f;
            float sy0 = by[i] - bh[i] * 0.5f, slotY = by[i] + bh[i] * 0.5f + bh[i] * 0.22f;
            if (x >= sx0 && x < sx1 && y >= sy0 && y < slotY + bh[i] * 0.12f) return new int[]{y > by[i] + bh[i] * 0.5f ? AT_SLOT : AT_SCREEN, i};
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
        // the room: dark, a faint glow from the screens on the floor
        for (int y = 0; y < h; y++) {
            float k = y / (float) h;
            int c = Backdrop.mix(0xFF0B0E12, 0xFF151A20, (float) Math.sin(k * Math.PI) * 0.8f);
            for (int x = 0; x < W; x++) f.a[y * W + x] = c;
        }
        for (int gy = h - 200; gy < h; gy += 14)
            f.rect(0, gy, W, gy + 1, 0xFF181E25);
        // the ring: angle of each screen off the middle, the far ones smaller and dimmer
        float step = (float) (Math.PI * 2 / SCREENS);
        for (int i = 0; i < SCREENS; i++) {
            float d = i - pos;
            d -= SCREENS * (float) Math.floor(d / SCREENS + 0.5f);
            // the turn between screens is shown wider than it is: the middle one whole, its
            // neighbours half out of the frame, turned away on the curve of the wall
            float a = d * step * 1.6f, c = (float) Math.cos(a);
            depth[i] = c;
            order[i] = i;
            shown[i] = c > 0.12f;
            bx[i] = W * 0.5f + 236f * (float) Math.sin(a);
            by[i] = h * 0.37f + 10f * (1f - c);
            bw[i] = 176f * (0.22f + 0.78f * c);
            bh[i] = 132f * (0.8f + 0.2f * c);
        }
        // back to front
        for (int a = 1; a < SCREENS; a++)
            for (int b = a; b > 0 && depth[order[b - 1]] > depth[order[b]]; b--) {
                int tmp = order[b];
                order[b] = order[b - 1];
                order[b - 1] = tmp;
            }
        for (int o = 0; o < SCREENS; o++) {
            int i = order[o];
            if (shown[i]) screen(f, i, depth[i]);
        }
        console(f, h);
        cubesRow(f);
        // the cube on its way into a slot
        if (inserting >= 0) {
            float u = Math.min(1f, insertT / INSERT_T), e = u * u * (3f - 2f * u);
            float x0 = 10 + w.cubes * 11, y0 = 12, x1 = bx[inserting], y1 = by[inserting] + bh[inserting] * 0.5f + bh[inserting] * 0.16f;
            float x = x0 + (x1 - x0) * e, y = y0 + (y1 - y0) * e - (float) Math.sin(u * Math.PI) * 40f;
            cube(f, Math.round(x - 4), Math.round(y - 4), 9);
        }
        // the hammer in flight to what was tapped
        if (aimKind >= 0) {
            float u = aimT / THROW_T;
            float x = W - 20 + (aimX - (W - 20)) * u, y = h + 10 + (aimY - (h + 10)) * u - (float) Math.sin(u * Math.PI) * 30f;
            hammer(f, x, y, u * 9f, 1.2f - 0.5f * u);
        }
        if (keep != null)
            for (int k = 0; k < keep.length; k++) f.a[k] = Backdrop.mix(keep[k], f.a[k], alpha);
    }

    private void screen(Px f, int i, float c) {
        float x0 = bx[i] - bw[i] * 0.5f, y0 = by[i] - bh[i] * 0.5f;
        int X0 = Math.round(x0), Y0 = Math.round(y0), X1 = Math.round(x0 + bw[i]), Y1 = Math.round(y0 + bh[i]);
        float dim = 0.35f + 0.65f * c;
        boolean reached = reached(i), cur = i < Episode.COUNT && i == current();
        // the case, and the glass inside it
        f.rect(X0 - 1, Y0 - 1, X1 + 1, Y1 + 1, Backdrop.scale(0xFF0E1013, 1f));
        f.rect(X0, Y0, X1, Y1, Backdrop.scale(0xFF3A4048, dim));
        int in = Math.max(2, Math.round(bw[i] * 0.06f));
        int gx0 = X0 + in, gy0 = Y0 + in, gx1 = X1 - in, gy1 = Y1 - in - Math.max(2, Math.round(bh[i] * 0.08f));
        int glass;
        if (!reached || i == GAME_A || i == GAME_B) glass = 0xFF0F1316;
        else glass = cur ? 0xFF8FB39C : 0xFF5E7A6A;
        boolean flick = cur && ((int) (t * 12f) % 9) == 0;
        if (flick) glass = Backdrop.scale(glass, 0.85f);
        for (int y = gy0; y < gy1; y++) {
            float k = (y - gy0) / (float) Math.max(1, gy1 - gy0);
            int row = Backdrop.scale(glass, (1.12f - 0.25f * k) * dim);
            // the scan lines of a tube
            if ((y & 1) == 0) row = Backdrop.scale(row, 0.92f);
            f.rect(gx0, y, gx1, y + 1, row);
        }
        // the dark ones: snow, faint always, thick after a throw
        if (!reached || i == GAME_A || i == GAME_B) {
            float thick = 0.04f + snow[i] * 0.6f;
            for (int y = gy0; y < gy1; y++)
                for (int x = gx0; x < gx1; x++)
                    if (rnd.nextFloat() < thick) f.set(x, y, Backdrop.scale(0xFFC8D0D4, (0.3f + rnd.nextFloat() * 0.7f) * dim));
        } else if (i < Episode.COUNT) {
            // the episode's levels, as on the plate
            String s = Episode.FIRST[i] + "-" + Episode.LAST[i];
            int sc = bw[i] > 120 ? 3 : bw[i] > 70 ? 2 : 1;
            int ink = Backdrop.scale(0xFF14201A, 1f);
            PixelFont.draw(f, s, Math.round(bx[i]) - PixelFont.width(s, sc) / 2, (gy0 + gy1) / 2 - 4 * sc, sc, ink);
            int stars = host.loadInt("ep" + i + "stars");
            if (stars > 0 && sc >= 2) {
                String st = String.valueOf(stars);
                PixelFont.draw(f, st, gx1 - PixelFont.width(st, 1) - 10, gy1 - 10, 1, ink);
                star(f, gx1 - 5, gy1 - 7, ink);
            }
        } else if (i == HUB) {
            // five cubes in a cross
            int cx = Math.round(bx[i]), cy = (gy0 + gy1) / 2, q = Math.max(3, Math.round(bw[i] * 0.07f));
            int ink = 0xFF14201A;
            f.rect(cx - q, cy - q, cx + q, cy + q, ink);
            f.rect(cx - q, cy - 4 * q, cx + q, cy - 2 * q, ink);
            f.rect(cx - q, cy + 2 * q, cx + q, cy + 4 * q, ink);
            f.rect(cx - 4 * q, cy - q, cx - 2 * q, cy + q, ink);
            f.rect(cx + 2 * q, cy - q, cx + 4 * q, cy + q, ink);
        }
        if (flash[i] > 0f)
            for (int y = gy0; y < gy1; y++)
                for (int x = Math.max(0, gx0); x < Math.min(World.W, gx1); x++)
                    if (y >= 0 && y < f.h) f.a[y * World.W + x] = Backdrop.mix(f.a[y * World.W + x], 0xFFFFFFF4, flash[i]);
        // the knob
        f.rect(X1 - in - 3, Y1 - in - 2, X1 - in, Y1 - in + 1, Backdrop.scale(0xFF8A9096, dim));
        // the slot under it, with its cube if one has gone in
        int sw = Math.max(8, Math.round(bw[i] * 0.22f)), shh = Math.max(4, Math.round(bh[i] * 0.12f));
        int sx = Math.round(bx[i]) - sw / 2, sy = Y1 + Math.max(3, Math.round(bh[i] * 0.1f));
        boolean shake = thudAt == i && thudT < 0.3f;
        if (shake) sx += Math.round((float) Math.sin(thudT * 70f) * 2f);
        f.rect(sx - 1, sy - 1, sx + sw + 1, sy + shh + 1, Backdrop.scale(0xFF4A5058, dim));
        f.rect(sx, sy, sx + sw, sy + shh, 0xFF06080A);
        if (open(i) && inserting != i) {
            int q = Math.max(2, shh - 2);
            cube(f, sx + sw / 2 - q / 2, sy + 1, q);
        }
    }

    private void console(Px f, int h) {
        int W = World.W;
        f.rect(0, h - 188, W, h, 0xFF1A1F25);
        f.rect(0, h - 188, W, h - 186, 0xFF2C333B);
        for (int k = 0; k < 3; k++) {
            float[] r = consoleRect(k, h);
            int x0 = Math.round(r[0]), y0 = Math.round(r[1]), x1 = Math.round(r[2]), y1 = Math.round(r[3]);
            boolean on = answers(k);
            f.rect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF0A0C0E);
            f.rect(x0, y0, x1, y1, 0xFF3A4048);
            int g = on ? 0xFFB8BEB8 : 0xFF2A2E30;
            for (int y = y0 + 4; y < y1 - 4; y++) f.rect(x0 + 4, y, x1 - 4, y + 1, (y & 1) == 0 ? g : Backdrop.scale(g, 0.9f));
            int ink = on ? 0xFF16181A : 0xFF3A3E42;
            int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
            if (k == GOLEM) PixelFont.draw(f, "GOLEM", cx - PixelFont.width("GOLEM", 1) / 2, cy - 3, 1, ink);
            else World.glyph(f, k == ALLEY ? World.BOWL : World.SKIP, cx, cy, 2, ink);
        }
        // the lever
        float[] r = consoleRect(HOME, h);
        int x0 = Math.round(r[0]), y0 = Math.round(r[1]), x1 = Math.round(r[2]), y1 = Math.round(r[3]);
        f.rect(x0, y1 - 14, x1, y1, 0xFF2A2E33);
        int lx = (x0 + x1) / 2;
        for (int k = 0; k < 30; k++) f.rect(lx - 12 + k * 8 / 10, y1 - 14 - k, lx - 9 + k * 8 / 10, y1 - 13 - k, 0xFF8A9096);
        f.rect(lx + 9, y0 + 8, lx + 21, y0 + 20, 0xFFC8231E);
        PixelFont.draw(f, "HOME", lx - PixelFont.width("HOME", 1) / 2, y1 - 10, 1, 0xFFE8E2D6);
    }

    /** The golden cubes in hand, in a row at the top, as beside the stars in the game. */
    private void cubesRow(Px f) {
        int n = w.cubes;
        for (int i = 0; i < Math.min(n, 12); i++) cube(f, 10 + i * 11, 8, 9);
        if (n > 12) PixelFont.draw(f, String.valueOf(n), 10 + 12 * 11 + 2, 10, 1, 0xFFFFE89A);
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

    /** The hammer, small, turning as it flies. */
    private static void hammer(Px f, float cx, float cy, float ang, float s) {
        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
        int r = Math.round(16 * s);
        for (int y = -r; y <= r; y++)
            for (int x = -r; x <= r; x++) {
                float u = (x * ca + y * sa) / (14f * s), v = (-x * sa + y * ca) / (14f * s);
                int c = 0;
                if (u > 0.3f && u < 1.05f && Math.abs(v) < 0.42f) c = (u > 0.42f && u < 0.52f) || (u > 0.82f && u < 0.92f) ? 0xFF3A3430 : 0xFFD9A066;
                else if (u > -1f && u <= 0.3f && Math.abs(v) < 0.1f) c = 0xFF8A5A32;
                if (c != 0) f.set(Math.round(cx) + x, Math.round(cy) + y, c);
            }
    }
}
