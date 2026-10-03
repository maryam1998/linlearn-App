package com.linglearn.app.overlay;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Free, key-less translation with automatic fallback — the native twin of translateFree() in app.jsx.
 * Order (same as the rest of the app): Google (gtx) -> MyMemory -> Lingva -> LibreTranslate.
 * A service that fails on the network is skipped for a short cool-down so a blocked/filtered
 * service never slows down every sentence. Results are cached (LRU).
 * If every service fails an exception is thrown; the caller then falls back to the app's AI worker.
 */
final class FreeTranslator {
    private static final String TAG = "FreeTranslator";
    private static final int SERVICES = 4;
    private static final int CACHE_MAX = 500;
    private static final long COOLDOWN_MS = 45_000;
    private static final int MYMEMORY_MAX_CHARS = 450;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final Pattern MYMEMORY_ERR =
            Pattern.compile("^(PLEASE SELECT|INVALID |NO TRANSLATION|AMOUNT OF WORDS|MYMEMORY WARNING)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private static final long[] downUntil = new long[SERVICES];

    /** Result that came back but is clearly not a translation (does not put the service in cool-down). */
    private static final class BadResult extends IOException {
        BadResult(String m) { super(m); }
    }

    private FreeTranslator() {}

    static String translate(OkHttpClient http, String text, String src, String tgt) throws Exception {
        if (text == null) throw new IllegalArgumentException("no text");
        final String t = text.trim();
        if (t.isEmpty()) return "";
        final String s = (src == null || src.isEmpty()) ? "auto" : src;
        if (s.equals(tgt)) return t;

        final String key = s + ">" + tgt + "|" + t;
        synchronized (CACHE) {
            String c = CACHE.get(key);
            if (c != null) return c;
        }

        // services not in cool-down first; if all are cooling down, try them all anyway
        final long now = SystemClock.elapsedRealtime();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < SERVICES; i++) if (downUntil[i] <= now) order.add(i);
        if (order.isEmpty()) for (int i = 0; i < SERVICES; i++) order.add(i);

        Exception last = null;
        for (int svc : order) {
            try {
                String out = call(http, svc, t, s, tgt);
                out = out == null ? "" : out.trim();
                if (out.isEmpty()) throw new BadResult("empty result");
                if (looksUntranslated(t, out, s, tgt)) throw new BadResult("not translated");
                downUntil[svc] = 0;
                synchronized (CACHE) { CACHE.put(key, out); }
                return out;
            } catch (BadResult e) {
                last = e;
                Log.d(TAG, "service " + svc + ": " + e.getMessage());
            } catch (Exception e) {
                last = e;
                downUntil[svc] = SystemClock.elapsedRealtime() + COOLDOWN_MS;
                Log.d(TAG, "service " + svc + " failed: " + e);
            }
        }
        throw last != null ? last : new IOException("no translator available");
    }

    private static String call(OkHttpClient http, int svc, String text, String src, String tgt) throws Exception {
        switch (svc) {
            case 0: return google(http, text, src, tgt);
            case 1: return myMemory(http, text, src, tgt);
            case 2: return lingva(http, text, src, tgt);
            default: return libre(http, text, src, tgt);
        }
    }

    // ───────── services ─────────

    private static String google(OkHttpClient http, String text, String src, String tgt) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + enc(gCode(src))
                + "&tl=" + enc(gCode(tgt)) + "&dt=t&q=" + enc(text);
        JSONArray segs = new JSONArray(get(http, url)).getJSONArray(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length(); i++) {
            JSONArray seg = segs.optJSONArray(i);
            if (seg != null) sb.append(seg.optString(0, ""));
        }
        return sb.toString();
    }

    private static String myMemory(OkHttpClient http, String text, String src, String tgt) throws Exception {
        if (text.length() > MYMEMORY_MAX_CHARS) throw new BadResult("too long for MyMemory");
        String sl = "auto".equals(src) ? "en" : src;          // MyMemory has no auto-detect
        String url = "https://api.mymemory.translated.net/get?q=" + enc(text)
                + "&langpair=" + enc(gCode(sl) + "|" + gCode(tgt));
        JSONObject root = new JSONObject(get(http, url));
        JSONObject rd = root.optJSONObject("responseData");
        String out = rd == null ? "" : rd.optString("translatedText", "");
        if (out.isEmpty() || MYMEMORY_ERR.matcher(out.trim()).find()) throw new BadResult("MyMemory: " + out);
        return out;
    }

    private static String lingva(OkHttpClient http, String text, String src, String tgt) throws Exception {
        String url = "https://lingva.ml/api/v1/" + enc(src) + "/" + enc(tgt) + "/" + enc(text).replace("+", "%20");
        return new JSONObject(get(http, url)).optString("translation", "");
    }

    private static String libre(OkHttpClient http, String text, String src, String tgt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("q", text);
        body.put("source", src);
        body.put("target", tgt);
        body.put("format", "text");
        Request req = new Request.Builder()
                .url("https://libretranslate.de/translate")
                .header("User-Agent", UA)
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON_MT))
                .build();
        return new JSONObject(exec(http, req)).optString("translatedText", "");
    }

    // ───────── helpers ─────────

    private static String get(OkHttpClient http, String url) throws IOException {
        return exec(http, new Request.Builder().url(url).header("User-Agent", UA).build());
    }

    private static String exec(OkHttpClient http, Request req) throws IOException {
        Response r = http.newCall(req).execute();
        ResponseBody b = r.body();
        try {
            if (!r.isSuccessful() || b == null) throw new IOException("http " + r.code());
            return b.string();
        } finally {
            if (b != null) b.close();
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    /** Google / MyMemory want zh-CN for Chinese. */
    private static String gCode(String c) {
        return "zh".equals(c) ? "zh-CN" : c;
    }

    private static Pattern scriptOf(String lang) {
        switch (lang) {
            case "fa": case "ar": return Pattern.compile("[\\u0600-\\u06FF]");
            case "ru": return Pattern.compile("[\\u0400-\\u04FF]");
            case "zh": return Pattern.compile("[\\u4E00-\\u9FFF]");
            case "ja": return Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");
            case "ko": return Pattern.compile("[\\uAC00-\\uD7AF]");
            case "hi": return Pattern.compile("[\\u0900-\\u097F]");
            default: return null;
        }
    }

    /** Cheap sanity check (same idea as looksLikelyMistranslated in app.jsx). */
    private static boolean looksUntranslated(String in, String out, String src, String tgt) {
        if (in.length() <= 3) return false;
        if (!"auto".equals(src) && !src.equals(tgt) && out.toLowerCase(Locale.ROOT).equals(in.toLowerCase(Locale.ROOT))) return true;
        Pattern p = scriptOf(tgt);
        if (p != null && !p.matcher(out).find()) return true;
        double ratio = out.length() / (double) in.length();
        return in.length() > 8 && (ratio < 0.2 || ratio > 4.5);
    }
}
