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

/**
 * Knows which languages have an on-device streaming Sherpa-ONNX model, where the model files live
 * (getFilesDir()/sherpa/&lt;lang&gt;/) and how to download them on demand from HuggingFace.
 *
 * Models are NEVER bundled in the APK. Languages without an entry in {@link #SPECS}
 * (Persian, Arabic, Turkish, ...) are "unsupported": the caller falls back to the Google/server path.
 *
 * To add a language, add one more {@code SPECS.put(...)} line below.
 */
final class SherpaModelManager {

    private static final String TAG = "SherpaModelMgr";
    private static final String HF_BASE = "https://huggingface.co/";

    private SherpaModelManager() {}

    interface ProgressCallback {
        /** @param done bytes downloaded so far (all files), @param total expected bytes or -1 */
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
        // English: streaming Zipformer (int8 encoder/joiner = ~72 MB total)
        m.put("en", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens.txt"));
        // Chinese (+English): bilingual streaming Zipformer
        m.put("zh", new Spec(
                "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt"));
        // de / fr / es (Kroko), ja, ko: add here once you have the exact HuggingFace repo + file names.
        SPECS = Collections.unmodifiableMap(m);
    }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    /** "en-US" / "en_US" / "EN" -> "en"; null if empty. */
    static String normalize(String lang) {
        if (lang == null) return null;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        int i = l.indexOf('-');
        if (i < 0) i = l.indexOf('_');
        if (i > 0) l = l.substring(0, i);
        return l.isEmpty() ? null : l;
    }

    /** True if a Sherpa model exists for this language (it may still need to be downloaded). */
    static boolean isAvailable(String lang) {
        String l = normalize(lang);
        return l != null && SPECS.containsKey(l);
    }

    private static File dirFor(Context ctx, String l) {
        return new File(new File(ctx.getFilesDir(), "sherpa"), l);
    }

    /** Model directory if every file is already downloaded; otherwise null. */
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

    /**
     * Downloads the model on a background thread into getFilesDir()/sherpa/&lt;lang&gt;/.
     * Files are written as *.part and renamed when complete, so an interrupted download never
     * leaves a half-written model that looks valid.
     */
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

    /** Deletes a downloaded model (frees storage). */
    static void deleteModel(Context ctx, String lang) {
        String l = normalize(lang);
        if (l == null) return;
        File dir = dirFor(ctx, l);
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) { //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
