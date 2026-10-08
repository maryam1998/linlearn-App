package com.linglearn.app.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Activity ی نامرئی برای ضبطِ صدای کاربر (تمرینِ shadowing).
 * از BubbleService با Intent باز می‌شه؛ با stopIfRunning() ضبط تموم و فایل به BubbleService.shadowDone می‌ره.
 *
 * 🔉 علاوه بر میکروفون، اگر «ضبطِ صدای سیستم» (MediaProjection) در دسترس باشد، صدای در حالِ پخشِ گوشی
 *    (مثلاً ویدیوی یوتیوب) هم با ولومِ کم داخلِ همان فایل مخلوط می‌شود تا موقعِ پخشِ دوباره، هم صدای تو و هم
 *    صدای اصلی شنیده شود. بدونِ MediaProjection فقط میکروفون ضبط می‌شود (مثلِ قبل).
 */
public class ShadowRecordActivity extends Activity {

    private static final String TAG = "ShadowRec";
    private static final int RATE = 44100;
    /** ولومِ صدای گوشی نسبت به صدای تو (۰ تا ۱). کم نگه داشته شده تا صدای خودت واضح بماند. */
    private static final float SYS_GAIN = 0.30f;

    private static volatile ShadowRecordActivity current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioRecord mic, sys;
    private File outFile;
    private Thread worker;
    private volatile boolean stopFlag = false;
    private boolean finishedReported = false;

    static void stopIfRunning() {
        final ShadowRecordActivity a = current;
        if (a == null) { BubbleService.shadowDone(null); return; }
        a.main.post(a::finishRecording);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } catch (Throwable ignored) {}

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report(null, 2);
            finish();
            return;
        }
        try {
            outFile = new File(getCacheDir(), "shadow_rec.wav");
            if (outFile.exists()) outFile.delete();

            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mic = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic not initialized");
            sys = buildSystemRecord();           // null = صدای گوشی در دسترس نیست
            mic.startRecording();
            if (sys != null) {
                try { sys.startRecording(); }
                catch (Throwable t) { Log.w(TAG, "system capture start failed", t); releaseSys(); }
            }
            Log.i(TAG, "shadow recording started, systemAudio=" + (sys != null));
            worker = new Thread(this::recordLoop, "shadow-rec");
            worker.start();
        } catch (Throwable e) {
            Log.w(TAG, "record start failed", e);
            releaseAll();
            report(null, 1);
            finish();
        }
    }

    /** ضبطِ صدای در حالِ پخشِ گوشی (نه صدای خودِ برنامه)؛ فقط اگر کاربر قبلاً «اجازه‌ی ضبطِ صدای سیستم» را داده باشد. */
    private AudioRecord buildSystemRecord() {
        if (Build.VERSION.SDK_INT < 29) return null;
        MediaProjection mp = BubbleService.shadowProjection();
        if (mp == null) return null;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(android.os.Process.myUid())
                    .build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, RATE / 5 * 2))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Throwable t) {
            Log.w(TAG, "system capture unavailable", t);
        }
        return null;
    }

    private void recordLoop() {
        final int frame = RATE / 50;                         // ۲۰ میلی‌ثانیه
        final short[] m = new short[frame];
        final short[] s = new short[frame];
        final short[] ring = new short[RATE * 2];            // بافرِ ۲ ثانیه‌ایِ صدای گوشی (تا دو رشته‌ی ضبط هم‌گام شوند)
        int rHead = 0, rCount = 0;
        final byte[] out = new byte[frame * 2];
        long samples = 0;
        boolean ok = false;
        BufferedOutputStream os = null;
        try {
            os = new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16);
            os.write(new byte[44]);                          // جای هدرِ WAV؛ آخرِ کار پر می‌شود
            while (!stopFlag) {
                int n = mic.read(m, 0, frame);
                if (n < 0) throw new IllegalStateException("mic read " + n);
                if (n == 0) continue;
                // صدای گوشی را هرچه آمده بریز توی بافر (بدونِ بلاک شدن)
                if (sys != null) {
                    int g;
                    while ((g = sys.read(s, 0, frame, AudioRecord.READ_NON_BLOCKING)) > 0) {
                        for (int i = 0; i < g; i++) {
                            if (rCount == ring.length) { rHead = (rHead + 1) % ring.length; rCount--; }   // پر شد: قدیمی‌ترین را دور بریز
                            ring[(rHead + rCount) % ring.length] = s[i];
                            rCount++;
                        }
                    }
                }
                for (int i = 0; i < n; i++) {
                    int v = m[i];
                    if (rCount > 0) {
                        v += (int) (ring[rHead] * SYS_GAIN);
                        rHead = (rHead + 1) % ring.length;
                        rCount--;
                    }
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    out[i * 2] = (byte) (v & 0xFF);
                    out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                }
                os.write(out, 0, n * 2);
                samples += n;
            }
            ok = true;
        } catch (Throwable e) {
            Log.w(TAG, "record loop failed", e);
        } finally {
            try { if (os != null) os.close(); } catch (Throwable ignored) {}
            releaseAll();
        }
        String path = null;
        if (ok && samples > RATE / 4) {                      // کمتر از ۰٫۲۵ ثانیه = ضبطِ معتبر نیست
            try { writeWavHeader(outFile, samples * 2); path = outFile.getAbsolutePath(); }
            catch (Throwable t) { Log.w(TAG, "wav header failed", t); }
        }
        final String fp = path;
        final boolean fok = ok;
        main.post(() -> {
            if (fok) report(fp, 0); else report(null, 1);
            finish();
        });
    }

    private static void writeWavHeader(File f, long dataLen) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long total = dataLen + 36;
            long byteRate = (long) RATE * 2;
            byte[] h = new byte[44];
            put(h, 0, "RIFF"); le32(h, 4, total); put(h, 8, "WAVE");
            put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1);
            le32(h, 24, RATE); le32(h, 28, byteRate); le16(h, 32, 2); le16(h, 34, 16);
            put(h, 36, "data"); le32(h, 40, dataLen);
            raf.seek(0);
            raf.write(h);
        }
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) (v & 0xFF); b[o + 1] = (byte) ((v >> 8) & 0xFF); }
    private static void le32(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF); b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF); b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private void finishRecording() {
        if (worker == null) { report(null, 1); finish(); return; }
        stopFlag = true;                                     // رشته‌ی ضبط فایل را می‌بندد و خودش report می‌کند
    }

    private void releaseSys() {
        AudioRecord r = sys; sys = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private void releaseAll() {
        AudioRecord r = mic; mic = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
            try { r.release(); } catch (Throwable ignored) {}
        }
        releaseSys();
    }

    /** err: 0 = ok, 1 = خطای ضبط، 2 = نبودِ مجوزِ میکروفون */
    private void report(String path, int err) {
        if (finishedReported) return;
        finishedReported = true;
        if (err != 0) BubbleService.shadowFailed(err);
        else BubbleService.shadowDone(path);
    }

    @Override
    protected void onDestroy() {
        stopFlag = true;
        if (worker == null) releaseAll();
        if (!finishedReported && worker == null) report(null, 1);
        if (current == this) current = null;
        super.onDestroy();
    }
}
