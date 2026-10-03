package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 🔖 صفِ «لغت/عبارت‌های انتخاب‌شده در پنلِ شناور» — پنل (که ممکنه اپ بسته باشه) اینجا می‌نویسه؛
 * اپ (وب‌ویو) موقعِ باز شدن/برگشتن با wordQueueList می‌خونه، به «ذخیره برای داستان بعدی» / «گرامر» /
 * «جعبه‌ی لایتنر» اضافه می‌کنه و با wordQueueAck پاکش می‌کنه.
 * key = action|lang|word؛ ثبتِ دوباره‌ی همان کلید، ردیفِ منتظر را جایگزین می‌کند (مثلاً وقتی معنی دیرتر رسید).
 * هر ردیف rev (ms) دارد تا ack فقط نسخه‌ی دیده‌شده را پاک کند.
 */
final class WordQueue {

    private static final String TAG = "WordQueue";
    private static final String FILE = "word_queue.json";
    private static final Object LOCK = new Object();

    private WordQueue() {}

    static boolean add(Context ctx, JSONObject item) {
        synchronized (LOCK) {
            try {
                String key = item.optString("key", "");
                JSONArray cur = read(ctx);
                JSONArray next = new JSONArray();
                for (int i = 0; i < cur.length(); i++) {
                    JSONObject o = cur.optJSONObject(i);
                    if (o != null && !key.equals(o.optString("key", ""))) next.put(o);
                }
                next.put(item);
                write(ctx, next);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "add failed: " + e);
                return false;
            }
        }
    }

    static JSONArray list(Context ctx) {
        synchronized (LOCK) { return read(ctx); }
    }

    /** فقط ردیف‌هایی که rev شان <= rev تأییدشده است پاک می‌شوند. */
    static void ack(Context ctx, JSONArray acked) {
        synchronized (LOCK) {
            try {
                JSONArray cur = read(ctx);
                JSONArray next = new JSONArray();
                for (int i = 0; i < cur.length(); i++) {
                    JSONObject o = cur.optJSONObject(i);
                    if (o == null) continue;
                    boolean drop = false;
                    for (int k = 0; acked != null && k < acked.length(); k++) {
                        JSONObject a = acked.optJSONObject(k);
                        if (a != null && a.optString("key", "").equals(o.optString("key", ""))
                                && o.optLong("rev", 0) <= a.optLong("rev", 0)) { drop = true; break; }
                    }
                    if (!drop) next.put(o);
                }
                write(ctx, next);
            } catch (Exception e) {
                Log.w(TAG, "ack failed: " + e);
            }
        }
    }

    private static JSONArray read(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return new JSONArray();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            String s = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            return s.trim().isEmpty() ? new JSONArray() : new JSONArray(s);
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    private static void write(Context ctx, JSONArray arr) throws Exception {
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            // renameTo روی بعضی دستگاه‌ها وقتی مقصد هست شکست می‌خورد
            f.delete();
            if (!tmp.renameTo(f)) throw new Exception("rename failed");
        }
    }
}
