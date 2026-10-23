package com.shumtugle.torodki;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** The window: full screen, no bars, screen kept on. Answers the world's requests to the phone. */
public final class MainActivity extends Activity implements World.Host {
    private GameView view;
    private Vibrator vibrator;
    private final Sound sound = new Sound();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        view = new GameView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        sound.start();
        view.resumeLoop();
    }

    @Override
    protected void onPause() {
        view.pauseLoop();
        sound.stop();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideBars();
    }

    @Override
    public void onBackPressed() {
        view.backPressed();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void buzz(int ms) {
        if (vibrator != null && vibrator.hasVibrator() && ms > 0)
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @Override
    public void sound(int id, float gain, float rate) {
        sound.synth.play(id, gain, rate);
    }

    @Override
    public void saveLevel(int level) {
        getSharedPreferences("game", MODE_PRIVATE).edit().putInt("level", level).apply();
    }

    @Override
    public int loadLevel() {
        return getSharedPreferences("game", MODE_PRIVATE).getInt("level", 0);
    }

    @Override
    public void saveGoldSeen(boolean seen) {
        getSharedPreferences("game", MODE_PRIVATE).edit().putBoolean("gold", seen).apply();
    }

    @Override
    public boolean loadGoldSeen() {
        return getSharedPreferences("game", MODE_PRIVATE).getBoolean("gold", false);
    }

    @Override
    public void saveFails(int fails) {
        getSharedPreferences("game", MODE_PRIVATE).edit().putInt("fails", fails).apply();
    }

    @Override
    public int loadFails() {
        return getSharedPreferences("game", MODE_PRIVATE).getInt("fails", 0);
    }

    @Override
    public void saveGifts(int heavy) {
        getSharedPreferences("game", MODE_PRIVATE).edit().putInt("heavy", heavy).apply();
    }

    @Override
    public int loadGifts() {
        return getSharedPreferences("game", MODE_PRIVATE).getInt("heavy", 0);
    }

    @Override
    public byte[] asset(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    @Override
    public void quit() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }
}
