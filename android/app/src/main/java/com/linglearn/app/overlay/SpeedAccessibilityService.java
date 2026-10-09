package com.linglearn.app.overlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⏩ تنظیمِ سرعتِ پخشِ یوتیوب از طریقِ منوی خودِ یوتیوب (وقتی MediaSession سرعت رو قبول نکرد).
 *
 * فقط برای com.google.android.youtube فعاله (accessibility_service_config.xml) و فقط وقتی bubble
 * درخواست بده کار می‌کنه: کنترل‌ها رو نشون می‌ده → ⚙ → «سرعت پخش» → نزدیک‌ترین گزینه رو می‌زنه.
 * هیچ متنی ذخیره یا ارسال نمی‌شه.
 */
public class SpeedAccessibilityService extends AccessibilityService {

    static final String YT = "com.google.android.youtube";

    public interface Done { void onDone(boolean ok, float applied); }

    private static volatile SpeedAccessibilityService inst;

    private static final String[] SPEED_WORDS = {
            "playback speed", "سرعت پخش", "سرعت پخش", "سرعة التشغيل", "velocidad de reproducción",
            "vitesse de lecture", "wiedergabegeschwindigkeit", "скорость воспроизведения",
            "oynatma hızı", "velocidade de reprodução", "velocità di riproduzione", "재생 속도", "再生速度", "播放速度"};
    private static final String[] GEAR_WORDS = {
            "settings", "more options", "more", "تنظیمات", "گزینه‌های بیشتر", "گزینه های بیشتر", "الإعدادات",
            "ajustes", "paramètres", "einstellungen", "настройки", "ayarlar", "configurações", "impostazioni", "설정", "設定", "设置"};
    private static final String[] NORMAL_WORDS = {
            "normal", "normale", "عادی", "عادي", "обычная", "normală", "일반", "標準", "正常", "standard"};
    private static final Pattern NUM = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*[x×✕]?$");

    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private float target = 1f;
    private Done done;
    private long startAt, lastRowClick, lastGearClick, lastReveal;
    private int gen;

    // ───────── API ─────────

    public static boolean isEnabled(Context ctx) {
        if (inst != null) return true;
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String me = new ComponentName(ctx, SpeedAccessibilityService.class).flattenToString();
            String meShort = new ComponentName(ctx, SpeedAccessibilityService.class).flattenToShortString();
            return s.contains(me) || s.contains(meShort);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void openSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
    }

    /** false = سرویس هنوز به سیستم وصل نیست. نتیجه با [Done] روی main thread می‌آد. */
    public static boolean request(float speed, Done cb) {
        SpeedAccessibilityService s = inst;
        if (s == null) return false;
        s.begin(speed, cb);
        return true;
    }

    // ───────── lifecycle ─────────

    @Override protected void onServiceConnected() { inst = this; }
    @Override public boolean onUnbind(Intent i) { inst = null; busy = false; return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; busy = false; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    // ───────── کار ─────────

    private void begin(float speed, Done cb) {
        target = speed;
        done = cb;
        busy = true;
        startAt = SystemClock.elapsedRealtime();
        lastRowClick = lastGearClick = lastReveal = 0;
        final int g = ++gen;
        h.post(() -> tick(g));
    }

    private void finish(boolean ok, float applied) {
        busy = false;
        gen++;
        Done d = done; done = null;
        if (d != null) d.onDone(ok, applied);
    }

    private void tick(final int g) {
        if (g != gen || !busy) return;
        if (SystemClock.elapsedRealtime() - startAt > 9000) { finish(false, -1f); return; }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && YT.contentEquals(root.getPackageName())) {
                if (step(root)) return;      // finish() صدا زده شد
            }
        } catch (Throwable ignored) {}
        h.postDelayed(() -> tick(g), 350);
    }

    private static final class Item {
        final AccessibilityNodeInfo node; final String label;
        Item(AccessibilityNodeInfo n, String l) { node = n; label = l; }
    }

    private void collect(AccessibilityNodeInfo n, List<Item> out, int[] budget) {
        if (n == null || budget[0]-- <= 0) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        if (t != null && t.length() > 0) out.add(new Item(n, t.toString().trim()));
        if (d != null && d.length() > 0 && (t == null || !d.toString().contentEquals(t))) out.add(new Item(n, d.toString().trim()));
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, budget);
    }

    /** true = کار تمام شد. */
    private boolean step(AccessibilityNodeInfo root) {
        List<Item> all = new ArrayList<>();
        collect(root, all, new int[]{900});
        long now = SystemClock.elapsedRealtime();

        // ۱) لیستِ سرعت‌ها بازه؟ → نزدیک‌ترین گزینه
        List<Item> opts = new ArrayList<>();
        List<Float> vals = new ArrayList<>();
        for (Item it : all) {
            float v = parseSpeed(it.label);
            if (Float.isNaN(v)) continue;
            boolean dup = false;
            for (Float f : vals) if (Math.abs(f - v) < 0.001f) { dup = true; break; }
            if (!dup) { opts.add(it); vals.add(v); }
        }
        if (opts.size() >= 3) {
            int best = 0;
            for (int i = 1; i < vals.size(); i++)
                if (Math.abs(vals.get(i) - target) < Math.abs(vals.get(best) - target)) best = i;
            clickNode(opts.get(best).node);
            finish(true, vals.get(best));
            return true;
        }

        // ۲) ردیفِ «سرعت پخش» در منوی تنظیمات
        if (now - lastRowClick > 1000) {
            for (Item it : all) {
                if (containsAny(it.label, SPEED_WORDS)) {
                    lastRowClick = now;
                    clickNode(it.node);
                    return false;
                }
            }
        }

        // ۳) دکمه‌ی ⚙ / «گزینه‌های بیشتر» روی پلیر
        if (now - lastGearClick > 1200) {
            for (Item it : all) {
                if (it.label.length() <= 24 && containsAny(it.label, GEAR_WORDS)) {
                    lastGearClick = now;
                    clickNode(it.node);
                    return false;
                }
            }
        }

        // ۴) کنترل‌ها پنهانن → یک لمس وسطِ ویدیو
        if (now - lastReveal > 1600) {
            lastReveal = now;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            boolean land = dm.widthPixels > dm.heightPixels;
            tap(dm.widthPixels / 2f, land ? dm.heightPixels * 0.5f : dm.heightPixels * 0.22f);
        }
        return false;
    }

    // ───────── کمکی‌ها ─────────

    private static boolean containsAny(String label, String[] words) {
        String l = label.toLowerCase(Locale.ROOT);
        for (String w : words) if (l.contains(w)) return true;
        return false;
    }

    private static String normDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '\u06F0' && c <= '\u06F9') b.append((char) ('0' + (c - '\u06F0')));
            else if (c >= '\u0660' && c <= '\u0669') b.append((char) ('0' + (c - '\u0660')));
            else if (c == '\u066B' || c == '\u060C' || c == ',') b.append('.');
            else b.append(c);
        }
        return b.toString();
    }

    /** «1.25x»، «۱٫۵»، «Normal»/«عادی» → عدد؛ در غیرِ این‌صورت NaN. */
    private static float parseSpeed(String label) {
        if (label == null || label.length() > 12) return Float.NaN;
        String l = normDigits(label.trim()).toLowerCase(Locale.ROOT);
        for (String w : NORMAL_WORDS) if (l.equals(w)) return 1f;
        Matcher m = NUM.matcher(l);
        if (!m.matches()) return Float.NaN;
        try {
            float v = Float.parseFloat(m.group(1));
            return (v >= 0.25f && v <= 4f) ? v : Float.NaN;
        } catch (Throwable e) {
            return Float.NaN;
        }
    }

    private void clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; i < 6 && p != null; i++) {
            if (p.isClickable() && p.isEnabled() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            p = p.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.isEmpty()) tap(r.exactCenterX(), r.exactCenterY());
    }

    private void tap(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null);
        } catch (Throwable ignored) {}
    }
}
