package com.linglearn.app.overlay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(name = "BubblePlugin")
public class BubblePlugin extends Plugin {

    // ✅ موتور TTS مشترک (بدون private تا BubbleService هم بتونه استفاده کنه)
    // = موتورِ آخرین زبانی که واقعاً خونده شده
    static volatile TtsEngine sharedTts;
    static volatile String sharedTtsLang = null;

    // چند موتور هم‌زمان توی حافظه (برای اینکه عوض‌کردنِ زبان، لودِ دوباره‌ی چندثانیه‌ای نداشته باشه)
    private static final int MAX_ENGINES = 2;
    private static final ConcurrentHashMap<String, TtsEngine> ENGINES = new ConcurrentHashMap<>();
    // با هر speak/stop بالا می‌ره؛ speak ی که قبل از stop شروع شده ولی هنوز لود می‌شه، دیگه پخش نمی‌شه
    private static final AtomicInteger SPEAK_SEQ = new AtomicInteger(0);
    private static final String TTS_PREFS = "tts_engine";

    private static String lastTtsLang(Context ctx) {
        try { return ctx.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE).getString("last_lang", null); }
        catch (Throwable e) { return null; }
    }

    private static void rememberTtsLang(Context ctx, String lang) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE);
            if (!lang.equals(sp.getString("last_lang", null))) sp.edit().putString("last_lang", lang).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * موتورِ این زبان رو برمی‌گردونه (اگه لود نیست، لودش می‌کنه).
     * forSpeak=true: موتور «آخرین‌استفاده‌شده» (sharedTts) می‌شه و در صورتِ پر بودنِ حافظه، قدیمی‌ترین موتور آزاد می‌شه.
     * forSpeak=false (preload): اگه جا نیست، چیزی رو آزاد نمی‌کنه و null می‌ده.
     */
    static TtsEngine obtainEngine(Context app, String lang, boolean forSpeak) {
        synchronized (BubblePlugin.class) {
            TtsEngine e = ENGINES.get(lang);
            if (e != null && e.isReleased()) { ENGINES.remove(lang); e = null; }
            if (e == null) {
                if (!forSpeak && ENGINES.size() >= MAX_ENGINES) return null;
                e = TtsEngine.create(app, lang);
                if (e == null) return null;
                ENGINES.put(lang, e);
                while (ENGINES.size() > MAX_ENGINES) {
                    TtsEngine victim = null;
                    for (TtsEngine x : ENGINES.values()) {
                        if (x == e || x == sharedTts) continue;
                        if (victim == null || x.lastUsed() < victim.lastUsed()) victim = x;
                    }
                    if (victim == null) break;
                    ENGINES.remove(victim.lang());
                    victim.release();
                }
            }
            if (forSpeak) {
                e.touch();
                sharedTts = e;
                sharedTtsLang = lang;
            }
            return e;
        }
    }

    private void emitSpeakDone(String lang, String text, String id, boolean ok) {
        JSObject ret = new JSObject();
        ret.put("lang", lang);
        ret.put("text", text);
        if (id != null) ret.put("id", id);
        ret.put("ok", ok);
        notifyListeners("ttsSpeakDone", ret);
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", Settings.canDrawOverlays(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        Context ctx = getContext();
        JSObject ret = new JSObject();
        if (Settings.canDrawOverlays(ctx)) {
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        ret.put("granted", false);
        call.resolve(ret);
    }

    // showBubble({ targetLang?, sourceLang?, translationTone? })
    @PluginMethod
    public void showBubble(PluginCall call) {
        Context ctx = getContext();
        if (!Settings.canDrawOverlays(ctx)) {
            call.reject("Overlay permission not granted");
            return;
        }
        BubbleService.saveLangs(ctx, call.getString("targetLang"), call.getString("sourceLang"));
        BubbleService.saveTone(ctx, call.getString("translationTone"));
        Intent i = new Intent(ctx, BubbleService.class).setAction(BubbleService.ACTION_SHOW);
        ctx.startForegroundService(i);
        call.resolve();
    }

    @PluginMethod
    public void hideBubble(PluginCall call) {
        Context ctx = getContext();
        ctx.stopService(new Intent(ctx, BubbleService.class));
        call.resolve();
    }

    // setLanguages({ targetLang?, sourceLang?, translationTone? })
    @PluginMethod
    public void setLanguages(PluginCall call) {
        BubbleService.saveLangs(getContext(), call.getString("targetLang"), call.getString("sourceLang"));
        BubbleService.saveTone(getContext(), call.getString("translationTone"));
        call.resolve();
    }

    // setDisplayMode({ mode: "both"|"original"|"translation" })
    @PluginMethod
    public void setDisplayMode(PluginCall call) {
        BubbleService.saveDisplayMode(getContext(), call.getString("mode"));
        call.resolve();
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", BubbleService.running);
        call.resolve(ret);
    }

    // ================================================================
    // ============ STT: مدل‌های تشخیص گفتار (Zipformer) ==============
    // ================================================================

    // checkModelStatus({ lang: "en" })
    @PluginMethod
    public void checkModelStatus(PluginCall call) {
        Context ctx = getContext();
        String lang = call.getString("lang", "en");
        JSObject ret = new JSObject();
        ret.put("supported", SherpaModelManager.isAvailable(lang));
        ret.put("downloaded", SherpaModelManager.getModelDir(ctx, lang) != null);
        ret.put("downloading", SherpaModelManager.isDownloading());
        call.resolve(ret);
    }

    // downloadModel({ lang: "en" })
    @PluginMethod
    public void downloadModel(PluginCall call) {
        final Context ctx = getContext();
        final String lang = call.getString("lang", "en");

        if (!SherpaModelManager.isAvailable(lang)) {
            call.reject("STT not supported for language: " + lang);
            return;
        }
        if (SherpaModelManager.getModelDir(ctx, lang) != null) {
            JSObject ret = new JSObject();
            ret.put("alreadyDownloaded", true);
            call.resolve(ret);
            return;
        }

        SherpaModelManager.downloadModel(ctx.getApplicationContext(), lang,
                new SherpaModelManager.ProgressCallback() {
                    @Override
                    public void onProgress(String l, long done, long total) {
                        JSObject ret = new JSObject();
                        ret.put("lang", l);
                        ret.put("bytes", done);
                        ret.put("total", total);
                        notifyListeners("modelDownloadProgress", ret);
                    }

                    @Override
                    public void onDone(String l) {
                        JSObject ret = new JSObject();
                        ret.put("lang", l);
                        notifyListeners("modelDownloadDone", ret);
                    }

                    @Override
                    public void onError(String l, Exception e) {
                        JSObject ret = new JSObject();
                        ret.put("lang", l);
                        ret.put("error", e.getMessage() != null ? e.getMessage() : "unknown");
                        notifyListeners("modelDownloadError", ret);
                    }
                });

        call.resolve();
    }

    // deleteModel({ lang: "en" })
    @PluginMethod
    public void deleteModel(PluginCall call) {
        Context ctx = getContext();
        String lang = call.getString("lang", "en");
        SherpaModelManager.deleteModel(ctx, lang);
        call.resolve();
    }

    // ================================================================
    // ============ TTS: تبدیل متن به گفتار (Piper/VITS) ==============
    // ================================================================

    // setTtsEnabled({ enabled: true })
    @PluginMethod
    public void setTtsEnabled(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", false);
        BubbleService.saveTtsEnabled(getContext(), enabled != null && enabled);
        call.resolve();
    }

    // checkTtsStatus({ lang: "fa" })
    @PluginMethod
    public void checkTtsStatus(PluginCall call) {
        Context ctx = getContext();
        String lang = call.getString("lang", "en");
        JSObject ret = new JSObject();
        ret.put("supported", SherpaModelManager.isTtsAvailable(lang));
        ret.put("downloaded", SherpaModelManager.getTtsModelDir(ctx, lang) != null);
        ret.put("downloading", SherpaModelManager.isDownloading());
        call.resolve(ret);
    }

    // getTtsCatalog() → { languages: [{lang, downloaded}], downloading, downloadingLang }
    // یک‌جا وضعیتِ همه‌ی زبان‌هایی که مدلِ آفلاین دارن (برای صفحه‌ی تنظیمات)
    @PluginMethod
    public void getTtsCatalog(PluginCall call) {
        Context ctx = getContext();
        JSArray arr = new JSArray();
        for (String l : SherpaModelManager.ttsLanguages()) {
            JSObject o = new JSObject();
            o.put("lang", l);
            o.put("downloaded", SherpaModelManager.getTtsModelDir(ctx, l) != null);
            arr.put(o);
        }
        JSObject ret = new JSObject();
        ret.put("languages", arr);
        ret.put("downloading", SherpaModelManager.isDownloading());
        String dl = SherpaModelManager.getTtsDownloadingLang();
        if (dl != null) ret.put("downloadingLang", dl);
        call.resolve(ret);
    }

    // downloadTtsModel({ lang: "fa" })
    @PluginMethod
    public void downloadTtsModel(PluginCall call) {
        final Context ctx = getContext();
        final String lang = call.getString("lang", "en");

        if (!SherpaModelManager.isTtsAvailable(lang)) {
            call.reject("TTS not supported for language: " + lang);
            return;
        }
        if (SherpaModelManager.getTtsModelDir(ctx, lang) != null) {
            JSObject ret = new JSObject();
            ret.put("alreadyDownloaded", true);
            call.resolve(ret);
            return;
        }

        SherpaModelManager.downloadTtsModel(ctx.getApplicationContext(), lang,
                new SherpaModelManager.ProgressCallback() {
                    @Override
                    public void onProgress(String l, long done, long total) {
                        JSObject ret = new JSObject();
                        ret.put("lang", l);
                        ret.put("bytes", done);
                        notifyListeners("ttsModelDownloadProgress", ret);
                    }

                    @Override
                    public void onDone(String l) {
                        JSObject ret = new JSObject();
                        ret.put("lang", l);
                        notifyListeners("ttsModelDownloadDone", ret);
                    }

                    @Override
                    public void onError(String l, Exception e) {
                        JSObject ret = new JSObject();
                        ret.put("lang", l);
                        ret.put("error", e.getMessage() != null ? e.getMessage() : "unknown");
                        notifyListeners("ttsModelDownloadError", ret);
                    }
                });

        call.resolve();
    }

    // deleteTtsModel({ lang: "fa" })
    @PluginMethod
    public void deleteTtsModel(PluginCall call) {
        Context ctx = getContext();
        String lang = call.getString("lang", "en");
        // اگه همین زبان الان توی حافظه لوده، اول آزادش کن (وگرنه فایلِ مدل درگیره)
        synchronized (BubblePlugin.class) {
            String n = SherpaModelManager.normalize(lang);
            TtsEngine e = n == null ? null : ENGINES.remove(n);
            if (e != null) e.release();
            if (n != null && n.equals(sharedTtsLang)) {
                sharedTts = null;
                sharedTtsLang = null;
            }
        }
        SherpaModelManager.deleteTtsModel(ctx, lang);
        call.resolve();
    }

    /**
     * ✅ متد اصلی خواندن متن با TTS
     * speak({ text: "سلام", lang: "fa", speed: 1.0, id: "..." })
     * بعد از تمام‌شدنِ واقعیِ پخش، event «ttsSpeakDone» با { lang, text, id, ok } فرستاده می‌شه
     * (ok=false یعنی شکستِ واقعی → سمتِ JS باید fallback کنه).
     */
    @PluginMethod
    public void speak(final PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        final String text = call.getString("text");
        final String lang = SherpaModelManager.normalize(call.getString("lang", "en"));
        final String id = call.getString("id");
        Double sp = call.getDouble("speed", 1.0);

        if (text == null || text.trim().isEmpty()) {
            call.reject("text is empty");
            return;
        }
        if (lang == null) {
            call.reject("language is empty");
            return;
        }
        if (SherpaModelManager.getTtsModelDir(ctx, lang) == null) {
            call.reject("TTS model not downloaded for language: " + lang);
            return;
        }

        final float speed = (sp == null || sp <= 0) ? 1.0f : sp.floatValue();
        final int mySeq = SPEAK_SEQ.incrementAndGet();

        new Thread(() -> {
            try {
                TtsEngine engine = obtainEngine(ctx, lang, true);
                if (engine == null) {
                    call.reject("failed to load TTS engine");
                    return;
                }
                // موفقیتِ شروع رو فوراً برمی‌گردونیم
                call.resolve();

                // وسطِ لود، stop() یا speak ی جدید اومده → این یکی دیگه پخش نشه
                if (mySeq != SPEAK_SEQ.get()) {
                    emitSpeakDone(lang, text, id, true);
                    return;
                }
                rememberTtsLang(ctx, lang);
                engine.speakAsync(text, speed, ok -> emitSpeakDone(lang, text, id, ok));
            } catch (Exception e) {
                call.reject("TTS error: " + e.getMessage());
            }
        }, "tts-speak").start();
    }

    /**
     * ✅ گرم‌کردنِ موتور: preloadTts({ lang? }) — بدونِ lang، آخرین زبانِ استفاده‌شده
     * (یا اولین زبانِ دانلودشده). فوراً resolve می‌شه و لود توی پس‌زمینه انجام می‌شه.
     */
    @PluginMethod
    public void preloadTts(final PluginCall call) {
        final Context ctx = getContext().getApplicationContext();
        String l = SherpaModelManager.normalize(call.getString("lang"));
        call.resolve();
        if (l == null) l = SherpaModelManager.normalize(lastTtsLang(ctx));
        if (l == null) {
            for (String x : SherpaModelManager.ttsLanguages()) {
                if (SherpaModelManager.getTtsModelDir(ctx, x) != null) { l = x; break; }
            }
        }
        if (l == null || SherpaModelManager.getTtsModelDir(ctx, l) == null) return;
        final String lang = l;
        new Thread(() -> {
            try {
                TtsEngine e = obtainEngine(ctx, lang, false);
                if (e != null) e.warmUp();
            } catch (Throwable ignored) {}
        }, "tts-preload").start();
    }

    /**
     * ✅ صدای جمله‌ی بعدی رو پیش‌پیش بساز (تا موقعِ نوبتش بدونِ تأخیر پخش بشه).
     * prefetchTts({ text, lang, speed }) — اگه موتورِ این زبان لود نیست، نادیده گرفته می‌شه.
     */
    @PluginMethod
    public void prefetchTts(PluginCall call) {
        String text = call.getString("text");
        String lang = SherpaModelManager.normalize(call.getString("lang", "en"));
        Double sp = call.getDouble("speed", 1.0);
        call.resolve();
        if (text == null || text.trim().isEmpty() || lang == null) return;
        TtsEngine e = ENGINES.get(lang);
        if (e == null || e.isReleased()) return;
        e.prefetch(text, (sp == null || sp <= 0) ? 1.0f : sp.floatValue());
    }

    /** ✅ توقف TTS */
    @PluginMethod
    public void stopSpeaking(PluginCall call) {
        SPEAK_SEQ.incrementAndGet();
        for (TtsEngine e : ENGINES.values()) {
            try { e.stop(); } catch (Throwable ignored) {}
        }
        TtsEngine shared = sharedTts;
        if (shared != null) shared.stop();
        call.resolve();
    }

    /** ✅ آزادسازیِ موتورها (اختیاری) */
    @PluginMethod
    public void releaseTts(PluginCall call) {
        synchronized (BubblePlugin.class) {
            for (TtsEngine e : ENGINES.values()) {
                try { e.release(); } catch (Throwable ignored) {}
            }
            ENGINES.clear();
            if (sharedTts != null) {
                sharedTts.release();
                sharedTts = null;
                sharedTtsLang = null;
            }
        }
        call.resolve();
    }
}
