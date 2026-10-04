package com.linglearn.app.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * تشخیص گفتارِ آفلاین با Whisper (برای آهنگ‌ها). Whisper جریانی نیست، پس:
 *  - صدا توی پنجره‌های ~۸ تا ۱۶ ثانیه جمع می‌شه (برش روی ساکت‌ترین نقطه، تا وسطِ کلمه قطع نشه)؛
 *  - هر پنجره توی یک نخِ جدا decode می‌شه تا feedLoop (ضبطِ صدا) هیچ‌وقت بلاک نشه؛
 *  - نتیجه با تأخیرِ چند ثانیه به BubbleService.asrFinal می‌ره (partial نداریم).
 * نکته: Whisper همیشه ۳۰ ثانیه رو پردازش می‌کنه، پس پنجره‌ی کوتاه‌تر سریع‌تر نمی‌شه.
 */
final class WhisperEngine implements PcmSink {

    private static final String TAG = "WhisperEngine";
    private static final int SR = 16000;
    private static final int FRAME = SR / 10;              // ۱۰۰ms
    private static final int MIN_WIN = 8 * SR;
    private static final int MAX_WIN = 16 * SR;
    private static final int SEARCH_BACK = 4 * SR;         // برش توی ۴ ثانیه‌ی آخرِ پنجره
    private static final int MAX_QUEUE = 2;                // عقب افتادیم → قدیمی‌ترین پنجره دور ریخته می‌شه
    private static final double SILENT_RMS = 0.004;        // پنجره‌ی تقریباً ساکت decode نمی‌شه

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private final ArrayDeque<float[]> queue = new ArrayDeque<>();
    private final float[] buf = new float[MAX_WIN + SR];
    private int len = 0;
    private boolean released = false;
    private boolean failed = false;
    private String lastText = "";

    private final OfflineRecognizer recognizer;   // فقط از نخِ worker استفاده می‌شه
    private final Thread worker;

    private WhisperEngine(OfflineRecognizer r) {
        this.recognizer = r;
        this.worker = new Thread(this::runWorker, "whisper-decode");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    static boolean isAvailable(Context ctx, String model) {
        return WhisperModelManager.getModelDir(ctx, model) != null;
    }

    /** lang: کدِ دو حرفی (en, fa, ...) یا "auto"/null برای تشخیصِ خودکار. */
    static WhisperEngine create(Context ctx, String model, String lang) {
        File dir = WhisperModelManager.getModelDir(ctx, model);
        if (dir == null) return null;
        OfflineRecognizer rec = null;
        try {
            String l = SherpaModelManager.normalize(lang);
            if (l == null || "auto".equals(l)) l = "";

            OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
            w.setEncoder(new File(dir, WhisperModelManager.encoderFile(model)).getAbsolutePath());
            w.setDecoder(new File(dir, WhisperModelManager.decoderFile(model)).getAbsolutePath());
            w.setLanguage(l);
            w.setTask("transcribe");

            OfflineModelConfig mc = new OfflineModelConfig();
            mc.setWhisper(w);
            mc.setTokens(new File(dir, WhisperModelManager.tokensFile(model)).getAbsolutePath());
            mc.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            mc.setDebug(false);
            mc.setProvider("cpu");

            FeatureConfig fc = new FeatureConfig();
            fc.setSampleRate(SR);
            fc.setFeatureDim(80);

            OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
            cfg.setFeatConfig(fc);
            cfg.setModelConfig(mc);
            cfg.setDecodingMethod("greedy_search");

            rec = new OfflineRecognizer(null, cfg);
            Log.i(TAG, "whisper " + model + " loaded, lang='" + l + "'");
            return new WhisperEngine(rec);
        } catch (Throwable e) {
            Log.e(TAG, "cannot load whisper " + model, e);
            if (rec != null) { try { rec.release(); } catch (Throwable ignored) {} }
            return null;
        }
    }

    // ------------------------------------------------------------------ ورودی صدا

    @Override
    public void accept(byte[] pcm, int bytes) {
        if (pcm == null || bytes < 2) return;
        synchronized (lock) {
            if (released || failed) return;
            int n = bytes / 2;
            if (len + n > buf.length) n = buf.length - len;     // دفاعی؛ عملاً پیش نمی‌آد
            for (int i = 0; i < n; i++) {
                short s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
                buf[len + i] = s / 32768f;
            }
            len += n;

            if (len >= MAX_WIN) {
                cutAtQuietestPoint();
            } else if (len >= MIN_WIN && tailIsQuiet()) {
                enqueue(len);
            }
        }
    }

    /** ۵۰۰ms آخر خیلی ساکت‌تر از میانگینِ پنجره‌ست؟ (مکثِ بینِ دو خط) */
    private boolean tailIsQuiet() {
        double tail = rms(len - SR / 2, len);
        double all = rms(0, len);
        return tail < Math.max(0.003, all * 0.25);
    }

    private void cutAtQuietestPoint() {
        int from = Math.max(MIN_WIN, len - SEARCH_BACK);
        int best = len, bestFrameStart = len - FRAME;
        double bestRms = Double.MAX_VALUE;
        for (int s = from; s + FRAME <= len; s += FRAME) {
            double r = rms(s, s + FRAME);
            if (r < bestRms) { bestRms = r; bestFrameStart = s; }
        }
        best = Math.min(len, bestFrameStart + FRAME);
        enqueue(best);
    }

    /** n نمونه‌ی اول رو توی صفِ decode می‌ذاره و باقی‌مونده رو به ابتدای بافر می‌بره. (زیرِ lock) */
    private void enqueue(int n) {
        float[] win = java.util.Arrays.copyOf(buf, n);
        int rest = len - n;
        if (rest > 0) System.arraycopy(buf, n, buf, 0, rest);
        len = Math.max(0, rest);

        if (rms(win, 0, win.length) < SILENT_RMS) return;       // ساکت → decode نکن
        while (queue.size() >= MAX_QUEUE) {
            queue.pollFirst();
            Log.w(TAG, "decoder is behind real-time; dropped oldest window");
        }
        queue.addLast(win);
        lock.notifyAll();
    }

    private double rms(int from, int to) { return rms(buf, from, to); }

    private static double rms(float[] a, int from, int to) {
        if (to <= from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += (double) a[i] * a[i];
        return Math.sqrt(sum / (to - from));
    }

    // ------------------------------------------------------------------ decode (نخِ worker)

    private void runWorker() {
        try {
            while (true) {
                float[] win;
                synchronized (lock) {
                    while (!released && queue.isEmpty()) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (released) return;
                    win = queue.pollFirst();
                }
                if (win == null) continue;
                String text = decode(win);
                if (text == null) {
                    synchronized (lock) { failed = true; }
                    MAIN.post(BubbleService::asrFallback);
                    return;
                }
                if (text.isEmpty()) continue;
                synchronized (lock) { if (released) return; }
                final String t = text;
                MAIN.post(() -> BubbleService.asrFinal(t));
            }
        } finally {
            try { recognizer.release(); } catch (Throwable e) { Log.w(TAG, "release", e); }
        }
    }

    /** متنِ پاک‌شده؛ "" = چیزی نبود/هذیان؛ null = خطا. */
    private String decode(float[] samples) {
        OfflineStream st = null;
        try {
            st = recognizer.createStream();
            st.acceptWaveform(samples, SR);
            recognizer.decode(st);
            String text = clean(recognizer.getResult(st).getText());
            if (text.isEmpty() || looksLikeLoop(text)) return "";
            if (text.equalsIgnoreCase(lastText)) return "";
            lastText = text;
            return text;
        } catch (Throwable e) {
            Log.e(TAG, "decode failed", e);
            return null;
        } finally {
            if (st != null) { try { st.release(); } catch (Throwable ignored) {} }
        }
    }

    private static String clean(String t) {
        if (t == null) return "";
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Whisper روی موسیقی گاهی یه عبارت رو بی‌نهایت تکرار می‌کنه؛ اون خروجی رو دور بریز. */
    static boolean looksLikeLoop(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        if (s.length() < 24) return false;
        Set<String> distinct = new HashSet<>();
        int total = 0;
        for (int i = 0; i + 3 <= s.length(); i++) {
            distinct.add(s.substring(i, i + 3));
            total++;
        }
        return total >= 20 && distinct.size() < total * 0.2;
    }

    @Override
    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            queue.clear();
            len = 0;
            lock.notifyAll();
        }
        // recognizer رو خودِ worker (بعد از تمام شدنِ decode ی جاری) آزاد می‌کنه.
    }
}
