package com.linglearn.app.overlay;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * موقتاً غیرفعال شده تا بیلد سبز بشه.
 */
final class SherpaModelManager {

    private SherpaModelManager() {}

    interface ProgressCallback {
        void onProgress(String lang, long done, long total);
        void onDone(String lang);
        void onError(String lang, Exception e);
    }

    static String normalize(String lang) {
        if (lang == null) return null;
        String l = lang.trim().toLowerCase(Locale.ROOT);
        int i = l.indexOf('-');
        if (i < 0) i = l.indexOf('_');
        if (i > 0) l = l.substring(0, i);
        return l.isEmpty() ? null : l;
    }

    static boolean isAvailable(String lang) {
        return false;
    }

    static File getModelDir(Context ctx, String lang) {
        return null;
    }

    static String encoderFile(String lang) { return null; }
    static String decoderFile(String lang) { return null; }
    static String joinerFile(String lang)  { return null; }
    static String tokensFile(String lang)  { return null; }

    static void downloadModel(Context ctx, String lang, ProgressCallback cb) {
        if (cb != null) cb.onError(lang, new UnsupportedOperationException("disabled"));
    }

    static void deleteModel(Context ctx, String lang) {
        // no-op
    }
}
