package com.linglearn.app.overlay;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTS خودِ گوشی (android.speech.tts.TextToSpeech).
 * داخل WebView، speechSynthesis معمولاً صدایی نداره؛ این کلاس مستقیم از موتور سیستم می‌خونه.
 */
final class SystemTts {

    interface Done { void onDone(boolean ok); }

    private static final String TAG = "SystemTts";

    private final Context app;
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile boolean initFailed = false;
    private final Object lock = new Object();
    private final ConcurrentHashMap<String, Done> callbacks = new ConcurrentHashMap<>();

    SystemTts(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    private void ensureInit() {
        synchronized (lock) {
            if (tts != null || initFailed) return;
            try {
                tts = new TextToSpeech(app, status -> {
                    synchronized (lock) {
                        if (status == TextToSpeech.SUCCESS) {
                            ready = true;
                        } else {
                            initFailed = true;
                        }
                        lock.notifyAll();
                    }
                });
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) { finish(id, true); }
                    @Override public void onError(String id) { finish(id, false); }
                    @Override public void onError(String id, int code) { finish(id, false); }
                    @Override public void onStop(String id, boolean interrupted) { finish(id, true); }
                });
            } catch (Throwable t) {
                Log.w(TAG, "init failed", t);
                initFailed = true;
                tts = null;
            }
        }
    }

    private void finish(String id, boolean ok) {
        Done d = id == null ? null : callbacks.remove(id);
        if (d != null) d.onDone(ok);
    }

    /** منتظر آماده‌شدنِ موتور می‌مونه (حداکثر چند ثانیه). باید روی نخِ پس‌زمینه صدا زده بشه. */
    private boolean awaitReady() {
        ensureInit();
        long end = System.currentTimeMillis() + 6000;
        synchronized (lock) {
            while (!ready && !initFailed && System.currentTimeMillis() < end) {
                try { lock.wait(200); } catch (InterruptedException e) { return false; }
            }
        }
        return ready && tts != null;
    }

    private static Locale localeFor(String lang) {
        String l = SherpaModelManager.normalize(lang);
        if (l == null) return Locale.ENGLISH;
        switch (l) {
            case "en": return Locale.US;
            case "zh": return Locale.SIMPLIFIED_CHINESE;
            default: return new Locale(l);
        }
    }

    /** آیا این زبان رو موتورِ گوشی می‌خونه؟ */
    boolean isLanguageAvailable(String lang) {
        if (!awaitReady()) return false;
        try {
            int r = tts.isLanguageAvailable(localeFor(lang));
            return r >= TextToSpeech.LANG_AVAILABLE;
        } catch (Throwable t) {
            return false;
        }
    }

    void speak(String text, String lang, float speed, String id, Done cb) {
        if (!awaitReady()) { cb.onDone(false); return; }
        try {
            Locale loc = localeFor(lang);
            int r = tts.setLanguage(loc);
            if (r < TextToSpeech.LANG_AVAILABLE) { cb.onDone(false); return; }
            tts.setSpeechRate(Math.max(0.25f, Math.min(speed, 2.0f)));
            final String uid = id != null ? id : ("sys-" + System.nanoTime());
            callbacks.put(uid, cb);
            int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), uid);
            if (res != TextToSpeech.SUCCESS) {
                callbacks.remove(uid);
                cb.onDone(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "speak failed", t);
            cb.onDone(false);
        }
    }

    void stop() {
        try { if (tts != null && ready) tts.stop(); } catch (Throwable ignored) {}
    }

    void release() {
        synchronized (lock) {
            try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
            tts = null; ready = false; initFailed = false;
        }
        callbacks.clear();
    }
}
