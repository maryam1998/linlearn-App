package com.linglearn.app.overlay;

import android.content.Context;

/**
 * موقتاً غیرفعال شده تا بیلد سبز بشه.
 * بعداً با API صحیح Sherpa-ONNX پیاده‌سازی می‌شه.
 */
final class SherpaEngine {

    private SherpaEngine() {}

    static boolean isAvailable(Context ctx, String lang) {
        return false;
    }

    static SherpaEngine create(Context ctx, String lang) {
        return null;
    }

    void accept(byte[] pcm16k, int len) {
        // no-op
    }

    void release() {
        // no-op
    }
}
