package com.shumtugle.torodki;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * The desktop stand: runs the same World as the phone, frame by frame, from a scene
 * script, and saves snapshots as PNG. No phone, no emulator, no platform classes.
 *
 * Scene commands, one per line (coordinates in game pixels, time in seconds):
 *   screen 1096 2560        phone screen in real pixels; sets the world's height
 *   wait 1.5                run the world for this long at 60 frames a second
 *   down x y | move x y | up x y
 *   drag x0 y0 x1 y1 0.3    a finger from one point to another over this time
 *   tap x y
 *   back
 *   level n                 start level n at once (counted from 0), without the rise
 *   state                   print the game state
 *   aim                     print where the aim ring is
 *   heavy                   give the heavy hammer, as the gods do
 *   ball x y z              drop a bowling ball at a world point
 *   throwat x y z speed     throw at a world point, as the bot does
 *   win n                   count the building as cleared after n throws
 *   lose                    put out every star
 *   shot name               save the current frame as name.png, scaled to the screen width
 *   # comment
 */
public final class Stand implements World.Host {
    private static final float DT = 1f / 60f;

    private final File outDir;
    private World world;
    private Px frame;
    private int screenW = 1096, screenH = 2560;
    private float time;
    private int frames, shots, buzzes;

    private Stand(File outDir) {
        this.outDir = outDir;
    }

    public static void main(String[] args) throws IOException {
        File scene = new File(args.length > 0 ? args[0] : "stand/scene.txt");
        File out = new File(args.length > 1 ? args[1] : "out/stand");
        out.mkdirs();
        new Stand(out).play(scene);
    }

    @Override
    public void buzz(int ms) {
        buzzes++;
    }

    private final java.util.ArrayList<float[]> sounds = new java.util.ArrayList<float[]>();
    private int savedLevel;
    private long worstNs, sumNs;

    @Override
    public void sound(int id, float gain, float rate) {
        sounds.add(new float[]{time, id, gain, rate});
    }

    @Override
    public byte[] asset(String name) {
        try {
            return java.nio.file.Files.readAllBytes(new File("assets", name).toPath());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void saveLevel(int level) {
        savedLevel = level;
    }

    private int savedGifts, savedFails;
    private final java.util.HashMap<String, Integer> ints = new java.util.HashMap<String, Integer>();

    @Override
    public void saveInt(String key, int value) {
        ints.put(key, value);
    }

    @Override
    public int loadInt(String key) {
        Integer v = ints.get(key);
        return v == null ? 0 : v;
    }
    private boolean savedGold;

    @Override
    public void saveGoldSeen(boolean seen) {
        savedGold = seen;
    }

    @Override
    public boolean loadGoldSeen() {
        return savedGold;
    }

    @Override
    public void saveFails(int fails) {
        savedFails = fails;
    }

    @Override
    public int loadFails() {
        return savedFails;
    }

    @Override
    public void saveGifts(int heavy) {
        savedGifts = heavy;
    }

    @Override
    public int loadGifts() {
        return savedGifts;
    }

    @Override
    public int loadLevel() {
        return savedLevel;
    }

    @Override
    public void quit() {
        System.out.println(String.format("%7.2fs  quit requested", time));
    }

    private void start() {
        world = new World(this);
        world.skipSplash();
        int h = Math.max(1, screenH * World.W / screenW);
        frame = new Px(World.W, h);
        world.resize(h);
    }

    private void step() {
        long t0 = System.nanoTime();
        world.update(DT);
        world.draw(frame);
        long ns = System.nanoTime() - t0;
        sumNs += ns;
        if (ns > worstNs && frames > 5) worstNs = ns;
        time += DT;
        frames++;
    }

    private void run(float seconds) {
        int n = Math.max(1, Math.round(seconds / DT));
        for (int i = 0; i < n; i++) step();
    }

    private void play(File scene) throws IOException {
        start();
        BufferedReader r = new BufferedReader(new FileReader(scene));
        String line;
        int no = 0;
        while ((line = r.readLine()) != null) {
            no++;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            try {
                exec(p);
            } catch (RuntimeException e) {
                System.out.println("scene line " + no + ": " + line);
                throw e;
            }
        }
        r.close();
        System.out.println(String.format("done: %d frames, %.1fs of game time, %d shots, %d buzzes, %d sounds",
                frames, time, shots, buzzes, sounds.size()));
        System.out.println(String.format("frame cost on this machine: mean %.2f ms, worst %.2f ms",
                sumNs / 1e6 / Math.max(1, frames), worstNs / 1e6));
        writeSound();
    }

    /**
     * Plays the golem fight many times with a player of a given aim (spread in game pixels)
     * and reaction (seconds before throwing again): knocks down cars in flight first, then
     * goes for the knees, then the head. Prints how often it wins and how.
     */
    private void bossBot(int runs, float spread, float reaction) {
        int wins = 0, armorLeft = 0;
        java.util.Random r = new java.util.Random(1);
        StringBuilder lost = new StringBuilder();
        for (int run = 0; run < runs; run++) {
            World w = new World(this);
            w.skipSplash();
            int hh = Math.max(1, screenH * World.W / screenW);
            w.resize(hh);
            w.startLevel(30, false);
            w.standBoss();
            float t = 0f, ready = 1f;
            int throwsN = 0;
            while (w.bossResult() == 0 && t < 150f) {
                w.update(DT);
                t += DT;
                if (w.hs == World.HAND && t >= ready) {
                    float[] pick = w.standPick(true);
                    if (pick != null) {
                        w.standBossThrow(pick[0] + (float) r.nextGaussian() * spread, pick[1] + (float) r.nextGaussian() * spread);
                        throwsN++;
                    }
                }
                if (w.hs != World.HAND) ready = t + reaction;
            }
            String st = w.bossState();
            for (int i = 0; i < 200; i++) w.update(DT);
            if (w.bossResult() == 1 || w.golem == null && w.level == 31) {
                wins++;
                armorLeft += w.armor;
            } else lost.append(String.format("    lost at %.0fs after %d throws: %s%n", t, throwsN, st));
            System.out.println(String.format("run %d: %s, %.0fs, %d throws, %s", run, w.level == 31 ? "WON" : "lost", t, throwsN, st));
        }
        System.out.println(String.format("golem: %d of %d won, spread %.1f px, reaction %.2fs, armor left on wins %.1f",
                wins, runs, spread, reaction, wins > 0 ? armorLeft / (float) wins : 0f));
    }

    private void exec(String[] p) throws IOException {
        String c = p[0];
        if (c.equals("screen")) {
            screenW = Integer.parseInt(p[1]);
            screenH = Integer.parseInt(p[2]);
            start();
        } else if (c.equals("wait")) {
            run(f(p[1]));
        } else if (c.equals("down")) {
            world.touch(World.DOWN, f(p[1]), f(p[2]));
            step();
        } else if (c.equals("move")) {
            world.touch(World.MOVE, f(p[1]), f(p[2]));
            step();
        } else if (c.equals("up")) {
            world.touch(World.UP, f(p[1]), f(p[2]));
            step();
        } else if (c.equals("tap")) {
            world.touch(World.DOWN, f(p[1]), f(p[2]));
            step();
            world.touch(World.UP, f(p[1]), f(p[2]));
            step();
        } else if (c.equals("drag")) {
            float x0 = f(p[1]), y0 = f(p[2]), x1 = f(p[3]), y1 = f(p[4]);
            int n = Math.max(2, Math.round(f(p[5]) / DT));
            world.touch(World.DOWN, x0, y0);
            step();
            for (int i = 1; i <= n; i++) {
                float k = i / (float) n;
                world.touch(World.MOVE, x0 + (x1 - x0) * k, y0 + (y1 - y0) * k);
                step();
            }
            world.touch(World.UP, x1, y1);
            step();
        } else if (c.equals("win")) {
            world.standWin(Integer.parseInt(p[1]));
        } else if (c.equals("lose")) {
            world.stars = 0;
        } else if (c.equals("level")) {
            savedLevel = Integer.parseInt(p[1]);
            world.startLevel(savedLevel, false);
        } else if (c.equals("state")) {
            System.out.println(String.format("%7.2fs  state %d, hammer %d, stars %d, on plinth %.2f, level %d, heavy %d%s, car damage %d",
                    time, world.state, world.hs, world.stars, world.onPlinth(), world.level + 1, world.heavyLeft,
                    world.heavyOn ? " in hand" : "", world.carDamage()) + ", fails " + world.fails + (world.goldOn ? " GOLD" : "") + (world.goldCube != null ? String.format(" cube at %.2f %.2f %.2f", world.goldCube.px, world.goldCube.py, world.goldCube.pz) : ""));
        } else if (c.equals("throwat")) {
            float[] o = world.screenOf(f(p[1]), f(p[2]), f(p[3]));
            world.throwAt(o[0], o[1], f(p[4]));
            step();
        } else if (c.equals("rowbot")) {
            // throws at whichever row stands more, the front one first, until won or out of stars
            float speed = f(p[1]), spread = p.length > 2 ? f(p[2]) : 0f;
            java.util.Random hand = new java.util.Random(world.level * 31L + 7L);
            float t0 = time, ready = time + 0.3f;
            while (time - t0 < 70f && world.state == World.PLAY) {
                if (world.hs == World.HAND && time >= ready) {
                    int row = !world.rows || world.onPlinth(0) > 1f - world.clearShare() ? 0 : 1;
                    if (world.standThrowRow(row, speed, (float) hand.nextGaussian() * spread, (float) hand.nextGaussian() * spread))
                        System.out.println(String.format("%7.2fs  throw at row %d, standing front %.2f back %.2f",
                                time, row, world.onPlinth(0), world.onPlinth(1)));
                    else break;
                }
                if (world.hs != World.HAND) ready = time + 1.6f;
                step();
            }
            for (int i = 0; i < 30; i++) step();
            System.out.println(String.format("%7.2fs  rowbot: state %d, stars %d, front %.2f, back %.2f, level %d",
                    time, world.state, world.stars, world.onPlinth(0), world.onPlinth(1), world.level + 1));
        } else if (c.equals("fight")) {
            // the fight bot's hand in this scene: so many seconds, this much spread
            float secs = f(p[1]), spread = f(p[2]);
            java.util.Random r = new java.util.Random(3);
            float ready = 0f, t0 = time;
            while (time - t0 < secs && world.bossResult() == 0) {
                if (world.hs == World.HAND && time - t0 >= ready) {
                    float[] pick = world.standPick(true);
                    if (pick != null) world.standBossThrow(pick[0] + (float) r.nextGaussian() * spread,
                            pick[1] + (float) r.nextGaussian() * spread);
                }
                if (world.hs != World.HAND) ready = time - t0 + 0.5f;
                step();
            }
            System.out.println(String.format("%7.2fs  %s", time, world.bossState()));
        } else if (c.equals("bossbot")) {
            bossBot(Integer.parseInt(p[1]), f(p[2]), f(p[3]));
        } else if (c.equals("press")) {
            float[] o = world.standCallButton();
            System.out.println(String.format("%7.2fs  call button at %.1f, %.1f", time, o[0], o[1]));
            world.touch(World.DOWN, o[0], o[1]);
            step();
            world.touch(World.UP, o[0], o[1]);
            step();
        } else if (c.equals("golemz")) {
            world.standGolemAt(f(p[1]));
        } else if (c.equals("boss")) {
            world.standBoss();
        } else if (c.equals("ball")) {
            world.standBall(f(p[1]), f(p[2]), f(p[3]));
        } else if (c.equals("luggage")) {
            // grow suitcases (1), mattresses (2) or both (3) into every building from now on
            Builder.force = Integer.parseInt(p[1]);
            world.standForget();
        } else if (c.equals("alley")) {
            world.startAlley();
            step();
        } else if (c.equals("splash")) {
            // the opening picture, as when the game starts
            world.showSplash();
            step();
        } else if (c.equals("skip")) {
            // stone skipping with the ice golem, out at sea
            world.startSkip();
            step();
        } else if (c.equals("hub")) {
            // the crossroads of five cubes, as come to through the portal from the ice
            world.startHub();
            step();
        } else if (c.equals("bowl")) {
            // wait for the alley to be ready for a ball, then roll one: across, speed, angle
            float t0 = time;
            while (world.alley != null && world.alley.phase != Alley.AIM && time - t0 < 30f) step();
            if (world.alley != null) world.alley.roll(f(p[1]), f(p[2]), f(p[3]));
            step();
            System.out.println(String.format("%7.2fs  rolled, round %d ball %d", time, world.alley == null ? -1 : world.alley.round,
                    world.alley == null ? -1 : world.alley.ball));
        } else if (c.equals("alleyuntil")) {
            // wait until the alley reaches a phase, or is over
            int want = Integer.parseInt(p[1]);
            float t0 = time;
            while (world.alley != null && world.alley.phase != want && time - t0 < 30f) step();
        } else if (c.equals("cube")) {
            // throw the hammer at one of the five cubes: 0 middle, 1 forward, 2 right, 3 back, 4 left
            float t0 = time;
            while (world.alley != null && !world.alley.signsReady() && time - t0 < 30f) step();
            if (world.alley != null) world.alley.strike(Integer.parseInt(p[1]));
            step();
        } else if (c.equals("cubesdue")) {
            world.cubesDue = Integer.parseInt(p[1]);
        } else if (c.equals("alleyneed")) {
            if (world.alley != null) world.alley.need = f(p[1]);
        } else if (c.equals("alleystate")) {
            Alley a = world.alley;
            if (a == null) System.out.println(String.format("%7.2fs  no alley; heavy %d, golden cubes %d, due %d", time, world.heavyLeft, world.cubes, world.cubesDue));
            else System.out.println(String.format("%7.2fs  alley phase %d round %d ball %d won %d standing %.2f%s; heavy %d",
                    time, a.phase, a.round, a.ball, a.won, a.standing, a.lost ? " LOST" : "", world.heavyLeft)
                    + (a.balls.isEmpty() ? "" : String.format(", stone at %.1f %.2f %.1f (v %.1f), skips %d", a.balls.get(a.balls.size() - 1).px,
                    a.balls.get(a.balls.size() - 1).py, a.balls.get(a.balls.size() - 1).pz, a.vOf(a.balls.get(a.balls.size() - 1).px, a.balls.get(a.balls.size() - 1).pz), a.skips)));
        } else if (c.equals("tear")) {
            world.standTear();
        } else if (c.equals("fliers")) {
            world.standFliers(Integer.parseInt(p[1]));
        } else if (c.equals("goldseen")) {
            world.goldSeen = true;
        } else if (c.equals("heavy")) {
            world.heavyLeft = World.HEAVY_GIFT;
            world.heavyOn = true;
        } else if (c.equals("downahead")) {
            // a finger down where the iceberg's middle would be, shifted so many meters across and up
            float[] o = world.standPointAt(f(p[1]), f(p[2]));
            world.touch(World.DOWN, o[0], o[1]);
            step();
            System.out.println(String.format("%7.2fs  finger down at %.1f, %.1f", time, o[0], o[1]));
        } else if (c.equals("ring")) {
            float[] a = world.aimPoint(), w = world.standRingWorld();
            System.out.println(String.format("%7.2fs  ring at %.1f, %.1f on screen; %.2f m across, %.2f m up from the iceberg's middle",
                    time, a[0], a[1], w[0], w[1]));
        } else if (c.equals("aim")) {
            float[] a = world.aimPoint();
            System.out.println(String.format("%7.2fs  aim at %.1f, %.1f", time, a[0], a[1]));
        } else if (c.equals("back")) {
            world.back();
            step();
        } else if (c.equals("shot")) {
            save(p[1]);
        } else {
            throw new IllegalArgumentException("unknown command: " + c);
        }
    }

    /** All the sounds of the scene, mixed as the phone would, into sound.wav. */
    private void writeSound() throws IOException {
        Synth syn = new Synth();
        int total = (int) ((time + 3f) * Synth.RATE);
        short[] pcm = new short[total];
        short[] chunk = new short[256];
        int si = 0;
        for (int pos = 0; pos < total; pos += chunk.length) {
            float t = pos / (float) Synth.RATE;
            while (si < sounds.size() && sounds.get(si)[0] <= t) {
                float[] e = sounds.get(si++);
                syn.play((int) e[1], e[2], e[3]);
            }
            int n = Math.min(chunk.length, total - pos);
            syn.mix(chunk, n);
            System.arraycopy(chunk, 0, pcm, pos, n);
        }
        java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.FileOutputStream(new File(outDir, "sound.wav")));
        int bytes = total * 2;
        o.writeBytes("RIFF");
        o.writeInt(Integer.reverseBytes(36 + bytes));
        o.writeBytes("WAVEfmt ");
        o.writeInt(Integer.reverseBytes(16));
        o.writeShort(Short.reverseBytes((short) 1));
        o.writeShort(Short.reverseBytes((short) 1));
        o.writeInt(Integer.reverseBytes(Synth.RATE));
        o.writeInt(Integer.reverseBytes(Synth.RATE * 2));
        o.writeShort(Short.reverseBytes((short) 2));
        o.writeShort(Short.reverseBytes((short) 16));
        o.writeBytes("data");
        o.writeInt(Integer.reverseBytes(bytes));
        for (short v : pcm) o.writeShort(Short.reverseBytes(v));
        o.close();
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    /** The frame scaled up by a whole factor with hard edges, as the phone shows it. */
    private void save(String name) throws IOException {
        int k = Math.max(1, screenW / World.W);
        BufferedImage img = new BufferedImage(frame.w * k, frame.h * k, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < frame.h; y++)
            for (int x = 0; x < frame.w; x++) {
                int c = frame.a[y * frame.w + x] | 0xFF000000;
                for (int j = 0; j < k; j++)
                    for (int i = 0; i < k; i++) img.setRGB(x * k + i, y * k + j, c);
            }
        File f = new File(outDir, name + ".png");
        ImageIO.write(img, "png", f);
        shots++;
        System.out.println(String.format("%7.2fs  %s", time, f.getPath()));
    }
}
