package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * کشِ ماندگارِ ترجمه (روی حافظه‌ی گوشی) — همتای getCachedTranslation / setCachedTranslation در app.jsx.
 * فقط ترجمه‌های «تأییدشده» (که از تستِ مشکوک‌بودن رد شده‌اند) ذخیره می‌شوند. LRU با سقفِ MAX_ENTRIES.
 * نوشتن روی دیسک با تأخیر و در پس‌زمینه انجام می‌شود تا ترجمه‌ی زنده کند نشود.
 */
final class TransCache {
    private static final String TAG = "TransCache";
    private static final String FILE = "lingolearn_tr_cache.json";
    private static final int MAX_ENTRIES = 3000;
    private static final int MAX_TEXT_CHARS = 600;
    private static final long SAVE_DELAY_MS = 4000;

    private static TransCache instance;

    static synchronized TransCache get(Context ctx) {
        if (instance == null) instance = new TransCache(ctx.getApplicationContext());
        return instance;
    }

    private final File file;
    private final LinkedHashMap<String, String> map = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private boolean loaded = false;
    private boolean dirty = false;
    private boolean saveQueued = false;

    private TransCache(Context app) {
        file = new File(app.getFilesDir(), FILE);
    }

    private static String key(String src, String tgt, String text) {
        String t = text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return (src == null || src.isEmpty() ? "auto" : src) + ">" + tgt + "|" + t;
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (!file.exists()) return;
            byte[] buf = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            }
            JSONObject o = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (!v.isEmpty()) map.put(k, v);
            }
        } catch (Throwable e) {
            Log.w(TAG, "cache load failed - starting empty", e);
            map.clear();
        }
    }

    synchronized String get(String src, String tgt, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        ensureLoaded();
        return map.get(key(src, tgt, text));
    }

    synchronized void put(String src, String tgt, String text, String translation) {
        if (text == null || translation == null) return;
        if (text.length() > MAX_TEXT_CHARS || translation.trim().isEmpty()) return;
        ensureLoaded();
        map.put(key(src, tgt, text), translation.trim());
        dirty = true;
        if (!saveQueued) {
            saveQueued = true;
            try {
                io.schedule(this::flush, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable e) { saveQueued = false; }
        }
    }

    /** یک ورودیِ بد (مثلاً مشکوک) را از کش حذف می‌کند. */
    synchronized void remove(String src, String tgt, String text) {
        ensureLoaded();
        if (map.remove(key(src, tgt, text)) != null) dirty = true;
    }

    synchronized void flush() {
        saveQueued = false;
        if (!dirty) return;
        dirty = false;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) o.put(e.getKey(), e.getValue());
            File tmp = new File(file.getParentFile(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Throwable e) {
            Log.w(TAG, "cache save failed", e);
            dirty = true;
        }
    }
}
