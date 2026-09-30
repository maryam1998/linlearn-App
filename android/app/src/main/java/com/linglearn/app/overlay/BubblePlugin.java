package com.linglearn.app.overlay;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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
        if (Settings.canDrawOverlays(ctx)) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName())
        );
        getActivity().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void showBubble(PluginCall call) {
        if (!Settings.canDrawOverlays(getContext())) {
            call.reject("Overlay permission not granted");
            return;
        }
        Intent intent = new Intent(getContext(), BubbleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void hideBubble(PluginCall call) {
        Intent intent = new Intent(getContext(), BubbleService.class);
        getContext().stopService(intent);
        call.resolve();
    }
}