package com.linglearn.app.overlay;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * موتورِ Piper توی یک پردازه‌ی جدا (android:process=":tts").
 * اگه کدِ native (sherpa-onnx / onnxruntime / espeak) کرش کنه یا exit() صدا بزنه،
 * فقط همین پردازه می‌میره و خودِ اپ باز می‌مونه؛ BubblePlugin خطا رو می‌گیره و fallback می‌ره.
 */
public class TtsService extends Service {

    private static final String TAG = "TtsService";

    // پیام‌های کلاینت → سرویس
    static final int MSG_SPEAK = 1;
    static final int MSG_STOP = 2;
    static final int MSG_PREFETCH = 3;
    static final int MSG_PRELOAD = 4;
    static final int MSG_RELEASE_ALL = 5;
    static final int MSG_RELEASE_LANG = 6;
    // پیام‌های سرویس → کلاینت
    static final int REPLY_DONE = 100;

    private static final int MAX_ENGINES = 2;

    private final ConcurrentHashMap<String, TtsEngine> engines = new ConcurrentHashMap<>();
    private volatile TtsEngine shared;
    private final AtomicInteger speakSeq = new AtomicInteger(0);
    private volatile Messenger client;

    private HandlerThread ctlThread;
    private Messenger messenger;
    // لودِ موتور (چند ثانیه) روی این thread انجام می‌شه تا thread ی کنترل هیچ‌وقت بلاک نشه
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-loader");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onCreate() {
        super.onCreate();
        TtsCrumb.init(this);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            TtsCrumb.mark("java-crash:" + TtsCrumb.brief(String.valueOf(e)));
            if (prev != null) prev.uncaughtException(t, e);
        });
        ctlThread = new HandlerThread("tts-ctl");
        ctlThread.start();
        messenger = new Messenger(new Handler(ctlThread.getLooper(), this::handle));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        try {
            for (TtsEngine e : engines.values()) {
                try { e.release(); } catch (Throwable ignored) {}
            }
            engines.clear();
        } catch (Throwable ignored) {}
        try { loader.shutdownNow(); } catch (Throwable ignored) {}
        try { if (ctlThread != null) ctlThread.quit(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ---------------------------------------------------------------

    private boolean handle(Message m) {
        if (m.replyTo != null) client = m.replyTo;
        Bundle b = m.getData();
        if (b == null) b = new Bundle();
        try {
            switch (m.what) {
                case MSG_SPEAK: {
                    final String text = b.getString("text", "");
                    final String lang = b.getString("lang", "en");
                    final String id = b.getString("id", "");
                    final float speed = b.getFloat("speed", 1.0f);
                    final boolean plain = b.getBoolean("plain", false);
                    final int mySeq = speakSeq.incrementAndGet();
                    try {
                        loader.execute(() -> doSpeak(text, lang, speed, id, mySeq, plain));
                    } catch (Throwable t) {
                        replyDone(lang, id, false, String.valueOf(t));
                    }
                    return true;
                }
                case MSG_STOP:
                    speakSeq.incrementAndGet();
                    for (TtsEngine e : engines.values()) {
                        try { e.stop(); } catch (Throwable ignored) {}
                    }
                    return true;
                case MSG_PREFETCH: {
                    String text = b.getString("text", "");
                    String lang = b.getString("lang", "en");
                    float speed = b.getFloat("speed", 1.0f);
                    TtsEngine e = engines.get(lang);
                    if (e != null && !e.isReleased()) {
                        e.plain = b.getBoolean("plain", false);
                        e.prefetch(text, speed);
                    }
                    return true;
                }
                case MSG_PRELOAD: {
                    final String lang = b.getString("lang", "");
                    loader.execute(() -> {
                        try {
                            TtsEngine e = obtainEngine(lang, false);
                            if (e != null) e.warmUp();
                        } catch (Throwable ignored) {}
                    });
                    return true;
                }
                case MSG_RELEASE_LANG: {
                    String lang = b.getString("lang", "");
                    synchronized (this) {
                        TtsEngine e = engines.remove(lang);
                        if (e != null) e.release();
                        if (shared == e) shared = null;
                    }
                    return true;
                }
                case MSG_RELEASE_ALL:
                    synchronized (this) {
                        for (TtsEngine e : engines.values()) {
                            try { e.release(); } catch (Throwable ignored) {}
                        }
                        engines.clear();
                        shared = null;
                    }
                    return true;
                default:
                    return false;
            }
        } catch (Throwable e) {
            Log.e(TAG, "handle failed", e);
            return true;
        }
    }

    private void doSpeak(String text, String lang, float speed, String id, int mySeq, boolean plain) {
        try {
            TtsEngine engine = obtainEngine(lang, true);
            if (engine == null) {
                String err = TtsEngine.lastCreateError;
                replyDone(lang, id, false, err == null ? "failed to load TTS engine" : err);
                return;
            }
            engine.plain = plain;
            // وسطِ لود، stop() یا speak ی جدید اومده → این یکی دیگه پخش نشه
            if (mySeq != speakSeq.get()) {
                replyDone(lang, id, true, null);
                return;
            }
            engine.speakAsync(text, speed, ok -> replyDone(lang, id, ok, null));
        } catch (Throwable e) {
            Log.e(TAG, "doSpeak failed", e);
            replyDone(lang, id, false, String.valueOf(e));
        }
    }

    /** موتورِ این زبان رو برمی‌گردونه (اگه لود نیست، لودش می‌کنه). */
    private TtsEngine obtainEngine(String lang, boolean forSpeak) {
        synchronized (this) {
            TtsEngine e = engines.get(lang);
            if (e != null && e.isReleased()) { engines.remove(lang); e = null; }
            if (e == null) {
                if (!forSpeak && engines.size() >= MAX_ENGINES) return null;
                e = TtsEngine.create(getApplicationContext(), lang);
                if (e == null) return null;
                engines.put(lang, e);
                while (engines.size() > MAX_ENGINES) {
                    TtsEngine victim = null;
                    for (TtsEngine x : engines.values()) {
                        if (x == e || x == shared) continue;
                        if (victim == null || x.lastUsed() < victim.lastUsed()) victim = x;
                    }
                    if (victim == null) break;
                    engines.remove(victim.lang());
                    victim.release();
                }
            }
            if (forSpeak) {
                e.touch();
                shared = e;
            }
            return e;
        }
    }

    private void replyDone(String lang, String id, boolean ok, String err) {
        Messenger c = client;
        if (c == null) return;
        try {
            Message r = Message.obtain(null, REPLY_DONE);
            Bundle b = new Bundle();
            b.putString("lang", lang);
            b.putString("id", id);
            b.putBoolean("ok", ok);
            if (err != null) b.putString("err", err);
            r.setData(b);
            c.send(r);
        } catch (RemoteException ignored) {
        } catch (Throwable t) {
            Log.w(TAG, "reply failed", t);
        }
    }
}
