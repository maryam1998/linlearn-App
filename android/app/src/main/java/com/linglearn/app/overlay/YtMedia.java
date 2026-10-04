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

    // seek از بیرون (متدِ ytSeek در BubblePlugin) — روی tracker فعالِ BubbleService
    private static volatile Tracker active;
    static void setActive(Tracker t) { active = t; }
    public static boolean seek(long ms) {
        Tracker t = active;
        return t != null && t.seekTo(ms);
    }
}
