package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * بسته‌های Whisper (MIT) برای تشخیص گفتارِ آهنگ‌ها — کاملاً جدا از SherpaModelManager.
 * بسته‌ها از HuggingFace (همان میرورِ SherpaModelManager) توسط خودِ کاربر دانلود می‌شن
 * و توی files/whisper/<id>/ ذخیره می‌شن. دانلود با Range قابل ادامه‌ست.
 */
final class WhisperModelManager {

    private static final String TAG = "WhisperModelMgr";
    private static final String HF_BASE = "https://hf-mirror.com/";

    private WhisperModelManager() {}

    interface Callback {
        void onProgress(String model, long done, long total);
        void onDone(String model);
        void onError(String model, Exception e);
    }

    private static final class Spec {
        final String repo, encoder, decoder, tokens;
        final int approxMb;
        Spec(String repo, String prefix, int approxMb) {
            this.repo = repo;
            this.encoder = prefix + "-encoder.int8.onnx";
            this.decoder = prefix + "-decoder.int8.onnx";
            this.tokens = prefix + "-tokens.txt";
            this.approxMb = approxMb;
        }
        String[] files() { return new String[]{encoder, decoder, tokens}; }
    }

    // بسته‌های چندزبانه (نه .en) تا فارسی/عربی/ترکی هم کار کنن.
    // نام فایل‌ها طبق مخزن‌های csukuangfj/sherpa-onnx-whisper-* هست؛ اگه نسخه‌ی جدیدی
    // نام‌ها رو عوض کرد، فقط همین‌جا اصلاح کن.
    private static final Map<String, Spec> SPECS;
    static {
        Map<String, Spec> m = new LinkedHashMap<>();
        m.put("tiny",  new Spec("csukuangfj/sherpa-onnx-whisper-tiny",  "tiny",  100));
        m.put("base",  new Spec("csukuangfj/sherpa-onnx-whisper-base",  "base",  160));
        m.put("small", new Spec("csukuangfj/sherpa-onnx-whisper-small", "small", 360));
        SPECS = Collections.unmodifiableMap(m);
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
    private static volatile boolean CANCEL = false;
    private static volatile String ACTIVE = null;

    static final class Cancelled extends IOException {
        Cancelled() { super("cancelled"); }
    }

    static boolean isValid(String model) { return model != null && SPECS.containsKey(model); }
    static boolean isDownloading() { return DOWNLOADING.get(); }
    static String activeModel() { return ACTIVE; }
    static void cancel() { if (DOWNLOADING.get()) CANCEL = true; }
    static int approxMb(String model) { Spec s = SPECS.get(model); return s == null ? 0 : s.approxMb; }
    static java.util.Set<String> models() { return SPECS.keySet(); }

    private static File dirFor(Context ctx, String model) {
        return new File(new File(ctx.getFilesDir(), "whisper"), model);
    }

    /** پوشه‌ی بسته اگه کامل دانلود شده باشه، وگرنه null. */
    static File getModelDir(Context ctx, String model) {
        Spec s = model == null ? null : SPECS.get(model);
        if (s == null) return null;
        File dir = dirFor(ctx, model);
        for (String f : s.files()) {
            File x = new File(dir, f);
            if (!x.isFile() || x.length() == 0) return null;
        }
        return dir;
    }

    static String encoderFile(String model) { Spec s = SPECS.get(model); return s == null ? null : s.encoder; }
    static String decoderFile(String model) { Spec s = SPECS.get(model); return s == null ? null : s.decoder; }
    static String tokensFile(String model)  { Spec s = SPECS.get(model); return s == null ? null : s.tokens; }

    /** بایت‌های نیمه‌تمامِ دانلود (برای نمایش «ادامه»). */
    static long partialBytes(Context ctx, String model) {
        Spec s = model == null ? null : SPECS.get(model);
        if (s == null) return 0;
        long sum = 0;
        File dir = dirFor(ctx, model);
        for (String f : s.files()) {
            File p = new File(dir, f + ".part");
            if (p.isFile()) sum += p.length();
        }
        return sum;
    }

    static void delete(Context ctx, String model) {
        if (!isValid(model)) return;
        deleteRecursive(dirFor(ctx, model));
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        f.delete();
    }

    static void download(final Context ctx, final String model, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final Spec s = model == null ? null : SPECS.get(model);
        if (s == null) {
            if (cb != null) cb.onError(String.valueOf(model), new IllegalArgumentException("unknown whisper model " + model));
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            if (cb != null) cb.onError(model, new IllegalStateException("another whisper download is running"));
            return;
        }
        CANCEL = false;
        ACTIVE = model;
        Thread t = new Thread(() -> {
            Exception result = null;
            try {
                Exception last = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        downloadOnce(app, model, s, cb);
                        last = null;
                        break;
                    } catch (Cancelled c) {
                        last = c;
                        break;
                    } catch (Exception e) {
                        last = e;
                        Log.w(TAG, "attempt " + attempt + " failed for " + model, e);
                        if (CANCEL) { last = new Cancelled(); break; }
                        try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { break; }
                    }
                }
                result = last;
            } finally {
                // اول وضعیت رو پاک کن، بعد به JS خبر بده — قبلاً JS موقعِ refresh (بعد از «توقف»)
                // هنوز downloading=true می‌دید و دکمه‌ها گیر می‌کردن.
                ACTIVE = null;
                CANCEL = false;
                DOWNLOADING.set(false);
            }
            if (cb != null) {
                if (result != null) cb.onError(model, result); else cb.onDone(model);
            }
        }, "whisper-download");
        t.setDaemon(true);
        t.start();
    }

    private static void downloadOnce(Context app, String model, Spec s, Callback cb) throws Exception {
        File dir = dirFor(app, model);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);

        // کل حجم = مجموع فایل‌ها؛ از HEAD نمی‌گیریم، همون Content-Length هر فایل رو جمع می‌زنیم.
        long doneBefore = 0;
        for (String name : s.files()) {
            if (CANCEL) throw new Cancelled();
            File target = new File(dir, name);
            if (target.isFile() && target.length() > 0) { doneBefore += target.length(); continue; }

            File part = new File(dir, name + ".part");
            long have = part.isFile() ? part.length() : 0;
            String url = HF_BASE + s.repo + "/resolve/main/" + name;
            Request.Builder rb = new Request.Builder().url(url);
            if (have > 0) rb.header("Range", "bytes=" + have + "-");

            try (Response r = HTTP.newCall(rb.build()).execute()) {
                if (r.code() == 416) {                       // .part قبلاً کامل بوده
                    if (!part.renameTo(target)) throw new IOException("rename failed: " + name);
                    doneBefore += target.length();
                    continue;
                }
                if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code() + " for " + name);
                boolean resumed = r.code() == 206 && have > 0;
                if (!resumed) have = 0;                      // سرور Range رو نپذیرفت → از صفر
                long len = r.body().contentLength();
                long fileTotal = len > 0 ? have + len : -1;

                long done = doneBefore + have;
                try (InputStream in = r.body().byteStream();
                     OutputStream out = new FileOutputStream(part, resumed)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (CANCEL) throw new Cancelled();
                        out.write(buf, 0, n);
                        done += n;
                        if (cb != null) cb.onProgress(model, done, -1);
                    }
                }
                if (fileTotal > 0 && part.length() != fileTotal) {
                    throw new IOException("incomplete download: " + name);
                }
            }
            if (!part.renameTo(target)) throw new IOException("rename failed: " + name);
            doneBefore += target.length();
        }
    }
}
