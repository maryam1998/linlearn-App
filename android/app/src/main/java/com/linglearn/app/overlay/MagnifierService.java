package com.linglearn.app.overlay;

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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

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
 * 🔍 حبابِ ذره‌بین: کاربر حباب را روشن می‌کند، روی هر متنی (در هر اپی) می‌زند،
 * از صفحه عکس گرفته می‌شود (MediaProjection)، متنِ نزدیکِ انگشت با ML Kit خوانده
 * (OCR) و با FreeTranslator ترجمه می‌شود و در یک کادرِ شناور کنارِ متن نشان داده می‌شود.
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
    private static final Set<String> LATIN = new HashSet<>(Arrays.asList(
            "en", "de", "fr", "es", "it", "pt", "nl", "tr"));

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

    private MagView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private CaptureLayer layer;
    private boolean active = false;
    private boolean busy = false;
    private boolean layerAttached = false;

    private TextRecognizer latinRec, devaRec;

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
            addBubble();
            running = true;
            toast(msg("ذره‌بین آماده است؛ حباب را بزنید و بعد روی متن بزنید",
                    "Magnifier ready: tap the bubble, then tap a text"));
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
        removeLayer();
        try { if (bubble != null) wm.removeViewImmediate(bubble); } catch (Exception ignored) {}
        bubble = null;
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
            synchronized (frameLock) {
                if (lastImage != null) { try { lastImage.close(); } catch (Exception ignored) {} lastImage = null; }
            }
            ImageReader old = reader;
            capW = dm.widthPixels; capH = dm.heightPixels; dpiVal = dm.densityDpi;
            reader = newReader(capW, capH);
            vd.resize(capW, capH, dpiVal);
            vd.setSurface(reader.getSurface());
            if (old != null) old.close();
            clampBubble();
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
                .setContentText(msg("حباب ذره‌بین فعال است", "Magnifier bubble is active"))
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

    // ───────────────────────── حباب ─────────────────────────

    private void addBubble() {
        bubble = new MagView(this);
        int size = dp(52);
        bubbleLp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = capW - size - dp(8);
        bubbleLp.y = capH / 3;
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy, startX, startY; boolean moved, longFired;
            final Runnable longPress = () -> { longFired = true; toast(msg("ذره‌بین بسته شد", "Magnifier closed")); stopSelf(); };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX(); startY = e.getRawY();
                        dx = bubbleLp.x - startX; dy = bubbleLp.y - startY;
                        moved = false; longFired = false;
                        main.postDelayed(longPress, 900);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.hypot(e.getRawX() - startX, e.getRawY() - startY) > dp(8)) {
                            moved = true;
                            main.removeCallbacks(longPress);
                        }
                        if (moved) {
                            bubbleLp.x = (int) (e.getRawX() + dx);
                            bubbleLp.y = (int) (e.getRawY() + dy);
                            clampBubble();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        if (!moved && !longFired && e.getActionMasked() == MotionEvent.ACTION_UP) toggleActive();
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);
    }

    private void clampBubble() {
        if (bubble == null || bubbleLp == null) return;
        bubbleLp.x = Math.max(0, Math.min(capW - bubbleLp.width, bubbleLp.x));
        bubbleLp.y = Math.max(0, Math.min(capH - bubbleLp.height, bubbleLp.y));
        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
    }

    private void toggleActive() {
        active = !active;
        bubble.setActive(active);
        if (active) {
            addLayer();
            // حباب باید بالای لایه‌ی لمس بماند
            try {
                wm.removeViewImmediate(bubble);
                wm.addView(bubble, bubbleLp);
            } catch (Exception ignored) {}
            toast(msg("روی متنِ مورد نظر بزنید", "Tap on a text"));
        } else {
            removeLayer();
        }
    }

    private void addLayer() {
        if (layerAttached) return;
        layer = new CaptureLayer(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(layer, lp);
        layerAttached = true;
    }

    private void removeLayer() {
        busy = false;
        if (!layerAttached || layer == null) return;
        try { wm.removeViewImmediate(layer); } catch (Exception ignored) {}
        layerAttached = false;
        layer = null;
    }

    // ───────────────────────── لمس روی متن ─────────────────────────

    private void onTapAt(final float x, final float y) {
        if (busy || layer == null) return;
        busy = true;
        layer.clearResult();
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);
        // صبر می‌کنیم کادر/هایلایتِ قبلی از فریم محو شود
        main.postDelayed(() -> {
            Bitmap bmp = frameBitmap();
            if (bubble != null) bubble.setVisibility(View.VISIBLE);
            if (bmp == null) {
                busy = false;
                toast(msg("هنوز تصویری از صفحه نیست؛ دوباره بزنید", "No screen image yet, try again"));
                return;
            }
            if (layer != null) layer.showWorking(x, y);
            final Bitmap b = bmp;
            worker.execute(() -> process(b, x, y));
        }, 260);
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private boolean uiFa() { return "fa".equals(prefs().getString("target", "fa")); }

    private String msg(String fa, String en) { return uiFa() ? fa : en; }

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
        if (best == null || bestD > dp(48)) {
            finish(msg("متنی کنار انگشت پیدا نشد", "No text found near your tap"));
            return;
        }
        Rect box = best.getBoundingBox();
        String orig = best.getText().replace('\n', ' ').trim();
        if (orig.length() > 500) {            // بلوکِ خیلی بلند: فقط خطِ نزدیک
            Text.Line bl = null; double bd = Double.MAX_VALUE;
            for (Text.Line l : best.getLines()) {
                Rect lr = l.getBoundingBox();
                if (lr == null) continue;
                double d = dist(lr, x, y);
                if (d < bd) { bd = d; bl = l; }
            }
            if (bl != null) { orig = bl.getText().trim(); box = bl.getBoundingBox(); }
        }
        if (orig.isEmpty() || box == null) { finish(msg("متنی پیدا نشد", "No text found")); return; }

        final ArrayList<String[]> results = new ArrayList<>();
        for (String tgt : targets(src)) {
            String tr;
            try { tr = FreeTranslator.translate(http, orig, src, tgt); }
            catch (Exception e) { tr = "—"; }
            results.add(new String[]{tgt.toUpperCase(Locale.ROOT), tr});
        }
        final String fo = orig;
        final RectF rf = new RectF(box);
        main.post(() -> {
            busy = false;
            if (layer != null) layer.showResult(rf, fo, results);
        });
    }

    private void finish(final String message) {
        main.post(() -> {
            busy = false;
            if (layer != null) layer.clearResult();
            toast(message);
        });
    }

    private static double dist(Rect r, float x, float y) {
        double dx = Math.max(Math.max(r.left - x, 0), x - r.right);
        double dy = Math.max(Math.max(r.top - y, 0), y - r.bottom);
        return Math.hypot(dx, dy);
    }

    private void toast(String s) { Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ───────────────────────── نماها ─────────────────────────

    /** لایه‌ی تمام‌صفحه‌ی شفاف: ضربه را می‌گیرد، هایلایت و کادرِ ترجمه را نشان می‌دهد. */
    private final class CaptureLayer extends FrameLayout {
        private RectF hl;
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        private View card;

        CaptureLayer(Context c) {
            super(c);
            setWillNotDraw(false);
            fillP.setColor(Color.parseColor("#33C9A227"));
            strokeP.setStyle(Paint.Style.STROKE);
            strokeP.setStrokeWidth(dp(2));
            strokeP.setColor(COLOR_GOLD);
        }

        @Override protected void onDraw(Canvas cv) {
            if (hl == null) return;
            cv.drawRoundRect(hl, dp(6), dp(6), fillP);
            cv.drawRoundRect(hl, dp(6), dp(6), strokeP);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) < dp(12)) onTapAt(e.getRawX(), e.getRawY());
                    return true;
            }
            return true;
        }

        void clearResult() {
            hl = null;
            if (card != null) { removeView(card); card = null; }
            invalidate();
        }

        private LinearLayout newCard() {
            LinearLayout c = new LinearLayout(getContext());
            c.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#FFFBF0"));
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(2), COLOR_GOLD);
            c.setBackground(bg);
            c.setPadding(dp(14), dp(10), dp(14), dp(12));
            c.setElevation(dp(10));
            c.setClickable(true);   // لمس روی کادر، ضربه‌ی تازه حساب نشود
            return c;
        }

        void showWorking(float x, float y) {
            clearResult();
            LinearLayout c = newCard();
            TextView t = new TextView(getContext());
            t.setText(msg("در حال ترجمه…", "Translating…"));
            t.setTextColor(COLOR_INK);
            t.setTextSize(14);
            c.addView(t);
            place(c, new RectF(x - dp(4), y - dp(4), x + dp(4), y + dp(4)), dp(180));
        }

        void showResult(RectF rect, String orig, List<String[]> results) {
            clearResult();
            hl = rect;
            LinearLayout c = newCard();

            LinearLayout head = new LinearLayout(getContext());
            head.setOrientation(LinearLayout.HORIZONTAL);
            TextView o = new TextView(getContext());
            o.setText(orig);
            o.setTextColor(Color.parseColor("#6B7280"));
            o.setTextSize(12);
            o.setMaxLines(3);
            o.setEllipsize(android.text.TextUtils.TruncateAt.END);
            o.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            head.addView(o, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = new TextView(getContext());
            x.setText("✕");
            x.setTextColor(COLOR_INK);
            x.setTextSize(16);
            x.setPadding(dp(10), 0, 0, 0);
            x.setOnClickListener(v -> clearResult());
            head.addView(x);
            c.addView(head);

            for (String[] r : results) {
                TextView tv = new TextView(getContext());
                tv.setText(r[0] + " · " + r[1]);
                tv.setTextColor(COLOR_INK);
                tv.setTextSize(16);
                tv.setTextIsSelectable(false);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
                tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                c.addView(tv, lp);
            }
            place(c, rect, Math.min(capW - dp(24), dp(360)));
            invalidate();
        }

        private void place(View c, RectF rect, int cardW) {
            c.measure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int ch = c.getMeasuredHeight();
            int left = Math.max(dp(12), Math.min(capW - cardW - dp(12), (int) (rect.centerX() - cardW / 2f)));
            int top;
            if (rect.bottom + dp(10) + ch <= capH - dp(24)) top = (int) rect.bottom + dp(10);
            else top = Math.max(dp(36), (int) rect.top - dp(10) - ch);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.leftMargin = left;
            flp.topMargin = top;
            addView(c, flp);
            card = c;
        }
    }

    /** دکمه‌ی گردِ حباب با نقشِ ذره‌بین (روشن = طلایی). */
    private static final class MagView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;

        MagView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
        }

        void setActive(boolean a) { on = a; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f - 3 * d;
            fill.setColor(on ? COLOR_GOLD : COLOR_INK);
            cv.drawCircle(w / 2f, h / 2f, r, fill);
            ring.setColor(on ? COLOR_INK : COLOR_GOLD);
            ring.setStrokeWidth(2 * d);
            cv.drawCircle(w / 2f, h / 2f, r, ring);
            glyph.setColor(on ? COLOR_INK : Color.WHITE);
            glyph.setStrokeWidth(3 * d);
            float cx = w / 2f - 3 * d, cy = h / 2f - 3 * d, lr = 8 * d;
            cv.drawCircle(cx, cy, lr, glyph);
            float o = lr * 0.72f;
            cv.drawLine(cx + o, cy + o, cx + o + 8 * d, cy + o + 8 * d, glyph);
        }
    }
}
