package com.linglearn.app.overlay;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * سمتِ اپِ اصلی برای TtsService (پردازه‌ی :tts).
 * - پیام‌ها رو می‌فرسته؛ تا وصل‌شدنِ سرویس توی صف نگه می‌داره
 * - اگه پردازه‌ی TTS وسطِ کار مُرد: همه‌ی پخش‌های منتظر با ok=false تموم می‌شن (JS می‌ره سراغ fallback)،
 *   و علتِ مردن (مرحله + دلیلِ سیستم) به‌صورت Toast نشون داده می‌شه.
 * - اگه یه زبان پشتِ‌هم ۲ بار پردازه رو کشت، تا بازشدنِ دوباره‌ی اپ Piper براش غیرفعال می‌شه (حلقه‌ی کرش نشه).
 */
final class TtsClient {

    private static final String TAG = "TtsClient";
    private static final int MAX_CRASHES = 2;

    interface Listener { void onDone(String lang, String text, String id, boolean ok); }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<Message> OUTBOX = new ArrayList<>();
    // id → {lang, text}
    private static final ConcurrentHashMap<String, String[]> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> CRASHES = new ConcurrentHashMap<>();

    private static Context app;
    private static Listener listener;
    private static Messenger service;
    private static boolean binding = false;
    private static Messenger replyTo;

    private TtsClient() {}

    static void init(Context ctx, Listener l) {
        synchronized (LOCK) {
            app = ctx.getApplicationContext();
            listener = l;
            if (replyTo == null) {
                replyTo = new Messenger(new Handler(Looper.getMainLooper(), TtsClient::handleReply));
            }
        }
        TtsCrumb.init(ctx);
    }

    // ================= API =================

    static boolean isBlocked(String lang) {
        Integer n = CRASHES.get(lang);
        return n != null && n >= MAX_CRASHES;
    }

    static void resetCrash(String lang) {
        if (lang != null) CRASHES.remove(lang);
    }

    static void speak(String lang, String text, float speed, String id) {
        if (id != null) PENDING.put(id, new String[]{lang, text});
        Bundle b = new Bundle();
        b.putString("lang", lang);
        b.putString("text", text);
        b.putFloat("speed", speed);
        b.putString("id", id == null ? "" : id);
        send(TtsService.MSG_SPEAK, b);
    }

    static void stop() { send(TtsService.MSG_STOP, null); }

    static void prefetch(String lang, String text, float speed) {
        Bundle b = new Bundle();
        b.putString("lang", lang);
        b.putString("text", text);
        b.putFloat("speed", speed);
        send(TtsService.MSG_PREFETCH, b);
    }

    static void preload(String lang) {
        Bundle b = new Bundle();
        b.putString("lang", lang);
        send(TtsService.MSG_PRELOAD, b);
    }

    static void releaseLang(String lang) {
        Bundle b = new Bundle();
        b.putString("lang", lang);
        send(TtsService.MSG_RELEASE_LANG, b);
    }

    static void releaseAll() { send(TtsService.MSG_RELEASE_ALL, null); }

    // ================= ارسال / اتصال =================

    private static void send(int what, Bundle data) {
        Message m = Message.obtain(null, what);
        if (data != null) m.setData(data);
        synchronized (LOCK) {
            m.replyTo = replyTo;
            Messenger s = service;
            if (s != null) {
                try {
                    s.send(m);
                    return;
                } catch (RemoteException e) {
                    // پردازه مُرده؛ onServiceDisconnected هم می‌رسه
                    service = null;
                }
            }
            // stop/release وقتی سرویس بالا نیست معنی ندارن
            if (what == TtsService.MSG_STOP || what == TtsService.MSG_RELEASE_ALL
                    || what == TtsService.MSG_RELEASE_LANG || what == TtsService.MSG_PREFETCH) {
                if (!binding) return;
            }
            OUTBOX.add(m);
            bindLocked();
        }
    }

    private static void bindLocked() {
        if (binding || service != null || app == null) return;
        try {
            binding = true;
            boolean ok = app.bindService(new Intent(app, TtsService.class), CONN, Context.BIND_AUTO_CREATE);
            if (!ok) {
                binding = false;
                failAllPending("cannot bind TtsService");
            }
        } catch (Throwable t) {
            binding = false;
            Log.e(TAG, "bind failed", t);
            failAllPending("bind failed: " + t);
        }
    }

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (LOCK) {
                service = new Messenger(binder);
                binding = false;
                ArrayList<Message> copy = new ArrayList<>(OUTBOX);
                OUTBOX.clear();
                for (Message m : copy) {
                    try { service.send(m); } catch (RemoteException e) { service = null; break; }
                }
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) { onDied(); }
        @Override public void onBindingDied(ComponentName name) { onDied(); }
        @Override public void onNullBinding(ComponentName name) { onDied(); }
    };

    // ================= مرگِ پردازه =================

    private static void onDied() {
        final String stage;
        synchronized (LOCK) {
            service = null;
            binding = false;
            OUTBOX.clear();
            try { if (app != null) app.unbindService(CONN); } catch (Throwable ignored) {}
        }
        stage = TtsCrumb.read();
        final boolean crashed = stage.length() > 0 && !"idle".equals(stage);
        String crashLang = null;
        if (crashed) {
            String[] parts = stage.split(":");
            boolean langStage = stage.startsWith("create:") || stage.startsWith("warm:")
                    || stage.startsWith("gen:") || stage.startsWith("prefetch:");
            if (langStage && parts.length >= 2) crashLang = parts[1];
            if (crashLang != null) {
                Integer n = CRASHES.get(crashLang);
                CRASHES.put(crashLang, n == null ? 1 : n + 1);
            }
        }
        TtsCrumb.mark("idle");
        // پخش‌های منتظر → شکست (JS می‌ره سراغ fallback)
        failAllPending(null);
        if (crashed) {
            final String lg = crashLang;
            // ApplicationExitInfo کمی دیرتر ثبت می‌شه
            MAIN.postDelayed(() -> showCrashToast(stage, lg), 700);
        }
    }

    private static void failAllPending(String why) {
        ArrayList<String> ids = new ArrayList<>(PENDING.keySet());
        for (String id : ids) {
            String[] v = PENDING.remove(id);
            if (v != null) emit(v[0], v[1], id, false);
        }
    }

    private static void showCrashToast(String stage, String lang) {
        Context c = app;
        if (c == null) return;
        StringBuilder sb = new StringBuilder("Piper TTS crashed @ ").append(stage);
        sb.append(exitInfo());
        if (lang != null && isBlocked(lang)) {
            sb.append(" | Piper off for '").append(lang).append("' until app restart");
        }
        Log.e(TAG, sb.toString());
        try { Toast.makeText(c, sb.toString(), Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    private static String exitInfo() {
        if (Build.VERSION.SDK_INT < 30 || app == null) return "";
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "";
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 8);
            long now = System.currentTimeMillis();
            for (ApplicationExitInfo i : list) {
                String pn = i.getProcessName();
                if (pn == null || !pn.endsWith(":tts")) continue;
                if (now - i.getTimestamp() > 20000L) continue;
                String r;
                switch (i.getReason()) {
                    case ApplicationExitInfo.REASON_EXIT_SELF: r = "exit()"; break;
                    case ApplicationExitInfo.REASON_SIGNALED: r = "signal"; break;
                    case ApplicationExitInfo.REASON_LOW_MEMORY: r = "low-memory"; break;
                    case ApplicationExitInfo.REASON_CRASH: r = "java-crash"; break;
                    case ApplicationExitInfo.REASON_CRASH_NATIVE: r = "native-crash"; break;
                    default: r = "reason" + i.getReason();
                }
                String d = i.getDescription();
                return " | " + r + " status=" + i.getStatus() + (d == null ? "" : " " + d);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    // ================= پاسخ‌ها =================

    private static boolean handleReply(Message m) {
        if (m.what != TtsService.REPLY_DONE) return false;
        Bundle b = m.getData();
        if (b == null) return true;
        String id = b.getString("id", "");
        boolean ok = b.getBoolean("ok", false);
        String lang = b.getString("lang", "");
        String err = b.getString("err");
        String[] v = id.isEmpty() ? null : PENDING.remove(id);
        if (ok) {
            resetCrash(lang);
        } else if (err != null) {
            Log.w(TAG, "TTS failed (" + lang + "): " + err);
            final String msg = "Piper TTS (" + lang + "): " + err;
            MAIN.post(() -> {
                Context c = app;
                if (c != null) {
                    try { Toast.makeText(c, msg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
                }
            });
        }
        emit(lang, v == null ? "" : v[1], id, ok);
        return true;
    }

    private static void emit(String lang, String text, String id, boolean ok) {
        Listener l = listener;
        if (l == null) return;
        try { l.onDone(lang, text, id, ok); } catch (Throwable t) { Log.w(TAG, "listener failed", t); }
    }

    /** برای وقتی که Piper غیرفعاله: همون لحظه شکست اعلام می‌شه تا JS فوراً fallback کنه. */
    static void failFast(String lang, String text, String id) {
        emit(lang, text, id, false);
    }
}
