package com.linglearn.app.overlay;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressLint("NewApi")
public class BubbleService extends Service {

    private static final String TAG = "BubbleService";

    public static final String ACTION_SHOW = "com.linglearn.app.overlay.SHOW";
    public static final String ACTION_HIDE = "com.linglearn.app.overlay.HIDE";
    public static final String ACTION_PROJECTION_RESULT = "com.linglearn.app.overlay.PROJECTION_RESULT";
    public static final String ACTION_PROJECTION_DENIED = "com.linglearn.app.overlay.PROJECTION_DENIED";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static final String WORKER_BASE = "https://phrasebook-api.maryam-s-sharifiyan.workers.dev";
    private static final String TRANSCRIBE_PATH = "/api/transcribe";
    private static final String GENERATE_PATH = "/api/generate";
    private static final String GENERATE_PROMPT_KEY = "prompt";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_MS = 100;
    private static final int CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS / 1000;
    private static final int MIN_SEG_MS = 800;
    private static final int MAX_SEG_MS = 2000;
    private static final int SILENCE_CUT_MS = 1400;
    private static final int MIN_VOICED_MS = 600;
    private static final double SILENCE_RMS = 250.0;
    private static final int MAX_PENDING = 3;
    private static final int ASR_WATCHDOG_VOICED_MS = 15000;
    private static final int MAX_TEXT_PENDING = 6;

    private static final long PANEL_HIDE_MS = 6000;
    private static final long PANEL_HIDE_MS_RECORDING = 60000;

    private static final int NET_ATTEMPTS = 3;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final OkHttpClient HTTP_FAST = HTTP.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build();

    private static final String PREFS = "bubble_prefs";
    private static final String CHANNEL_ID = "bubble_channel";
    private static final int NOTIF_ID = 4711;
    private static final int COLOR_INK = Color.parseColor("#1C2541");
    private static final int COLOR_GOLD = Color.parseColor("#C9A227");
    private static final int COLOR_REC = Color.parseColor("#E53935");

    // پنل: تنظیمات اندازه
    private static final int PANEL_MAX_ROWS = 20;
    private static final int PANEL_DEFAULT_HEIGHT_DP = 180;
    private static final int PANEL_MIN_WIDTH_DP = 200;
    private static final int PANEL_MIN_HEIGHT_DP = 80;
    private static final int RESIZE_HANDLE_DP = 48;

    public static volatile boolean running = false;

    private static final Map<String, String> LANG_NAMES = new HashMap<>();
    private static final Map<String, String> LOCALE_TAGS = new HashMap<>();
    static {
        LANG_NAMES.put("fa", "Persian");
        LANG_NAMES.put("en", "English");
        LANG_NAMES.put("ar", "Arabic");
        LANG_NAMES.put("tr", "Turkish");
        LANG_NAMES.put("de", "German");
        LANG_NAMES.put("fr", "French");
        LANG_NAMES.put("es", "Spanish");
        LANG_NAMES.put("it", "Italian");
        LANG_NAMES.put("ru", "Russian");
        LANG_NAMES.put("zh", "Chinese");
        LANG_NAMES.put("ja", "Japanese");
        LANG_NAMES.put("ko", "Korean");
        LANG_NAMES.put("hi", "Hindi");
        LANG_NAMES.put("pt", "Portuguese");
        LANG_NAMES.put("ur", "Urdu");
        LANG_NAMES.put("nl", "Dutch");
        LANG_NAMES.put("he", "Hebrew");

        LOCALE_TAGS.put("fa", "fa-IR");
        LOCALE_TAGS.put("en", "en-US");
        LOCALE_TAGS.put("ar", "ar-SA");
        LOCALE_TAGS.put("tr", "tr-TR");
        LOCALE_TAGS.put("de", "de-DE");
        LOCALE_TAGS.put("fr", "fr-FR");
        LOCALE_TAGS.put("es", "es-ES");
        LOCALE_TAGS.put("it", "it-IT");
        LOCALE_TAGS.put("ru", "ru-RU");
        LOCALE_TAGS.put("zh", "zh-CN");
        LOCALE_TAGS.put("ja", "ja-JP");
        LOCALE_TAGS.put("ko", "ko-KR");
        LOCALE_TAGS.put("hi", "hi-IN");
        LOCALE_TAGS.put("pt", "pt-BR");
        LOCALE_TAGS.put("ur", "ur-PK");
        LOCALE_TAGS.put("nl", "nl-NL");
        LOCALE_TAGS.put("he", "he-IL");
    }

    public static void saveLangs(Context ctx, String target, String source) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (target != null && !target.isEmpty()) e.putString("target", target);
        if (source != null && !source.isEmpty()) e.putString("source", source);
        e.apply();
    }

    public static void saveTone(Context ctx, String tone) {
        if (tone == null) return;
        String t = tone.trim().toLowerCase(Locale.ROOT);
        if (!t.equals("formal") && !t.equals("casual") && !t.equals("neutral")) return;
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("tone", t).apply();
    }

    public static void saveDisplayMode(Context ctx, String mode) {
        if (mode == null) return;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (!m.equals("both") && !m.equals("original") && !m.equals("translation")) return;
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("displayMode", m).apply();
    }

    private String displayMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("displayMode", "both");
    }

    private String tone() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("tone", "neutral");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService netTr = Executors.newFixedThreadPool(2);
    private final AtomicInteger pending = new AtomicInteger(0);
    private final AtomicInteger textPending = new AtomicInteger(0);

    private WindowManager wm;
    private TextView bubble;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams bubbleLp;
    private int bubbleSize;

    // ======= پنل به‌روزشده =======
    private ScrollView panel;
    private LinearLayout panelList;
    private WindowManager.LayoutParams panelLp;
    private boolean panelShown = false;
    private int panelWidthPx = 0;
    private int panelHeightPx = 0;
    private String lastPartialSrc = "";
    private String shownSrc = "";
    private String shownTr = "";
    private final Runnable hidePanel = this::removePanel;

    private ObjectAnimator pulse;
    private GestureDetector gestures;
    private boolean dragging = false;

    private MediaProjection mediaProjection;
    private AudioRecord record;
    private volatile boolean recording = false;

    private static volatile BubbleService instance;
    private volatile boolean micEngine = false;
    private volatile SherpaEngine sherpaEngine;
    private volatile boolean gotAsrText = false;
    private volatile int voicedSinceText = 0;

    private Translator localTr;
    private String localTrKey = "";
    private volatile boolean localReady = false;
    private int finalSeq = 0;
    private int shownFinalSeq = 0;
    private volatile String prevFinalText = "";

    private int speechHostRestarts = 0;
    private static final int MAX_SPEECH_HOST_RESTARTS = 40;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        instance = this;

        if (ACTION_HIDE.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        enterForeground(ACTION_PROJECTION_RESULT.equals(action));

        if (ACTION_PROJECTION_RESULT.equals(action)) {
            handleProjectionResult(intent);
        } else if (ACTION_PROJECTION_DENIED.equals(action)) {
            showText("", isFa() ? "مجوز ضبط صدا داده نشد" : "Audio capture permission denied");
        } else {
            running = true;
            addBubbleIfNeeded();
            prepareLocalTranslator();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void enterForeground(boolean withProjection) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (withProjection) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIF_ID, n, type);
        } else if (withProjection) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Live translation bubble", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stop = new Intent(this, BubbleService.class).setAction(ACTION_HIDE);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action close = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Close", pi).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("LingoLearn")
                .setContentText("Live translation bubble is active")
                .setOngoing(true)
                .addAction(close)
                .build();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private int screenW() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getMaximumWindowMetrics().getBounds().width();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenH() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getMaximumWindowMetrics().getBounds().height();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private String targetLang() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("target", "fa");
    }

    private String sourceLang() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("source", "auto");
    }

    private boolean srcExplicit() {
        String s = sourceLang();
        return s != null && !s.isEmpty() && !"auto".equals(s);
    }

    private String effectiveSource() {
        if (srcExplicit()) return sourceLang();
        return "en".equals(targetLang()) ? "auto" : "en";
    }

    private boolean isFa() { return "fa".equals(targetLang()); }

    private void addBubbleIfNeeded() {
        if (bubble != null) return;
        if (!Settings.canDrawOverlays(this)) { shutdown(); return; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubbleSize = dp(56);

        bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(COLOR_INK);
        bubbleBg.setStroke(dp(3), COLOR_GOLD);

        bubble = new TextView(this);
        bubble.setText("\uD83C\uDF99");
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(bubbleBg);

        bubbleLp = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = screenW() - bubbleSize - dp(8);
        bubbleLp.y = dp(200);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showText("", isFa()
                        ? "نگه‌داشتن: شروع/توقف ضبط  ·  دوبار لمس: بستن"
                        : "Long-press: start/stop  ·  Double-tap: close");
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) { shutdown(); return true; }

            @Override
            public void onLongPress(MotionEvent e) {
                if (dragging) return;
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                toggleRecording();
            }
        });

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                gestures.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = bubbleLp.x; startY = bubbleLp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true;
                        if (dragging) {
                            bubbleLp.x = clamp(Math.round(startX + dx), 0, screenW() - bubbleSize);
                            bubbleLp.y = clamp(Math.round(startY + dy), 0, screenH() - bubbleSize);
                            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
                            movePanel();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return true;
            }
        });

        try { wm.addView(bubble, bubbleLp); }
        catch (Exception e) { Log.e(TAG, "addView failed", e); shutdown(); }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ============ پنل جدید با ScrollView ============
    private void ensurePanel() {
        if (panel != null) return;

        panel = new ScrollView(this);
        panel.setFillViewport(false);
        panel.setVerticalScrollBarEnabled(true);
        panel.setScrollbarFadingEnabled(false);

        panelList = new LinearLayout(this);
        panelList.setOrientation(LinearLayout.VERTICAL);
        panelList.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EB1C2541"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COLOR_GOLD);
        panelList.setBackground(bg);

        panel.addView(panelList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        // هندل تغییر اندازه (گوشه‌ی پایین-راست)
        panel.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startW, startH;
            boolean resizing = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                float x = e.getX();
                float y = e.getY();
                boolean inResizeArea = x > v.getWidth() - dp(RESIZE_HANDLE_DP)
                        && y > v.getHeight() - dp(RESIZE_HANDLE_DP);

                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!inResizeArea) return false;
                        downX = e.getRawX(); downY = e.getRawY();
                        startW = panelLp.width; startH = panelLp.height;
                        resizing = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!resizing) return false;
                        int newW = clamp((int) (startW + (e.getRawX() - downX)),
                                dp(PANEL_MIN_WIDTH_DP), screenW() - dp(24));
                        int newH = clamp((int) (startH + (e.getRawY() - downY)),
                                dp(PANEL_MIN_HEIGHT_DP), screenH() / 2);
                        panelLp.width = newW;
                        panelLp.height = newH;
                        panelWidthPx = newW;
                        panelHeightPx = newH;
                        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        resizing = false;
                        return true;
                }
                return false;
            }
        });
    }

    private void computePanelPos() {
        if (panelWidthPx == 0) panelWidthPx = screenW() - dp(24);
        if (panelHeightPx == 0) panelHeightPx = dp(PANEL_DEFAULT_HEIGHT_DP);
        panelLp.width = panelWidthPx;
        panelLp.height = panelHeightPx;
        panelLp.x = dp(12);
        int sh = screenH();
        boolean below = bubbleLp.y + bubbleSize / 2 < sh / 2;
        if (below) {
            panelLp.gravity = Gravity.TOP | Gravity.START;
            panelLp.y = bubbleLp.y + bubbleSize + dp(8);
        } else {
            panelLp.gravity = Gravity.BOTTOM | Gravity.START;
            panelLp.y = sh - bubbleLp.y + dp(8);
        }
    }

    private void movePanel() {
        if (!panelShown || panel == null) return;
        computePanelPos();
        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
    }

    private LinearLayout currentRow = null;

    private void showText(String src, String tr) {
        if (wm == null || bubble == null) return;
        ensurePanel();

        String newSrc = src == null ? "" : src;
        String newTr = tr == null ? "" : tr;
        String mode = displayMode();
        if ("original".equals(mode)) {
            newTr = "";
        } else if ("translation".equals(mode)) {
            if (!newTr.isEmpty() && !newTr.equals("…")) newSrc = "";
            else if (newTr.equals("…") && !newSrc.isEmpty()) newTr = "";
        }

        boolean isPartialUpdate = !lastPartialSrc.isEmpty()
                && newSrc.startsWith(lastPartialSrc)
                && currentRow != null
                && !lastPartialSrc.equals(newSrc);

        if (isPartialUpdate) {
            TextView s = currentRow.findViewWithTag("src");
            TextView t = currentRow.findViewWithTag("tr");
            if (s != null) s.setText(newSrc);
            if (t != null) {
                t.setText(newTr);
                t.setVisibility(newTr.isEmpty() ? View.GONE : View.VISIBLE);
            }
        } else if (!newSrc.isEmpty() || !newTr.isEmpty()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView s = new TextView(this);
            s.setTag("src");
            s.setText(newSrc);
            s.setTextColor(Color.parseColor("#C8CCD8"));
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            s.setVisibility(newSrc.isEmpty() ? View.GONE : View.VISIBLE);

            TextView t = new TextView(this);
            t.setTag("tr");
            t.setText(newTr);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            t.setPadding(0, dp(2), 0, 0);
            t.setVisibility(newTr.isEmpty() ? View.GONE : View.VISIBLE);

            row.addView(s);
            row.addView(t);
            panelList.addView(row);
            currentRow = row;

            while (panelList.getChildCount() > PANEL_MAX_ROWS) {
                panelList.removeViewAt(0);
            }
        }

        if (!newSrc.isEmpty() && !isPartialUpdate) lastPartialSrc = newSrc;
        shownSrc = newSrc;
        shownTr = newTr;

        if (!panelShown) {
            computePanelPos();
            try {
                wm.addView(panel, panelLp);
                panelShown = true;
            } catch (Exception e) {
                Log.w(TAG, "panel add failed", e);
                return;
            }
        } else {
            computePanelPos();
            try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
        }

        panel.post(() -> panel.fullScroll(View.FOCUS_DOWN));

        main.removeCallbacks(hidePanel);
        long delay = recording ? PANEL_HIDE_MS_RECORDING : PANEL_HIDE_MS;
        main.postDelayed(hidePanel, delay);
    }

    private void removePanel() {
        if (panelShown && panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
        }
        panelShown = false;
        shownSrc = "";
        shownTr = "";
        lastPartialSrc = "";
        currentRow = null;
        if (panelList != null) panelList.removeAllViews();
    }

    private void setRecordingUi(boolean rec) {
        if (bubbleBg == null) return;
        bubbleBg.setStroke(dp(rec ? 4 : 3), rec ? COLOR_REC : COLOR_GOLD);
        if (pulse != null) { pulse.cancel(); pulse = null; }
        if (rec) {
            pulse = ObjectAnimator.ofFloat(bubble, View.ALPHA, 1f, 0.55f);
            pulse.setDuration(700);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.start();
        } else {
            bubble.setAlpha(1f);
        }
    }

    private String msg(String fa, String en) { return isFa() ? fa : en; }

    private void toggleRecording() {
        if (recording) { stopRecording(); return; }
        if (mediaProjection == null) {
            Intent i = new Intent(this, ProjectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); }
            catch (Exception e) { Log.e(TAG, "cannot launch ProjectionActivity", e); showText("", "⚠ " + briefErr(e)); }
            return;
        }
        startCapture();
    }

    @SuppressWarnings("deprecation")
    private void handleProjectionResult(Intent intent) {
        try {
            int rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                    : (Intent) intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            final MediaProjection mp = mpm.getMediaProjection(rc, data);
            mp.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (mediaProjection == mp) { mediaProjection = null; stopRecording(); }
                }
            }, main);
            mediaProjection = mp;
            startCapture();
        } catch (Exception e) {
            Log.e(TAG, "projection failed", e);
            showText("", "⚠ " + briefErr(e));
        }
    }

    private static String localeTag(String lang) {
        String t = LOCALE_TAGS.get(lang);
        return t != null ? t : lang;
    }

    private void startCapture() {
        if (mediaProjection == null || recording) return;
        prepareLocalTranslator();
        prevFinalText = "";
        speechHostRestarts = 0;
        gotAsrText = false; voicedSinceText = 0;
        final String src = effectiveSource();
        if (src != null && !"auto".equals(src) && SherpaModelManager.isAvailable(src)) {
            if (startSherpaEngine(src)) return;
        }
        boolean micOk = Build.VERSION.SDK_INT >= 33
                && src != null && !src.isEmpty() && !"auto".equals(src)
                && SpeechRecognizer.isRecognitionAvailable(this);
        if (micOk && startMicEngine(src)) return;
        beginCapture();
    }

    private boolean startMicEngine(String src) {
        AudioRecord rec = null;
        try {
            rec = buildPlaybackRecord();
            if (rec == null) return false;
            record = rec;
            micEngine = true;
            recording = true;
            setRecordingUi(true);
            rec.startRecording();
            final AudioRecord fr = rec;
            new Thread(() -> feedLoop(fr), "bubble-feed").start();
            Intent i = new Intent(this, SpeechHostActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(SpeechHostActivity.EXTRA_LANG_TAG, localeTag(src));
            startActivity(i);
            showText("", msg("🎙 گوش‌دادن به صدای سیستم…", "🎙 Listening to system audio…"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot start recognizer engine", e);
            micEngine = false; recording = false; setRecordingUi(false);
            PcmFeed.close();
            if (rec != null) { record = null; try { rec.stop(); } catch (Exception ignored) {} try { rec.release(); } catch (Exception ignored) {} }
            return false;
        }
    }

    private boolean startSherpaEngine(final String src) {
        if (SherpaModelManager.getModelDir(this, src) == null) return false;
        AudioRecord rec = null;
        try {
            rec = buildPlaybackRecord();
            if (rec == null) return false;
            record = rec;
            micEngine = true;
            recording = true;
            setRecordingUi(true);
            rec.startRecording();
            final AudioRecord fr = rec;
            new Thread(() -> runSherpa(fr, src), "bubble-feed-sherpa").start();
            showText("", msg("🎙 ترجمه آفلاین فعال…", "🎙 Offline mode active…"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot start sherpa engine", e);
            micEngine = false; recording = false; setRecordingUi(false);
            if (rec != null) { record = null; try { rec.stop(); } catch (Exception ignored) {} try { rec.release(); } catch (Exception ignored) {} }
            return false;
        }
    }

    private void runSherpa(final AudioRecord rec, final String src) {
        SherpaEngine eng = SherpaEngine.create(getApplicationContext(), src);
        boolean stillActive = recording && micEngine && record == rec;
        if (eng != null && stillActive) {
            sherpaEngine = eng;
            if (!(recording && micEngine && record == rec)) { releaseSherpa(); stillActive = false; }
        } else {
            if (eng != null) eng.release();
            if (stillActive) {
                main.post(() -> fallbackToServer(msg("بارگذاری مدل آفلاین ناموفق بود؛ حالت سرور فعال شد",
                        "Offline model failed to load; using server")));
            }
            stillActive = false;
        }
        if (stillActive) {
            feedLoop(rec);
        } else {
            try { rec.stop(); } catch (Exception ignored) {}
            try { rec.release(); } catch (Exception ignored) {}
        }
    }

    private void releaseSherpa() {
        SherpaEngine e = sherpaEngine;
        sherpaEngine = null;
        if (e != null) e.release();
    }

    private AudioRecord buildPlaybackRecord() {
        if (mediaProjection == null) return null;
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        int[] rates = {48000, 44100, SAMPLE_RATE};
        for (int rate : rates) {
            AudioRecord r = null;
            try {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                r = new AudioRecord.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(min, rate / 5 * 2))
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build();
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                r.release();
            } catch (Exception e) {
                Log.w(TAG, "playback record @" + rate + " failed", e);
                if (r != null) { try { r.release(); } catch (Exception ignored) {} }
            }
        }
        return null;
    }

    private void feedLoop(final AudioRecord rec) {
        final int rate = rec.getSampleRate() > 0 ? rec.getSampleRate() : SAMPLE_RATE;
        final int frame = Math.max(1, rate / 50);
        final short[] in = new short[frame];
        final byte[] out = new byte[(frame * SAMPLE_RATE / rate + 4) * 2];
        try {
            while (recording && micEngine && record == rec) {
                int n = rec.read(in, 0, frame);
                if (n < 0) break;
                if (n == 0) continue;
                int outBytes = toPcm16k(in, n, rate, out);
                final SherpaEngine se = sherpaEngine;
                if (se != null) {
                    se.accept(out, outBytes);
                } else {
                    PcmFeed.write(out, outBytes);
                }
                if (!gotAsrText) {
                    if (rms16(in, n) > SILENCE_RMS) voicedSinceText += 20;
                    if (voicedSinceText >= ASR_WATCHDOG_VOICED_MS) {
                        voicedSinceText = 0;
                        main.post(() -> fallbackToServer(
                                msg("تشخیص گفتار روی صدای سیستم کار نکرد؛ حالت سرور فعال شد",
                                        "Recognizer can't hear system audio; using server")));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "feed loop", e);
        } finally {
            try { rec.stop(); } catch (Exception ignored) {}
            try { rec.release(); } catch (Exception ignored) {}
            if (recording && micEngine && record == rec) {
                record = null; recording = false;
                main.post(() -> setRecordingUi(false));
            }
        }
    }

    private static int toPcm16k(short[] in, int n, int rate, byte[] out) {
        int outN;
        if (rate == SAMPLE_RATE) {
            outN = Math.min(n, out.length / 2);
            for (int j = 0; j < outN; j++) {
                out[2 * j] = (byte) (in[j] & 0xff);
                out[2 * j + 1] = (byte) ((in[j] >> 8) & 0xff);
            }
            return outN * 2;
        }
        outN = Math.min((int) ((long) n * SAMPLE_RATE / rate), out.length / 2);
        for (int j = 0; j < outN; j++) {
            int a = (int) ((long) j * rate / SAMPLE_RATE);
            int b = Math.min(n, (int) ((long) (j + 1) * rate / SAMPLE_RATE));
            if (b <= a) b = Math.min(n, a + 1);
            int sum = 0;
            for (int k = a; k < b; k++) sum += in[k];
            short s = (short) (sum / (b - a));
            out[2 * j] = (byte) (s & 0xff);
            out[2 * j + 1] = (byte) ((s >> 8) & 0xff);
        }
        return outN * 2;
    }

    private static double rms16(short[] s, int n) {
        if (n <= 0) return 0;
        long sum = 0;
        for (int i = 0; i < n; i++) sum += (long) s[i] * s[i];
        return Math.sqrt(sum / (double) n);
    }

    private void fallbackToServer(String notice) {
        if (!micEngine) return;
        micEngine = false;
        recording = false;
        releaseSherpa();
        SpeechHostActivity.finishIfRunning();
        PcmFeed.close();
        AudioRecord r = record; record = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }
        beginCapture();
        if (notice != null) showText("", notice);
    }

    private void restartSpeechHost() {
        if (!recording || !micEngine) return;
        if (speechHostRestarts >= MAX_SPEECH_HOST_RESTARTS) {
            fallbackToServer(null); return;
        }
        final String src = effectiveSource();
        if (src == null || src.isEmpty() || "auto".equals(src)) {
            fallbackToServer(null); return;
        }
        speechHostRestarts++;
        main.postDelayed(() -> {
            if (!recording || !micEngine) return;
            try {
                Intent i = new Intent(this, SpeechHostActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(SpeechHostActivity.EXTRA_LANG_TAG, localeTag(src));
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "restart speech host failed", e);
                fallbackToServer(null);
            }
        }, 200);
    }

    private void beginCapture() {
        if (mediaProjection == null || recording) return;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            final AudioRecord rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, SAMPLE_RATE * 2))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                throw new IllegalStateException("AudioRecord not initialized");
            }
            rec.startRecording();
            record = rec;
            recording = true;
            setRecordingUi(true);
            showText("", msg("🎙 در حال گوش‌دادن به صدای سیستم…", "🎙 Listening to system audio…"));
            new Thread(() -> captureLoop(rec), "bubble-capture").start();
        } catch (Exception e) {
            Log.e(TAG, "startCapture failed", e);
            recording = false; setRecordingUi(false);
            showText("", "⚠ " + briefErr(e));
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        if (micEngine) { micEngine = false; SpeechHostActivity.finishIfRunning(); PcmFeed.close(); }
        releaseSherpa();
        AudioRecord r = record; record = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }
        setRecordingUi(false);
        showText("", msg("ضبط متوقف شد", "Stopped"));
    }

    private void captureLoop(AudioRecord rec) {
        byte[] buf = new byte[CHUNK_BYTES];
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        int segMs = 0, voicedMs = 0, silentMs = 0;
        try {
            while (recording) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n == 0) continue;
                seg.write(buf, 0, n);
                int chunkMs = n * 1000 / (SAMPLE_RATE * 2);
                segMs += chunkMs;
                if (rms(buf, n) > SILENCE_RMS) { voicedMs += chunkMs; silentMs = 0; }
                else silentMs += chunkMs;
                boolean cut = segMs >= MAX_SEG_MS || (segMs >= MIN_SEG_MS && silentMs >= SILENCE_CUT_MS);
                if (cut) {
                    if (voicedMs >= MIN_VOICED_MS) submit(seg.toByteArray());
                    seg.reset(); segMs = voicedMs = silentMs = 0;
                } else if (voicedMs == 0 && segMs >= 1000) {
                    seg.reset(); segMs = silentMs = 0;
                }
            }
            if (voicedMs >= MIN_VOICED_MS) submit(seg.toByteArray());
        } catch (Exception e) { Log.e(TAG, "capture loop", e); }
        finally {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
            if (recording) { recording = false; main.post(() -> setRecordingUi(false)); }
        }
    }

    private static double rms(byte[] b, int len) {
        long sum = 0;
        int n = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (long) s * s;
        }
        return n == 0 ? 0 : Math.sqrt(sum / (double) n);
    }

    static void asrPartial(String text) {
        BubbleService s = instance;
        if (s != null && s.micEngine) { s.gotAsrText = true; s.onPartialText(text); }
    }

    static void asrFinal(String text) {
        BubbleService s = instance;
        if (s != null && s.micEngine) { s.gotAsrText = true; s.onFinalText(text); }
    }

    static void asrFallback() {
        BubbleService s = instance;
        if (s == null || !s.micEngine) return;
        s.fallbackToServer(s.msg("تشخیص گفتار گوگل در دسترس نیست؛ حالت سرور فعال شد",
                "Google speech unavailable; using server"));
    }

    static void asrNeedsPermission() {
        BubbleService s = instance;
        if (s == null || !s.micEngine) return;
        s.stopRecording();
        s.showText("", s.msg("مجوز میکروفون لازم است", "Microphone permission required"));
    }

    static void asrClosed() {
        BubbleService s = instance;
        if (s == null || !s.micEngine) return;
        s.restartSpeechHost();
    }

    private void prepareLocalTranslator() {
        String src = TranslateLanguage.fromLanguageTag(effectiveSource());
        String tgt = TranslateLanguage.fromLanguageTag(targetLang());
        final boolean wasReady = localReady;
        if (src == null || tgt == null || src.equals(tgt)) { closeLocalTranslator(); return; }
        String key = src + ">" + tgt;
        if (key.equals(localTrKey) && localTr != null && wasReady) return;
        localReady = false;
        if (!key.equals(localTrKey) || localTr == null) {
            closeLocalTranslator();
            localTr = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(src).setTargetLanguage(tgt).build());
            localTrKey = key;
        }
        final Translator tr = localTr;
        tr.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> {
                    if (tr == localTr) {
                        localReady = true;
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "translation model download failed", e);
                    if (tr == localTr) {
                        main.postDelayed(() -> { if (tr == localTr && !localReady) prepareLocalTranslator(); }, 8000);
                    }
                });
    }

    private void closeLocalTranslator() {
        Translator t = localTr; localTr = null; localTrKey = ""; localReady = false;
        if (t != null) { try { t.close(); } catch (Exception ignored) {} }
    }

    private void onPartialText(final String text) {
        showText(text, "");
        gotAsrText = true;
    }

    private void onFinalText(final String text) {
        lastPartialSrc = "";
        currentRow = null;
        final int fseq = ++finalSeq;
        final String ctxPrev = prevFinalText;
        prevFinalText = text;
        final Translator tr = localTr;
        if (localReady && tr != null) {
            showText(text, "…");
            tr.translate(text)
                    .addOnSuccessListener(out -> {
                        if (fseq < shownFinalSeq) return;
                        shownFinalSeq = fseq;
                        showText(text, out);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "local translate failed", e);
                        remoteTranslate(text, fseq, ctxPrev);
                    });
            return;
        }
        remoteTranslate(text, fseq, ctxPrev);
    }

    private void remoteTranslate(final String text, final int fseq, final String ctxPrev) {
        if (!hasInternet()) {
            if (fseq >= shownFinalSeq) {
                shownFinalSeq = fseq;
                showText(text, msg("ترجمه در دسترس نیست (آفلاین)", "No translation (offline)"));
            }
            return;
        }
        showText(text, "…");
        submitText(text, fseq, ctxPrev);
    }

    private boolean hasInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    private boolean isOfflineError(Exception e) {
        return e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof SocketTimeoutException || !hasInternet();
    }

    private String netErrText(Exception e) {
        if (isOfflineError(e)) return msg("ترجمه در دسترس نیست (آفلاین)", "No translation (offline)");
        return "⚠ " + briefErr(e);
    }

    private void submit(final byte[] pcm) {
        if (pending.get() >= MAX_PENDING) { Log.w(TAG, "dropping segment (backlog)"); return; }
        pending.incrementAndGet();
        try {
            net.execute(() -> { try { processSegment(pcm); } finally { pending.decrementAndGet(); } });
        } catch (Exception e) { pending.decrementAndGet(); }
    }

    private void submitText(final String text, final int fseq, final String ctxPrev) {
        if (textPending.get() >= MAX_TEXT_PENDING) {
            Log.w(TAG, "dropping translation (backlog)");
            main.post(() -> showText(text, ""));
            return;
        }
        textPending.incrementAndGet();
        try {
            netTr.execute(() -> { try { translateAndShow(text, fseq, ctxPrev); } finally { textPending.decrementAndGet(); } });
        } catch (Exception e) { textPending.decrementAndGet(); }
    }

    private void processSegment(byte[] pcm) {
        try {
            final String text = transcribe(pcm);
            if (text.isEmpty()) return;
            main.post(() -> onFinalText(text));
        } catch (Exception e) {
            Log.w(TAG, "transcribe failed", e);
            final String m = netErrText(e);
            main.post(() -> showText("", m));
        }
    }

    private void translateAndShow(final String text, final int fseq, final String ctxPrev) {
        String tr;
        try { tr = translate(text, ctxPrev); }
        catch (Exception e) { Log.w(TAG, "translate failed", e); tr = netErrText(e); }
        final String shown = tr;
        main.post(() -> {
            if (fseq < shownFinalSeq) return;
            shownFinalSeq = fseq;
            showText(text, shown);
        });
    }

    private String transcribe(byte[] pcm) throws Exception {
        String src = sourceLang();
        String url = WORKER_BASE + TRANSCRIBE_PATH;
        if (src != null && !src.isEmpty() && !"auto".equals(src)) {
            url += "?lang=" + URLEncoder.encode(src, "UTF-8");
        }
        String resp = postBytes(url, "audio/wav", toWav(pcm));
        JSONObject j = new JSONObject(resp);
        return j.optString("text", "").trim();
    }

    private String translate(String text, String prevContext) throws Exception {
        String target = targetLang();
        String name = LANG_NAMES.containsKey(target) ? LANG_NAMES.get(target) : target;
        String prompt = "Translate the following text to " + name
                + ". Reply with ONLY the translation, no quotes, no explanations.";
        String tone = tone();
        if ("formal".equals(tone)) {
            prompt += " Use a formal, polite register.";
        } else if ("casual".equals(tone)) {
            prompt += " Use a casual, spoken, everyday register.";
        }
        if (prevContext != null && !prevContext.isEmpty()) {
            prompt += "\n\nPrevious sentence (context only, do NOT translate it): " + prevContext;
        }
        prompt += "\n\nText: " + text;
        JSONObject body = new JSONObject();
        body.put(GENERATE_PROMPT_KEY, prompt);
        String resp = postBytes(HTTP_FAST, 2, WORKER_BASE + GENERATE_PATH,
                "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8));
        return extractText(resp);
    }

    private static String extractText(String raw) {
        try {
            String r = dig(new JSONTokener(raw).nextValue());
            if (r != null && !r.trim().isEmpty()) return r.trim();
        } catch (Exception ignored) {}
        return raw.trim();
    }

    private static String dig(Object o) {
        if (o instanceof String) return (String) o;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String[] keys = {"text", "result", "response", "output", "translation", "reply",
                    "content", "message", "choices", "candidates", "parts"};
            for (String k : keys) {
                if (j.has(k)) { String s = dig(j.opt(k)); if (s != null) return s; }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            if (a.length() > 0) return dig(a.opt(0));
        }
        return null;
    }

    private static byte[] toWav(byte[] pcm) {
        int len = pcm.length;
        ByteBuffer b = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + len);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * 2);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(len);
        b.put(pcm);
        return b.array();
    }

    private static class HttpStatusException extends IOException {
        final int code;
        HttpStatusException(int code, String body) { super("HTTP " + code + " " + body); this.code = code; }
    }

    private static String postBytes(String url, String contentType, byte[] body) throws IOException {
        return postBytes(HTTP, NET_ATTEMPTS, url, contentType, body);
    }

    private static String postBytes(OkHttpClient client, int attempts, String url, String contentType,
                                    byte[] body) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try { return postOnce(client, url, contentType, body); }
            catch (IOException e) {
                last = e;
                if (e instanceof UnknownHostException) throw e;
                if (e instanceof HttpStatusException) {
                    int code = ((HttpStatusException) e).code;
                    if (code < 500 && code != 429) throw e;
                }
                Log.w(TAG, "request attempt " + attempt + " failed: " + e);
                if (attempt == attempts) break;
                try { Thread.sleep(300L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last;
    }

    private static String postOnce(OkHttpClient client, String url, String contentType, byte[] body)
            throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, MediaType.get(contentType)))
                .build();
        try (Response r = client.newCall(req).execute()) {
            ResponseBody rb = r.body();
            String resp = rb == null ? "" : rb.string();
            if (!r.isSuccessful()) throw new HttpStatusException(r.code(), resp);
            return resp;
        }
    }

    private static String briefErr(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("unexpected end of stream") || m.contains("Unable to resolve host")
                || m.contains("timed out") || m.contains("Connection reset")
                || m.contains("Software caused connection abort")) {
            return "Network unstable, retrying next segment";
        }
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }

    private void shutdown() { cleanup(); stopForeground(true); stopSelf(); }

    private void cleanup() {
        running = false;
        main.removeCallbacksAndMessages(null);
        closeLocalTranslator();
        recording = false;
        if (micEngine) { micEngine = false; SpeechHostActivity.finishIfRunning(); }
        releaseSherpa();
        PcmFeed.close();
        instance = null;
        AudioRecord r = record; record = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }
        if (pulse != null) { pulse.cancel(); pulse = null; }
        MediaProjection mp = mediaProjection; mediaProjection = null;
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) {} }
        removePanel();
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
        }
        bubble = null;
        net.shutdownNow();
        netTr.shutdownNow();
    }
}
