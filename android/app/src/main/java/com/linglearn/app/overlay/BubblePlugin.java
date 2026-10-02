package com.linglearn.app.overlay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "BubblePlugin")
public class BubblePlugin extends Plugin {

    // ✅ موتور TTS مشترک (بدون private تا BubbleService هم بتونه استفاده کنه)
    static volatile TtsEngine sharedTts;
    static volatile String sharedTtsLang = null;

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
        SherpaModelManager.deleteTtsModel(ctx, lang);
        call.resolve();
    }

    /**
     * ✅ متد اصلی خواندن متن با TTS
     * speak({ text: "سلام", lang: "fa", speed: 1.0 })
     */
    @PluginMethod
    public void speak(final PluginCall call) {
        final Context ctx = getContext();
        final String text = call.getString("text");
        final String lang = call.getString("lang", "en");
        Double sp = call.getDouble("speed", 1.0);

        if (text == null || text.trim().isEmpty()) {
            call.reject("text is empty");
            return;
        }

        if (SherpaModelManager.getTtsModelDir(ctx, lang) == null) {
            call.reject("TTS model not downloaded for language: " + lang);
            return;
        }

        final float speed = (sp == null || sp <= 0) ? 1.0f : sp.floatValue();

        new Thread(() -> {
            try {
                TtsEngine engine;
                synchronized (BubblePlugin.class) {
                    if (sharedTts == null || !lang.equals(sharedTtsLang)) {
                        if (sharedTts != null) {
                            sharedTts.release();
                            sharedTts = null;
                        }
                        sharedTts = TtsEngine.create(ctx.getApplicationContext(), lang);
                        sharedTtsLang = lang;
                    }
                    engine = sharedTts;
                }
                if (engine == null) {
                    call.reject("failed to load TTS engine");
                    return;
                }
                engine.speak(text, speed);
                call.resolve();
            } catch (Exception e) {
                call.reject("TTS error: " + e.getMessage());
            }
        }, "tts-speak").start();
    }

    /** ✅ توقف TTS */
    @PluginMethod
    public void stopSpeaking(PluginCall call) {
        TtsEngine engine = sharedTts;
        if (engine != null) {
            engine.stop();
        }
        call.resolve();
    }

    /** ✅ آزادسازی موتور (اختیاری) */
    @PluginMethod
    public void releaseTts(PluginCall call) {
        synchronized (BubblePlugin.class) {
            if (sharedTts != null) {
                sharedTts.release();
                sharedTts = null;
                sharedTtsLang = null;
            }
        }
        call.resolve();
    }
}
