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

    // ✅ بررسی وضعیت مدل Sherpa
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

    // ✅ دانلود مدل (از صفحه تنظیمات صدا زده می‌شه)
    // downloadModel({ lang: "en" })
    @PluginMethod
    public void downloadModel(PluginCall call) {
        final Context ctx = getContext();
        final String lang = call.getString("lang", "en");

        if (!SherpaModelManager.isAvailable(lang)) {
            call.reject("Model not supported for language: " + lang);
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

    // ✅ حذف مدل (برای آزادسازی فضا)
    // deleteModel({ lang: "en" })
    @PluginMethod
    public void deleteModel(PluginCall call) {
        Context ctx = getContext();
        String lang = call.getString("lang", "en");
        SherpaModelManager.deleteModel(ctx, lang);
        call.resolve();
    }
}
