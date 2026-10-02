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
        Map<String, TtsSpec> m = new HashMap<>();

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

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
    private static volatile boolean DOWNLOADING_STATE = false;

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

    static File getTtsModelDir(Context ctx, String lang) {
        String l = normalize(lang);
        TtsSpec s = l == null ? null : TTS_SPECS.get(l);
        if (s == null) return null;
        File dir = ttsDirFor(ctx, l);
        File model = new File(dir, "model.onnx");
        File tokens = new File(dir, "tokens.txt");
        File dataDir = new File(dir, "espeak-ng-data");
        if (!model.isFile() || model.length() == 0) return null;
        if (!tokens.isFile() || tokens.length() == 0) return null;
        if (!dataDir.isDirectory()) return null;
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
    static void downloadTtsModel(final Context ctx, final String lang, final ProgressCallback cb) {
        final Context app = ctx.getApplicationContext();
        final String l = normalize(lang);
        final TtsSpec s = l == null ? null : TTS_SPECS.get(l);
        if (s == null) {
            if (cb != null) cb.onError(String.valueOf(lang), new IllegalArgumentException("no TTS for " + lang));
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            if (cb != null) cb.onError(l, new IllegalStateException("another download is running"));
            return;
        }
        DOWNLOADING_STATE = true;
        Thread t = new Thread(() -> {
            try {
                File dir = ttsDirFor(app, l);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);

                // ۱. دانلود model.onnx
                File modelTarget = new File(dir, "model.onnx");
                if (!modelTarget.isFile() || modelTarget.length() == 0) {
                    downloadFile(
                            HF_BASE + s.repo + "/resolve/main/" + s.modelFile,
                            modelTarget, cb, l);
                }

                // ۲. دانلود tokens.txt
                File tokensTarget = new File(dir, "tokens.txt");
                if (!tokensTarget.isFile() || tokensTarget.length() == 0) {
                    downloadFile(
                            HF_BASE + s.repo + "/resolve/main/" + s.tokensFile,
                            tokensTarget, cb, l);
                }

                // ۳. دانلود espeak-ng-data (به صورت tar.bz2 یا یه فایل zip)
                // ⚠️ این بخش بستگی به ساختار مخزن داره. بعضی مخازن پوشه‌ی آماده دارن،
                // بعضی دیگه فایل tar.bz2. این کد فرض می‌کنه پوشه‌ی espeak-ng-data مستقیم هست.
                File dataDir = new File(dir, "espeak-ng-data");
                if (!dataDir.isDirectory()) {
                    // اگه مخزن پوشه‌ی espeak-ng-data رو جدا داره، باید همه‌ی فایل‌های
                    // داخلش رو دانلود کنی. این کار پیچیده‌ست و نیاز به لیست کردن مخزن داره.
                    // راه ساده‌تر: خودت یه بار دانلود کن و کنار APK بذار.
                    Log.w(TAG, "espeak-ng-data not found for " + l + " - model may not work");
                }

                if (cb != null) cb.onDone(l);
            } catch (Exception e) {
                Log.w(TAG, "TTS download failed for " + l, e);
                if (cb != null) cb.onError(l, e);
            } finally {
                DOWNLOADING.set(false);
                DOWNLOADING_STATE = false;
            }
        }, "sherpa-tts-download");
        t.setDaemon(true);
        t.start();
    }

    private static void downloadFile(String url, File target, ProgressCallback cb, String l) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        Request req = new Request.Builder().url(url).build();
        try (Response r = HTTP.newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                throw new java.io.IOException("HTTP " + r.code() + " for " + target.getName());
            }
            long done = 0;
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
