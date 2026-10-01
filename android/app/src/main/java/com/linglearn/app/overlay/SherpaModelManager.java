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
    // ✅ آینه چینی (از ایران قابل دسترسه)
    private static final String HF_BASE = "https://hf-mirror.com/";

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

        // ✅ انگلیسی - Streaming Zipformer
        m.put("en", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens.txt"));

        // ✅ چینی + انگلیسی - Streaming Zipformer
        m.put("zh", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt"));

        // 📌 برای اضافه کردن زبان‌های دیگه، از این لینک استفاده کن:
        // https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html

        SPECS = Collections.unmodifiableMap(m);
    }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
    private static volatile boolean DOWNLOADING_STATE = false;

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

    static boolean isDownloading() {
        return DOWNLOADING_STATE;
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
        DOWNLOADING_STATE = true;
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
                DOWNLOADING_STATE = false;
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
