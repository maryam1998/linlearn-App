package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class SherpaModelManager {

    private static final String TAG = "SherpaModelMgr";
    private static final String HF_BASE = "https://hf-mirror.com/";

    private SherpaModelManager() {}

    interface ProgressCallback {
        void onProgress(String lang, long done, long total);
        void onDone(String lang);
        void onError(String lang, Exception e);
    }

    // ============ STT (تشخیص گفتار) ============
    private static final class Spec {
        final String repo;
        final String encoder, decoder, joiner, tokens;
        Spec(String repo, String encoder, String decoder, String joiner, String tokens) {
            this.repo = repo; this.encoder = encoder; this.decoder = decoder;
            this.joiner = joiner; this.tokens = tokens;
        }
        String[] files() { return new String[]{encoder, decoder, joiner, tokens}; }
    }

    private static final Map<String, Spec> SPECS;
    static {
        Map<String, Spec> m = new HashMap<>();
        m.put("en", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens.txt"));
        m.put("zh", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt"));
        SPECS = Collections.unmodifiableMap(m);
    }

    // ============ TTS (تبدیل متن به گفتار) ============
    private static final class TtsSpec {
        final String repo;         // مخزن HuggingFace
        final String modelFile;    // فایل .onnx داخل مخزن
        final String tokensFile;   // فایل tokens.txt
        final String dataDirName;  // نام پوشه espeak-ng-data
        TtsSpec(String repo, String modelFile, String tokensFile, String dataDirName) {
            this.repo = repo;
            this.modelFile = modelFile;
            this.tokensFile = tokensFile;
            this.dataDirName = dataDirName;
        }
    }

    private static final Map<String, TtsSpec> TTS_SPECS;
    static {
        Map<String, TtsSpec> m = new LinkedHashMap<>();

        m.put("en", new TtsSpec(
                "csukuangfj/vits-piper-en_US-ryan-medium",
                "en_US-ryan-medium.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("fa", new TtsSpec(
                "csukuangfj/vits-piper-fa_IR-gyro-medium",
                "fa_IR-gyro-medium.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("ar", new TtsSpec(
                "csukuangfj/vits-piper-ar_JO-kareem-medium",
                "ar_JO-kareem-medium.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("tr", new TtsSpec(
                "csukuangfj/vits-piper-tr_TR-fahrettin-medium",
                "tr_TR-fahrettin-medium.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("de", new TtsSpec(
                "csukuangfj/vits-piper-de_DE-thorsten_emotional-medium",
                "de_DE-thorsten_emotional-medium.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("fr", new TtsSpec(
                "csukuangfj/vits-piper-fr_FR-miro-high",
                "fr_FR-miro-high.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("es", new TtsSpec(
                "csukuangfj/vits-piper-es_ES-miro-high",
                "es_ES-miro-high.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("it", new TtsSpec(
                "csukuangfj/vits-piper-it_IT-miro-high",
                "it_IT-miro-high.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("ru", new TtsSpec(
                "csukuangfj/vits-piper-ru_RU-irina-medium",
                "ru_RU-irina-medium.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("zh", new TtsSpec(
                "csukuangfj/vits-piper-zh_CN-huayan-medium",
                "zh_CN-huayan-medium.onnx", "tokens.txt", "espeak-ng-data"));
        m.put("hi", new TtsSpec(
                "csukuangfj/vits-piper-hi_IN-rohan-medium",
                "hi_IN-rohan-medium.onnx", "tokens.txt", "espeak-ng-data"));
        // he (عبری), ja (ژاپنی), ko (کره‌ای): مدل Piper رسمی ندارن؛
        // اگه پیدا کردی همینجا اضافه کن.

        TTS_SPECS = Collections.unmodifiableMap(m);
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
    private static volatile boolean DOWNLOADING_STATE = false;
    // دانلودِ TTS برای هر زبان مستقله؛ چند زبان می‌تونن هم‌زمان دانلود بشن
    private static final Set<String> TTS_ACTIVE = ConcurrentHashMap.newKeySet();
    // espeak-ng-data بینِ همه‌ی زبان‌ها مشترکه؛ فقط یک نخ دانلودش می‌کنه، بقیه صبر می‌کنن
    private static final ReentrantLock ESPEAK_LOCK = new ReentrantLock();
    // زبان‌هایی که کاربر «توقف» رو زده؛ فایل .part نگه داشته می‌شه تا دفعه‌ی بعد ادامه پیدا کنه
    private static final Set<String> TTS_CANCEL = ConcurrentHashMap.newKeySet();

    /** پرتاب می‌شه وقتی کاربر دانلود رو متوقف کرده. */
    static final class DownloadCancelled extends java.io.IOException {
        DownloadCancelled() { super("cancelled"); }
    }

    static void cancelTtsDownload(String lang) {
        String l = normalize(lang);
        if (l != null && TTS_ACTIVE.contains(l)) TTS_CANCEL.add(l);
    }

    static void cancelAllTtsDownloads() {
        TTS_CANCEL.addAll(TTS_ACTIVE);
    }

    private static void checkCancel(String l) throws DownloadCancelled {
        if (l != null && TTS_CANCEL.contains(l)) throw new DownloadCancelled();
    }

    /** حجمِ بخشِ دانلودشده‌ی ناتمامِ مدلِ یه زبان (برای دکمه‌ی «ادامه»). */
    static long getTtsPartialBytes(Context ctx, String lang) {
        String l = normalize(lang);
        if (l == null) return 0;
        File part = new File(ttsDirFor(ctx, l), "model.onnx.part");
        return part.isFile() ? part.length() : 0;
    }
    // آخرین مقدارِ پیشرفتِ هر زبان (بایت) — برای نوتیفیکیشنِ سرویسِ دانلود
    private static final Map<String, Long> TTS_BYTES = new ConcurrentHashMap<>();

    static long getTtsDownloadedBytes() {
        long sum = 0;
        for (Long v : TTS_BYTES.values()) if (v != null) sum += v;
        return sum;
    }

    // ============ متدهای مشترک ============
    static String normalize(String lang) {
        if (lang == null) return null;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        int i = l.indexOf('-');
        if (i < 0) i = l.indexOf('_');
        if (i > 0) l = l.substring(0, i);
        return l.isEmpty() ? null : l;
    }

    // ============ STT ============
    static boolean isAvailable(String lang) {
        String l = normalize(lang);
        return l != null && SPECS.containsKey(l);
    }

    static boolean isDownloading() { return DOWNLOADING_STATE; }

    private static File sttDirFor(Context ctx, String l) {
        return new File(new File(ctx.getFilesDir(), "sherpa"), l);
    }

    static File getModelDir(Context ctx, String lang) {
        String l = normalize(lang);
        Spec s = l == null ? null : SPECS.get(l);
        if (s == null) return null;
        File dir = sttDirFor(ctx, l);
        for (String f : s.files()) {
            File x = new File(dir, f);
            if (!x.isFile() || x.length() == 0) return null;
        }
        return dir;
    }

    static String encoderFile(String lang) { Spec s = SPECS.get(normalize(lang)); return s == null ? null : s.encoder; }
    static String decoderFile(String lang) { Spec s = SPECS.get(normalize(lang)); return s == null ? null : s.decoder; }
    static String joinerFile(String lang)  { Spec s = SPECS.get(normalize(lang)); return s == null ? null : s.joiner; }
    static String tokensFile(String lang)  { Spec s = SPECS.get(normalize(lang)); return s == null ? null : s.tokens; }

    // ============ TTS ============
    static boolean isTtsAvailable(String lang) {
        String l = normalize(lang);
        return l != null && TTS_SPECS.containsKey(l);
    }

    private static File ttsDirFor(Context ctx, String l) {
        return new File(new File(ctx.getFilesDir(), "tts"), l);
    }

    /** زبان‌هایی که مدل TTS آفلاین دارن (به ترتیب تعریف). */
    static List<String> ttsLanguages() {
        return new ArrayList<>(TTS_SPECS.keySet());
    }

    /** زبان‌هایی که مدل TTS‌شون همین الان در حال دانلوده. */
    static List<String> getTtsDownloadingLangs() {
        return new ArrayList<>(TTS_ACTIVE);
    }

    static boolean isTtsDownloading() { return !TTS_ACTIVE.isEmpty(); }

    static boolean isTtsDownloading(String lang) {
        String l = normalize(lang);
        return l != null && TTS_ACTIVE.contains(l);
    }

    /** espeak-ng-data مشترکِ همه‌ی مدل‌های Piper (یک بار دانلود می‌شه). */
    static File getEspeakDataDir(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "tts"), "espeak-ng-data");
    }

    private static File espeakMarker(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "tts"), "espeak-ng-data.ok");
    }

    private static boolean isEspeakReady(Context ctx) {
        return espeakMarker(ctx).isFile() && getEspeakDataDir(ctx).isDirectory();
    }

    /** espeak-ng-data ناقص/خراب بود: marker رو پاک کن تا دانلودِ بعدی فایل‌های کم‌وکسر رو دوباره بگیره. */
    static void invalidateEspeak(Context ctx) {
        try { espeakMarker(ctx).delete(); } catch (Throwable ignored) {}
    }

    static File getTtsModelDir(Context ctx, String lang) {
        String l = normalize(lang);
        TtsSpec s = l == null ? null : TTS_SPECS.get(l);
        if (s == null) return null;
        File dir = ttsDirFor(ctx, l);
        File model = new File(dir, "model.onnx");
        File tokens = new File(dir, "tokens.txt");
        if (!model.isFile() || model.length() == 0) return null;
        if (!tokens.isFile() || tokens.length() == 0) return null;
        if (!isEspeakReady(ctx)) return null;
        return dir;
    }

    // ============ دانلود STT ============
    static void downloadModel(final Context ctx, final String lang, final ProgressCallback cb) {
        final Context app = ctx.getApplicationContext();
        final String l = normalize(lang);
        final Spec s = l == null ? null : SPECS.get(l);
        if (s == null) {
            if (cb != null) cb.onError(String.valueOf(lang), new IllegalArgumentException("no model for " + lang));
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            if (cb != null) cb.onError(l, new IllegalStateException("another download is running"));
            return;
        }
        DOWNLOADING_STATE = true;
        Thread t = new Thread(() -> {
            try {
                File dir = sttDirFor(app, l);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
                long done = 0;
                for (String name : s.files()) {
                    File target = new File(dir, name);
                    if (target.isFile() && target.length() > 0) { done += target.length(); continue; }
                    File part = new File(dir, name + ".part");
                    String url = HF_BASE + s.repo + "/resolve/main/" + name;
                    Request req = new Request.Builder().url(url).build();
                    try (Response r = HTTP.newCall(req).execute()) {
                        if (!r.isSuccessful() || r.body() == null) {
                            throw new java.io.IOException("HTTP " + r.code() + " for " + name);
                        }
                        try (InputStream in = r.body().byteStream();
                             OutputStream out = new FileOutputStream(part)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                                done += n;
                                if (cb != null) cb.onProgress(l, done, -1);
                            }
                        }
                    }
                    if (!part.renameTo(target)) throw new java.io.IOException("rename failed: " + name);
                }
                if (cb != null) cb.onDone(l);
            } catch (Exception e) {
                Log.w(TAG, "STT download failed for " + l, e);
                if (cb != null) cb.onError(l, e);
            } finally {
                DOWNLOADING.set(false);
                DOWNLOADING_STATE = false;
            }
        }, "sherpa-download");
        t.setDaemon(true);
        t.start();
    }

    // ============ دانلود TTS ============
    // هر زبان توی نخِ خودش دانلود می‌شه، پس می‌شه همه‌ی زبان‌ها رو هم‌زمان زد.
    static void downloadTtsModel(final Context ctx, final String lang, final ProgressCallback cb) {
        final Context app = ctx.getApplicationContext();
        final String l = normalize(lang);
        final TtsSpec s = l == null ? null : TTS_SPECS.get(l);
        if (s == null) {
            if (cb != null) cb.onError(String.valueOf(lang), new IllegalArgumentException("no TTS for " + lang));
            return;
        }
        if (!TTS_ACTIVE.add(l)) {
            // همین زبان همین الان در حال دانلوده؛ دوباره شروعش نمی‌کنیم
            return;
        }
        TTS_CANCEL.remove(l);
        Thread t = new Thread(() -> {
            try {
                Exception last = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        downloadTtsOnce(app, l, s, new ProgressCallback() {
                            @Override public void onProgress(String lg, long done, long total) {
                                TTS_BYTES.put(l, done);
                                if (cb != null) cb.onProgress(lg, done, total);
                            }
                            @Override public void onDone(String lg) {}
                            @Override public void onError(String lg, Exception e) {}
                        });
                        last = null;
                        break;
                    } catch (DownloadCancelled dc) {
                        last = dc;
                        break;
                    } catch (Exception e) {
                        last = e;
                        Log.w(TAG, "TTS download attempt " + attempt + " failed for " + l, e);
                        if (TTS_CANCEL.contains(l)) { last = new DownloadCancelled(); break; }
                        try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { break; }
                    }
                }
                if (last != null) {
                    if (cb != null) {
                        if (last instanceof DownloadCancelled) cb.onError(l, last);
                        else cb.onError(l, new Exception(last.getClass().getSimpleName() + ": " + last.getMessage(), last));
                    }
                } else {
                    if (cb != null) cb.onDone(l);
                }
            } finally {
                TTS_BYTES.remove(l);
                TTS_CANCEL.remove(l);
                TTS_ACTIVE.remove(l);
            }
        }, "sherpa-tts-download-" + l);
        t.setDaemon(true);
        t.start();
    }

    private static void downloadTtsOnce(Context app, String l, TtsSpec s, ProgressCallback cb) throws Exception {
        File dir = ttsDirFor(app, l);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);

        // ۱. tokens.txt (کوچیکه)
        checkCancel(l);
        File tokensTarget = new File(dir, "tokens.txt");
        if (!tokensTarget.isFile() || tokensTarget.length() == 0) {
            downloadFile(HF_BASE + s.repo + "/resolve/main/" + s.tokensFile, tokensTarget, cb, l);
        }

        // ۲. model.onnx (بزرگ‌ترین فایل؛ از همین‌جا پیشرفت دیده می‌شه و از نقطه‌ی توقف ادامه پیدا می‌کنه)
        checkCancel(l);
        File modelTarget = new File(dir, "model.onnx");
        if (!modelTarget.isFile() || modelTarget.length() == 0) {
            downloadFile(HF_BASE + s.repo + "/resolve/main/" + s.modelFile, modelTarget, cb, l);
        }

        // ۳. espeak-ng-data مشترک (فقط یک بار؛ زبان‌های دیگه همین‌جا صبر می‌کنن، ولی با امکانِ توقف)
        if (!isEspeakReady(app)) {
            while (!ESPEAK_LOCK.tryLock(500, TimeUnit.MILLISECONDS)) {
                checkCancel(l);
                if (isEspeakReady(app)) break;
            }
            if (ESPEAK_LOCK.isHeldByCurrentThread()) {
                try {
                    if (!isEspeakReady(app)) downloadEspeakData(app, s.repo, cb, l);
                } finally {
                    ESPEAK_LOCK.unlock();
                }
            }
        }
    }

    /**
     * پوشه‌ی espeak-ng-data رو از HuggingFace (لیستِ فایل‌ها با API) دانلود می‌کنه.
     * فقط وقتی کامل شد فایلِ marker ساخته می‌شه.
     */
    private static void downloadEspeakData(Context app, String repo, ProgressCallback cb, String l) throws Exception {
        final String prefix = "espeak-ng-data/";
        File root = getEspeakDataDir(app);
        if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("cannot create " + root);

        // لیست فایل‌ها (با صفحه‌بندی)
        List<String> paths = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        String next = HF_BASE + "api/models/" + repo + "/tree/main/espeak-ng-data?recursive=true";
        int guard = 0;
        while (next != null && guard++ < 20) {
            checkCancel(l);
            Request req = new Request.Builder().url(next).build();
            try (Response r = HTTP.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) {
                    throw new java.io.IOException("HTTP " + r.code() + " listing espeak-ng-data");
                }
                String body = r.body().string();
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (!"file".equals(o.optString("type"))) continue;
                    String p = o.optString("path");
                    if (p.startsWith(prefix)) {
                        paths.add(p);
                        sizes.add(o.optLong("size", 0));
                    }
                }
                next = fixHost(parseNextLink(r.header("Link")));
            }
        }
        if (paths.isEmpty()) throw new java.io.IOException("espeak-ng-data list is empty");

        // فایل‌ها رو چندتا چندتا هم‌زمان می‌گیریم (صدها فایل کوچیکه؛ یکی‌یکی خیلی کند بود)
        final AtomicLong done = new AtomicLong(0);
        final long totalBytes = sumSizes(sizes);
        ExecutorService ex = Executors.newFixedThreadPool(6);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                final String p = paths.get(i);
                final String rel = p.substring(prefix.length());
                final File target = new File(root, rel);
                final long expected = sizes.get(i);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new java.io.IOException("cannot create " + parent);
                }
                if (target.isFile() && (expected <= 0 || target.length() == expected)) {
                    done.addAndGet(target.length());
                    continue;
                }
                final String url = HF_BASE + repo + "/resolve/main/" + p;
                futures.add(ex.submit(new Callable<Void>() {
                    @Override public Void call() throws Exception {
                        checkCancel(l);
                        downloadFile(url, target, null, l);
                        long now = done.addAndGet(target.length());
                        if (cb != null) cb.onProgress(l, now, totalBytes);
                        return null;
                    }
                }));
            }
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof Exception) throw (Exception) c;
                    throw ee;
                }
            }
        } finally {
            ex.shutdownNow();
        }
        File marker = espeakMarker(app);
        if (!marker.exists() && !marker.createNewFile()) throw new java.io.IOException("cannot create marker");
    }

    private static long sumSizes(List<Long> sizes) {
        long t = 0;
        for (Long v : sizes) if (v != null) t += v;
        return t;
    }

    /** لینکِ صفحه‌ی بعدیِ API ممکنه به huggingface.co اشاره کنه؛ همون مسیر رو روی آینه‌ی خودمون می‌خونیم. */
    private static String fixHost(String url) {
        if (url == null) return null;
        int i = url.indexOf("/api/");
        if (i < 0) return url;
        return HF_BASE + url.substring(i + 1);
    }

    /** از هدرِ Link مقدارِ rel="next" رو درمیاره (یا null). */
    private static String parseNextLink(String link) {
        if (link == null) return null;
        for (String part : link.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int a = part.indexOf('<');
                int b = part.indexOf('>');
                if (a >= 0 && b > a) return part.substring(a + 1, b).trim();
            }
        }
        return null;
    }

    /** دانلودِ فایل با امکانِ ادامه از نقطه‌ی توقف (Range) و توقفِ کاربر. */
    private static void downloadFile(String url, File target, ProgressCallback cb, String l) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        long existing = part.isFile() ? part.length() : 0;
        Request.Builder rb = new Request.Builder().url(url);
        if (existing > 0) rb.header("Range", "bytes=" + existing + "-");
        try (Response r = HTTP.newCall(rb.build()).execute()) {
            if (r.code() == 416) {
                // بخشِ قبلی با فایلِ سرور نمی‌خونه؛ از اول می‌گیریم
                part.delete();
                downloadFile(url, target, cb, l);
                return;
            }
            if (!r.isSuccessful() || r.body() == null) {
                throw new java.io.IOException("HTTP " + r.code() + " for " + target.getName());
            }
            // صفحه‌ی خطای آینه (HTML) به‌جای فایل → خرابِ بی‌صدا؛ همین‌جا ردش کن
            String ctype = r.header("Content-Type");
            if (ctype != null && ctype.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                part.delete();
                throw new java.io.IOException("server returned HTML instead of " + target.getName());
            }
            boolean resumed = r.code() == 206 && existing > 0;
            long base = resumed ? existing : 0;
            long len = r.body().contentLength();
            long total = len > 0 ? base + len : -1;
            long done = base;
            long lastEmit = 0;
            try (InputStream in = r.body().byteStream();
                 OutputStream out = new FileOutputStream(part, resumed)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    checkCancel(l);
                    out.write(buf, 0, n);
                    done += n;
                    if (cb != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastEmit > 300) { lastEmit = now; cb.onProgress(l, done, total); }
                    }
                }
            }
            if (cb != null) cb.onProgress(l, done, total);
            if (total > 0 && part.length() != total) throw new java.io.IOException("incomplete download");
            if (target.getName().endsWith(".onnx") && part.length() < 2_000_000L) {
                part.delete();
                throw new java.io.IOException("model file too small: " + part.length());
            }
        }
        if (target.exists()) target.delete();
        if (!part.renameTo(target)) throw new java.io.IOException("rename failed: " + target.getName());
    }

    // ============ حذف ============
    static void deleteModel(Context ctx, String lang) {
        String l = normalize(lang);
        if (l == null) return;
        File dir = sttDirFor(ctx, l);
        deleteRecursive(dir);
    }

    static void deleteTtsModel(Context ctx, String lang) {
        String l = normalize(lang);
        if (l == null) return;
        File dir = ttsDirFor(ctx, l);
        deleteRecursive(dir);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        f.delete();
    }
}
