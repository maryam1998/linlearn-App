package com.linglearn.app.overlay;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📤 «Share → Hope»: کاربر در اینستاگرام/تیک‌تاک/تلگرام/… دکمه‌ی اشتراک‌گذاری را می‌زند و Hope را انتخاب می‌کند.
 * لینکِ دقیقِ همان ویدیو/ریل به «داستان‌های ذخیره‌شده» اضافه می‌شود. هیچ مجوزِ ویژه‌ای لازم نیست و
 * هیچ‌چیز از برنامه‌های دیگر خوانده نمی‌شود؛ فقط متنی که خودِ کاربر می‌فرستد.
 */
public class ShareReceiverActivity extends Activity {

    private static final Pattern URL = Pattern.compile("(?i)\\b(https?://[^\\s\"'<>]+)");

    /** از 💾 حباب: به‌جای متنِ Share، لینکِ کپی‌شده‌ی کلیپ‌بورد خوانده می‌شود. */
    static final String EXTRA_FROM_CLIP = "com.linglearn.app.FROM_CLIP";
    private boolean fromClip = false, clipDone = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        fromClip = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_CLIP, false);
        if (fromClip) {
            // کلیپ‌بورد فقط وقتی خوانده می‌شود که پنجره فوکوس داشته باشد؛ اگر فوکوس دیر آمد، بعد از ۱.۲ ثانیه هم امتحان می‌کنیم
            new Handler(Looper.getMainLooper()).postDelayed(this::finishFromClip, 1200);
            return;
        }
        boolean ok = false;
        try {
            ok = handle(getIntent());
        } catch (Throwable ignored) {}
        Toast.makeText(this, ok ? "ذخیره شد ✓ — Saved to Hope" : "لینکی پیدا نشد — No link found",
                Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fromClip) finishFromClip();
    }

    private void finishFromClip() {
        if (clipDone) return;
        clipDone = true;
        boolean ok = false;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm == null ? null : cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).coerceToText(this);
                if (t != null) ok = handleText(t.toString(), null);
            }
        } catch (Throwable ignored) {}
        Toast.makeText(this, ok ? "لینکِ کپی‌شده ذخیره شد ✓ — Saved to Hope"
                : "لینکی در کلیپ‌بورد نیست — اول در همان برنامه Share ← Copy link را بزن",
                Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    private boolean handle(Intent in) throws Exception {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return false;
        return handleText(in.getStringExtra(Intent.EXTRA_TEXT), in.getStringExtra(Intent.EXTRA_SUBJECT));
    }

    private boolean handleText(String text, String subject) throws Exception {
        if (text == null || text.trim().isEmpty()) return false;
        Matcher m = URL.matcher(text);
        if (!m.find()) return false;
        String url = m.group(1).replaceAll("[)\\].,;!?]+$", "");

        String host = "";
        try { host = new java.net.URI(url).getHost(); } catch (Throwable ignored) {}
        host = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^(www|m|vm|vt|mobile)\\.", "");
        String app = appName(host);

        // عنوان: متنِ همراهِ لینک (مثلاً کپشنِ تیک‌تاک)، وگرنه نامِ برنامه
        String title = text.replace(m.group(1), "").replaceAll("\\s+", " ").trim();
        if (title.length() > 120) title = title.substring(0, 120).trim() + "…";
        if (title.isEmpty() && subject != null && !subject.trim().isEmpty()) title = subject.trim();
        if (title.isEmpty()) title = app;

        long now = System.currentTimeMillis();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

        JSONObject src = new JSONObject()
                .put("app", app)
                .put("pkg", "")
                .put("title", title)
                .put("artist", "")
                .put("url", url)
                .put("inApp", false)
                .put("timed", false);
        JSONObject item = new JSONObject()
                .put("key", "share-" + Integer.toHexString(url.hashCode()))
                .put("live", false)
                .put("title", title)
                .put("channel", app)
                .put("url", url)
                .put("lang", "en")
                .put("targets", new JSONArray())
                .put("rev", now)
                .put("savedAt", fmt.format(new Date(now)))
                .put("lines", new JSONArray())
                .put("source", src);
        return YtSaved.add(this, item);
    }

    private static String appName(String h) {
        if (h.endsWith("instagram.com")) return "Instagram";
        if (h.endsWith("tiktok.com")) return "TikTok";
        if (h.endsWith("youtube.com") || h.equals("youtu.be")) return "YouTube";
        if (h.equals("t.me") || h.endsWith("telegram.me") || h.endsWith("telegram.org")) return "Telegram";
        if (h.endsWith("facebook.com") || h.equals("fb.watch") || h.equals("fb.me")) return "Facebook";
        if (h.equals("x.com") || h.endsWith("twitter.com")) return "X";
        if (h.endsWith("snapchat.com")) return "Snapchat";
        if (h.endsWith("reddit.com") || h.equals("redd.it")) return "Reddit";
        if (h.endsWith("vimeo.com")) return "Vimeo";
        if (h.endsWith("twitch.tv")) return "Twitch";
        if (h.endsWith("linkedin.com")) return "LinkedIn";
        if (h.endsWith("pinterest.com") || h.equals("pin.it")) return "Pinterest";
        if (h.endsWith("spotify.com")) return "Spotify";
        return h.isEmpty() ? "Link" : h;
    }
}
