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
 * تشخیص گفتارِ آفلاین (برای آهنگ‌ها). بسته ذاتاً جریانی نیست، پس «استریمِ شبه‌زنده» داریم:
 *  - هر ~۲ تا ۶ ثانیه روی صدای جمع‌شده یک پیش‌نمایش decode می‌شه و متن حین پخش (بدونِ نیاز به مکث) می‌آد؛
 *  - وقتی مکث/طولِ کافی رسید، همون بخش «نهایی» می‌شه (دقیق‌تر) و جایگزینِ پیش‌نمایش می‌شه؛
 *  - جزئیاتِ پنجره‌های نهایی:
 *  - صدا توی پنجره‌های کوتاهِ تطبیقی (~۳ تا ۱۰ ثانیه، بسته به سرعتِ گوشی) جمع می‌شه
 *    (برش روی مکث/ساکت‌ترین نقطه، تا وسطِ کلمه قطع نشه)؛
 *  - هر پنجره توی یک نخِ جدا decode می‌شه تا feedLoop (ضبطِ صدا) هیچ‌وقت بلاک نشه؛
 *  - نتیجه با تأخیرِ چند ثانیه به BubbleService.asrFinal می‌ره (partial نداریم).
 * پنجره‌ی حداقل خودکار با زمانِ واقعیِ decode تنظیم می‌شه: گوشیِ سریع → تأخیرِ کم؛ گوشیِ کند → پنجره‌ی
 * بزرگ‌تر تا decode عقب نیفته و صدا دور ریخته نشه.
 */
final class WhisperEngine implements PcmSink {

    private static final String TAG = "WhisperEngine";
    private static final int SR = 16000;
    private static final int FRAME = SR / 10;              // ۱۰۰ms
    private static final int PARTIAL_MIN = 2 * SR;         // اولین «پیش‌نمایشِ زنده» بعد از ۲ ثانیه صدا
    private static final long PARTIAL_MIN_GAP_MS = 1500;   // کمترین فاصله‌ی دو پیش‌نمایش
    private static final long PARTIAL_MAX_GAP_MS = 6000;
    private static final int MIN_WIN_FLOOR = 5 * SR;       // کمترین طولِ پنجره‌ی «نهایی» (پیش‌نمایش‌ها زودتر متن رو نشون می‌دن)
    private static final int MIN_WIN_CEIL = 9 * SR;        // بیشترین مقدارِ «حداقلِ تطبیقی»
    private static final int MAX_WIN = 12 * SR;
    private static final int SEARCH_BACK = 3 * SR;         // برش توی ۳ ثانیه‌ی آخرِ پنجره
    private static final int MAX_QUEUE = 3;                // عقب افتادیم → قدیمی‌ترین پنجره دور ریخته می‌شه
    private static final double SILENT_RMS = 0.004;        // پنجره‌ی تقریباً ساکت decode نمی‌شه

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private final ArrayDeque<float[]> queue = new ArrayDeque<>();
    private final float[] buf = new float[MAX_WIN + SR];
    private int len = 0;
    private boolean released = false;
    private boolean failed = false;
    private int minWin = MIN_WIN_FLOOR;
    // ── استریم زنده: روی صدای در حال جمع‌شدن هر چند ثانیه یک‌بار decode می‌شه و به‌عنوان partial می‌ره
    private float[] partialReq = null;                     // آخرین درخواستِ پیش‌نمایش (فقط آخری مهمه)
    private boolean partialBusy = false;
    private int sincePartial = 0;                          // نمونه‌های رسیده از آخرین درخواستِ پیش‌نمایش
    private int epoch = 0;                                 // با هر برشِ نهایی بالا می‌ره؛ پیش‌نمایشِ کهنه دور ریخته می‌شه
    private boolean partialShown = false;                  // برای این بخش پیش‌نمایشی نشون داده شده؟
    private String lastPartialText = "";                    // زیرِ lock؛ با زمانِ decode تطبیق پیدا می‌کنه
    private double avgDecodeMs = 0;
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
            int cores = Runtime.getRuntime().availableProcessors();
            mc.setNumThreads(Math.max(2, Math.min(4, cores >= 8 ? 4 : cores - 1)));
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

            sincePartial += n;
            if (len >= MAX_WIN) {
                cutAtQuietestPoint();
            } else if (len >= minWin && tailIsQuiet()) {
                enqueue(len);
            } else {
                maybeRequestPartial();
            }
        }
    }

    /** ۳۰۰ms آخر خیلی ساکت‌تر از میانگینِ پنجره‌ست؟ (مکثِ بینِ دو خط) */
    private boolean tailIsQuiet() {
        double tail = rms(len - (SR * 3) / 10, len);
        double all = rms(0, len);
        return tail < Math.max(0.003, all * 0.25);
    }

    private void cutAtQuietestPoint() {
        int from = Math.max(minWin, len - SEARCH_BACK);
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
        epoch++;                       // پیش‌نمایشِ در راهِ این بخش دیگه معتبر نیست؛ نسخه‌ی نهایی می‌آد
        partialReq = null;
        sincePartial = 0;

        if (rms(win, 0, win.length) < SILENT_RMS) return;       // ساکت → decode نکن
        while (queue.size() >= MAX_QUEUE) {
            queue.pollFirst();
            Log.w(TAG, "decoder is behind real-time; dropped oldest window");
        }
        queue.addLast(win);
        lock.notifyAll();
    }

    /** هر ~۲ تا ۶ ثانیه (بسته به سرعتِ گوشی) از صدای جمع‌شده تا الان یک پیش‌نمایشِ متن می‌گیریم. (زیرِ lock) */
    private void maybeRequestPartial() {
        if (partialBusy || partialReq != null || len < PARTIAL_MIN || !queue.isEmpty()) return;
        long gapMs = (long) Math.max(PARTIAL_MIN_GAP_MS, Math.min(PARTIAL_MAX_GAP_MS, avgDecodeMs * 1.3));
        if (sincePartial < gapMs * SR / 1000) return;
        if (rms(0, len) < SILENT_RMS) { sincePartial = 0; return; }       // ساکت → decode نکن
        partialReq = java.util.Arrays.copyOf(buf, len);
        sincePartial = 0;
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
                float[] win = null, part = null;
                int partEpoch = 0;
                synchronized (lock) {
                    while (!released && queue.isEmpty() && partialReq == null) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                    if (released) return;
                    if (!queue.isEmpty()) {
                        win = queue.pollFirst();                    // نسخه‌ی نهایی همیشه اولویت داره
                    } else {
                        part = partialReq; partialReq = null;
                        partialBusy = true;
                        partEpoch = epoch;
                    }
                }
                if (win != null) {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    String text = decode(win, true);
                    adaptWindow(android.os.SystemClock.elapsedRealtime() - t0);
                    if (text == null) {
                        synchronized (lock) { failed = true; }
                        MAIN.post(BubbleService::asrFallback);
                        return;
                    }
                    boolean hadPartial;
                    synchronized (lock) {
                        if (released) return;
                        hadPartial = partialShown;
                        partialShown = false;
                        lastPartialText = "";
                    }
                    if (!text.isEmpty()) {
                        final String t = text;
                        MAIN.post(() -> BubbleService.asrFinal(t));
                    } else if (hadPartial) {
                        MAIN.post(BubbleService::asrCommit);        // نسخه‌ی نهایی چیزِ جدیدی نداشت؛ همون پیش‌نمایش بمونه
                    }
                } else if (part != null) {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    String text = decode(part, false);
                    adaptWindow(android.os.SystemClock.elapsedRealtime() - t0);
                    synchronized (lock) {
                        partialBusy = false;
                        if (released) return;
                        if (text == null) continue;                 // خطای پیش‌نمایش مهم نیست؛ نسخه‌ی نهایی تلاش می‌کنه
                        if (partEpoch != epoch) continue;           // وسطِ decode برشِ نهایی خورد → کهنه‌ست
                        if (text.isEmpty() || text.equals(lastPartialText)) continue;
                        lastPartialText = text;
                        partialShown = true;
                    }
                    final String t = text;
                    MAIN.post(() -> BubbleService.asrPartial(t));
                }
            }
        } finally {
            try { recognizer.release(); } catch (Throwable e) { Log.w(TAG, "release", e); }
        }
    }

    /** حداقلِ طولِ پنجره رو کمی بیشتر از زمانِ واقعیِ decode نگه می‌داره تا صف عقب نیفته. */
    private void adaptWindow(long ms) {
        synchronized (lock) {
            avgDecodeMs = (avgDecodeMs == 0) ? ms : (avgDecodeMs * 0.6 + ms * 0.4);
            long want = (long) (avgDecodeMs * 1.25 * SR / 1000.0);
            minWin = (int) Math.max(MIN_WIN_FLOOR, Math.min(MIN_WIN_CEIL, want));
        }
    }

    /** متنِ پاک‌شده؛ "" = چیزی نبود/هذیان؛ null = خطا. */
    private String decode(float[] samples, boolean isFinal) {
        OfflineStream st = null;
        try {
            st = recognizer.createStream();
            st.acceptWaveform(samples, SR);
            recognizer.decode(st);
            String text = clean(recognizer.getResult(st).getText());
            if (text.isEmpty() || looksLikeLoop(text)) return "";
            if (!isFinal) return text;                       // پیش‌نمایش: dedupe نداره
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

    /** موتور روی موسیقی گاهی یه عبارت رو بی‌نهایت تکرار می‌کنه؛ اون خروجی رو دور بریز. */
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
