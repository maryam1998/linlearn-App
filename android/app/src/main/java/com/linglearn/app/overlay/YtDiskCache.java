package com.linglearn.app.overlay;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 💾 کشِ ماندگارِ زیرنویسِ یوتیوب و ترجمه‌ها روی حافظه‌ی گوشی.
 * بار دوم که همان ویدیو پخش شود، نه شبکه‌ی زیرنویس لازم است نه ترجمه: همه‌چیز فوری از دیسک می‌آید.
 *  - زیرنویس: {videoId}_{lang}.caps.json  (۷ روز اعتبار)
 *  - ترجمه:   {videoId}_{lang}_{tone}.tr.json  (به‌همراهِ «امضای جمله‌ها»؛ اگر زیرنویس عوض شده باشد نادیده گرفته می‌شود)
 * همه‌ی متدها امنِ thread هستند (فایل‌ها اتمیک نوشته می‌شوند) و هیچ‌وقت exception بیرون نمی‌دهند.
 */
final class YtDiskCache {
    private static final String TAG = "YtDiskCache";
    private static final String DIR = "yt_cache";
    private static final long CAPS_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_FILES = 80;

    private YtDiskCache() {}

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String read(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void writeAtomic(File f, String data) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new Exception("rename failed");
        }
    }

    // ───────── زیرنویس ─────────

    static YtCaptionFetcher.Out loadCaps(Context c, String videoId, String lang) {
        try {
            File f = new File(dir(c), safe(videoId) + "_" + safe(lang) + ".caps.json");
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > CAPS_TTL_MS) return null;
            JSONObject o = new JSONObject(read(f));
            JSONArray a = o.getJSONArray("cues");
            List<YtSubtitles.Cue> cues = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                JSONArray q = a.getJSONArray(i);
                cues.add(new YtSubtitles.Cue(q.getLong(0), q.getLong(1), q.getString(2)));
            }
            if (cues.isEmpty()) return null;
            return new YtCaptionFetcher.Out(o.optString("lang", lang), o.optBoolean("auto", false), cues);
        } catch (Throwable t) {
            Log.w(TAG, "loadCaps failed: " + t);
            return null;
        }
    }

    static void saveCaps(Context c, String videoId, String lang, YtCaptionFetcher.Out out) {
        try {
            JSONArray a = new JSONArray();
            for (YtSubtitles.Cue q : out.cues) a.put(new JSONArray().put(q.startMs).put(q.endMs).put(q.text));
            JSONObject o = new JSONObject().put("lang", out.lang).put("auto", out.auto).put("cues", a);
            writeAtomic(new File(dir(c), safe(videoId) + "_" + safe(lang) + ".caps.json"), o.toString());
            prune(c);
        } catch (Throwable t) {
            Log.w(TAG, "saveCaps failed: " + t);
        }
    }

    // ───────── ترجمه ─────────

    /** امضای جمله‌ها: اگر زیرنویس عوض شده باشد، ترجمه‌ی ذخیره‌شده دیگر به همان جمله‌ها تعلق ندارد. */
    static String signature(List<String> sentTexts) {
        int n = sentTexts.size();
        if (n == 0) return "0";
        int h = 17;
        for (int i = 0; i < n; i += Math.max(1, n / 16)) h = h * 31 + sentTexts.get(i).hashCode();
        h = h * 31 + sentTexts.get(n - 1).hashCode();
        return n + ":" + Integer.toHexString(h);
    }

    static String[] loadTr(Context c, String videoId, String lang, String tone, String sig) {
        try {
            File f = new File(dir(c), safe(videoId) + "_" + safe(lang) + "_" + safe(tone) + ".tr.json");
            if (!f.exists()) return null;
            JSONObject o = new JSONObject(read(f));
            if (!sig.equals(o.optString("sig", ""))) return null;
            JSONArray a = o.getJSONArray("tr");
            String[] out = new String[a.length()];
            for (int i = 0; i < out.length; i++) {
                String v = a.isNull(i) ? "" : a.optString(i, "");
                out[i] = v.isEmpty() ? null : v;
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "loadTr failed: " + t);
            return null;
        }
    }

    static void saveTr(Context c, String videoId, String lang, String tone, String sig, String[] tr) {
        try {
            JSONArray a = new JSONArray();
            for (String s : tr) a.put(s == null ? "" : s);
            JSONObject o = new JSONObject().put("sig", sig).put("tr", a);
            writeAtomic(new File(dir(c), safe(videoId) + "_" + safe(lang) + "_" + safe(tone) + ".tr.json"), o.toString());
            prune(c);
        } catch (Throwable t) {
            Log.w(TAG, "saveTr failed: " + t);
        }
    }

    private static void prune(Context c) {
        try {
            File[] fs = dir(c).listFiles();
            if (fs == null || fs.length <= MAX_FILES) return;
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File x, File y) { return Long.compare(x.lastModified(), y.lastModified()); }
            });
            for (int i = 0; i < fs.length - MAX_FILES; i++) fs[i].delete();
        } catch (Throwable ignored) {}
    }
}
