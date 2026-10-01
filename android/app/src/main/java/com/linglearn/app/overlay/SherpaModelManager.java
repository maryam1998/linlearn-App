package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class SherpaModelManager {

    private static final String TAG = "SherpaModelMgr";
    private static final String HF_BASE = "https://huggingface.co/";

    private SherpaModelManager() {}

    interface ProgressCallback {
        void onProgress(String lang, long done, long total);
        void onDone(String lang);
        void onError(String lang, Exception e);
    }

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

        // ============ انگلیسی (تأییدشده) ============
        m.put("en", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens.txt"));

        // ============ چینی + انگلیسی (تأییدشده) ============
        m.put("zh", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt"));

        // ============ کره‌ای (تأییدشده) ============
        m.put("ko", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-korean-2024-06-16",
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens.txt"));

        // ============ Kroko (اروپایی) ============
        // توجه: الگوی فایل این مدل‌ها متفاوته؛ قبل از استفاده تست کن.
        // اگه خطا داد، مدل رو حذف کن (کامنت کن) و از مسیر Google/server استفاده کن.
        m.put("de", kroko("de"));
        m.put("fr", kroko("fr"));
        m.put("es", kroko("es"));
        m.put("it", kroko("it"));
        m.put("pt", kroko("pt"));

        // زبان‌هایی که Sherpa جریانی نداره (به Google/server fallback می‌رن):
        // fa, ar, tr, he, hi, ur, ru, nl, ja

        SPECS = Collections.unmodifiableMap(m);
    }

    /** ساخت spec برای مدل‌های Kroko (ساختار استاندارد). */
    private static Spec kroko(String lang) {
        return new Spec(
                "kroko-ai/kroko-onnx-streaming-asr",
                "encoder-" + lang + ".onnx",
                "decoder-" + lang + ".onnx",
                "joiner-" + lang + ".onnx",
                "tokens-" + lang + ".txt");
    }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    static String normalize(String lang) {
        if (lang == null) return null;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        int i = l.indexOf('-');
        if (i < 0) i = l.indexOf('_');
        if (i > 0) l = l.substring(0, i);
        return l.isEmpty() ? null : l;
    }

    static boolean isAvailable(String lang) {
        String l = normalize(lang);
        return l != null && SPECS.containsKey(l);
    }

    private static File dirFor(Context ctx, String l) {
        return new File(new File(ctx.getFilesDir(), "sherpa"), l);
    }

    static File getModelDir(Context ctx, String lang) {
        String l = normalize(lang);
        Spec s = l == null ? null : SPECS.get(l);
        if (s == null) return null;
        File dir = dirFor(ctx, l);
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
        Thread t = new Thread(() -> {
            try {
                File dir = dirFor(app, l);
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
                    Log.i(TAG, "downloaded " + name + " (" + target.length() + " bytes)");
                }
                if (cb != null) cb.onDone(l);
            } catch (Exception e) {
                Log.w(TAG, "download failed for " + l, e);
                if (cb != null) cb.onError(l, e);
            } finally {
                DOWNLOADING.set(false);
            }
        }, "sherpa-download");
        t.setDaemon(true);
        t.start();
    }

    static void deleteModel(Context ctx, String lang) {
        String l = normalize(lang);
        if (l == null) return;
        File dir = dirFor(ctx, l);
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) { f.delete(); }
        dir.delete();
    }
}
