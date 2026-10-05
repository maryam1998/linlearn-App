package com.linglearn.app.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * دریافتِ سریعِ زیرنویس و شناسه‌ی ویدیو.
 *
 * یوتیوب درخواست‌های سرور/WEB (IP کلودفلر) را اغلب رد می‌کند؛ پس مستقیم از خودِ گوشی (IP خانگی) با
 * کلاینت‌های ANDROID / ANDROID_VR / IOS / TV-embed می‌گیریم و هم‌زمان Worker را هم صدا می‌زنیم.
 * هر کدام زودتر جواب درست داد برنده است و بقیه کنسل می‌شوند. اگر همه شکست خوردند، دلیلِ واقعیِ هر
 * کدام در [Fail.getMessage] می‌آید.
 */
final class YtCaptionFetcher {

    private YtCaptionFetcher() {}

    static final class Out {
        final String lang;
        final boolean auto;
        final List<YtSubtitles.Cue> cues;
        Out(String lang, boolean auto, List<YtSubtitles.Cue> cues) { this.lang = lang; this.auto = auto; this.cues = cues; }
    }

    static final class Fail extends Exception {
        final boolean noCaptions;
        Fail(String m, boolean noCaptions) { super(m); this.noCaptions = noCaptions; }
    }

    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final long OVERALL_MS = 26000;
    private static final long WEBVIEW_MS = 22000;

    // ═════════════════════════ زیرنویس ═════════════════════════

    static Out fetch(OkHttpClient base, final String workerBase, final String videoId, final String wantLang) throws Fail {
        final OkHttpClient http = base.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build();

        final ExecutorService pool = Executors.newFixedThreadPool(5);
        final ExecutorCompletionService<Out> ecs = new ExecutorCompletionService<>(pool);
        final List<String> reasons = Collections.synchronizedList(new ArrayList<String>());
        int noCapVotes = 0;

        submit(ecs, 0, new Callable<Out>() { public Out call() throws Exception { return viaClient(http, "ANDROID", videoId, wantLang); } });
        submit(ecs, 0, new Callable<Out>() { public Out call() throws Exception { return viaWorker(http, workerBase, videoId, wantLang); } });
        submit(ecs, 300, new Callable<Out>() { public Out call() throws Exception { return viaClient(http, "ANDROID_VR", videoId, wantLang); } });
        submit(ecs, 600, new Callable<Out>() { public Out call() throws Exception { return viaClient(http, "IOS", videoId, wantLang); } });
        // کلاینت TV-embed دیگر از طرف یوتیوب پشتیبانی نمی‌شود؛ جایش یک مرورگرِ واقعی (WebView) است
        submit(ecs, 1200, new Callable<Out>() { public Out call() throws Exception { return viaWebView(videoId, wantLang); } });

        final long deadline = System.currentTimeMillis() + OVERALL_MS;
        try {
            for (int i = 0; i < 5; i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) { reasons.add("timeout"); break; }
                Future<Out> f = ecs.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) { reasons.add("timeout"); break; }
                try {
                    return f.get();
                } catch (ExecutionException e) {
                    Throwable t = e.getCause();
                    if (t instanceof Fail) {
                        reasons.add(t.getMessage());
                        if (((Fail) t).noCaptions && ++noCapVotes >= 2) break;   // دو منبع می‌گویند زیرنویس ندارد
                    } else {
                        reasons.add(t == null ? "error" : t.getClass().getSimpleName() + ": " + brief(t.getMessage(), 60));
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        String all = join(reasons);
        boolean blocked = all.contains("LOGIN_REQUIRED") || all.contains("not a bot") || all.contains("HTTP 429") || all.contains("HTTP 403");
        throw new Fail(blocked && noCapVotes < 2
                ? "یوتیوب این IP را مسدود کرده؛ VPN/شبکه را عوض کن (YouTube bot-check) · " + brief(all, 120)
                : all, noCapVotes >= 2);
    }

    private static <T> void submit(ExecutorCompletionService<T> ecs, final long delayMs, final Callable<T> job) {
        ecs.submit(new Callable<T>() {
            @Override public T call() throws Exception {
                if (delayMs > 0) Thread.sleep(delayMs);
                return job.call();
            }
        });
    }

    // ───────── مسیرِ Worker ─────────

    private static Out viaWorker(OkHttpClient http, String workerBase, String videoId, String wantLang) throws Exception {
        Request req = new Request.Builder()
                .url(workerBase + "/api/youtube-captions?videoId=" + enc(videoId) + "&lang=" + enc(wantLang)).get().build();
        try (Response r = http.newCall(req).execute()) {
            String s = r.body() == null ? "" : r.body().string();
            JSONObject j;
            try { j = new JSONObject(s); } catch (Exception e) { throw new Fail("worker: HTTP " + r.code(), false); }
            if (!j.optBoolean("ok", false)) {
                throw new Fail("worker: " + brief(j.optString("error", "HTTP " + r.code()), 90), j.optBoolean("noCaptions", false));
            }
            List<YtSubtitles.Cue> out = new ArrayList<>();
            JSONArray arr = j.getJSONArray("cues");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                String t = c.optString("text", "").trim();
                if (t.isEmpty()) continue;
                long st = Math.round(c.optDouble("start", 0) * 1000.0);
                long en = Math.round(c.optDouble("end", c.optDouble("start", 0)) * 1000.0);
                out.add(new YtSubtitles.Cue(st, Math.max(st, en), t));
            }
            if (out.isEmpty()) throw new Fail("worker: empty captions", false);
            return new Out(normLang(j.optString("lang", wantLang)), j.optBoolean("isAuto", false), out);
        }
    }

    // ───────── مسیرِ مرورگرِ واقعی (WebView) ─────────
    //  صفحه‌ی watch را مثلِ کروم باز می‌کند، فهرستِ زیرنویس را از ytInitialPlayerResponse می‌خواند و متنِ آن را
    //  از داخلِ خودِ صفحه (با کوکی‌ها) می‌گیرد. اگر یوتیوب بدونِ pot جواب خالی داد، از پلیرِ خودِ صفحه می‌خواهیم
    //  زیرنویس را روشن کند و درخواستِ timedtext (که pot دارد) را شنود و همان را دوباره می‌گیریم.
    //  صدایی پخش نمی‌شود (autoplay خاموش است).

    private static final class Bridge {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Out out;
        volatile Fail failure;
        volatile String potUrl = "";

        @JavascriptInterface public void ok(String lang, boolean auto, String body) {
            try {
                List<YtSubtitles.Cue> cues = parseCues(body == null ? "" : body);
                if (cues.isEmpty()) failure = new Fail("webview: unparsable captions", false);
                else out = new Out(normLang(lang), auto, cues);
            } catch (Throwable t) {
                failure = new Fail("webview: parse " + t.getClass().getSimpleName(), false);
            }
            latch.countDown();
        }

        @JavascriptInterface public void fail(String reason, boolean noCaptions) {
            failWith(reason, noCaptions);
        }

        @JavascriptInterface public String pot() { return potUrl; }

        void failWith(String reason, boolean noCaptions) {
            if (out == null && failure == null) failure = new Fail(brief(reason, 100), noCaptions);
            latch.countDown();
        }
    }

    private static Context appCtx() {
        try {
            Object a = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (a instanceof Context) return ((Context) a).getApplicationContext();
        } catch (Throwable ignored) { /* بعدی */ }
        try {
            Object a = Class.forName("android.app.AppGlobals").getMethod("getInitialApplication").invoke(null);
            if (a instanceof Context) return ((Context) a).getApplicationContext();
        } catch (Throwable ignored) { /* هیچ */ }
        return null;
    }

    private static String webJs(String wantLang) {
        String want = normLang(wantLang).replace("\\", "").replace("'", "");
        return "(function(){if(window.__llRun)return;window.__llRun=1;"
            + "var WANT='" + want + "';"
            + "function sl(ms){return new Promise(function(r){setTimeout(r,ms);});}"
            + "function fail(m,nc){try{LL.fail(String(m),!!nc);}catch(e){}}"
            + "function pick(tr){var best=null,any=null,asr=null;"
            +   "for(var i=0;i<tr.length;i++){var t=tr[i];var lc=String(t.languageCode||'').toLowerCase().split('-')[0];"
            +     "if(!any)any=t;"
            +     "if(lc===WANT){if(t.kind!=='asr')return t;if(!asr)asr=t;}"
            +     "else if(t.kind!=='asr'&&!best)best=t;}"
            +   "return asr||best||any;}"
            + "async function get(u){var r=await fetch(u,{credentials:'include'});"
            +   "if(!r.ok)return '';return await r.text();}"
            + "function good(x){return x&&x.trim().length>2;}"
            + "(async function(){try{"
            +   "var pr=null;"
            +   "for(var i=0;i<50;i++){pr=window.ytInitialPlayerResponse;if(pr&&pr.playabilityStatus)break;await sl(200);}"
            +   "if(!pr){fail('webview: no player response');return;}"
            +   "var ps=pr.playabilityStatus||{};"
            +   "if(ps.status!=='OK'){fail('webview: '+(ps.status||'?')+(ps.reason?' ('+String(ps.reason).slice(0,50)+')':''));return;}"
            +   "var tl=pr.captions&&pr.captions.playerCaptionsTracklistRenderer;"
            +   "var tracks=tl&&tl.captionTracks;"
            +   "if(!tracks||!tracks.length){fail('webview: no caption tracks',true);return;}"
            +   "var t=pick(tracks);"
            +   "var u=new URL(t.baseUrl,location.href);u.searchParams.set('fmt','json3');"
            +   "var x=await get(u.toString());"
            +   "if(good(x)){LL.ok(t.languageCode||WANT,t.kind==='asr',x);return;}"
            +   "var p=document.getElementById('movie_player');"
            +   "for(var k=0;k<24&&!p;k++){await sl(250);p=document.getElementById('movie_player');}"
            +   "if(!p){fail('webview: timedtext empty, player not ready');return;}"
            +   "try{if(p.toggleSubtitlesOn)p.toggleSubtitlesOn();}catch(e){}"
            +   "try{var list=p.getOption('captions','tracklist')||[];var m=null;"
            +     "for(var j=0;j<list.length;j++){var lc=String(list[j].languageCode||'').toLowerCase().split('-')[0];"
            +       "if(lc===WANT&&(!m||list[j].kind!=='asr'))m=list[j];}"
            +     "p.setOption('captions','track',m||{languageCode:t.languageCode});}catch(e){}"
            +   "var pu='';"
            +   "for(var k=0;k<40&&!pu;k++){await sl(250);pu=LL.pot();}"
            +   "if(!pu){fail('webview: timedtext empty (no pot)');return;}"
            +   "var u2=new URL(pu,location.href);u2.searchParams.set('fmt','json3');"
            +   "var y=await get(u2.toString());"
            +   "if(good(y)){LL.ok(u2.searchParams.get('lang')||t.languageCode||WANT,u2.searchParams.get('kind')==='asr',y);return;}"
            +   "fail('webview: timedtext empty even with pot');"
            + "}catch(e){fail('webview: '+e);}})();"
            + "})();";
    }

    private static Out viaWebView(final String videoId, final String wantLang) throws Exception {
        final Context ctx = appCtx();
        if (ctx == null) throw new Fail("webview: no context", false);
        final Bridge br = new Bridge();
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];
        final String js = webJs(wantLang);

        main.post(new Runnable() {
            @Override public void run() {
                try {
                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    cm.setCookie("https://www.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/; Secure");
                    WebView wv = new WebView(ctx);
                    holder[0] = wv;
                    WebSettings st = wv.getSettings();
                    st.setJavaScriptEnabled(true);
                    st.setDomStorageEnabled(true);
                    st.setMediaPlaybackRequiresUserGesture(true);
                    st.setLoadsImagesAutomatically(false);
                    st.setUserAgentString(st.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
                    wv.addJavascriptInterface(br, "LL");
                    wv.setWebViewClient(new WebViewClient() {
                        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                            try {
                                String u = r.getUrl().toString();
                                if (u.contains("/api/timedtext") && u.contains("pot=")) br.potUrl = u;
                            } catch (Throwable ignored) { /* نادیده */ }
                            return null;
                        }
                        @Override public void onPageFinished(WebView v, String url) {
                            if (url != null && url.contains("consent.")) { br.failWith("webview: consent page", false); return; }
                            v.evaluateJavascript(js, null);
                        }
                        @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                            if (r != null && r.isForMainFrame()) {
                                br.failWith("webview: load error " + (e == null ? "" : e.getErrorCode()), false);
                            }
                        }
                    });
                    wv.loadUrl("https://m.youtube.com/watch?v=" + videoId);
                } catch (Throwable t) {
                    br.failWith("webview: " + t.getClass().getSimpleName(), false);
                }
            }
        });

        try {
            if (!br.latch.await(WEBVIEW_MS, TimeUnit.MILLISECONDS)) throw new Fail("webview: timeout", false);
        } finally {
            main.post(new Runnable() {
                @Override public void run() {
                    WebView wv = holder[0];
                    if (wv == null) return;
                    try {
                        wv.stopLoading();
                        wv.removeJavascriptInterface("LL");
                        wv.destroy();
                    } catch (Throwable ignored) { /* نادیده */ }
                }
            });
        }
        if (br.out != null) return br.out;
        throw br.failure != null ? br.failure : new Fail("webview: unknown", false);
    }

    // ───────── مسیرِ مستقیم از گوشی ─────────

    private static String uaOf(String c) {
        switch (c) {
            case "ANDROID": return "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
            case "ANDROID_VR": return "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
            case "IOS": return "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
            default: return "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
        }
    }

    private static String idOf(String c) {
        switch (c) {
            case "ANDROID": return "3";
            case "ANDROID_VR": return "28";
            case "IOS": return "5";
            default: return "85";
        }
    }

    private static String verOf(String c) {
        switch (c) {
            case "ANDROID": return "20.10.38";
            case "ANDROID_VR": return "1.62.27";
            case "IOS": return "20.10.4";
            default: return "2.0";
        }
    }

    private static JSONObject playerBody(String c, String videoId) throws Exception {
        JSONObject client = new JSONObject().put("hl", "en").put("gl", "US").put("clientVersion", verOf(c));
        JSONObject ctx = new JSONObject();
        switch (c) {
            case "ANDROID":
                client.put("clientName", "ANDROID").put("androidSdkVersion", 30).put("osName", "Android").put("osVersion", "11");
                break;
            case "ANDROID_VR":
                client.put("clientName", "ANDROID_VR").put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
                        .put("androidSdkVersion", 32).put("osName", "Android").put("osVersion", "12L");
                break;
            case "IOS":
                client.put("clientName", "IOS").put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
                        .put("osName", "iPhone").put("osVersion", "18.3.2.22D82");
                break;
            default:
                client.put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER");
                ctx.put("thirdParty", new JSONObject().put("embedUrl", "https://www.youtube.com/watch?v=" + videoId));
        }
        ctx.put("client", client);
        return new JSONObject().put("context", ctx).put("videoId", videoId)
                .put("contentCheckOk", true).put("racyCheckOk", true);
    }

    private static Out viaClient(OkHttpClient http, String c, String videoId, String wantLang) throws Exception {
        final String label = c.equals("TVE") ? "tv" : c.toLowerCase(Locale.ROOT);
        final String ua = uaOf(c);
        String body = playerBody(c, videoId).toString();
        Request req = new Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", ua)
                .header("X-YouTube-Client-Name", idOf(c))
                .header("X-YouTube-Client-Version", verOf(c))
                .header("Origin", "https://www.youtube.com")
                .header("Accept-Language", "en-US,en;q=0.9")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON_MT)).build();

        JSONObject data;
        try (Response r = http.newCall(req).execute()) {
            String s = r.body() == null ? "" : r.body().string();
            if (!r.isSuccessful()) throw new Fail(label + ": player HTTP " + r.code(), false);
            data = new JSONObject(s);
        }

        JSONObject ps = data.optJSONObject("playabilityStatus");
        String status = ps == null ? "" : ps.optString("status", "");
        if (!"OK".equals(status)) {
            String reason = ps == null ? "" : ps.optString("reason", "");
            throw new Fail(label + ": " + (status.isEmpty() ? "no playability" : status)
                    + (reason.isEmpty() ? "" : " (" + brief(reason, 70) + ")"), false);
        }

        JSONObject tl = data.optJSONObject("captions");
        tl = tl == null ? null : tl.optJSONObject("playerCaptionsTracklistRenderer");
        JSONArray tracks = tl == null ? null : tl.optJSONArray("captionTracks");
        if (tracks == null || tracks.length() == 0) throw new Fail(label + ": no caption tracks", true);

        JSONObject track = pickTrack(tracks, wantLang);
        String baseUrl = track.optString("baseUrl", "");
        if (baseUrl.isEmpty()) throw new Fail(label + ": track without url", false);
        String lang = normLang(track.optString("languageCode", wantLang));
        boolean auto = "asr".equals(track.optString("kind", ""));

        String clean = baseUrl.replaceAll("[&?]fmt=[^&]*", "");
        List<YtSubtitles.Cue> cues = null;
        String why = "";
        for (int attempt = 0; attempt < 2 && (cues == null || cues.isEmpty()); attempt++) {
            String url = attempt == 0 ? clean + "&fmt=json3" : clean;
            Request cr = new Request.Builder().url(url).header("User-Agent", ua)
                    .header("Accept-Language", "en-US,en;q=0.9").get().build();
            try (Response r = http.newCall(cr).execute()) {
                String s = r.body() == null ? "" : r.body().string();
                if (!r.isSuccessful()) { why = "timedtext HTTP " + r.code(); continue; }
                if (s.trim().isEmpty()) { why = "timedtext empty (needs PO token?)"; continue; }
                cues = parseCues(s);
                if (cues.isEmpty()) why = "timedtext unparsable";
            }
        }
        if (cues == null || cues.isEmpty()) throw new Fail(label + ": " + why, false);
        return new Out(lang, auto, cues);
    }

    private static JSONObject pickTrack(JSONArray tracks, String wantLang) {
        String want = normLang(wantLang);
        JSONObject manualAny = null, first = null;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            if (first == null) first = t;
            boolean asr = "asr".equals(t.optString("kind", ""));
            if (!asr && manualAny == null) manualAny = t;
            if (normLang(t.optString("languageCode", "")).equals(want) && !asr) return t;
        }
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t != null && normLang(t.optString("languageCode", "")).equals(want)) return t;
        }
        return manualAny != null ? manualAny : first;
    }

    // ───────── پارس: json3 یا XML (srv1/srv3) ─────────

    private static final Pattern P_SRV1 = Pattern.compile("<text\\s+start=\"([\\d.]+)\"(?:\\s+dur=\"([\\d.]+)\")?[^>]*>(.*?)</text>", Pattern.DOTALL);
    private static final Pattern P_SRV3 = Pattern.compile("<p\\s+t=\"(\\d+)\"(?:\\s+d=\"(\\d+)\")?[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern P_NUM = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    private static List<YtSubtitles.Cue> parseCues(String s) {
        List<YtSubtitles.Cue> out = new ArrayList<>();
        String t = s.trim();
        try {
            if (t.startsWith("{")) {
                JSONArray ev = new JSONObject(t).optJSONArray("events");
                if (ev == null) return out;
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.optJSONObject(i);
                    if (e == null) continue;
                    JSONArray segs = e.optJSONArray("segs");
                    if (segs == null || segs.length() == 0) continue;
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < segs.length(); k++) {
                        JSONObject sg = segs.optJSONObject(k);
                        if (sg != null) sb.append(sg.optString("utf8", ""));
                    }
                    String text = sb.toString().replace('\n', ' ').trim();
                    if (text.isEmpty()) continue;
                    long st = Math.round(e.optDouble("tStartMs", 0));
                    long en = st + Math.round(e.optDouble("dDurationMs", 0));
                    out.add(new YtSubtitles.Cue(st, Math.max(st, en), text));
                }
                return out;
            }
        } catch (Exception ignored) { /* بعد XML امتحان می‌شود */ }

        Matcher m = P_SRV3.matcher(t);
        boolean v3 = false;
        while (m.find()) {
            v3 = true;
            addXml(out, Long.parseLong(m.group(1)), m.group(2) == null ? 0 : Long.parseLong(m.group(2)), m.group(3));
        }
        if (v3) return out;
        m = P_SRV1.matcher(t);
        while (m.find()) {
            long st = Math.round(Double.parseDouble(m.group(1)) * 1000.0);
            long du = m.group(2) == null ? 0 : Math.round(Double.parseDouble(m.group(2)) * 1000.0);
            addXml(out, st, du, m.group(3));
        }
        return out;
    }

    private static void addXml(List<YtSubtitles.Cue> out, long st, long dur, String raw) {
        String text = unesc(raw.replaceAll("<[^>]+>", "")).replace('\n', ' ').trim();
        if (!text.isEmpty()) out.add(new YtSubtitles.Cue(st, st + dur, text));
    }

    private static String unesc(String s) {
        for (int pass = 0; pass < 2 && s.indexOf('&') >= 0; pass++) {
            Matcher m = P_NUM.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String g = m.group(1);
                String rep;
                try {
                    int cp = g.startsWith("x") ? Integer.parseInt(g.substring(1), 16) : Integer.parseInt(g);
                    rep = new String(Character.toChars(cp));
                } catch (Exception e) { rep = ""; }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            s = sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        }
        return s;
    }

    // ═════════════════════════ پیدا کردنِ videoId از روی عنوان ═════════════════════════

    static String resolve(OkHttpClient base, final String workerBase, final String title, final String channel,
                          final long durSec) throws Fail {
        final OkHttpClient http = base.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS).build();
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final ExecutorCompletionService<String> ecs = new ExecutorCompletionService<>(pool);
        final List<String> reasons = new ArrayList<>();
        submit(ecs, 0, new Callable<String>() { public String call() throws Exception { return searchOnPhone(http, title, channel, durSec); } });
        submit(ecs, 0, new Callable<String>() { public String call() throws Exception { return searchViaWorker(http, workerBase, title, channel, durSec); } });
        try {
            long deadline = System.currentTimeMillis() + 11000;
            for (int i = 0; i < 2; i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) { reasons.add("timeout"); break; }
                Future<String> f = ecs.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) { reasons.add("timeout"); break; }
                try { return f.get(); }
                catch (ExecutionException e) {
                    Throwable t = e.getCause();
                    reasons.add(t == null ? "error" : (t instanceof Fail ? t.getMessage()
                            : t.getClass().getSimpleName() + ": " + brief(t.getMessage(), 60)));
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        throw new Fail(join(reasons), false);
    }

    private static String searchViaWorker(OkHttpClient http, String workerBase, String title, String channel, long durSec) throws Exception {
        Request req = new Request.Builder().url(workerBase + "/api/youtube-resolve?title=" + enc(title)
                + "&channel=" + enc(channel) + "&durationSec=" + durSec).get().build();
        try (Response r = http.newCall(req).execute()) {
            String s = r.body() == null ? "" : r.body().string();
            JSONObject j;
            try { j = new JSONObject(s); } catch (Exception e) { throw new Fail("worker: HTTP " + r.code(), false); }
            String v = j.optString("videoId", "");
            if (!YtMedia.isValidYouTubeVideoId(v)) throw new Fail("worker: " + brief(j.optString("error", "no match"), 80), false);
            return v;
        }
    }

    private static String searchOnPhone(OkHttpClient http, String title, String channel, long durSec) throws Exception {
        JSONObject client = new JSONObject().put("clientName", "WEB").put("clientVersion", "2.20250101.00.00")
                .put("hl", "en").put("gl", "US");
        JSONObject body = new JSONObject().put("query", channel.isEmpty() ? title : title + " " + channel)
                .put("context", new JSONObject().put("client", client));
        Request req = new Request.Builder().url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", uaOf("TVE")).header("Origin", "https://www.youtube.com")
                .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON_MT)).build();
        JSONObject data;
        try (Response r = http.newCall(req).execute()) {
            String s = r.body() == null ? "" : r.body().string();
            if (!r.isSuccessful()) throw new Fail("phone: search HTTP " + r.code(), false);
            data = new JSONObject(s);
        }
        List<JSONObject> found = new ArrayList<>();
        walk(data, 0, found);
        if (found.isEmpty()) throw new Fail("phone: no search results", false);

        String wantTitle = norm(title), wantChannel = norm(channel);
        JSONObject best = null;
        int bestScore = -100;
        for (JSONObject v : found) {
            String vt = norm(runs(v.optJSONObject("title")));
            String vc = norm(runs(v.optJSONObject("ownerText")));
            JSONObject lt = v.optJSONObject("lengthText");
            long vs = toSec(lt == null ? "" : lt.optString("simpleText", ""));
            int score = 0;
            if (vt.equals(wantTitle)) score += 6;
            else if (!vt.isEmpty() && !wantTitle.isEmpty() && (vt.contains(wantTitle) || wantTitle.contains(vt))) score += 3;
            if (!wantChannel.isEmpty() && !vc.isEmpty() && (vc.equals(wantChannel) || vc.contains(wantChannel) || wantChannel.contains(vc))) score += 2;
            if (durSec > 0 && vs > 0) {
                long d = Math.abs(vs - durSec);
                if (d <= 2) score += 5; else if (d <= 6) score += 2; else score -= 3;
            }
            if (score > bestScore) { bestScore = score; best = v; }
        }
        if (best == null || bestScore < 3) throw new Fail("phone: no confident match", false);
        String id = best.optString("videoId", "");
        if (!YtMedia.isValidYouTubeVideoId(id)) throw new Fail("phone: bad id", false);
        return id;
    }

    private static void walk(Object node, int depth, List<JSONObject> found) {
        if (node == null || depth > 14 || found.size() > 40) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), depth + 1, found);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject vr = o.optJSONObject("videoRenderer");
            if (vr != null && !vr.optString("videoId", "").isEmpty()) found.add(vr);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) walk(o.opt(it.next()), depth + 1, found);
        }
    }

    private static String runs(JSONObject t) {
        if (t == null) return "";
        JSONArray r = t.optJSONArray("runs");
        if (r == null) return t.optString("simpleText", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.optJSONObject(i);
            if (x != null) sb.append(x.optString("text", ""));
        }
        return sb.toString();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static long toSec(String t) {
        if (t == null || t.isEmpty()) return 0;
        long acc = 0;
        for (String p : t.split(":")) {
            try { acc = acc * 60 + Long.parseLong(p.trim()); } catch (Exception e) { return 0; }
        }
        return acc;
    }

    // ═════════════════════════ ابزار ═════════════════════════

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static String normLang(String l) {
        if (l == null || l.isEmpty()) return "en";
        String x = l.toLowerCase(Locale.ROOT);
        int i = x.indexOf('-');
        return i > 0 ? x.substring(0, i) : x;
    }

    private static String brief(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        synchronized (parts) {
            for (String p : parts) {
                if (p == null || p.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(p);
            }
        }
        return brief(sb.length() == 0 ? "unknown error" : sb.toString(), 220);
    }
}
