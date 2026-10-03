package com.linglearn.app.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 📺 زیرنویسِ زنده‌ی یوتیوب برای حبابِ شناور.
 *
 * سه کار (دقیقاً جدا از مسیرِ STT):
 *  ۱) تشخیصِ ویدیو و زمانِ پخش از MediaSession ([YtMedia.Tracker]).
 *  ۲) گرفتنِ کلِ زیرنویس (با زمان‌بندی) یک‌جا از بک‌اندِ خودمون (/api/youtube-captions).
 *  ۳) ترجمه‌ی تکه‌تکه (lazy): خط‌ها به chunk های ۷تایی تقسیم می‌شن و فقط «یک» chunk هم‌زمان در
 *     حالِ ترجمه‌ست (اول chunkِ جاریِ پخش، بعد چندتای بعدی). هر chunk با جمله‌های همسایه (قبل/بعد)
 *     به /api/translate-subtitles می‌ره؛ اگه اون در دسترس نبود، خط‌به‌خط با FreeTranslator ترجمه می‌شه.
 *
 * نمایش: با هر تیکِ ۱۵۰ms از [YtMedia.State.nowMs] + offset جمله‌ی فعلی پیدا می‌شه و به Host داده می‌شه.
 * چون زیرنویس یک‌جا اومده، نمایش هیچ وابستگی‌ای به شبکه نداره؛ فقط ترجمه ممکنه چند ثانیه عقب باشه
 * (برای خطِ جاری همزمان یک ترجمه‌ی سریعِ تک‌خطی هم می‌گیریم و بعداً با ترجمه‌ی chunk جایگزین می‌شه).
 */
final class YtSubtitles {

    private static final String TAG = "YtSubtitles";

    static final int CHUNK = 7;                    // تعداد خط در هر chunk
    private static final int AHEAD_CHUNKS = 3;     // چند chunk جلوتر از پخش از قبل ترجمه بشه
    private static final int CONTEXT_LINES = 3;    // جمله‌های همسایه (قبل/بعد) به‌عنوان زمینه
    private static final long TICK_MS = 150;
    private static final int MAX_FAILS = 2;

    private static final String CAPTIONS_PATH = "/api/youtube-captions";
    private static final String TRANSLATE_PATH = "/api/translate-subtitles";
    private static final String RESOLVE_PATH = "/api/youtube-resolve";

    /** پلی بین موتور و حباب (BubbleService). همه‌ی callback ها روی main thread صدا زده می‌شن. */
    interface Host {
        /** زبان‌های مقصدِ انتخابیِ کاربر. */
        List<String> targets();
        /** زبانِ گفتاریِ انتخاب‌شده در تنظیمات (برای انتخابِ ترکِ زیرنویس). "auto" هم ممکنه. */
        String requestedSource();
        String tone();
        String langName(String code);
        boolean fa();
        /** پیامِ کوتاهِ وضعیت؛ "" = پاک کن. */
        void notice(String message);
        /** خطِ جدیدِ جاری رو نشون بده. reset=true یعنی تاریخچه اول پاک بشه (seek / ویدیوی جدید). */
        void show(int idx, String src, Map<String, String> tr, boolean reset);
        /** ترجمه‌ی خطِ idx به lang رسید (ممکنه هنوز نمایش داده نشده باشه؛ Host خودش چک می‌کنه). */
        void update(int idx, String lang, String text);
    }

    static final class Cue {
        final long startMs;
        final long endMs;
        final String text;
        Cue(long s, long e, String t) { startMs = s; endMs = e; text = t; }
    }

    private final Context app;
    private final Host host;
    private final String base;
    private final OkHttpClient http;
    private final OkHttpClient httpFast;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService netCaps = Executors.newCachedThreadPool();   // fetchهای ویدیوی قبلی جلوی ویدیوی جدید را نگیرند
    private final ExecutorService netChunk = Executors.newSingleThreadExecutor();   // فقط یک chunk هم‌زمان
    private final ExecutorService netQuick = Executors.newFixedThreadPool(2);
    private final YtMedia.Tracker tracker;
    private final Map<String, String> idByTitle = new HashMap<>();
    // کشِ زیرنویسِ ویدیوهای اخیر (پخشِ دوباره / seek به ویدیوی قبلی فوری باشد)
    private final Map<String, YtCaptionFetcher.Out> capCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, YtCaptionFetcher.Out>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, YtCaptionFetcher.Out> e) { return size() > 6; }
            });

    private volatile boolean active = false;
    private volatile int gen = 0;                 // با هر ویدیو بالا می‌ره؛ جوابِ دیررسیده‌ی ویدیوی قبلی دور ریخته می‌شه
    private YtMedia.State last;
    private String curKey = "";
    private String curTitle = "";
    private List<Cue> cues = new ArrayList<>();
    private String trackLang = "en";
    private final HashMap<String, String[]> trs = new HashMap<>();   // lang -> ترجمه‌ی هر خط (null = هنوز نه)
    private final HashSet<String> done = new HashSet<>();            // "lang#chunk"
    private final HashMap<String, Integer> fails = new HashMap<>();
    private final HashSet<String> quickAsked = new HashSet<>();      // "lang#idx"
    private boolean chunkBusy = false;
    private int shownIdx = -1;
    private int tickCount = 0;
    private volatile long offsetMs = 0;

    YtSubtitles(Context ctx, Host host, String workerBase, OkHttpClient httpFast) {
        this.app = ctx.getApplicationContext();
        this.host = host;
        this.base = workerBase;
        this.httpFast = httpFast;
        this.http = httpFast.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build();
        this.tracker = new YtMedia.Tracker(app, this::onState);
    }

    boolean isActive() { return active; }

    void setOffsetMs(long ms) { offsetMs = ms; }

    /** false = «دسترسی به اعلان‌ها» داده نشده. */
    boolean start() {
        if (active) return true;
        if (!tracker.start()) return false;
        active = true;
        YtMedia.setActive(tracker);
        main.post(tick);
        return true;
    }

    void stop() {
        active = false;
        YtMedia.setActive(null);
        main.removeCallbacksAndMessages(null);
        tracker.stop();
        gen++;
        cues = new ArrayList<>();
        trs.clear(); done.clear(); fails.clear(); quickAsked.clear();
        netCaps.shutdownNow();
        netChunk.shutdownNow();
        netQuick.shutdownNow();
    }

    /** زبان‌های مقصد یا نمایش عوض شد. */
    void onSettingsChanged() {
        if (active) main.post(this::pump);
    }

    private String msg(String fa, String en) { return host.fa() ? fa : en; }

    // ════════════════════ ۱) ویدیو و زمانِ پخش ════════════════════

    private final Runnable loadRunnable = this::maybeLoad;

    private void onState(YtMedia.State s) {
        if (!active) return;
        last = s;
        if (!s.hasSession) return;
        String key = keyOf(s);
        if (key.equals(curKey)) return;
        curKey = key;
        main.removeCallbacks(loadRunnable);
        // با videoId فوری؛ بدون videoId کمی صبر می‌کنیم تا عنوانِ تبلیغ/گذرا جستجو نشه
        main.postDelayed(loadRunnable, s.videoId != null ? 0 : 400);
    }

    private String keyOf(YtMedia.State s) {
        String id = s.videoId != null ? s.videoId : idByTitle.get(s.title);
        if (id != null) return id;
        return "t:" + s.title + "|" + (s.durationMs / 1000);
    }

    private void maybeLoad() {
        final YtMedia.State s = last;
        if (!active || s == null || !s.hasSession) return;
        if (!keyOf(s).equals(curKey)) return;     // دوباره عوض شده
        String id = s.videoId != null ? s.videoId : idByTitle.get(s.title);
        if (id != null) { loadCaptions(id, s.title); return; }
        if (s.title.isEmpty()) return;
        // MediaSession شناسه نداد → از روی عنوان (+مدت و کانال) پیداش کن
        resetVideo();
        curTitle = s.title;
        final int g = gen;
        final String title = s.title, channel = s.channel;
        final long dur = s.durationMs / 1000;
        netCaps.execute(() -> {
            String found = null;
            String err = null;
            try {
                found = YtCaptionFetcher.resolve(httpFast, base, title, channel, dur);
            } catch (Exception e) {
                Log.w(TAG, "resolve failed: " + e);
                err = e.getMessage() == null ? "error" : e.getMessage();
            }
            final String id2 = found, ferr = err;
            main.post(() -> {
                if (g != gen || !active) return;
                if (id2 == null) {
                    host.notice(msg("نتوانستم ویدیو را تشخیص بدهم: ", "Couldn't identify the video: ") + ferr);
                    return;
                }
                idByTitle.put(title, id2);
                curKey = id2;     // تا با تغییرِ کلید دوباره لود نشود
                loadCaptions(id2, title);
            });
        });
    }

    // ════════════════════ ۲) گرفتنِ زیرنویس ════════════════════

    private void resetVideo() {
        gen++;
        cues = new ArrayList<>();
        trs.clear(); done.clear(); fails.clear(); quickAsked.clear();
        chunkBusy = false;
        shownIdx = -1;
    }

    private void loadCaptions(final String videoId, final String title) {
        resetVideo();
        curTitle = title == null ? "" : title;
        final int g = gen;
        String want = host.requestedSource();
        if (want == null || want.isEmpty() || "auto".equals(want)) want = "en";
        final String wantLang = want;
        final String cacheKey = videoId + "|" + wantLang;
        netCaps.execute(() -> {
            YtCaptionFetcher.Out got = capCache.get(cacheKey);
            String err = null;
            boolean noCaps = false;
            if (got == null) {
                try {
                    got = YtCaptionFetcher.fetch(httpFast, base, videoId, wantLang);
                    capCache.put(cacheKey, got);
                } catch (YtCaptionFetcher.Fail f) {
                    Log.w(TAG, "captions failed: " + f.getMessage());
                    err = f.getMessage();
                    noCaps = f.noCaptions;
                } catch (Exception e) {
                    Log.w(TAG, "captions failed: " + e);
                    err = e.getMessage() == null ? "error" : e.getMessage();
                }
            }
            final YtCaptionFetcher.Out fin = got;
            final String ferr = err;
            final boolean fNoCaps = noCaps;
            main.post(() -> {
                if (g != gen || !active) return;
                if (fin == null) {
                    host.notice(fNoCaps
                            ? msg("این ویدیو زیرنویس ندارد", "This video has no captions")
                            : msg("زیرنویس نیامد: ", "Captions failed: ") + ferr);
                    return;
                }
                cues = fin.cues;
                trackLang = fin.lang;
                host.notice("");
                pump();
            });
        });
    }

    // ════════════════════ نمایش (تیکِ ۱۵۰ms) ════════════════════

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try { onTick(); } catch (Throwable t) { Log.w(TAG, "tick failed", t); }
            main.postDelayed(this, TICK_MS);
        }
    };

    /** آخرین خطی که start ـش <= pos. */
    private int findCue(long pos) {
        int lo = 0, hi = cues.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs <= pos) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    private List<String> langs() {
        ArrayList<String> out = new ArrayList<>();
        List<String> ts = host.targets();
        if (ts != null) for (String t : ts) if (t != null && !t.isEmpty() && !t.equals(trackLang) && !out.contains(t)) out.add(t);
        return out;
    }

    private String[] arr(String lang) {
        String[] a = trs.get(lang);
        if (a == null || a.length != cues.size()) { a = new String[cues.size()]; trs.put(lang, a); }
        return a;
    }

    private void onTick() {
        final YtMedia.State s = last;
        if (cues.isEmpty() || s == null || !s.hasSession) return;
        long pos = s.nowMs() + offsetMs;
        int idx = findCue(pos);
        if (idx >= 0 && idx != shownIdx) {
            boolean reset = shownIdx < 0 || idx < shownIdx || idx - shownIdx > 3;   // seek یا شروع
            shownIdx = idx;
            Cue c = cues.get(idx);
            HashMap<String, String> tr = new HashMap<>();
            for (String lang : langs()) {
                String v = arr(lang)[idx];
                tr.put(lang, v == null ? "…" : v);
                if (v == null) quick(idx, lang);
            }
            host.show(idx, c.text, tr, reset);
            pump();
        }
        if (++tickCount % 7 == 0) pump();    // ~هر ثانیه: chunk های جدید/زبان‌های تازه
    }

    // ════════════════════ ۳) ترجمه‌ی lazy، chunk به chunk ════════════════════

    private int currentChunk() {
        int i = shownIdx;
        if (i < 0 && last != null && !cues.isEmpty()) i = findCue(last.nowMs() + offsetMs);
        return Math.max(0, i) / CHUNK;
    }

    private void pump() {
        if (!active || chunkBusy || cues.isEmpty()) return;
        final List<String> ls = langs();
        if (ls.isEmpty()) return;
        final int total = (cues.size() + CHUNK - 1) / CHUNK;
        final int cur = currentChunk();
        for (int d = 0; d <= AHEAD_CHUNKS; d++) {
            int c = cur + d;
            if (c >= total) break;
            for (String lang : ls) {
                if (done.contains(lang + "#" + c)) continue;
                startChunk(c, lang);
                return;
            }
        }
    }

    private void startChunk(final int chunk, final String lang) {
        chunkBusy = true;
        final int g = gen;
        final int from = chunk * CHUNK;
        final int to = Math.min(cues.size(), from + CHUNK);
        final List<String> lines = new ArrayList<>();
        for (int i = from; i < to; i++) lines.add(cues.get(i).text);
        final List<String> before = new ArrayList<>();
        for (int i = Math.max(0, from - CONTEXT_LINES); i < from; i++) before.add(cues.get(i).text);
        final List<String> after = new ArrayList<>();
        for (int i = to; i < Math.min(cues.size(), to + CONTEXT_LINES); i++) after.add(cues.get(i).text);
        final String src = trackLang;
        final String title = curTitle;
        final String tone = host.tone();
        final String srcName = host.langName(src);
        final String tgtName = host.langName(lang);
        final String key = lang + "#" + chunk;

        netChunk.execute(() -> {
            String[] out = null;
            try {
                out = requestChunk(lines, before, after, title, src, srcName, lang, tgtName, tone);
            } catch (Exception e) {
                Log.w(TAG, "chunk request failed (" + e + "); falling back to per-line translation");
            }
            if (out == null) {
                out = new String[lines.size()];
                for (int i = 0; i < lines.size(); i++) {
                    try { out[i] = FreeTranslator.translate(httpFast, lines.get(i), src, lang); }
                    catch (Exception e) { break; }     // شبکه/سرویس‌ها پایین‌ان؛ بقیه رو الکی منتظر نمون
                }
            }
            final String[] res = out;
            main.post(() -> {
                if (g != gen || !active) return;     // ویدیوی دیگه‌ای شده
                chunkBusy = false;
                boolean any = false;
                String[] a = arr(lang);
                for (int i = 0; i < res.length; i++) {
                    if (res[i] == null || res[i].trim().isEmpty()) continue;
                    any = true;
                    a[from + i] = res[i].trim();
                    host.update(from + i, lang, a[from + i]);
                }
                if (any) {
                    done.add(key);
                    pump();
                } else {
                    int f = fails.containsKey(key) ? fails.get(key) + 1 : 1;
                    fails.put(key, f);
                    if (f >= MAX_FAILS) done.add(key);            // دیگه تلاش نکن (برای این chunk)
                    main.postDelayed(this::pump, 2500);
                }
            });
        });
    }

    private String[] requestChunk(List<String> lines, List<String> before, List<String> after, String title,
                                  String src, String srcName, String tgt, String tgtName, String tone)
            throws Exception {
        JSONObject body = new JSONObject();
        body.put("subtitles", new JSONArray(lines));
        body.put("context_before", new JSONArray(before));
        body.put("context_after", new JSONArray(after));
        body.put("content_title", title == null ? "" : title);
        body.put("source_language", srcName);
        body.put("target_language", tgtName);
        body.put("source_lang_code", src);
        body.put("target_lang_code", tgt);
        body.put("translation_tone", tone == null ? "neutral" : tone);
        String resp = post(http, base + TRANSLATE_PATH, body.toString());
        JSONArray arr = new JSONObject(resp).getJSONArray("translations");
        if (arr.length() != lines.size()) throw new Exception("count mismatch");
        String[] out = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i, "");
        return out;
    }

    /** ترجمه‌ی سریعِ تک‌خطی برای خطِ جاری تا منتظر chunk نمونیم؛ chunk بعداً جایگزینش می‌کنه. */
    private void quick(final int idx, final String lang) {
        if (!quickAsked.add(lang + "#" + idx)) return;
        final int g = gen;
        final String text = cues.get(idx).text;
        final String src = trackLang;
        try {
            netQuick.execute(() -> {
                final String out;
                try { out = FreeTranslator.translate(httpFast, text, src, lang); }
                catch (Exception e) { return; }
                main.post(() -> {
                    if (g != gen || !active) return;
                    String[] a = arr(lang);
                    if (idx < a.length && a[idx] == null) {     // اگه chunk زودتر رسیده بود، دست نزن
                        a[idx] = out;
                        host.update(idx, lang, out);
                    }
                });
            });
        } catch (Exception ignored) {}
    }

    // ════════════════════ ابزارهای شبکه ════════════════════

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String normLang(String l) {
        if (l == null || l.isEmpty()) return "en";
        String x = l.toLowerCase(Locale.ROOT);
        int i = x.indexOf('-');
        return i > 0 ? x.substring(0, i) : x;
    }

    private static String getString(OkHttpClient c, String url) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response r = c.newCall(req).execute()) {
            ResponseBody b = r.body();
            String s = b == null ? "" : b.string();
            if (!r.isSuccessful()) throw new Exception("HTTP " + r.code() + " " + brief(s));
            return s;
        }
    }

    private static String post(OkHttpClient c, String url, String json) throws Exception {
        Request req = new Request.Builder().url(url)
                .post(RequestBody.create(json.getBytes(StandardCharsets.UTF_8), JSON_MT)).build();
        try (Response r = c.newCall(req).execute()) {
            ResponseBody b = r.body();
            String s = b == null ? "" : b.string();
            if (!r.isSuccessful()) throw new Exception("HTTP " + r.code() + " " + brief(s));
            return s;
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
