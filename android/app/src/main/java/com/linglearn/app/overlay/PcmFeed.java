package com.linglearn.app.overlay;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bridges captured SYSTEM audio (16 kHz, mono, PCM16) to Android's SpeechRecognizer.
 *
 * BubbleService captures the phone's playback audio and calls write().
 * SpeechHostActivity calls open() before every recognition session; the returned read end is
 * passed to the recognizer through RecognizerIntent.EXTRA_AUDIO_SOURCE, so the recognizer
 * "listens" to the playback audio instead of the microphone.
 *
 * A small writer thread per session keeps the capture thread from ever blocking on the pipe.
 */
final class PcmFeed {

    private static final String TAG = "PcmFeed";
    private static final int QUEUE_FRAMES = 150; // ~3 s of 20 ms frames

    private static final Object LOCK = new Object();
    private static volatile Session current;

    private PcmFeed() {}

    private static final class Session {
        final OutputStream out;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_FRAMES);
        volatile boolean dead = false;

        Session(ParcelFileDescriptor writeEnd) {
            out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
            Thread t = new Thread(this::pump, "pcm-feed");
            t.setDaemon(true);
            t.start();
        }

        private void pump() {
            try {
                while (!dead) {
                    byte[] b = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (b != null) out.write(b);
                }
            } catch (Exception ignored) {
                // reader closed the pipe (session ended) - normal
            } finally {
                closeQuietly();
            }
        }

        void closeQuietly() {
            try { out.close(); } catch (Exception ignored) {}
        }

        void kill() {
            dead = true;
            queue.clear();
            // best effort: unblock a pump stuck in write()
            new Thread(this::closeQuietly, "pcm-feed-close").start();
        }
    }

    /** Starts a fresh pipe for a new recognition session. Returns the READ end (or null). */
    static ParcelFileDescriptor open() {
        try {
            ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
            Session s = new Session(p[1]);
            Session old;
            synchronized (LOCK) { old = current; current = s; }
            if (old != null) old.kill();
            return p[0];
        } catch (IOException e) {
            Log.w(TAG, "createPipe failed", e);
            return null;
        }
    }

    /** Called from the capture thread with 16 kHz mono PCM16 little-endian bytes. */
    static void write(byte[] buf, int len) {
        Session s = current;
        if (s == null || len <= 0) return;
        byte[] copy = new byte[len];
        System.arraycopy(buf, 0, copy, 0, len);
        if (!s.queue.offer(copy)) {      // recognizer too slow: drop the oldest frame
            s.queue.poll();
            s.queue.offer(copy);
        }
    }

    /** Stops feeding (no active session). */
    static void close() {
        Session old;
        synchronized (LOCK) { old = current; current = null; }
        if (old != null) old.kill();
    }
}
