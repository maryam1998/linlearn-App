package com.linglearn.app.overlay;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 🔍 حبابِ ذره‌بین: یک ذره‌بینِ حلقه‌ای که کاربر روی متنِ هر اپی می‌کشد و رها می‌کند.
 * رها که شد: از صفحه عکس گرفته می‌شود (MediaProjection)، متنِ زیرِ ذره‌بین با ML Kit خوانده (OCR)،
 * هایلایت می‌شود و ترجمه‌اش به همه‌ی زبان‌های انتخابی در یک کادر می‌آید.
 * ضربه روی یک لغت (یا کشیدنِ انگشت روی چند لغت) کادرِ «ذخیره برای داستان بعدی / گرامر / لایتنر» را باز می‌کند.
 * خروج: ذره‌بین را بکش روی ✕ی پایینِ صفحه (مثلِ حبابِ ترجمه‌ی زنده) یا «بستن» در اعلان.
 *
 * زبان‌ها از همان SharedPreferencesِ حبابِ ترجمه‌ی زنده خوانده می‌شود
 * (source = «زبان صدا»، targets = زبان‌های مقصد).
 */
public class MagnifierService extends Service {

    private static final String TAG = "MagnifierService";
    public static final String ACTION_START = "com.linglearn.app.overlay.MAG_START";
    public static final String ACTION_STOP = "com.linglearn.app.overlay.MAG_STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    static volatile boolean running = false;

    private static final String PREFS = "bubble_prefs";   // همان فایلِ تنظیماتِ BubbleService
    private static final String CHANNEL_ID = "magnifier_channel";
    private static final int NOTIF_ID = 4720;
    private static final int COLOR_INK = Color.parseColor("#1C2541");
    private static final int COLOR_GOLD = Color.parseColor("#C9A227");
    private static final int COLOR_REC = Color.parseColor("#E53935");
    private static final Set<String> LATIN = new HashSet<>(Arrays.asList(
            "en", "de", "fr", "es", "it", "pt", "nl", "tr"));

    // اندازه‌ی ذره‌بین (dp): هم‌اندازه‌ی حبابِ ترجمه‌ی زنده (60)؛ مرکزِ شیشه در (26,26) با شعاعِ 20
    private static final float LENS_SIZE = 60f, LENS_C = 26f, LENS_R = 20f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

    private WindowManager wm;
    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private int capW, capH, dpiVal;
    private final Object frameLock = new Object();
    private Image lastImage;

    private LensView lens;
    private WindowManager.LayoutParams lensLp;
    private CloseTargetView closeView;
    private boolean overTarget = false;
    private boolean lensMode = false;      // true = حلقه‌ی شفاف (موقعِ کشیدن/رها)؛ false = حبابِ کنارِ صفحه
    private boolean docked = true;
    private ValueAnimator dockAnim;
    private final Runnable dockRun = () -> dockLens();
    private boolean busy = false;

    private TextRecognizer latinRec, devaRec;

    // انتخابِ فعلی
    private static final class ElemInfo {
        final Rect r; final String t;
        ElemInfo(Rect r, String t) { this.r = r; this.t = t; }
    }
    private SelView selView;
    private WindowManager.LayoutParams selLp;
    private LinearLayout popup;
    private WindowManager.LayoutParams popupLp;
    private RectF selBox;
    private String selSrc = "en", selOrig = "";

    // کادرِ سورمه‌ای (هم‌سبکِ پنلِ حبابِ ترجمه‌ی زنده)
    private static final int REPEAT_INF = -1;                 // همان مقدارِ حبابِ ترجمه‌ی زنده
    private LinearLayout rowsBox, wordCard, wordRows, opacityRow;
    private TextView cardTerm, termSpeak, wordTerm, wordSpeak, tvRepeat;
    private TextView cardBtnStory, cardBtnGrammar, cardBtnLeitner;
    private GradientDrawable popupBg;
    private float panelAlpha = 1f;
    private String cardWord = "", cardMeaning = "", cardMeaningLang = "fa";
    private boolean cardStoryDone, cardGrammarDone, cardLeitnerDone;
    private int cardSeq = 0;
    private final ArrayList<String[]> wholeResults = new ArrayList<>();   // ترجمه‌ی کلِ متن: {کدِ زبان، متن}
    private TextView speakingBtn;
    private int speakTok = 0;

    // ───────────────────────── چرخه‌ی عمر ─────────────────────────

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action) || !ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (!Settings.canDrawOverlays(this)) {
            toast(msg("مجوز نمایش روی برنامه‌های دیگر داده نشده", "Overlay permission is not granted"));
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            setupProjection(intent);
            addLens();
            running = true;
            toast(msg("ذره‌بین را روی متن بکشید و رها کنید",
                    "Drag the magnifier over a text and release"));
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            toast("⚠ " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (dockAnim != null) dockAnim.cancel();
        clearSelection();
        try { if (closeView != null) wm.removeViewImmediate(closeView); } catch (Exception ignored) {}
        closeView = null;
        try { if (lens != null) wm.removeViewImmediate(lens); } catch (Exception ignored) {}
        lens = null;
        synchronized (frameLock) {
            if (lastImage != null) { try { lastImage.close(); } catch (Exception ignored) {} lastImage = null; }
        }
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (latinRec != null) latinRec.close(); } catch (Exception ignored) {}
        try { if (devaRec != null) devaRec.close(); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (vd == null) return;
        try {
            DisplayMetrics dm = realMetrics();
            if (dm.widthPixels == capW && dm.heightPixels == capH) return;
            clearSelection();
            synchronized (frameLock) {
                if (lastImage != null) { try { lastImage.close(); } catch (Exception ignored) {} lastImage = null; }
            }
            ImageReader old = reader;
            capW = dm.widthPixels; capH = dm.heightPixels; dpiVal = dm.densityDpi;
            reader = newReader(capW, capH);
            vd.resize(capW, capH, dpiVal);
            vd.setSurface(reader.getSurface());
            if (old != null) old.close();
            clampLens();
        } catch (Exception e) {
            Log.e(TAG, "rotate failed", e);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Magnifier bubble", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stop = new Intent(this, MagnifierService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action close = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                msg("بستن", "Close"), pi).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("LingoLearn")
                .setContentText(msg("ذره‌بین فعال است", "Magnifier is active"))
                .setOngoing(true)
                .addAction(close)
                .build();
    }

    // ───────────────────────── عکس‌گرفتن از صفحه ─────────────────────────

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        DisplayManager dmg = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display d = dmg.getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(dm);
        return dm;
    }

    @SuppressWarnings("deprecation")
    private void setupProjection(Intent intent) {
        int rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : (Intent) intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(rc, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(() -> stopSelf()); }
        }, main);
        DisplayMetrics dm = realMetrics();
        capW = dm.widthPixels; capH = dm.heightPixels; dpiVal = dm.densityDpi;
        reader = newReader(capW, capH);
        vd = projection.createVirtualDisplay("lingo-magnifier", capW, capH, dpiVal,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(rd -> {
            Image im = null;
            try { im = rd.acquireLatestImage(); } catch (Exception ignored) {}
            if (im == null) return;
            synchronized (frameLock) {
                if (lastImage != null) { try { lastImage.close(); } catch (Exception ignored) {} }
                lastImage = im;
            }
        }, main);
        return r;
    }

    /** آخرین فریمِ صفحه به‌شکل Bitmap (null اگه هنوز فریمی نیومده). */
    private Bitmap frameBitmap() {
        synchronized (frameLock) {
            if (lastImage == null) return null;
            try {
                Image.Plane p = lastImage.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                int ps = p.getPixelStride(), rs = p.getRowStride();
                int w = lastImage.getWidth(), h = lastImage.getHeight();
                Bitmap full = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888);
                buf.rewind();
                full.copyPixelsFromBuffer(buf);
                if (full.getWidth() == w) return full;
                Bitmap crop = Bitmap.createBitmap(full, 0, 0, w, h);
                full.recycle();
                return crop;
            } catch (Exception e) {
                Log.e(TAG, "frame failed", e);
                return null;
            }
        }
    }

    // ───────────────────────── ذره‌بین (کشیدن / رها کردن) ─────────────────────────

    private void addLens() {
        createCloseTarget();
        lens = new LensView(this);
        int size = dp(LENS_SIZE);
        lensLp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lensLp.gravity = Gravity.TOP | Gravity.START;
        // شروع: کنارِ راستِ صفحه، نیمه‌پنهان (مثلِ حبابِ ترجمه‌ی زنده)
        lensLp.x = capW - (int) (size * 0.58f);
        lensLp.y = (int) (capH * 0.42f);
        lens.setAlpha(0.62f);
        docked = true;
        lens.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy, startX, startY; boolean moved; boolean wasDocked;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        main.removeCallbacks(dockRun);
                        if (dockAnim != null) dockAnim.cancel();
                        startX = e.getRawX(); startY = e.getRawY();
                        dx = lensLp.x - startX; dy = lensLp.y - startY;
                        moved = false; wasDocked = docked;
                        lens.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.hypot(e.getRawX() - startX, e.getRawY() - startY) > dp(8)) {
                            moved = true;
                            docked = false;
                            setLensMode(true);
                            showCloseTarget();
                        }
                        if (moved) {
                            lensLp.x = (int) (e.getRawX() + dx);
                            lensLp.y = (int) (e.getRawY() + dy);
                            clampLens();
                            updateOverTarget();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                        boolean onX = overTarget;
                        hideCloseTarget();
                        if (!up) { scheduleDock(600); return true; }
                        if (moved && onX) {
                            toast(msg("ذره‌بین بسته شد", "Magnifier closed"));
                            stopSelf();
                        } else if (moved) {
                            onLensDropped();
                        } else if (wasDocked) {
                            // ضربه روی حبابِ کنارِ صفحه: بیاید داخلِ صفحه و به ذره‌بین تبدیل شود
                            docked = false;
                            setLensMode(true);
                            lensLp.x = capW - lensLp.width - dp(24);
                            clampLens();
                            toast(msg("ذره‌بین را روی متن بکشید و رها کنید", "Drag the magnifier over a text and release"));
                            scheduleDock(6000);
                        } else {
                            onLensDropped();          // ضربه روی ذره‌بین: دوباره بخوان
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(lens, lensLp);
    }

    private void setLensMode(boolean on) {
        lensMode = on;
        if (lens != null) lens.invalidate();
    }

    /** چند لحظه بعد، ذره‌بین به کنارِ نزدیک‌ترین لبه برمی‌گردد (مثلِ حبابِ ترجمه‌ی زنده). */
    private void scheduleDock(long ms) {
        main.removeCallbacks(dockRun);
        main.postDelayed(dockRun, ms);
    }

    private void dockLens() {
        if (lens == null || lensLp == null || busy) return;
        if (dockAnim != null) dockAnim.cancel();
        final boolean left = lensLp.x + lensLp.width / 2 < capW / 2;
        final int target = left ? -(int) (lensLp.width * 0.42f) : capW - (int) (lensLp.width * 0.58f);
        setLensMode(false);
        dockAnim = ValueAnimator.ofInt(lensLp.x, target);
        dockAnim.setDuration(220);
        dockAnim.addUpdateListener(a -> {
            if (lens == null) return;
            lensLp.x = (Integer) a.getAnimatedValue();
            try { wm.updateViewLayout(lens, lensLp); } catch (Exception ignored) {}
        });
        dockAnim.start();
        docked = true;
        lens.setAlpha(0.62f);
    }

    private void clampLens() {
        if (lens == null || lensLp == null) return;
        lensLp.x = Math.max(0, Math.min(capW - lensLp.width, lensLp.x));
        lensLp.y = Math.max(0, Math.min(capH - lensLp.height, lensLp.y));
        try { wm.updateViewLayout(lens, lensLp); } catch (Exception ignored) {}
    }

    private void bringLensToFront() {
        if (lens == null) return;
        try {
            wm.removeViewImmediate(lens);
            wm.addView(lens, lensLp);
        } catch (Exception ignored) {}
    }

    // ✕ی خروج: یک بار (نامرئی) قبل از ذره‌بین ساخته می‌شود تا زیرِ آن بماند؛ هنگامِ کشیدن ظاهر می‌شود.
    private void createCloseTarget() {
        int sz = dp(84);
        closeView = new CloseTargetView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(sz, sz,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = capW / 2 - sz / 2;
        lp.y = capH - dp(120) - sz / 2;
        closeView.setVisibility(View.INVISIBLE);
        try { wm.addView(closeView, lp); } catch (Exception ex) { closeView = null; }
    }

    private void showCloseTarget() {
        if (closeView != null) closeView.setVisibility(View.VISIBLE);
    }

    private void hideCloseTarget() {
        overTarget = false;
        if (closeView == null) return;
        closeView.setHot(false);
        closeView.setVisibility(View.INVISIBLE);
    }

    private void updateOverTarget() {
        if (closeView == null) return;
        double cx = lensLp.x + dp(LENS_C), cy = lensLp.y + dp(LENS_C);
        double tx = capW / 2.0, ty = capH - dp(120);
        boolean hot = Math.hypot(cx - tx, cy - ty) < dp(60);
        if (hot != overTarget) { overTarget = hot; closeView.setHot(hot); }
    }

    // ───────────────────────── رها شدنِ ذره‌بین روی متن ─────────────────────────

    private void onLensDropped() {
        if (busy) return;
        busy = true;
        main.removeCallbacks(dockRun);
        setLensMode(true);
        clearSelection();                         // هایلایت و کادرِ قبلی از فریم محو شود
        if (lens != null) lens.setBusy(true);
        main.postDelayed(() -> {
            Bitmap bmp = frameBitmap();
            if (bmp == null) {
                finish(msg("هنوز تصویری از صفحه نیست؛ دوباره رها کنید", "No screen image yet, try again"));
                return;
            }
            final float cx = lensLp.x + dp(LENS_C), cy = lensLp.y + dp(LENS_C);
            final Bitmap b = bmp;
            worker.execute(() -> process(b, cx, cy));
        }, 260);
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    // زبانِ متن‌های ذره‌بین = «زبان نرم‌افزار» در تنظیماتِ اپ (نه زبانِ مقصدِ ترجمه)
    private boolean uiFa() { return BubbleService.uiLangIsFa(this); }

    private String msg(String fa, String en) { return NativeI18n.tr(NativeI18n.lang(this), fa, en); }

    private String sourceLang() {
        String s = prefs().getString("source", "en");
        return (s == null || s.isEmpty() || "auto".equals(s)) ? "en" : s;
    }

    private List<String> targets(String src) {
        ArrayList<String> out = new ArrayList<>();
        String csv = prefs().getString("targets", "");
        if (csv != null) {
            for (String t : csv.split(",")) {
                t = t.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty() && !out.contains(t) && !t.equals(src)) out.add(t);
            }
        }
        if (out.isEmpty()) {
            String t = prefs().getString("target", "fa");
            if (!t.equals(src)) out.add(t);
        }
        if (out.isEmpty()) out.add("en".equals(src) ? "fa" : "en");
        return out;
    }

    private TextRecognizer recognizerFor(String src) {
        if ("hi".equals(src)) {
            if (devaRec == null) devaRec = TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
            return devaRec;
        }
        if (LATIN.contains(src)) {
            if (latinRec == null) latinRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            return latinRec;
        }
        return null;
    }

    private void process(final Bitmap bmp, final float x, final float y) {
        final String src = sourceLang();
        final TextRecognizer rec = recognizerFor(src);
        if (rec == null) {
            finish(msg("خواندنِ متن از تصویر برای این زبان هنوز پشتیبانی نمی‌شود", "Reading text from the screen is not supported for this language yet"));
            return;
        }
        rec.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> worker.execute(() -> handleText(text, src, x, y)))
                .addOnFailureListener(e -> finish(msg("خواندنِ متن ناموفق بود", "Could not read the text")));
    }

    private void handleText(Text text, String src, float x, float y) {
        Text.TextBlock best = null;
        double bestD = Double.MAX_VALUE;
        for (Text.TextBlock b : text.getTextBlocks()) {
            Rect r = b.getBoundingBox();
            if (r == null) continue;
            double d = dist(r, x, y);
            if (d < bestD) { bestD = d; best = b; }
        }
        if (best == null || bestD > dp(40)) {
            finish(msg("زیرِ ذره‌بین متنی پیدا نشد", "No text found under the magnifier"));
            return;
        }
        Rect box = best.getBoundingBox();
        List<Text.Line> lines = best.getLines();
        String orig = best.getText().replace('\n', ' ').trim();
        if (orig.length() > 500) {                    // بلوکِ خیلی بلند: فقط خطِ نزدیک
            Text.Line bl = null; double bd = Double.MAX_VALUE;
            for (Text.Line l : best.getLines()) {
                Rect lr = l.getBoundingBox();
                if (lr == null) continue;
                double d = dist(lr, x, y);
                if (d < bd) { bd = d; bl = l; }
            }
            if (bl != null) {
                orig = bl.getText().trim();
                box = bl.getBoundingBox();
                lines = new ArrayList<>();
                lines.add(bl);
            }
        }
        if (orig.isEmpty() || box == null) { finish(msg("متنی پیدا نشد", "No text found")); return; }

        final ArrayList<ElemInfo> elems = new ArrayList<>();
        for (Text.Line l : lines) {
            for (Text.Element e : l.getElements()) {
                Rect er = e.getBoundingBox();
                if (er != null && !e.getText().trim().isEmpty()) elems.add(new ElemInfo(new Rect(er), e.getText()));
            }
        }

        final ArrayList<String[]> results = new ArrayList<>();
        for (String tgt : targets(src)) {
            String tr;
            try { tr = FreeTranslator.translate(http, orig, src, tgt); }
            catch (Exception ex) { tr = "—"; }
            results.add(new String[]{tgt, tr});
        }
        final String fo = orig, fsrc = src;
        final RectF rf = new RectF(box);
        main.post(() -> {
            busy = false;
            if (lens != null) lens.setBusy(false);
            showSelection(rf, elems, fo, fsrc, results);
            scheduleDock(2500);
        });
    }

    private void finish(final String message) {
        main.post(() -> {
            busy = false;
            if (lens != null) lens.setBusy(false);
            toast(message);
            scheduleDock(2000);
        });
    }

    private static double dist(Rect r, float x, float y) {
        double dx = Math.max(Math.max(r.left - x, 0), x - r.right);
        double dy = Math.max(Math.max(r.top - y, 0), y - r.bottom);
        return Math.hypot(dx, dy);
    }

    // ───────────────────────── هایلایت + کادرِ ترجمه ─────────────────────────

    private void clearSelection() {
        cardSeq++;
        stopSpeech();
        if (selView != null) { try { wm.removeViewImmediate(selView); } catch (Exception ignored) {} selView = null; }
        if (popup != null) { try { wm.removeViewImmediate(popup); } catch (Exception ignored) {} popup = null; }
        rowsBox = null; wordCard = null; wordRows = null; opacityRow = null;
        cardTerm = null; termSpeak = null; wordTerm = null; wordSpeak = null; tvRepeat = null; popupBg = null;
    }

    private void showSelection(RectF box, ArrayList<ElemInfo> elems, String orig, String src, List<String[]> results) {
        clearSelection();
        selBox = box;
        selSrc = src;
        selOrig = orig;
        wholeResults.clear();
        wholeResults.addAll(results);

        // پنجره‌ی هایلایت: فقط به‌اندازه‌ی متن، تا بقیه‌ی صفحه لمس‌پذیر بماند
        int pad = dp(6);
        selView = new SelView(this, elems, (int) box.left - pad, (int) box.top - pad);
        selLp = new WindowManager.LayoutParams(
                (int) box.width() + 2 * pad, (int) box.height() + 2 * pad,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        selLp.gravity = Gravity.TOP | Gravity.START;
        selLp.x = (int) box.left - pad;
        selLp.y = (int) box.top - pad;
        try { wm.addView(selView, selLp); } catch (Exception ex) { selView = null; }

        buildPopup();
        int w = Math.min(capW - dp(24), dp(360));
        popupLp = new WindowManager.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        popupLp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(popup, popupLp);
            placePopup();
        } catch (Exception ex) { popup = null; }
        bringLensToFront();
        // کادرِ «ذخیره برای داستان بعدی / گرامر / لایتنر» فقط وقتی باز می‌شود که کاربر روی لغت یا محدوده بزند (یا 📄)
    }

    private void placePopup() {
        if (popup == null || popupLp == null || selBox == null) return;
        int w = popupLp.width;
        popup.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = popup.getMeasuredHeight();
        int pad = dp(6), gap = dp(8);
        int left = Math.max(dp(12), Math.min(capW - w - dp(12), (int) (selBox.centerX() - w / 2f)));
        int top;
        if (selBox.bottom + pad + gap + h <= capH - dp(24)) top = (int) selBox.bottom + pad + gap;
        else top = Math.max(dp(36), (int) selBox.top - pad - gap - h);
        popupLp.x = left;
        popupLp.y = top;
        try { wm.updateViewLayout(popup, popupLp); } catch (Exception ignored) {}
    }

    /** رنگِ کادر (قابلِ تنظیم در اپ، مشترک با حبابِ ترجمه‌ی زنده)؛ پیش‌فرض سورمه‌ای. */
    private int panelRgb() { return prefs().getInt("panelColor", 0x1C2541) & 0xFFFFFF; }

    /** رنگِ کادرِ لغت: همان منطقِ حبابِ ترجمه‌ی زنده. */
    private int wordCardBgColor() {
        int c = panelRgb();
        if (c == 0x1C2541) return Color.parseColor("#26335F");
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r += Math.round((255 - r) * 0.10f); g += Math.round((255 - g) * 0.10f); b += Math.round((255 - b) * 0.10f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private TextView txt(String s, int color, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return t;
    }

    private TextView headBtn(String s, View.OnClickListener l) {
        TextView t = txt(s, Color.WHITE, 17);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(40));
        t.setMinHeight(dp(36));
        t.setOnClickListener(l);
        return t;
    }

    private TextView speakBtn() {
        TextView t = headBtn("\uD83D\uDD0A", null);
        t.setTextSize(18);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void applyPopupAlpha() {
        if (popupBg == null) return;
        int a = Math.round(Math.max(0f, Math.min(1f, panelAlpha)) * 255f);
        popupBg.setColor((a << 24) | panelRgb());
    }

    private void buildPopup() {
        panelAlpha = Math.max(0f, Math.min(1f, prefs().getFloat("panelAlphaV2", 1f)));
        popup = new LinearLayout(this);
        popup.setOrientation(LinearLayout.VERTICAL);
        popupBg = new GradientDrawable();
        popupBg.setCornerRadius(dp(16));
        popupBg.setStroke(dp(1), COLOR_GOLD);
        popup.setBackground(popupBg);
        applyPopupAlpha();
        popup.setPadding(dp(12), dp(4), dp(10), dp(12));
        popup.setElevation(dp(10));

        // نوارِ ابزار: 🔁 تکرار (۲× / ۳× / ∞) · 🌓 شفافیت · 📄 کلِ متن · ✕ بستن
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        tvRepeat = headBtn("\uD83D\uDD01", v -> cycleRepeat());
        bar.addView(tvRepeat);
        bar.addView(headBtn("\uD83C\uDF13", v -> toggleOpacityRow()));
        bar.addView(headBtn("\uD83D\uDCC4", v -> { if (selView != null) selView.selectAll(); }));
        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(headBtn("\u2715", v -> clearSelection()));
        popup.addView(bar);
        updateRepeatButton();

        // نوارِ شفافیت (مثلِ حبابِ ترجمه‌ی زنده)
        opacityRow = new LinearLayout(this);
        opacityRow.setOrientation(LinearLayout.HORIZONTAL);
        opacityRow.setGravity(Gravity.CENTER_VERTICAL);
        opacityRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        opacityRow.setVisibility(View.GONE);
        SeekBar ob = new SeekBar(this);
        ob.setMax(100);
        ob.setProgress(Math.round(panelAlpha * 100f));
        ob.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_GOLD));
        ob.setThumbTintList(android.content.res.ColorStateList.valueOf(COLOR_GOLD));
        final TextView pct = txt(Math.round(panelAlpha * 100f) + "%", Color.WHITE, 12);
        pct.setMinWidth(dp(44));
        pct.setGravity(Gravity.CENTER);
        ob.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                panelAlpha = progress / 100f;
                pct.setText(progress + "%");
                applyPopupAlpha();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                prefs().edit().putFloat("panelAlphaV2", panelAlpha).apply();
            }
        });
        opacityRow.addView(ob, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        opacityRow.addView(pct);
        popup.addView(opacityRow, matchWrap(0));

        // متنِ اصلی + 🔊
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        cardTerm = txt(selOrig, Color.WHITE, 16);
        cardTerm.setTypeface(cardTerm.getTypeface(), android.graphics.Typeface.BOLD);
        cardTerm.setMaxLines(6);
        cardTerm.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(cardTerm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        termSpeak = speakBtn();
        termSpeak.setOnClickListener(v -> toggleSpeak(termSpeak, selOrig, selSrc));
        head.addView(termSpeak);
        popup.addView(head, matchWrap(2));

        // ترجمه به همه‌ی زبان‌های انتخابی (هر ردیف 🔊 دارد)
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        popup.addView(rowsBox, matchWrap(0));
        fillRows(rowsBox, wholeResults);

        // کادرِ لغت (اول بسته): لغت/محدوده + ترجمه‌ها + «ذخیره برای داستان بعدی / گرامر / لایتنر»
        buildWordCard();
        popup.addView(wordCard);

        TextView hint = txt(msg("روی یک لغت یا محدوده‌ی هایلایت‌شده بزنید (انگشت را روی چند لغت بکشید)",
                "Tap a word, or drag across several words in the highlighted text"),
                Color.parseColor("#8F98B8"), 11);
        popup.addView(hint, matchWrap(8));
    }

    private void toggleOpacityRow() {
        if (opacityRow == null) return;
        opacityRow.setVisibility(opacityRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        placePopup();
    }

    // 🔁 تکرارِ خوانش: خاموش ← ۲ ← ۳ ← ∞ (همان تنظیمِ حبابِ ترجمه‌ی زنده)
    private int repeatSetting() {
        int v = prefs().getInt("repeatSetting", 0);
        return (v == 2 || v == 3 || v == REPEAT_INF) ? v : 0;
    }

    private void cycleRepeat() {
        int r = repeatSetting();
        r = r == 0 ? 2 : r == 2 ? 3 : r == 3 ? REPEAT_INF : 0;
        prefs().edit().putInt("repeatSetting", r).apply();
        updateRepeatButton();
        toast(r == 0 ? msg("تکرار خاموش", "Repeat off")
                : r == REPEAT_INF ? msg("تکرار ∞ — تا وقتی ⏹ نزنید", "Repeat ∞ — until you press ⏹")
                : msg("هر خوانش " + r + " بار", "Each reading " + r + "×"));
    }

    private void updateRepeatButton() {
        if (tvRepeat == null) return;
        int r = repeatSetting();
        tvRepeat.setText(r == 0 ? "\uD83D\uDD01" : r == REPEAT_INF ? "\uD83D\uDD01\u221E" : "\uD83D\uDD01" + r);
        tvRepeat.setTextColor(r == 0 ? Color.WHITE : COLOR_GOLD);
        tvRepeat.setAlpha(r == 0 ? 0.6f : 1f);
    }

    private TextView cardAction(View.OnClickListener l) {
        TextView b = txt("", Color.WHITE, 12);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setOnClickListener(l);
        styleCardAction(b, false);
        return b;
    }

    private void styleCardAction(TextView b, boolean done) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#14FFFFFF"));
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), done ? COLOR_GOLD : Color.parseColor("#40FFFFFF"));
        b.setBackground(g);
        b.setTextColor(done ? COLOR_GOLD : Color.WHITE);
    }

    private void buildWordCard() {
        wordCard = new LinearLayout(this);
        wordCard.setOrientation(LinearLayout.VERTICAL);
        wordCard.setPadding(dp(10), dp(6), dp(8), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(wordCardBgColor());
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_GOLD);
        wordCard.setBackground(bg);
        wordCard.setVisibility(View.GONE);
        wordCard.setLayoutParams(matchWrap(10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        wordTerm = txt("", Color.WHITE, 17);
        wordTerm.setTypeface(wordTerm.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(wordTerm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wordSpeak = speakBtn();
        wordSpeak.setOnClickListener(v -> toggleSpeak(wordSpeak, cardWord, selSrc));
        top.addView(wordSpeak);
        wordCard.addView(top);

        wordRows = new LinearLayout(this);
        wordRows.setOrientation(LinearLayout.VERTICAL);
        wordCard.addView(wordRows, matchWrap(0));

        cardBtnStory = cardAction(v -> onCardStory());
        cardBtnGrammar = cardAction(v -> onCardGrammar());
        cardBtnLeitner = cardAction(v -> onCardLeitner());
        for (TextView b : new TextView[]{cardBtnStory, cardBtnGrammar, cardBtnLeitner}) {
            LinearLayout.LayoutParams lp = matchWrap(6);
            lp.rightMargin = dp(2);
            wordCard.addView(b, lp);
        }
    }

    private void refreshCardButtons() {
        if (cardBtnStory == null) return;
        cardBtnStory.setText(cardStoryDone
                ? msg("\uD83D\uDD16 ذخیره شد برای داستان بعدی", "\uD83D\uDD16 Saved for next story")
                : msg("\uD83D\uDD16 ذخیره برای داستان بعدی", "\uD83D\uDD16 Save for next story"));
        cardBtnGrammar.setText(cardGrammarDone
                ? msg("\uD83E\uDDE9 ذخیره شد در گرامر", "\uD83E\uDDE9 Saved to grammar")
                : msg("\uD83E\uDDE9 افزودن به یادگیری گرامر", "\uD83E\uDDE9 Add to grammar learning"));
        cardBtnLeitner.setText(cardLeitnerDone
                ? msg("\uD83D\uDD01 به جعبه‌ی لایتنر اضافه شد", "\uD83D\uDD01 Added to Leitner box")
                : msg("\uD83D\uDD01 افزودن به جعبه‌ی لایتنر", "\uD83D\uDD01 Add to Leitner box"));
        styleCardAction(cardBtnStory, cardStoryDone);
        styleCardAction(cardBtnGrammar, cardGrammarDone);
        styleCardAction(cardBtnLeitner, cardLeitnerDone);
    }

    /** ردیف‌های ترجمه: [کدِ زبان، متن]؛ هر ردیف 🔊ِ همان زبان دارد. */
    private void fillRows(LinearLayout box, List<String[]> rows) {
        if (box == null) return;
        box.removeAllViews();
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final String lg = r[0], tx = r[1];
            if (!lg.isEmpty()) {
                TextView lab = txt(lg.toUpperCase(Locale.ROOT), COLOR_GOLD, 11);
                lab.setTypeface(lab.getTypeface(), android.graphics.Typeface.BOLD);
                lab.setMinWidth(dp(28));
                row.addView(lab);
            }
            TextView t = txt(tx, Color.parseColor("#E8EAF2"), 15);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!lg.isEmpty()) {
                final TextView sp = speakBtn();
                sp.setOnClickListener(v -> toggleSpeak(sp, tx, lg));
                row.addView(sp);
            }
            box.addView(row, matchWrap(4));
        }
    }

    // 🔊 خوانش با همان سیستمِ خوانشِ اپ (Piper اگر بسته‌ی زبان دانلود شده، وگرنه TTS گوشی) — برای هر زبانی؛ با تکرارِ 🔁
    private void stopSpeech() {
        speakTok++;
        if (speakingBtn != null) { speakingBtn.setText("\uD83D\uDD0A"); speakingBtn = null; }
        try { PanelTts.stop(this); } catch (Throwable ignored) {}
    }

    private void toggleSpeak(final TextView b, String text, String lang) {
        if (text == null || text.trim().isEmpty() || "…".equals(text) || "—".equals(text)) return;
        if (speakingBtn == b) { stopSpeech(); return; }
        stopSpeech();
        speakingBtn = b;
        b.setText("\u23F9");
        speakLoop(b, text, lang, ++speakTok, 0);
    }

    private void speakLoop(final TextView b, final String text, final String lang, final int token, final int done) {
        PanelTts.speak(this, text, lang, 1.0f, ok -> main.post(() -> {
            if (token != speakTok) return;
            int rep = repeatSetting();
            int cap = rep == 0 ? 1 : rep == REPEAT_INF ? Integer.MAX_VALUE : rep;
            final int n = done + 1;
            if (ok && n < cap) {
                main.postDelayed(() -> { if (token == speakTok) speakLoop(b, text, lang, token, n); }, 700);
            } else {
                if (speakingBtn != null) { speakingBtn.setText("\uD83D\uDD0A"); speakingBtn = null; }
            }
        }));
    }

    /** لغت/محدوده‌ی انتخاب‌شده (یا کلِ متن با 📄) ← کادرِ لغت باز می‌شود. */
    private void onRangePicked(int i, int j, ArrayList<ElemInfo> el) {
        if (popup == null || wordCard == null || el == null || el.isEmpty()) return;
        final boolean whole = (i == 0 && j == el.size() - 1);
        String word;
        if (whole) {
            word = selOrig;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int k = i; k <= j && k < el.size(); k++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(el.get(k).t);
            }
            word = sb.toString().trim().replaceAll("^[\\p{Punct}\\u2018\\u2019\\u201C\\u201D]+|[\\p{Punct}\\u2018\\u2019\\u201C\\u201D]+$", "");
        }
        if (word.isEmpty()) return;
        stopSpeech();
        final int my = ++cardSeq;
        cardWord = word;
        cardMeaning = "";
        cardMeaningLang = "fa";
        cardStoryDone = cardGrammarDone = cardLeitnerDone = false;
        wordTerm.setText(word);
        wordCard.setVisibility(View.VISIBLE);
        refreshCardButtons();

        if (whole && !wholeResults.isEmpty()) {           // ترجمه‌ی کلِ متن را از قبل داریم
            fillRows(wordRows, wholeResults);
            adoptMeaning(wholeResults);
            placePopup();
            return;
        }
        ArrayList<String[]> wait = new ArrayList<>();
        wait.add(new String[]{"", "…"});
        fillRows(wordRows, wait);
        placePopup();

        final String from = selSrc, w2 = word;
        final List<String> tg = targets(from);
        worker.execute(() -> {
            final ArrayList<String[]> out = new ArrayList<>();
            for (String t : tg) {
                String r;
                try { r = FreeTranslator.translate(http, w2, from, t); }
                catch (Exception ex) { r = ""; }
                out.add(new String[]{t, (r == null || r.trim().isEmpty()) ? "—" : r.trim()});
            }
            main.post(() -> onWordMeanings(my, out));
        });
    }

    private void adoptMeaning(List<String[]> rows) {
        for (String[] r : rows) {
            if (!"—".equals(r[1]) && !r[1].isEmpty()) { cardMeaningLang = r[0]; cardMeaning = r[1]; return; }
        }
    }

    private void onWordMeanings(int token, List<String[]> rows) {
        if (token != cardSeq || wordRows == null) return;
        fillRows(wordRows, rows);
        adoptMeaning(rows);
        if (cardStoryDone) queueWord("story");
        if (cardLeitnerDone) queueWord("leitner");
        placePopup();
    }

    private void queueWord(String action) {
        if (cardWord == null || cardWord.isEmpty()) return;
        try {
            JSONObject o = new JSONObject()
                    .put("key", action + "|" + selSrc + "|" + cardWord.toLowerCase(Locale.ROOT))
                    .put("action", action)
                    .put("word", cardWord)
                    .put("lang", selSrc)
                    .put("meaning", cardMeaning == null ? "" : cardMeaning)
                    .put("meaningLang", cardMeaningLang == null ? "fa" : cardMeaningLang)
                    .put("sentence", selOrig == null || selOrig.isEmpty() ? cardWord : selOrig)
                    .put("rev", System.currentTimeMillis());
            WordQueue.add(this, o);
            BubblePlugin.notifyWordQueued();
        } catch (Throwable t) {
            toast(msg("ذخیره نشد", "Save failed"));
        }
    }

    private void onCardStory()   { if (cardStoryDone) return;   cardStoryDone = true;   queueWord("story");   refreshCardButtons(); }
    private void onCardGrammar() { if (cardGrammarDone) return; cardGrammarDone = true; queueWord("grammar"); refreshCardButtons(); }
    private void onCardLeitner() { if (cardLeitnerDone) return; cardLeitnerDone = true; queueWord("leitner"); refreshCardButtons(); }

    private void toast(String s) { Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ───────────────────────── نماها ─────────────────────────

    /** پنجره‌ی هایلایت روی متنِ انتخاب‌شده؛ ضربه/کشیدن روی لغت‌ها = انتخابِ لغت یا محدوده. */
    private final class SelView extends View {
        private final ArrayList<ElemInfo> el;
        private final int ox, oy;
        private int a = -1, b = -1;
        private final Paint blockP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blockStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selP = new Paint(Paint.ANTI_ALIAS_FLAG);

        SelView(Context c, ArrayList<ElemInfo> el, int ox, int oy) {
            super(c);
            this.el = el; this.ox = ox; this.oy = oy;
            blockP.setColor(Color.parseColor("#33C9A227"));
            blockStroke.setStyle(Paint.Style.STROKE);
            blockStroke.setStrokeWidth(dp(2));
            blockStroke.setColor(COLOR_GOLD);
            selP.setColor(Color.parseColor("#8CC9A227"));
        }

        @Override protected void onDraw(Canvas cv) {
            RectF all = new RectF(dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1));
            cv.drawRoundRect(all, dp(8), dp(8), blockP);
            cv.drawRoundRect(all, dp(8), dp(8), blockStroke);
            if (a >= 0 && b >= 0) {
                int lo = Math.min(a, b), hi = Math.max(a, b);
                for (int i = lo; i <= hi && i < el.size(); i++) {
                    Rect r = el.get(i).r;
                    RectF rf = new RectF(r.left - ox - dp(2), r.top - oy - dp(1), r.right - ox + dp(2), r.bottom - oy + dp(1));
                    cv.drawRoundRect(rf, dp(4), dp(4), selP);
                }
            }
        }

        private int nearest(float x, float y) {
            int best = -1; double bd = Double.MAX_VALUE;
            for (int i = 0; i < el.size(); i++) {
                Rect r = el.get(i).r;
                double d = dist(new Rect(r.left - ox, r.top - oy, r.right - ox, r.bottom - oy), x, y);
                if (d < bd) { bd = d; best = i; }
            }
            return best;
        }

        void selectAll() {
            if (el.isEmpty()) return;
            a = 0; b = el.size() - 1;
            invalidate();
            onRangePicked(0, el.size() - 1, el);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    a = b = nearest(e.getX(), e.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int i = nearest(e.getX(), e.getY());
                    if (i >= 0 && i != b) { b = i; invalidate(); }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (a >= 0 && b >= 0) onRangePicked(Math.min(a, b), Math.max(a, b), el);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        }
    }

    /** ذره‌بین: کنارِ صفحه = حبابِ گرد (هم‌سبکِ حبابِ ترجمه‌ی زنده)؛ هنگامِ کشیدن = حلقه‌ی با وسطِ شفاف تا متنِ زیرش خوانده شود. */
    private final class LensView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean working;

        LensView(Context c) {
            super(c);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeCap(Paint.Cap.ROUND);
            gold.setStyle(Paint.Style.STROKE);
            gold.setStrokeCap(Paint.Cap.ROUND);
        }

        void setBusy(boolean b) { working = b; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            if (lensMode) drawRing(cv); else drawDisc(cv);
        }

        private void drawRing(Canvas cv) {
            float cx = dp(LENS_C), cy = dp(LENS_C), r = dp(LENS_R);
            float hx0 = cx + r * 0.72f, hy0 = cy + r * 0.72f, hx1 = hx0 + dp(10), hy1 = hy0 + dp(10);
            outline.setColor(COLOR_INK);
            outline.setStrokeWidth(dp(6));
            cv.drawCircle(cx, cy, r, outline);
            cv.drawLine(hx0, hy0, hx1, hy1, outline);
            gold.setColor(working ? Color.WHITE : COLOR_GOLD);
            gold.setStrokeWidth(dp(3));
            cv.drawCircle(cx, cy, r, gold);
            cv.drawLine(hx0, hy0, hx1, hy1, gold);
        }

        private void drawDisc(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f - 8 * d;
            fill.setColor(COLOR_INK);
            cv.drawCircle(w / 2f, h / 2f, r, fill);
            gold.setColor(COLOR_GOLD);
            gold.setStrokeWidth(2 * d);
            cv.drawCircle(w / 2f, h / 2f, r, gold);
            outline.setColor(Color.WHITE);
            outline.setStrokeWidth(2.4f * d);
            float cx = w / 2f - 2 * d, cy = h / 2f - 2 * d, lr = 6 * d;
            cv.drawCircle(cx, cy, lr, outline);
            float o = lr * 0.72f;
            cv.drawLine(cx + o, cy + o, cx + o + 6 * d, cy + o + 6 * d, outline);
        }
    }

    /** ✕ی خروج (مثلِ حبابِ ترجمه‌ی زنده): ذره‌بین را رویش رها کنید. */
    private static final class CloseTargetView extends View {
        private final float d;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean hot;

        CloseTargetView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.5f * d);
            ring.setColor(0xE6FFFFFF);
            cross.setStyle(Paint.Style.STROKE);
            cross.setStrokeCap(Paint.Cap.ROUND);
            cross.setStrokeWidth(2.4f * d);
            cross.setColor(Color.WHITE);
        }

        void setHot(boolean h) { hot = h; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float sc = hot ? 1.22f : 1f;
            cv.save();
            cv.scale(sc, sc, cx, cy);
            fill.setColor(hot ? COLOR_REC : 0xE61C2541);
            fill.setShadowLayer(8f * d, 0f, 2f * d, 0x66000000);
            cv.drawCircle(cx, cy, 26f * d, fill);
            cv.drawCircle(cx, cy, 25f * d, ring);
            float k = 7.5f * d;
            cv.drawLine(cx - k, cy - k, cx + k, cy + k, cross);
            cv.drawLine(cx - k, cy + k, cx + k, cy - k, cross);
            cv.restore();
        }
    }
}
