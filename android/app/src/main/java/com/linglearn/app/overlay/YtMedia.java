package com.linglearn.app.overlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 📺 تشخیصِ ویدیو و زمانِ دقیقِ پخشِ اپ یوتیوب (com.google.android.youtube).
 *
 * روشِ کار: به MediaSessionِ خودِ یوتیوب وصل می‌شیم (نه ping به یوتیوب، نه WebView):
 *   • عنوان، مدت و PlaybackState (موقعیت، زمانِ آخرین آپدیت، سرعت) از MediaController خونده می‌شه
 *     و بین آپدیت‌ها موقعیت extrapolate می‌شه ([State.nowMs]).
 *   • seek هم از همون controller انجام می‌شه ([Tracker.seekTo]).
 *   • videoId از متادیتای session یا extrasِ نوتیفیکیشنِ یوتیوب درمیاد (+ isValidYouTubeVideoId).
 *
 * خودِ این کلاس یک NotificationListenerService هست چون اندروید فقط به برنامه‌هایی که
 * «دسترسی به اعلان‌ها» دارن اجازه‌ی دیدنِ MediaSessionهای بقیه‌ی برنامه‌ها رو می‌ده.
 * هیچ اعلانی دست‌کاری یا ذخیره نمی‌شه؛ فقط نوتیفیکیشنِ خودِ یوتیوب برای پیدا کردنِ videoId اسکن می‌شه.
 */
public class YtMedia extends NotificationListenerService {

    private static final String TAG = "YtMedia";
    static final String YT_PKG = "com.google.android.youtube";

    private static final Pattern ID_IN_TEXT = Pattern.compile(
            "(?:[?&]v=|youtu\\.be/|/vi/|/vi_webp/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");
    private static final Pattern ID_ONLY = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    // ───────── videoId ─────────

    public static boolean isValidYouTubeVideoId(String s) {
        return s != null && ID_ONLY.matcher(s).matches();
    }

    /** videoId از یک متن/URL (watch?v=، youtu.be/، thumbnail /vi/، shorts/، embed/). */
    static String idFromText(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = ID_IN_TEXT.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // آخرین سرنخی که از نوتیفیکیشنِ یوتیوب گرفتیم (فقط وقتی سرویس به سیستم وصله)
    private static volatile String hintId;
    private static volatile String hintTitle = "";

    private static void scan(StatusBarNotification sbn) {
        try {
            if (sbn == null || !YT_PKG.equals(sbn.getPackageName()) || sbn.getNotification() == null) return;
            Bundle ex = sbn.getNotification().extras;
            if (ex == null) return;
            String found = null;
            for (String k : ex.keySet()) {
                Object v = ex.get(k);
                if (v instanceof CharSequence) {
                    String id = idFromText(v.toString());
                    if (id != null) { found = id; break; }
                }
            }
            if (found == null) return;
            CharSequence t = ex.getCharSequence(android.app.Notification.EXTRA_TITLE);
            hintId = found;
            hintTitle = t == null ? "" : t.toString().trim();
        } catch (Throwable e) {
            Log.d(TAG, "scan failed: " + e);
        }
    }

    @Override public void onListenerConnected() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) scan(s);
        } catch (Throwable ignored) {}
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { scan(sbn); }

    // ───────── دسترسی ─────────

    /** آیا کاربر «دسترسی به اعلان‌ها» رو برای این اپ روشن کرده؟ */
    public static boolean hasAccess(Context ctx) {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** صفحه‌ی تنظیماتِ «دسترسی به اعلان‌ها» رو باز می‌کنه (در اندروید ۱۱+ مستقیم صفحه‌ی همین اپ). */
    public static void openAccessSettings(Context ctx) {
        Intent i = null;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(ctx, YtMedia.class).flattenToString());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {}
        }
        try {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable e) {
            Log.w(TAG, "cannot open notification access settings", e);
        }
    }

    // ───────── وضعیتِ پخش ─────────

    public static final class State {
        public boolean hasSession;
        public String videoId;          // null = از session درنیومد (مثلاً باید از روی عنوان پیدا بشه)
        public String title = "";
        public String channel = "";
        public long durationMs;         // 0 = نامشخص (مثلاً لایو)
        public long positionMs;         // موقعیت در لحظه‌ی updatedAt
        public long updatedAt;          // SystemClock.elapsedRealtime() همان لحظه
        public float speed = 1f;
        public boolean playing;

        /** موقعیتِ فعلی؛ وقتی در حال پخشه بین آپدیت‌ها extrapolate می‌شه. */
        public long nowMs() {
            if (!playing) return positionMs;
            float sp = speed <= 0f ? 1f : speed;
            long p = positionMs + (long) ((SystemClock.elapsedRealtime() - updatedAt) * sp);
            if (durationMs > 0 && p > durationMs) p = durationMs;
            return Math.max(0L, p);
        }
    }

    public interface Listener {
        /** روی main thread؛ با هر تغییرِ session / متادیتا / وضعیتِ پخش (play، pause، seek، سرعت). */
        void onYtState(State s);
    }

    public static final class Tracker {
        private final Context app;
        private final Listener listener;
        private final Handler h = new Handler(Looper.getMainLooper());
        private MediaSessionManager msm;
        private ComponentName cn;
        private MediaController ctrl;
        private boolean started = false;

        private final MediaSessionManager.OnActiveSessionsChangedListener sessionsL = this::pick;

        private final MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
            @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
            @Override public void onSessionDestroyed() { pick(null); }
        };

        public Tracker(Context ctx, Listener l) {
            this.app = ctx.getApplicationContext();
            this.listener = l;
        }

        /** false = دسترسی به اعلان‌ها داده نشده (SecurityException). */
        public boolean start() {
            if (started) return true;
            try {
                msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
                if (msm == null) return false;
                cn = new ComponentName(app, YtMedia.class);
                msm.addOnActiveSessionsChangedListener(sessionsL, cn, h);
                started = true;
                pick(msm.getActiveSessions(cn));
                return true;
            } catch (SecurityException e) {
                Log.w(TAG, "no notification access: " + e);
                started = false;
                return false;
            } catch (Throwable e) {
                Log.w(TAG, "tracker start failed", e);
                started = false;
                return false;
            }
        }

        public void stop() {
            if (!started) return;
            started = false;
            try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsL); } catch (Throwable ignored) {}
            detach();
        }

        private void detach() {
            MediaController c = ctrl; ctrl = null;
            if (c != null) { try { c.unregisterCallback(cb); } catch (Throwable ignored) {} }
        }

        private void pick(List<MediaController> list) {
            MediaController best = null;
            if (list != null) {
                for (MediaController c : list) {
                    if (!YT_PKG.equals(c.getPackageName())) continue;
                    if (best == null) best = c;
                    PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                }
            }
            if (best != null && ctrl != null && best.getSessionToken().equals(ctrl.getSessionToken())) {
                publish();
                return;
            }
            detach();
            ctrl = best;
            if (ctrl != null) { try { ctrl.registerCallback(cb, h); } catch (Throwable ignored) {} }
            publish();
        }

        private void publish() {
            if (!started) return;
            listener.onYtState(snapshot());
        }

        public State snapshot() {
            State s = new State();
            MediaController c = ctrl;
            if (c == null) return s;
            s.hasSession = true;
            try {
                MediaMetadata md = c.getMetadata();
                if (md != null) {
                    String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                    s.title = title == null ? "" : title.trim();
                    String ch = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (ch == null) ch = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                    s.channel = ch == null ? "" : ch.trim();
                    s.durationMs = Math.max(0L, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
                    s.videoId = videoIdFrom(md, s.title);
                }
            } catch (Throwable e) {
                Log.d(TAG, "metadata read failed: " + e);
            }
            try {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null) {
                    s.playing = ps.getState() == PlaybackState.STATE_PLAYING;
                    s.positionMs = Math.max(0L, ps.getPosition());
                    s.updatedAt = ps.getLastPositionUpdateTime();
                    if (s.updatedAt <= 0) s.updatedAt = SystemClock.elapsedRealtime();
                    s.speed = ps.getPlaybackSpeed();
                } else {
                    s.updatedAt = SystemClock.elapsedRealtime();
                }
            } catch (Throwable e) {
                Log.d(TAG, "playback state read failed: " + e);
            }
            return s;
        }

        private static String videoIdFrom(MediaMetadata md, String title) {
            // ۱) خودِ شناسه‌ی مدیا (اگه دقیقاً یک videoId باشه)
            String mid = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (mid != null && isValidYouTubeVideoId(mid.trim())) return mid.trim();
            // ۲) هر فیلدِ متنیِ متادیتا که URL یا thumbnailِ یوتیوب (…/vi/<id>/…) داشته باشه
            try {
                for (String k : md.keySet()) {
                    String v;
                    try { v = md.getString(k); } catch (Throwable e) { continue; }
                    String id = idFromText(v);
                    if (id != null) return id;
                }
            } catch (Throwable ignored) {}
            // ۳) سرنخِ نوتیفیکیشنِ یوتیوب، به شرطِ این‌که عنوانش با عنوانِ فعلی یکی باشه
            String hid = hintId;
            if (hid != null && !title.isEmpty() && title.equalsIgnoreCase(hintTitle)) return hid;
            return null;
        }

        /** پخش (اگر مکث بود) از همان controller. */
        public void play() {
            MediaController c = ctrl;
            if (c == null) return;
            try { c.getTransportControls().play(); } catch (Throwable ignored) {}
        }

        /** seek از همان controller. */
        public boolean seekTo(long ms) {
            MediaController c = ctrl;
            if (c == null) return false;
            try {
                c.getTransportControls().seekTo(Math.max(0L, ms));
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
    }

    // ───────── 🎙 «الان چه چیزی پخش می‌شود؟» (برای ذخیره‌ی ترجمه‌ی زنده با منبعِ صدا) ─────────

    /** عکسِ فوریِ پلیرِ بیرونی (هر برنامه‌ای که MediaSession دارد: پلیر موسیقی/کتاب صوتی/پادکست/یوتیوب…). */
    public static final class Now {
        public String pkg = "";
        public String app = "";          // نامِ برنامه (مثلاً «Poweramp»)
        public String title = "";
        public String artist = "";
        public String url = "";          // اگر در متادیتا لینک بود (یا ویدیوی یوتیوب بود)
        public long posMs = -1;          // موقعیتِ پخش (ms)، -1 = نامشخص
        public boolean playing;
        public boolean inApp;            // صدا از خودِ همین اپ است
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)\\b((?:https?://|www\\.)[^\\s\"'<>]+)");
    private static volatile Now cachedNow;
    private static volatile long cachedNowAt = 0;
    private static volatile long noAccessUntil = 0;

    /** null = دسترسیِ اعلان‌ها داده نشده یا چیزی در حال پخش نیست. نتیجه ~۶۰۰ms کش می‌شود. */
    public static Now nowPlaying(Context ctx) {
        final long t = SystemClock.elapsedRealtime();
        if (t < noAccessUntil) return null;
        if (t - cachedNowAt < 600) return adjust(cachedNow, t - cachedNowAt);
        Now n = null;
        try {
            Context app = ctx.getApplicationContext();
            MediaSessionManager msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                List<MediaController> list = msm.getActiveSessions(new ComponentName(app, YtMedia.class));
                MediaController best = null;
                if (list != null) {
                    for (MediaController c : list) {
                        PlaybackState ps = c.getPlaybackState();
                        if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                    }
                    if (best == null) {                       // هیچ‌کدام در حالِ پخش نیست → اولین sessionِ دارایِ عنوان
                        for (MediaController c : list) {
                            MediaMetadata md = c.getMetadata();
                            if (md != null && md.getString(MediaMetadata.METADATA_KEY_TITLE) != null) { best = c; break; }
                        }
                    }
                }
                if (best != null) n = describe(app, best);
            }
        } catch (SecurityException e) {
            noAccessUntil = t + 30_000;                       // دسترسی نیست؛ هر لحظه binder را صدا نزن
        } catch (Throwable e) {
            Log.d(TAG, "nowPlaying failed: " + e);
        }
        cachedNow = n;
        cachedNowAt = t;
        return n;
    }

    private static Now adjust(Now n, long ageMs) {
        if (n == null) return null;
        Now c = new Now();
        c.pkg = n.pkg; c.app = n.app; c.title = n.title; c.artist = n.artist; c.url = n.url;
        c.playing = n.playing; c.inApp = n.inApp;
        c.posMs = (n.posMs >= 0 && n.playing) ? n.posMs + ageMs : n.posMs;
        return c;
    }

    private static Now describe(Context app, MediaController c) {
        Now n = new Now();
        n.pkg = c.getPackageName() == null ? "" : c.getPackageName();
        n.inApp = n.pkg.equals(app.getPackageName());
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            n.app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(n.pkg, 0)));
        } catch (Throwable e) {
            n.app = n.pkg;
        }
        try {
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                n.title = title == null ? "" : title.trim();
                String ar = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (ar == null) ar = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                if (ar == null) ar = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                n.artist = ar == null ? "" : ar.trim();
                String vid = null;
                for (String k : md.keySet()) {
                    String v;
                    try { v = md.getString(k); } catch (Throwable e) { continue; }
                    if (v == null || v.isEmpty()) continue;
                    if (vid == null && YT_PKG.equals(n.pkg)) {
                        String id = isValidYouTubeVideoId(v.trim()) && MediaMetadata.METADATA_KEY_MEDIA_ID.equals(k)
                                ? v.trim() : idFromText(v);
                        if (id != null) vid = id;
                    }
                    if (n.url.isEmpty()) {
                        Matcher m = URL_IN_TEXT.matcher(v);
                        if (m.find()) {
                            String u = m.group(1);
                            n.url = u.toLowerCase(java.util.Locale.ROOT).startsWith("www.") ? "https://" + u : u;
                        }
                    }
                }
                if (vid != null) n.url = "https://youtu.be/" + vid;
            }
        } catch (Throwable e) {
            Log.d(TAG, "now metadata failed: " + e);
        }
        try {
            PlaybackState ps = c.getPlaybackState();
            if (ps != null) {
                n.playing = ps.getState() == PlaybackState.STATE_PLAYING;
                long pos = ps.getPosition();
                if (pos >= 0) {
                    if (n.playing) {
                        long upd = ps.getLastPositionUpdateTime();
                        float sp = ps.getPlaybackSpeed() <= 0f ? 1f : ps.getPlaybackSpeed();
                        if (upd > 0) pos += (long) ((SystemClock.elapsedRealtime() - upd) * sp);
                    }
                    n.posMs = pos;
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "now playback failed: " + e);
        }
        return n;
    }

    // ───────── ⏩ سرعتِ پخشِ هر برنامه (یوتیوب، اینستاگرام، پلیر گوشی، …) ─────────

    /** پلیرِ در حالِ پخش (یا اولین sessionِ دارایِ عنوان). null = دسترسی نیست یا sessionی نیست. */
    private static MediaController pickPlayer(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            MediaSessionManager msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(new ComponentName(app, YtMedia.class));
            if (list == null || list.isEmpty()) return null;
            for (MediaController c : list) {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return c;
            }
            for (MediaController c : list) {
                MediaMetadata md = c.getMetadata();
                if (md != null && md.getString(MediaMetadata.METADATA_KEY_TITLE) != null) return c;
            }
            return list.get(0);
        } catch (Throwable e) {
            return null;
        }
    }

    /** نامِ پکیجِ پلیرِ فعلی (مثلاً com.google.android.youtube)؛ "" = نامشخص. */
    public static String playerPackage(Context ctx) {
        MediaController c = pickPlayer(ctx);
        return c == null || c.getPackageName() == null ? "" : c.getPackageName();
    }

    /** سرعتِ فعلیِ پلیرِ بیرونی؛ -1 = نامشخص (sessionی نیست / دسترسیِ اعلان‌ها نیست). */
    public static float currentSpeed(Context ctx) {
        MediaController c = pickPlayer(ctx);
        if (c == null) return -1f;
        try {
            PlaybackState ps = c.getPlaybackState();
            if (ps == null) return -1f;
            float sp = ps.getPlaybackSpeed();
            return sp > 0f ? sp : 1f;
        } catch (Throwable e) {
            return -1f;
        }
    }

    /**
     * سرعتِ پخشِ برنامه‌ی در حالِ پخش رو عوض می‌کنه (از طریقِ MediaSession).
     * 1 = درخواست فرستاده شد، 0 = هیچ پلیری پیدا نشد، -1 = «دسترسی به اعلان‌ها» داده نشده.
     * نتیجه‌ی واقعی رو با [currentSpeed] چند لحظه بعد چک کن (بعضی برنامه‌ها درخواست رو نادیده می‌گیرن).
     */
    public static int setSpeed(Context ctx, float speed) {
        if (!hasAccess(ctx)) return -1;
        MediaController c = pickPlayer(ctx);
        if (c == null) return 0;
        try {
            float sp = Math.max(0.25f, Math.min(4f, speed));
            c.getTransportControls().setPlaybackSpeed(sp);
            return 1;
        } catch (Throwable e) {
            Log.d(TAG, "setSpeed failed: " + e);
            return 0;
        }
    }

    // seek از بیرون (متدِ ytSeek در BubblePlugin) — روی tracker فعالِ BubbleService
    private static volatile Tracker active;
    static void setActive(Tracker t) { active = t; }
    public static boolean seek(long ms) {
        Tracker t = active;
        return t != null && t.seekTo(ms);
    }
}
