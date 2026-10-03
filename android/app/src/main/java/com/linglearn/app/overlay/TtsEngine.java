package com.linglearn.app.overlay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * موتور TTS (Piper/VITS) — نسخه‌ی سریع:
 *  - پخشِ استریم: همین‌که اولین تکه‌ی صدا ساخته شد پخش شروع می‌شه (منتظرِ ساختنِ کلِ جمله نمی‌مونه)
 *  - AudioTrack هم‌زمان با تولیدِ صدا ساخته می‌شه (نه بعد از اون)
 *  - prefetch: جمله‌ی بعدی پیش‌پیش، موقعِ پخشِ جمله‌ی فعلی ساخته و کش می‌شه
 *  - کشِ صدا: تکرارِ یه جمله (یا A-B) فوری و بدونِ محاسبه‌ی دوباره پخش می‌شه
 *  - warmUp: اولین inference (که کنده) قبل از اولین تپ انجام می‌شه
 */
final class TtsEngine {

    private static final String TAG = "TtsEngine";
    private static final int CACHE_MAX = 8;
    private static final short[] END = new short[0];

    /** نتیجه‌ی پخش: ok=false یعنی شکستِ واقعی (برای fallback)؛ لغو شدن ok=true حساب می‌شه. */
    interface DoneCallback { void onDone(boolean ok); }

    private interface Sink { boolean accept(float[] samples); } // false → توقفِ تولید

    // true → به‌جای generateWithCallback از generate() ی ساده استفاده می‌شه (اگه مسیرِ callback کرش می‌کرد)
    volatile boolean plain = false;

    /** متن رو برای espeak-ng تمیز می‌کنه (علائمِ عربی/فارسی، نیم‌فاصله، ارقام، کاراکترهای کنترلی). */
    static String sanitize(String lang, String t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            switch (c) {
                case '\u060C': sb.append(','); break;          // ،
                case '\u061B': sb.append(';'); break;          // ؛
                case '\u061F': sb.append('?'); break;          // ؟
                case '\u066B': sb.append('.'); break;
                case '\u066C': sb.append(','); break;
                case '\u200C': case '\u200D': case '\u200E': case '\u200F':
                case '\u202A': case '\u202B': case '\u202C': case '\u202D': case '\u202E':
                case '\u2066': case '\u2067': case '\u2068': case '\u2069':
                case '\uFEFF': case '\u0640':
                    sb.append(c == '\u0640' ? "" : " "); break;
                case '\u201C': case '\u201D': case '\u00AB': case '\u00BB': sb.append('"'); break;
                case '\u2018': case '\u2019': sb.append('\''); break;
                case '\u2026': sb.append("..."); break;
                default:
                    if (c >= '\u06F0' && c <= '\u06F9') sb.append((char) ('0' + (c - '\u06F0')));
                    else if (c >= '\u0660' && c <= '\u0669') sb.append((char) ('0' + (c - '\u0660')));
                    else if (c < 32 && c != '\n' && c != '\t') sb.append(' ');
                    else if (c >= '\u064B' && c <= '\u065F') { /* اعراب */ }
                    else sb.append(c);
            }
        }
        String r = sb.toString().replaceAll("\\s+", " ").trim();
        return r;
    }

    private final OfflineTts tts;
    private final int sampleRate;
    private final String lang;

    private volatile boolean released = false;
    private volatile long lastUsed = SystemClock.uptimeMillis();
    private volatile AudioTrack currentTrack = null;
    private volatile String inflightPrefetchKey = null;
    private final AtomicBoolean warmed = new AtomicBoolean(false);
    // با هر stop()/speak ی جدید بالا می‌ره؛ پخش‌های قدیمی خودشون رو متوقف می‌کنن
    private final AtomicInteger epoch = new AtomicInteger(0);
    // با هر speak ی غیرِ هم‌کلید بالا می‌ره؛ prefetch های در صف/در حالِ اجرا کنسل می‌شن
    private final AtomicInteger pfAbort = new AtomicInteger(0);

    private final LinkedHashMap<String, float[]> cache =
            new LinkedHashMap<String, float[]>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> e) {
                    return size() > CACHE_MAX;
                }
            };

    // کدِ native (espeak-ng / onnxruntime) حالتِ سراسری داره و thread-safe نیست؛ پس تولیدِ صدای «همه‌ی»
    // زبان‌ها روی «یک» thread ی مشترک انجام می‌شه (قبلاً هر موتور thread ی خودش رو داشت و دو زبان هم‌زمان
    // وارد native می‌شدن → کرشِ تصادفی). NATIVE_LOCK هم ساختنِ موتور رو با تولید هم‌زمان نمی‌ذاره.
    private static final Object NATIVE_LOCK = new Object();
    private static final ExecutorService genExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
            r.run();
        }, "tts-gen");
        t.setDaemon(true);
        return t;
    });

    private TtsEngine(OfflineTts tts, int sampleRate, String lang) {
        this.tts = tts;
        this.sampleRate = sampleRate;
        this.lang = lang;
    }

    String lang() { return lang; }
    boolean isReleased() { return released; }
    long lastUsed() { return lastUsed; }
    void touch() { lastUsed = SystemClock.uptimeMillis(); }

    /**
     * قبل از لودِ native فایل‌ها رو بررسی می‌کنه (فایلِ خراب/ناقص باعثِ exit() یا abort ی native می‌شه).
     * اگه مشکلی باشه متنِ خطا رو برمی‌گردونه و فایلِ خراب رو پاک می‌کنه تا دوباره دانلود بشه.
     */
    private static String validateFiles(Context ctx, File dir, String lang) {
        File model = new File(dir, "model.onnx");
        if (model.length() < 2_000_000L) {
            model.delete();
            return "model.onnx too small (corrupt download)";
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(model)) {
            int b = in.read();
            // HTML / JSON / متن (مثلاً صفحه‌ی خطای آینه یا pointer ی git-lfs) → مدل نیست
            if (b == '<' || b == '{' || b == 'v' || b == ' ' || b == '\n' || b == '\r') {
                model.delete();
                return "model.onnx is not an ONNX file";
            }
        } catch (Throwable e) {
            return "cannot read model.onnx";
        }
        File es = SherpaModelManager.getEspeakDataDir(ctx);
        String[] need = {"phontab", "phonindex", "phondata", "intonations", lang + "_dict"};
        for (String n : need) {
            File f = new File(es, n);
            if (!f.isFile() || f.length() == 0) {
                SherpaModelManager.invalidateEspeak(ctx);
                return "espeak-ng-data incomplete: " + n;
            }
        }
        return null;
    }

    /** آخرین خطای لود (برای گزارش به کاربر)؛ فقط توی پردازه‌ی TTS معنی داره. */
    static volatile String lastCreateError = null;

    static TtsEngine create(Context ctx, String lang) {
        lastCreateError = null;
        File dir = SherpaModelManager.getTtsModelDir(ctx, lang);
        if (dir == null) {
            Log.w(TAG, "TTS model not found for " + lang);
            lastCreateError = "model not found";
            return null;
        }
        String bad = validateFiles(ctx, dir, lang);
        if (bad != null) {
            Log.e(TAG, "invalid TTS files for " + lang + ": " + bad);
            lastCreateError = bad;
            return null;
        }
        int oldPrio = Process.THREAD_PRIORITY_DEFAULT;
        boolean prioChanged = false;
        try {
            long t0 = SystemClock.uptimeMillis();
            TtsCrumb.mark("create:" + lang);
            // threadهای ONNX Runtime اولویتِ threadی رو که می‌سازتشون به ارث می‌برن
            try {
                oldPrio = Process.getThreadPriority(Process.myTid());
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                prioChanged = true;
            } catch (Throwable ignored) {}

            OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig();
            vitsConfig.setModel(new File(dir, "model.onnx").getAbsolutePath());
            vitsConfig.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
            // espeak-ng-dataِ مشترکِ همه‌ی مدل‌ها (یک بار دانلود می‌شه)
            vitsConfig.setDataDir(SherpaModelManager.getEspeakDataDir(ctx).getAbsolutePath());

            int cores = Runtime.getRuntime().availableProcessors();
            int threads = Math.max(2, Math.min(4, cores / 2));

            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setVits(vitsConfig);
            modelConfig.setNumThreads(threads);
            modelConfig.setDebug(false);

            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(modelConfig);

            OfflineTts engine;
            int sr;
            synchronized (NATIVE_LOCK) {
                engine = new OfflineTts(null, config);
                // نرخ نمونه‌برداری رو از خودِ مدل بخون (مدل‌های Piper همه ۲۲۰۵۰ نیستن)
                sr = engine.sampleRate();
            }
            if (sr <= 0) sr = 22050;
            Log.i(TAG, "TTS loaded for " + lang + ", sampleRate=" + sr + ", threads=" + threads
                    + ", loadMs=" + (SystemClock.uptimeMillis() - t0));
            TtsCrumb.mark("idle");
            return new TtsEngine(engine, sr, lang);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load TTS for " + lang, e);
            lastCreateError = String.valueOf(e);
            TtsCrumb.mark("idle");
            return null;
        } finally {
            if (prioChanged) {
                try { Process.setThreadPriority(oldPrio); } catch (Throwable ignored) {}
            }
        }
    }

    /** یک inference کوچیکِ بی‌صدا تا اولین تپِ واقعی کند نباشه. یک‌بار اجرا می‌شه. */
    void warmUp() {
        if (released || !warmed.compareAndSet(false, true)) return;
        final String w;
        switch (lang == null ? "" : lang) {
            case "fa": w = "سلام"; break;
            case "ar": w = "مرحبا"; break;
            case "ru": w = "привет"; break;
            case "zh": w = "你好"; break;
            case "hi": w = "नमस्ते"; break;
            default:   w = "Hello."; break;
        }
        try {
            genExec.execute(() -> {
                if (released) return;
                try {
                    long t0 = SystemClock.uptimeMillis();
                    TtsCrumb.mark("warm:" + lang);
                    synchronized (NATIVE_LOCK) { tts.generate(w, 0, 1.0f); }
                    TtsCrumb.mark("idle");
                    Log.i(TAG, "warmUp " + lang + " ms=" + (SystemClock.uptimeMillis() - t0));
                } catch (Throwable e) {
                    Log.w(TAG, "warmUp failed", e);
                }
            });
        } catch (Throwable ignored) {}
    }

    // ================= کش =================
    private static String keyOf(String t, float sp) { return sp + "|" + t; }

    private float[] cacheGet(String key) {
        synchronized (cache) { return cache.get(key); }
    }
    private boolean cacheHas(String key) {
        synchronized (cache) { return cache.containsKey(key); }
    }
    private void cachePut(String key, float[] v) {
        if (v == null || v.length == 0) return;
        synchronized (cache) { cache.put(key, v); }
    }

    private static short[] toPcm16(float[] samples) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            if (s > 1f) s = 1f; else if (s < -1f) s = -1f;
            pcm[i] = (short) (s * 32767);
        }
        return pcm;
    }

    /** تولیدِ استریم؛ sink برای هر تکه صدا زده می‌شه. اگه sink false بده، null برمی‌گرده (لغو). */
    private float[] synth(String t, float sp, boolean pl, final Sink sink) {
        if (pl) {
            com.k2fsa.sherpa.onnx.GeneratedAudio ga;
            synchronized (NATIVE_LOCK) { ga = tts.generate(t, 0, sp); }
            float[] all = ga == null ? null : ga.getSamples();
            if (all == null || all.length == 0) return new float[0];
            if (!sink.accept(all)) return null;
            return all;
        }
        final ArrayList<float[]> parts = new ArrayList<>();
        final boolean[] aborted = {false};
        final Throwable[] err = {null};
        synchronized (NATIVE_LOCK) {
            tts.generateWithCallback(t, 0, sp, samples -> {
                // هیچ exception ی نباید از callback به native برگرده: JNI بعدش FindClass صدا می‌زنه
                // و ART با exceptionِ معلق کلِ پردازه رو abort می‌کنه (SIGABRT / status=6).
                try {
                    if (samples == null || samples.length == 0) return 1;
                    float[] copy = samples.clone();
                    parts.add(copy);
                    if (!sink.accept(copy)) {
                        aborted[0] = true;
                        return 0;
                    }
                    return 1;
                } catch (Throwable e) {
                    err[0] = e;
                    aborted[0] = true;
                    return 0;
                }
            });
        }
        if (err[0] != null) throw new RuntimeException("synth callback failed", err[0]);
        if (aborted[0]) return null;
        int total = 0;
        for (float[] p : parts) total += p.length;
        float[] merged = new float[total];
        int off = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, merged, off, p.length);
            off += p.length;
        }
        return merged;
    }

    // ================= prefetch =================
    /** صدای این متن رو پیش‌پیش می‌سازه و کش می‌کنه (برای جمله‌ی بعدی). */
    void prefetch(String text, float speed) {
        if (released || text == null || text.trim().isEmpty()) return;
        final String t = sanitize(lang, text);
        if (t.isEmpty()) return;
        final float sp = (speed <= 0f) ? 1.0f : speed;
        final String key = keyOf(t, sp);
        if (cacheHas(key)) return;
        final int g = pfAbort.get();
        try {
            genExec.execute(() -> {
                if (released || pfAbort.get() != g || cacheHas(key)) return;
                inflightPrefetchKey = key;
                final boolean pl = plain; // یک‌بار بخون تا بینِ crumb و synth عوض نشه
                try {
                    TtsCrumb.mark((pl ? "prefetchp:" : "prefetch:") + lang + ":" + t.length() + ":" + TtsCrumb.brief(t));
                    float[] audio = synth(t, sp, pl, s -> !released && pfAbort.get() == g);
                    TtsCrumb.mark("idle");
                    if (audio != null) cachePut(key, audio);
                } catch (Throwable e) {
                    Log.w(TAG, "prefetch failed", e);
                } finally {
                    inflightPrefetchKey = null;
                }
            });
        } catch (Throwable ignored) {}
    }

    // ================= پخش =================
    /**
     * خواندن متن؛ cb دقیقاً یک‌بار، بعد از تمام‌شدنِ واقعیِ پخش (یا شکست/لغو) صدا زده می‌شه.
     */
    void speakAsync(String text, float speed, final DoneCallback cb) {
        if (text == null || text.trim().isEmpty()) {
            if (cb != null) cb.onDone(true);
            return;
        }
        if (released) {
            if (cb != null) cb.onDone(false);
            return;
        }
        final String t = sanitize(lang, text);
        if (t.isEmpty()) {
            if (cb != null) cb.onDone(true);
            return;
        }
        final float sp = (speed <= 0f) ? 1.0f : speed;
        final String key = keyOf(t, sp);
        touch();

        // اگه prefetchِ در حالِ اجرا مالِ همین متن نیست، کنسلش کن تا این پخش معطل نشه
        if (!key.equals(inflightPrefetchKey)) pfAbort.incrementAndGet();
        haltPlayback();
        final int myEpoch = epoch.get();

        final LinkedBlockingQueue<short[]> q = new LinkedBlockingQueue<>();
        final AtomicBoolean genFailed = new AtomicBoolean(false);
        final AtomicBoolean gotAudio = new AtomicBoolean(false);
        final AtomicBoolean fired = new AtomicBoolean(false);
        final DoneCallback once = ok -> {
            if (fired.compareAndSet(false, true) && cb != null) cb.onDone(ok);
        };

        // پلیر اول شروع می‌شه: ساختنِ AudioTrack هم‌زمان با تولیدِ صدا انجام می‌شه
        Thread player = new Thread(() -> runPlayer(q, myEpoch, genFailed, gotAudio, once), "tts-play");
        player.setDaemon(true);
        player.start();

        try {
            genExec.execute(() -> runGenerate(t, sp, key, myEpoch, q, genFailed, gotAudio));
        } catch (Throwable e) {
            genFailed.set(true);
            q.add(END);
        }
    }

    // نسخه‌ی قدیمی (سازگاری با کدهای قبلی مثل BubbleService)
    void speak(String text, float speed, final Runnable onDone) {
        speakAsync(text, speed, ok -> { if (onDone != null) onDone.run(); });
    }

    void speak(String text, float speed) {
        speakAsync(text, speed, null);
    }

    private void runGenerate(String t, float sp, String key, final int myEpoch,
                             final LinkedBlockingQueue<short[]> q,
                             AtomicBoolean genFailed, final AtomicBoolean gotAudio) {
        try {
            if (released || epoch.get() != myEpoch) return;
            float[] cached = cacheGet(key);
            if (cached != null) {
                if (cached.length > 0) {
                    gotAudio.set(true);
                    q.add(toPcm16(cached));
                }
                return;
            }
            long t0 = SystemClock.uptimeMillis();
            final boolean[] first = {true};
            final boolean pl = plain; // یک‌بار بخون تا بینِ crumb و synth عوض نشه
            TtsCrumb.mark((pl ? "genp:" : "gen:") + lang + ":" + t.length() + ":" + TtsCrumb.brief(t));
            float[] audio = synth(t, sp, pl, s -> {
                if (released || epoch.get() != myEpoch) return false;
                if (first[0]) {
                    first[0] = false;
                    Log.i(TAG, "first audio after ms=" + (SystemClock.uptimeMillis() - t0));
                }
                gotAudio.set(true);
                q.add(toPcm16(s));
                return true;
            });
            TtsCrumb.mark("idle");
            if (audio != null) cachePut(key, audio);
        } catch (Throwable e) {
            Log.e(TAG, "TTS generate failed", e);
            TtsCrumb.mark("idle");
            genFailed.set(true);
        } finally {
            q.add(END);
        }
    }

    private AudioTrack buildTrack() {
        int minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, sampleRate * 2); // حدود ۱ ثانیه
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) {
            b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        return b.build();
    }

    private void runPlayer(LinkedBlockingQueue<short[]> q, int myEpoch,
                           AtomicBoolean genFailed, AtomicBoolean gotAudio, DoneCallback once) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); } catch (Throwable ignored) {}
        AudioTrack track = null;
        boolean ok = true;
        boolean cancelled = false;
        boolean started = false;
        long frames = 0;
        try {
            track = buildTrack();
            if (epoch.get() != myEpoch || released) {
                cancelled = true;
            } else {
                currentTrack = track;
            }
            loop:
            while (!cancelled) {
                if (epoch.get() != myEpoch || released) { cancelled = true; break; }
                short[] c = q.poll(40, TimeUnit.MILLISECONDS);
                if (c == null) continue;
                if (c == END) break;
                if (!started) {
                    track.play();
                    started = true;
                }
                int off = 0;
                while (off < c.length) {
                    if (epoch.get() != myEpoch || released) { cancelled = true; break loop; }
                    int n = track.write(c, off, c.length - off);
                    if (n < 0) { ok = false; break loop; }
                    off += n;
                }
                frames += c.length;
            }
            // صبر تا آخرین نمونه واقعاً پخش بشه (تا onDone زودتر از پایانِ صدا نیاد)
            if (!cancelled && ok && started) {
                long deadline = SystemClock.uptimeMillis() + (frames * 1000L / sampleRate) + 1500;
                while (SystemClock.uptimeMillis() < deadline) {
                    if (epoch.get() != myEpoch || released) { cancelled = true; break; }
                    long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    if (head >= frames) break;
                    Thread.sleep(15);
                }
            }
            if (!cancelled && ok && !gotAudio.get()) {
                Log.w(TAG, "Generated audio is empty");
                ok = false;
            }
        } catch (Throwable e) {
            Log.e(TAG, "playback failed", e);
            ok = false;
        } finally {
            if (track != null) {
                if (currentTrack == track) currentTrack = null;
                try { track.pause(); } catch (Throwable ignored) {}
                try { track.flush(); } catch (Throwable ignored) {}
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
            once.onDone(cancelled || ok);
        }
    }

    /** پخشِ فعلی رو فوراً قطع می‌کنه (آزادسازیِ track رو خودِ thread پلیر انجام می‌ده). */
    private void haltPlayback() {
        epoch.incrementAndGet();
        AudioTrack t = currentTrack;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
        }
    }

    void stop() {
        haltPlayback();
    }

    void release() {
        if (released) return;
        released = true;
        haltPlayback();
        try {
            // release رو بعد از تولیدِ در حالِ اجرا انجام بده (وگرنه کرشِ native)
            genExec.execute(() -> { try { synchronized (NATIVE_LOCK) { tts.release(); } } catch (Throwable ignored) {} });
        } catch (Throwable e) {
            try { tts.release(); } catch (Throwable ignored) {}
        }
    }
}
