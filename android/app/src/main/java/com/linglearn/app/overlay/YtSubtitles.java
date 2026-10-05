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

    static final int CHUNK = 8;                    // تعداد «جمله» در هر chunk (نه تکه‌ی زیرنویس)
    private static final int PARALLEL = 3;         // چند chunk هم‌زمان ترجمه شود
    private static final long GAP_BREAK_MS = 1200;  // مکثِ بیشتر از این بینِ دو تکه = مرزِ جمله
    private static final int MAX_SENT_CHARS = 220;  // جمله‌ی بدونِ نقطه از این بلندتر نشه
    // زیرنویسِ خودکارِ یوتیوب (بدونِ نقطه‌گذاری): به‌جای یک بلوکِ بلند، جمله‌های کوتاهِ قابل‌خواندن
    private static final long AUTO_GAP_MS = 450;     // مکثِ بیشتر از این = مرزِ جمله
    private static final int AUTO_MIN_CHARS = 24;    // جمله‌ی خیلی کوتاه با مکث جدا نشه
    private static final int AUTO_MAX_CHARS = 70;    // بعد از این طول، سرِ اولین مرزِ تکه می‌شکنه
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
        /** کلِ زیرنویس (جمله‌به‌جمله) رسید: همه‌ی جمله‌ها یک‌جا و کم‌رنگ در پنل چیده شوند.
         *  tr: lang -> ترجمه‌ی هر جمله (null = هنوز نه). cur = جمله‌ی در حالِ پخش (یا -1). */
        void loadAll(List<String> sentences, Map<String, String[]> tr, int cur);
        /** جمله‌ی در حالِ پخش عوض شد: پررنگش کن. force=true (seek/شروع) یعنی حتماً اسکرول کن. */
        void setCurrent(int sentIdx, boolean force);
        /** ترجمه‌ی جمله‌ی sentIdx به lang رسید. */
        void updateSentence(int sentIdx, String lang, String text);
        /** ویدیوی دیگری شروع شد: تاریخچه‌ی ویدیوی قبلی باید پاک شود. */
        void videoChanged();
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
    private final ExecutorService netChunk = Executors.newFixedThreadPool(PARALLEL);   // چند chunk موازی
    private final ExecutorService netIo = Executors.newSingleThreadExecutor();        // نوشتن کش روی دیسک
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
    String trackLang() { return trackLang; }
    /** ویدیوی یوتیوب همین الان در حالِ پخش است؟ (برای باز/بسته‌شدنِ خودکارِ کادر) */
    boolean isPlaying() { YtMedia.State s = last; return s != null && s.hasSession && s.playing; }
    private final HashMap<String, String[]> trs = new HashMap<>();   // lang -> ترجمه‌ی هر خط (null = هنوز نه)
    // ── ترجمه در سطحِ «جمله»: تکه‌های زیرنویس یوتیوب وسطِ جمله بریده می‌شن و ترجمه‌ی جدا‌جدایِ هر تکه غلط درمی‌آد.
    //    پس جمله‌ی کامل ترجمه می‌شه و بعد کلمه‌های ترجمه بینِ همون تکه‌ها (به نسبتِ طولِ متنِ اصلی) پخش می‌شه.
    private List<int[]> sents = new ArrayList<>();                 // هر جمله: {شروع، پایان} توی متنِ چسبیده
    private List<String> sentTexts = new ArrayList<>();
    private int[] cueRangeS = new int[0];                          // محدوده‌ی هر تکه توی متنِ چسبیده (کاشی‌شده، بدونِ فاصله)
    private int[] cueRangeE = new int[0];
    private int[][] cueSents = new int[0][];                       // هر تکه: جمله‌هایی که باهاشون هم‌پوشانی داره
    private int[] sentFirstCue = new int[0];
    private int[] sentLastCue = new int[0];
    private final HashMap<String, String[]> sentTr = new HashMap<>();   // lang -> ترجمه‌ی هر جمله
    private final HashSet<String> done = new HashSet<>();            // "lang#chunk"
    private final HashMap<String, Integer> fails = new HashMap<>();
    private final HashSet<String> quickAsked = new HashSet<>();      // "lang#idx"
    private final HashSet<String> running = new HashSet<>();      // "lang#chunk" های در حالِ ترجمه
    private int shownIdx = -1;
    private int shownSent = -1;
    private int tickCount = 0;
    private volatile long offsetMs = 0;
    private final java.util.TreeSet<Integer> shown = new java.util.TreeSet<>();   // خط‌هایی که واقعاً روی پنل نمایش داده شدند (برای ذخیره)
    private String curVideoId = "";
    private String histVideoId = "";   // ویدیوی آخرینِ تاریخچه‌ی روی پنل (با resetVideo پاک نمی‌شود)

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

    /**
     * 💾 عکسِ فوریِ جلسه برای ذخیره در «داستان‌های ذخیره‌شده»: «کلِ» زیرنویسِ ویدیو (حتی خط‌هایی که هنوز
     * نخوانده/پخش نشده‌اند، به ترتیبِ زمان) + ترجمه‌هایی که تا الان رسیده + لینکِ ویدیو. null = هنوز چیزی برای ذخیره نیست.
     * فقط روی main thread صدا بزن.
     */
    JSONObject snapshot() throws Exception {
        if (cues.isEmpty() || curVideoId.isEmpty()) return null;
        List<String> ls = langs();
        JSONArray lines = new JSONArray();
        for (int idx = 0; idx < cues.size(); idx++) {
            Cue c = cues.get(idx);
            if (c.text == null || c.text.trim().isEmpty()) continue;
            JSONObject tr = new JSONObject();
            for (String lang : ls) {
                String[] a = trs.get(lang);
                if (a != null && idx < a.length && a[idx] != null && !a[idx].trim().isEmpty()) tr.put(lang, a[idx]);
            }
            lines.put(new JSONObject().put("t", c.startMs / 1000.0).put("s", c.text).put("tr", tr));
        }
        if (lines.length() == 0) return null;
        long now = System.currentTimeMillis();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String iso = fmt.format(new java.util.Date(now));
        YtMedia.State s = last;
        return new JSONObject()
                .put("key", curVideoId)
                .put("videoId", curVideoId)
                .put("url", "https://youtu.be/" + curVideoId)
                .put("title", curTitle)
                .put("channel", s == null || s.channel == null ? "" : s.channel)
                .put("lang", trackLang)
                .put("targets", new JSONArray(ls))
                .put("rev", now)
                .put("savedAt", iso)
                .put("lines", lines);
    }

    // ── 🔁 تکرارِ جمله با صدای خودِ پلیر یوتیوب
    private int loopSent = -1;            // جمله‌ی در حالِ تکرار (-1 = هیچ)
    private int loopLeft = 0;             // تعداد دفعاتِ باقی‌مانده‌ی برگشت (-1 = بی‌نهایت)
    private long loopStartMs = 0, loopEndMs = 0;
    private long loopCooldownUntil = 0;   // بعد از هر seek چند لحظه صبر می‌کنیم تا موقعیتِ پلیر به‌روز شود

    boolean isLooping(int sentIdx) { return loopSent >= 0 && loopSent == sentIdx; }

    void stopLoop() { loopSent = -1; loopLeft = 0; }

    /**
     * پلیر یوتیوب را به ابتدای جمله‌ی sentIdx برمی‌گرداند و با صدای خودِ یوتیوب پخش می‌کند.
     * times: تعداد کلِ پخش‌ها (۱ = یک بار)، یا -1 = بی‌نهایت تا وقتی کاربر دوباره بزند.
     */
    boolean replaySentence(int sentIdx, int times) {
        if (!active || sentIdx < 0 || sentIdx >= sentFirstCue.length) return false;
        int c = sentFirstCue[sentIdx], c2 = sentLastCue[sentIdx];
        if (c < 0 || c >= cues.size() || c2 < c) return false;
        loopStartMs = Math.max(0L, cues.get(c).startMs - offsetMs);
        long end = cues.get(c2).endMs;
        if (end <= cues.get(c).startMs) end = cues.get(c).startMs + 2500;
        loopEndMs = end - offsetMs;
        boolean ok = tracker.seekTo(loopStartMs);
        if (!ok) { stopLoop(); return false; }
        tracker.play();
        loopCooldownUntil = android.os.SystemClock.uptimeMillis() + 900;
        if (times == 1 || times == 0) stopLoop();
        else { loopSent = sentIdx; loopLeft = times < 0 ? -1 : times - 1; }
        return true;
    }

    private void loopTick(long pos) {
        if (loopSent < 0) return;
        if (android.os.SystemClock.uptimeMillis() < loopCooldownUntil) return;
        long p = pos - offsetMs;
        if (p >= loopEndMs - 150) {
            // کاربر خودش جلو/عقب رفته (خیلی دور از جمله) → تکرار را رها کن
            if (p > loopEndMs + 2500 || p < loopStartMs - 2500) { stopLoop(); return; }
            if (loopLeft == 0) { stopLoop(); return; }
            if (loopLeft > 0) loopLeft--;
            if (tracker.seekTo(loopStartMs)) {
                tracker.play();
                loopCooldownUntil = android.os.SystemClock.uptimeMillis() + 900;
            } else stopLoop();
        } else if (p < loopStartMs - 2500) {
            stopLoop();
        }
    }

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
        stopLoop();
        active = false;
        YtMedia.setActive(null);
        main.removeCallbacksAndMessages(null);
        tracker.stop();
        gen++;
        cues = new ArrayList<>();
        buildSentences();
        trs.clear(); sentTr.clear(); done.clear(); fails.clear(); quickAsked.clear();
        netCaps.shutdownNow();
        netChunk.shutdownNow();
        netQuick.shutdownNow();
        netIo.shutdown();
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
        stopLoop();
        gen++;
        cues = new ArrayList<>();
        buildSentences();
        trs.clear(); sentTr.clear(); done.clear(); fails.clear(); quickAsked.clear();
        running.clear();
        shownIdx = -1;
        shownSent = -1;
        shown.clear();
        curVideoId = "";
    }

    private void loadCaptions(final String videoId, final String title) {
        final boolean other = !videoId.equals(histVideoId);
        histVideoId = videoId;
        resetVideo();
        if (other) host.videoChanged();
        curTitle = title == null ? "" : title;
        curVideoId = videoId;
        final int g = gen;
        String want = host.requestedSource();
        if (want == null || want.isEmpty() || "auto".equals(want)) want = "en";
        final String wantLang = want;
        final String cacheKey = videoId + "|" + wantLang;
        netCaps.execute(() -> {
            YtCaptionFetcher.Out got = capCache.get(cacheKey);
            if (got == null) {
                got = YtDiskCache.loadCaps(app, videoId, wantLang);      // 💾 بار دوم: فوری از دیسک
                if (got != null) capCache.put(cacheKey, got);
            }
            String err = null;
            boolean noCaps = false;
            if (got == null) {
                try {
                    got = YtCaptionFetcher.fetch(httpFast, base, videoId, wantLang);
                    capCache.put(cacheKey, got);
                    YtDiskCache.saveCaps(app, videoId, wantLang, got);
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
                buildSentences();
                host.notice("");
                restoreCachedTranslations();     // 💾 ترجمه‌های قبلیِ همین ویدیو (اگر هست) فوری
                publishList();                   // 📜 کلِ زیرنویس یک‌جا و کم‌رنگ در پنل
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
        loopTick(pos);
        int idx = findCue(pos);
        if (idx >= 0 && idx != shownIdx) {
            boolean reset = shownIdx < 0 || idx < shownIdx || idx - shownIdx > 3;   // seek یا شروع
            shownIdx = idx;
            shown.add(idx);
            for (String lang : langs()) {
                if (arr(lang)[idx] == null) quick(idx, lang);     // ترجمه‌ی سریعِ جمله‌ی جاری تا منتظرِ chunk نمانیم
            }
            int si = sentOfCue(idx);
            if (si >= 0 && (si != shownSent || reset)) {
                shownSent = si;
                host.setCurrent(si, reset);
            }
            pump();
        }
        if (++tickCount % 7 == 0) pump();    // ~هر ثانیه: chunk های جدید/زبان‌های تازه
    }

    // ════════════════════ ۳) ترجمه‌ی lazy، chunk به chunk ════════════════════

    /** جمله‌ای که بیشترین هم‌پوشانی را با تکه‌ی cue دارد. */
    private int sentOfCue(int cue) {
        if (cue < 0 || cue >= cueSents.length || cueSents[cue].length == 0) return -1;
        int best = cueSents[cue][0], bestOv = -1;
        for (int si : cueSents[cue]) {
            int[] sp = sents.get(si);
            int ov = Math.min(cueRangeE[cue], sp[1]) - Math.max(cueRangeS[cue], sp[0]);
            if (ov >= bestOv) { bestOv = ov; best = si; }
        }
        return best;
    }

    private static final int MAX_LIST = 3000;

    /** کلِ جمله‌ها + ترجمه‌های موجود را به پنل می‌دهد. */
    private void publishList() {
        if (sents.isEmpty()) return;
        int n = Math.min(sents.size(), MAX_LIST);
        List<String> texts = new ArrayList<>(sentTexts.subList(0, n));
        HashMap<String, String[]> trCopy = new HashMap<>();
        for (String lang : langs()) {
            String[] a = sentArr(lang);
            trCopy.put(lang, java.util.Arrays.copyOf(a, n));
        }
        int cur = -1;
        if (last != null && last.hasSession) cur = sentOfCue(findCue(last.nowMs() + offsetMs));
        host.loadAll(texts, trCopy, cur);
    }

    private int currentChunk() {
        int i = shownIdx;
        if (i < 0 && last != null && !cues.isEmpty()) i = findCue(last.nowMs() + offsetMs);
        i = Math.max(0, i);
        int si = (i < cueSents.length && cueSents[i].length > 0) ? cueSents[i][0] : 0;
        return si / CHUNK;
    }

    /** ⚡ ترجمه‌ی کلِ ویدیو در پس‌زمینه: اول chunk جاری، بعد به‌ترتیبِ جلوتر، بعد عقب‌تر؛ تا PARALLEL تا هم‌زمان. */
    private void pump() {
        if (!active || cues.isEmpty() || sents.isEmpty()) return;
        final List<String> ls = langs();
        if (ls.isEmpty()) return;
        final int total = (sents.size() + CHUNK - 1) / CHUNK;
        final int cur = Math.min(currentChunk(), total - 1);
        // ترتیبِ اولویت: cur, cur+1, ..., total-1, cur-1, cur-2, ..., 0
        for (int step = 0; step < total && running.size() < PARALLEL; step++) {
            int c = (cur + step < total) ? cur + step : cur - (cur + step - total + 1);
            if (c < 0 || c >= total) continue;
            for (String lang : ls) {
                if (running.size() >= PARALLEL) break;
                String key = lang + "#" + c;
                if (done.contains(key) || running.contains(key)) continue;
                startChunk(c, lang);
            }
        }
    }

    private void startChunk(final int chunk, final String lang) {
        final String key = lang + "#" + chunk;
        running.add(key);
        final int g = gen;
        final int from = chunk * CHUNK;
        final int to = Math.min(sents.size(), from + CHUNK);
        final List<String> lines = new ArrayList<>();
        for (int i = from; i < to; i++) lines.add(sentTexts.get(i));
        final List<String> before = new ArrayList<>();
        for (int i = Math.max(0, from - CONTEXT_LINES); i < from; i++) before.add(sentTexts.get(i));
        final List<String> after = new ArrayList<>();
        for (int i = to; i < Math.min(sents.size(), to + CONTEXT_LINES); i++) after.add(sentTexts.get(i));
        final String src = trackLang;
        final String title = curTitle;
        final String tone = host.tone();
        final String srcName = host.langName(src);
        final String tgtName = host.langName(lang);

        netChunk.execute(() -> {
            String[] out = null;
            try {
                out = requestChunk(lines, before, after, title, src, srcName, lang, tgtName, tone);
            } catch (Exception e) {
                Log.w(TAG, "chunk request failed (" + e + "); falling back to per-sentence translation");
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
                running.remove(key);
                boolean any = false;
                for (int i = 0; i < res.length; i++) {
                    if (res[i] == null || res[i].trim().isEmpty()) continue;
                    any = true;
                    setSentence(from + i, lang, res[i].trim(), true);
                }
                if (any) {
                    done.add(key);
                    persistTranslations(lang);
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

    // ───── 💾 کشِ ماندگارِ ترجمه‌ها ─────

    /** ترجمه‌های ذخیره‌شده‌ی همین ویدیو را (اگر هست) بدونِ شبکه اعمال می‌کند. روی main thread. */
    private void restoreCachedTranslations() {
        if (curVideoId.isEmpty() || sents.isEmpty()) return;
        final String sig = YtDiskCache.signature(sentTexts);
        final String tone = host.tone() == null ? "neutral" : host.tone();
        for (String lang : langs()) {
            String[] saved = YtDiskCache.loadTr(app, curVideoId, lang, tone, sig);
            if (saved == null || saved.length != sents.size()) continue;
            String[] sa = sentArr(lang);
            for (int i = 0; i < saved.length; i++) if (saved[i] != null) sa[i] = saved[i];
            String[] a = arr(lang);
            for (int c = 0; c < a.length; c++) {
                String v = composeCue(lang, c);
                if (v != null) a[c] = v;
            }
            final int total = (sents.size() + CHUNK - 1) / CHUNK;
            for (int ch = 0; ch < total; ch++) {
                boolean full = true;
                for (int i = ch * CHUNK; i < Math.min(sents.size(), (ch + 1) * CHUNK); i++) if (sa[i] == null) { full = false; break; }
                if (full) done.add(lang + "#" + ch);
            }
        }
    }

    private void persistTranslations(final String lang) {
        if (curVideoId.isEmpty() || sents.isEmpty()) return;
        final String vid = curVideoId;
        final String sig = YtDiskCache.signature(sentTexts);
        final String tone = host.tone() == null ? "neutral" : host.tone();
        final String[] snap = sentArr(lang).clone();
        try {
            netIo.execute(() -> YtDiskCache.saveTr(app, vid, lang, tone, sig, snap));
        } catch (Exception ignored) {}
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

    /**
     * ترجمه‌ی سریعِ «جمله‌ی کامل» (نه تکه‌ی شکسته) برای خطِ جاری تا منتظر chunk نمونیم؛
     * chunk بعداً جایگزینش می‌کنه.
     */
    private void quick(final int idx, final String lang) {
        if (idx < 0 || idx >= cueSents.length) return;
        final int[] ss = cueSents[idx];
        for (final int si : ss) {
            if (!quickAsked.add(lang + "#" + si)) continue;
            final int g = gen;
            final String text = sentTexts.get(si);
            final String src = trackLang;
            try {
                netQuick.execute(() -> {
                    final String out;
                    try { out = FreeTranslator.translate(httpFast, text, src, lang); }
                    catch (Exception e) { return; }
                    main.post(() -> {
                        if (g != gen || !active) return;
                        setSentence(si, lang, out == null ? "" : out.trim(), false);   // اگه chunk زودتر رسیده بود، دست نزن
                    });
                });
            } catch (Exception ignored) {}
        }
    }

    // ───── ساختنِ جمله‌ها از تکه‌های زیرنویس، و پخشِ ترجمه‌ی جمله بینِ تکه‌ها ─────

    private static boolean isTerminal(char ch) {
        return ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '。' || ch == '！' || ch == '？' || ch == '۔' || ch == '؟';
    }

    private void buildSentences() {
        sents = new ArrayList<>();
        sentTexts = new ArrayList<>();
        final int n = cues.size();
        cueRangeS = new int[n]; cueRangeE = new int[n];
        cueSents = new int[n][];
        if (n == 0) { sentFirstCue = new int[0]; sentLastCue = new int[0]; return; }

        StringBuilder full = new StringBuilder();
        int[] cs = new int[n], ce = new int[n];
        for (int i = 0; i < n; i++) {
            String t = cues.get(i).text.replaceAll("\\s+", " ").trim();
            if (full.length() > 0) full.append(' ');
            cs[i] = full.length();
            full.append(t);
            ce[i] = full.length();
        }
        final int len = full.length();
        boolean[] brk = new boolean[len + 1];
        // ۱) مرزِ جمله از روی علامتِ پایان (فقط وقتی بعدش فاصله/انتها باشه؛ «3.5» نشکنه)
        for (int i = 0; i < len; i++) {
            if (!isTerminal(full.charAt(i))) continue;
            int j = i + 1;
            while (j < len && isTerminal(full.charAt(j))) j++;                       // «...» / «?!»
            while (j < len && "\"'”’»)]".indexOf(full.charAt(j)) >= 0) j++;         // نقل‌قولِ بسته
            if (j >= len || full.charAt(j) == ' ') brk[Math.min(j, len)] = true;
            i = Math.max(i, j - 1);
        }
        // ۲) مرزِ اجباری: مکثِ زیاد بینِ دو تکه، یا جمله‌ی خیلی بلندِ بدونِ نقطه (زیرنویس‌های خودکار)
        int terminals = 0;
        for (int i = 0; i < len; i++) if (isTerminal(full.charAt(i))) terminals++;
        final boolean autoCaps = terminals * 250 < len;     // کمتر از ~یک نقطه در هر ۲۵۰ حرف = زیرنویسِ خودکارِ بدونِ نقطه‌گذاری
        final long gapLimit = autoCaps ? AUTO_GAP_MS : GAP_BREAK_MS;
        final int maxChars = autoCaps ? AUTO_MAX_CHARS : MAX_SENT_CHARS;
        int lastB = 0;
        int scan = 0;
        for (int i = 0; i < n - 1; i++) {
            while (scan <= ce[i]) { if (brk[scan]) lastB = scan; scan++; }
            long gap = cues.get(i + 1).startMs - cues.get(i).endMs;
            int run = ce[i] - lastB;
            boolean cut = (gap > gapLimit && (!autoCaps || run >= AUTO_MIN_CHARS)) || run >= maxChars;
            if (!brk[ce[i]] && cut) {
                brk[ce[i]] = true;
                lastB = ce[i];
            }
        }
        brk[len] = true;
        // ۳) برش
        int start = 0;
        for (int i = 1; i <= len; i++) {
            if (!brk[i]) continue;
            int a = start, b = i;
            while (a < b && full.charAt(a) == ' ') a++;
            while (b > a && full.charAt(b - 1) == ' ') b--;
            if (b > a) { sents.add(new int[]{a, b}); sentTexts.add(full.substring(a, b)); }
            start = i;
        }
        // ۴) محدوده‌ی کاشی‌شده‌ی هر تکه (فاصله‌ی بینِ دو تکه به تکه‌ی قبلی تعلق داره) + هم‌پوشانی با جمله‌ها
        sentFirstCue = new int[sents.size()];
        sentLastCue = new int[sents.size()];
        java.util.Arrays.fill(sentFirstCue, Integer.MAX_VALUE);
        java.util.Arrays.fill(sentLastCue, -1);
        int sp = 0;
        for (int i = 0; i < n; i++) {
            cueRangeS[i] = cs[i];
            cueRangeE[i] = (i < n - 1) ? cs[i + 1] : ce[i];
            ArrayList<Integer> hit = new ArrayList<>();
            while (sp < sents.size() && sents.get(sp)[1] <= cueRangeS[i]) sp++;
            for (int k = sp; k < sents.size() && sents.get(k)[0] < cueRangeE[i]; k++) {
                int o1 = Math.max(cueRangeS[i], sents.get(k)[0]), o2 = Math.min(cueRangeE[i], sents.get(k)[1]);
                if (o2 > o1) hit.add(k);
            }
            if (hit.isEmpty() && !sents.isEmpty()) hit.add(Math.min(Math.max(0, sp), sents.size() - 1));
            cueSents[i] = new int[hit.size()];
            for (int h = 0; h < hit.size(); h++) {
                int k = hit.get(h);
                cueSents[i][h] = k;
                sentFirstCue[k] = Math.min(sentFirstCue[k], i);
                sentLastCue[k] = Math.max(sentLastCue[k], i);
            }
        }
    }

    private String[] sentArr(String lang) {
        String[] a = sentTr.get(lang);
        if (a == null || a.length != sents.size()) { a = new String[sents.size()]; sentTr.put(lang, a); }
        return a;
    }

    private static String[] tokens(String t) {
        String x = t.trim();
        if (x.isEmpty()) return new String[0];
        if (x.indexOf(' ') < 0 && x.codePointCount(0, x.length()) > 8) {     // چینی/ژاپنی/تایلندی: بدونِ فاصله
            int[] cps = x.codePoints().toArray();
            String[] out = new String[cps.length];
            for (int i = 0; i < cps.length; i++) out[i] = new String(Character.toChars(cps[i]));
            return out;
        }
        return x.split("\\s+");
    }

    /** بخشی از ترجمه‌ی جمله‌ی si که مالِ تکه‌ی cue است. */
    private String segment(String lang, int si, int cue) {
        String tr = sentArr(lang)[si];
        if (tr == null) return null;
        int[] sp = sents.get(si);
        String[] w = tokens(tr);
        int nw = w.length;
        int a = sp[0], b = sp[1];
        int o1 = Math.max(cueRangeS[cue], a), o2 = Math.min(cueRangeE[cue], b);
        if (nw == 0 || b <= a) return "";
        int ts = (o1 <= a) ? 0 : (int) Math.round((double) nw * (o1 - a) / (b - a));
        int te = (o2 >= b) ? nw : (int) Math.round((double) nw * (o2 - a) / (b - a));
        if (te <= ts) return "";
        boolean joinNoSpace = tr.indexOf(' ') < 0 && nw > 8;
        StringBuilder sb = new StringBuilder();
        for (int i = ts; i < te; i++) { if (sb.length() > 0 && !joinNoSpace) sb.append(' '); sb.append(w[i]); }
        return sb.toString();
    }

    /** ترجمه‌ی نهاییِ یک تکه از روی جمله‌هایی که پوشش می‌ده؛ null = هنوز یکی از جمله‌ها نرسیده. */
    private String composeCue(String lang, int cue) {
        if (cue < 0 || cue >= cueSents.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int si : cueSents[cue]) {
            String seg = segment(lang, si, cue);
            if (seg == null) return null;
            if (!seg.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(seg); }
        }
        return sb.length() == 0 ? "…" : sb.toString();
    }

    /** ترجمه‌ی جمله‌ی si رسید (overwrite=false: فقط اگه هنوز چیزی نداره) → همه‌ی تکه‌های مربوطه به‌روز می‌شن. */
    private void setSentence(int si, String lang, String text, boolean overwrite) {
        if (si < 0 || si >= sents.size() || text == null || text.trim().isEmpty()) return;
        String[] sa = sentArr(lang);
        if (!overwrite && sa[si] != null) return;
        sa[si] = text.trim();
        String[] a = arr(lang);
        for (int c = sentFirstCue[si]; c <= sentLastCue[si] && c < a.length; c++) {
            String v = composeCue(lang, c);
            if (v == null) continue;
            if (v.equals(a[c])) continue;
            a[c] = v;
        }
        host.updateSentence(si, lang, sa[si]);
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
