package com.shumtugle.torodki;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Streams the synthesizer's mix to the speaker on a thread of its own. */
final class Sound implements Runnable {
    final Synth synth = new Synth();
    private volatile boolean running;
    private Thread thread;

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "sound");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
    }

    @Override
    public void run() {
        AudioTrack t;
        try {
            int min = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(Synth.RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            t.play();
        } catch (RuntimeException e) {
            running = false;
            return;
        }
        short[] buf = new short[512];
        try {
            while (running) {
                synth.mix(buf, buf.length);
                t.write(buf, 0, buf.length);
            }
        } finally {
            try {
                t.pause();
                t.flush();
            } catch (RuntimeException ignored) {
            }
            t.release();
        }
    }
}
