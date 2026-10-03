package com.linglearn.app.overlay;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
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

    // زبان‌هایی که مسیرِ callback کرش کرده → generate() ی ساده
    private static final java.util.Set<String> PLAIN = ConcurrentHashMap.newKeySet();
    private static final String PREFS = "tts_engine";

    static boolean isPlain(String lang) { return lang != null && PLAIN.contains(lang); }

    private static void persistPlain(String lang) {
        try {
            Context c = app;
            if (c != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("plain_" + lang, true).apply();
        } catch (Throwable ignored) {}
    }

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
        try {
            android.content.SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (String lg : SherpaModelManager.ttsLanguages()) {
                if (sp.getBoolean("plain_" + lg, false)) PLAIN.add(lg);
            }
        } catch (Throwable ignored) {}
    }

    /** پنلِ شناور (BubbleService) وقتی اپ بسته است هم باید بتواند بخواند؛ فقط اگر هنوز init نشده init می‌کند
     *  (listenerِ پلاگین را که بعداً در load() ست می‌شود خراب نمی‌کند). */
    static void initIfNeeded(Context ctx) {
        synchronized (LOCK) {
            if (replyTo != null) return;
        }
        init(ctx, (lang, text, id, ok) -> {});
    }

    /** callbackِ یک‌بارمصرف برای یک پخشِ مشخص (id) — مستقل از listenerِ اصلی (که به JS می‌رود). */
    private static final ConcurrentHashMap<String, Listener> ONESHOT = new ConcurrentHashMap<>();

    static void speakWith(String lang, String text, float speed, String id, Listener cb) {
        if (cb != null && id != null) ONESHOT.put(id, cb);
        speak(lang, text, speed, id);
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
        b.putBoolean("plain", isPlain(lang));
        send(TtsService.MSG_SPEAK, b);
    }

    static void stop() { send(TtsService.MSG_STOP, null); }

    static void prefetch(String lang, String text, float speed) {
        Bundle b = new Bundle();
        b.putString("lang", lang);
        b.putString("text", text);
        b.putFloat("speed", speed);
        b.putBoolean("plain", isPlain(lang));
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
                    || stage.startsWith("gen:") || stage.startsWith("genp:")
                    || stage.startsWith("prefetch:") || stage.startsWith("prefetchp:");
            if (langStage && parts.length >= 2) crashLang = parts[1];
            // مسیرِ callback کرش کرد → دفعه‌ی بعد generate() ی ساده
            if (crashLang != null && (stage.startsWith("gen:") || stage.startsWith("prefetch:"))) {
                PLAIN.add(crashLang);
                persistPlain(crashLang);
            }
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
        String info = exitInfo();
        StringBuilder sb = new StringBuilder("Piper TTS crashed @ ").append(stage).append(info);
        if (lang != null && isBlocked(lang)) {
            sb.append(" | Piper off for '").append(lang).append("' until app restart");
        } else if (lang != null && isPlain(lang)) {
            sb.append(" | retry in safe mode");
        }
        final String full = sb.toString() + lastTrace;
        Log.e(TAG, full);
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("piper-crash", full));
        } catch (Throwable ignored) {}
        String shortMsg = sb.toString();
        if (shortMsg.length() > 120) shortMsg = shortMsg.substring(0, 120) + "…";
        try { Toast.makeText(c, shortMsg + "\n(full details copied — paste it to Claude)", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    private static volatile String lastTrace = "";

    /** از tombstone (protobuf) فقط رشته‌های خوانا و مفید رو بیرون می‌کشه. */
    private static String traceStrings(ApplicationExitInfo i) {
        try {
            java.io.InputStream in = i.getTraceInputStream();
            if (in == null) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < 400_000) { bo.write(buf, 0, n); total += n; }
            in.close();
            byte[] d = bo.toByteArray();
            StringBuilder out = new StringBuilder();
            StringBuilder cur = new StringBuilder();
            java.util.LinkedHashSet<String> keep = new java.util.LinkedHashSet<>();
            for (int k = 0; k <= d.length; k++) {
                int ch = k < d.length ? (d[k] & 0xff) : 0;
                if (ch >= 32 && ch < 127) { cur.append((char) ch); continue; }
                if (cur.length() >= 6) {
                    String x = cur.toString();
                    String lx = x.toLowerCase(java.util.Locale.ROOT);
                    if (lx.contains("abort") || lx.contains("signal") || lx.contains("sherpa")
                            || lx.contains("onnx") || lx.contains("espeak") || lx.contains("fault")
                            || lx.contains("libc.so") || lx.contains("jni") || lx.contains("assert")
                            || lx.contains("terminate") || lx.contains("what()")) {
                        keep.add(x.length() > 160 ? x.substring(0, 160) : x);
                    }
                }
                cur.setLength(0);
                if (keep.size() >= 40) break;
            }
            for (String x : keep) out.append("\n").append(x);
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
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
                lastTrace = i.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                        ? "\n--- trace ---" + traceStrings(i) : "";
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
        if (id != null && !id.isEmpty()) {
            Listener one = ONESHOT.remove(id);
            if (one != null) {
                try { one.onDone(lang, text, id, ok); } catch (Throwable t) { Log.w(TAG, "oneshot failed", t); }
            }
        }
        Listener l = listener;
        if (l == null) return;
        try { l.onDone(lang, text, id, ok); } catch (Throwable t) { Log.w(TAG, "listener failed", t); }
    }

    /** برای وقتی که Piper غیرفعاله: همون لحظه شکست اعلام می‌شه تا JS فوراً fallback کنه. */
    static void failFast(String lang, String text, String id) {
        emit(lang, text, id, false);
    }
}
