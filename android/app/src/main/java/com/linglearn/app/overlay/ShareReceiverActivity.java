package com.linglearn.app.overlay;

import android.app.Activity;
import android.app.AlertDialog;
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
    static final String EXTRA_REPLACE_KEY = "com.linglearn.app.REPLACE_KEY";   // ردیفِ بدونِ لینکِ قبلی که باید با لینک کامل شود
    static final String EXTRA_TITLE = "com.linglearn.app.TITLE";
    private boolean fromClip = false, clipDone = false;

    /** از ▶ حباب (وقتی «دسترسی به اعلان‌ها» نیست): توضیحِ شفاف + انتخابِ «همگام با پخش» یا «فقط متنِ کامل». */
    static final String EXTRA_YT_CHOOSE = "com.linglearn.app.YT_CHOOSE";
    static final String EXTRA_FA = "com.linglearn.app.FA";
    private boolean ytFullClip = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        fromClip = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_CLIP, false);
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_YT_CHOOSE, false)) {
            showYtChoice(getIntent().getBooleanExtra(EXTRA_FA, true));
            return;
        }
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

    private void showYtChoice(final boolean fa) {
        String msg = fa
                ? "۱) همگام با پخش: جمله‌ی در حال پخش پررنگ می‌شود و دکمه‌های ⏮ و ↺ کار می‌کنند. این روش «دسترسی به اعلان‌ها» می‌خواهد. "
                + "فقط اعلانِ خودِ یوتیوب برای تشخیصِ ویدیو و زمانِ پخش بررسی می‌شود؛ اعلان‌های برنامه‌های دیگر خوانده یا ذخیره نمی‌شوند، "
                + "و هر وقت خواستی از تنظیماتِ اندروید خاموشش کن.\n\n"
                + "۲) فقط متنِ کامل: بدونِ هیچ دسترسی. لینکِ ویدیو را در یوتیوب کپی کن (Share ← Copy link)، بعد «فقط متنِ کامل» را بزن؛ "
                + "کلِ زیرنویس یک‌جا می‌آید، بدونِ پررنگ‌شدن، با دکمه‌ی تکرارِ کلِ متن."
                : "1) Synced: highlights the sentence being played; ⏮ and ↺ work. Needs “Notification access”. "
                + "Only YouTube's own notification is checked (to detect the video and its play time); other apps' notifications are not read or stored. "
                + "You can turn it off anytime in Android settings.\n\n"
                + "2) Full text only: no permission. Copy the video link in YouTube (Share → Copy link), then tap “Full text only”; "
                + "all subtitles load at once, with no highlighting, plus a repeat-all-text button.";
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(fa ? "زیرنویس یوتیوب" : "YouTube subtitles")
                .setMessage(msg)
                .setPositiveButton(fa ? "فقط متنِ کامل" : "Full text only", (dlg, w) -> {
                    ytFullClip = true; fromClip = true; clipDone = false;
                    // کلیپ‌بورد فقط وقتی خوانده می‌شود که پنجره فوکوس داشته باشد
                    new Handler(Looper.getMainLooper()).postDelayed(this::finishFromClip, 900);
                })
                .setNeutralButton(fa ? "همگام‌سازی" : "Synced", (dlg, w) -> {
                    YtMedia.openAccessSettings(this);
                    finish();
                })
                .setNegativeButton(fa ? "بستن" : "Close", (dlg, w) -> finish())
                .create();
        d.setOnCancelListener(x -> finish());
        d.show();
    }

    private void finishFromClip() {
        if (clipDone) return;
        clipDone = true;
        boolean ok = false;
        if (ytFullClip) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = cm == null ? null : cm.getPrimaryClip();
                CharSequence t = cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).coerceToText(this) : null;
                String id = t == null ? null : YtMedia.idFromText(t.toString());
                if (id == null && t != null && YtMedia.isValidYouTubeVideoId(t.toString().trim())) id = t.toString().trim();
                if (id != null) { BubbleService.startYoutubeFull(getApplicationContext(), id); ok = true; }
            } catch (Throwable ignored) {}
            if (!ok) Toast.makeText(this, "لینکِ یوتیوب در کلیپ‌بورد نیست — در یوتیوب Share ← Copy link را بزن و دوباره ▶",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm == null ? null : cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).coerceToText(this);
                if (t != null) ok = handleText(t.toString(), null,
                        getIntent().getStringExtra(EXTRA_REPLACE_KEY), getIntent().getStringExtra(EXTRA_TITLE));
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
        return handleText(in.getStringExtra(Intent.EXTRA_TEXT), in.getStringExtra(Intent.EXTRA_SUBJECT), null, null);
    }

    private boolean handleText(String text, String subject, String replaceKey, String titleOverride) throws Exception {
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
        if (titleOverride != null && !titleOverride.trim().isEmpty()) title = titleOverride.trim();

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
                .put("key", (replaceKey != null && !replaceKey.isEmpty()) ? replaceKey : "share-" + Integer.toHexString(url.hashCode()))
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
