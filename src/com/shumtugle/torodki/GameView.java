package com.shumtugle.torodki;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * The phone side of the game loop. It owns a thread, queues touches from the UI
 * thread and hands them to the world on the game thread, so game state is only
 * ever touched there. The world paints a small frame, World.W pixels wide, which
 * is scaled up to the screen without smoothing: every game pixel a crisp square.
 */
final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private final World world;
    private Thread thread;
    private volatile boolean running;
    private int viewW, viewH, H;
    private Px frame;
    private Bitmap fb;
    private final Paint blit = new Paint();
    private final Rect dst = new Rect();

    // touches, handed from the UI thread to the game thread
    private final float[] evX = new float[64], evY = new float[64];
    private final int[] evType = new int[64];
    private int evCount;
    private boolean backPending;

    GameView(MainActivity act) {
        super(act);
        world = new World(act);
        getHolder().addCallback(this);
        setFocusable(true);
        blit.setFilterBitmap(false);
    }

    void resumeLoop() {
        if (running) return;
        running = true;
        thread = new Thread(this, "game");
        thread.start();
    }

    void pauseLoop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
    }

    void backPressed() {
        synchronized (this) {
            backPending = true;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        synchronized (this) {
            viewW = w;
            viewH = hh;
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        int type = a == MotionEvent.ACTION_DOWN ? World.DOWN : a == MotionEvent.ACTION_MOVE ? World.MOVE
                : a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL ? World.UP : -1;
        if (type < 0) return true;
        synchronized (this) {
            if (evCount < evX.length && viewW > 0) {
                // into game pixels
                evX[evCount] = e.getX() * World.W / viewW;
                evY[evCount] = e.getY() * World.W / viewW;
                evType[evCount] = type;
                evCount++;
            }
        }
        return true;
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            if (!ensureBuffer()) {
                sleep(16);
                continue;
            }
            processEvents();
            world.update(dt);
            world.draw(frame);
            present();
            long spent = (System.nanoTime() - now) / 1000000L;
            if (spent < 16) sleep(16 - spent);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** The frame: W wide, as tall as the screen's shape allows. */
    private boolean ensureBuffer() {
        int vw, vh;
        synchronized (this) {
            vw = viewW;
            vh = viewH;
        }
        if (vw <= 0 || vh <= 0) return false;
        int h = Math.max(1, vh * World.W / vw);
        if (frame == null || H != h) {
            H = h;
            frame = new Px(World.W, H);
            fb = Bitmap.createBitmap(World.W, H, Bitmap.Config.ARGB_8888);
            world.resize(H);
        }
        return true;
    }

    private void processEvents() {
        boolean back;
        synchronized (this) {
            for (int i = 0; i < evCount; i++) world.touch(evType[i], evX[i], evY[i]);
            evCount = 0;
            back = backPending;
            backPending = false;
        }
        if (back) world.back();
    }

    /** The small frame, scaled up to the screen with hard edges. */
    private void present() {
        fb.setPixels(frame.a, 0, World.W, 0, 0, World.W, H);
        SurfaceHolder h = getHolder();
        Canvas c = h.lockCanvas();
        if (c == null) return;
        try {
            dst.set(0, 0, c.getWidth(), c.getWidth() * H / World.W);
            c.drawColor(0xFF000000);
            c.drawBitmap(fb, null, dst, blit);
        } finally {
            h.unlockCanvasAndPost(c);
        }
    }
}
