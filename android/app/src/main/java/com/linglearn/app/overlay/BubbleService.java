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
import android.os.SystemClock;
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
import android.view.ViewGroup;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    // ✅ تغییر ۴: از 300 به 1400 (جمله‌های کامل‌تر، متنِ باکیفیت‌تر)
    private static final int SILENCE_CUT_MS = 1400;
    private static final int MIN_VOICED_MS = 600;
    private static final double SILENCE_RMS = 250.0;
    private static final int MAX_PENDING = 3;
    // If loud audio plays this long and the recognizer never produced text, switch to the server.
    private static final int ASR_WATCHDOG_VOICED_MS = 15000;
    private static final int MAX_TEXT_PENDING = 24;

    private static final long PANEL_HIDE_MS = 6000;
    private static final long PANEL_HIDE_MS_RECORDING = 60000;

    // ✅ تغییر ۵: از 80 به 400 (بار CPU سبک‌تر)
    private static final long PARTIAL_TRANSLATE_INTERVAL_MS = 400;
    private static final long REMOTE_PARTIAL_INTERVAL_MS = 900;
    private static final int MAX_HISTORY = 40;

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
    }

    public static void saveLangs(Context ctx, String target, String source) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (target != null && !target.isEmpty()) e.putString("target", target);
        if (source != null && !source.isEmpty()) e.putString("source", source);
        e.apply();
    }

    /** Comma-separated list of ALL translation target languages the user picked in the app (e.g. "fa,fr,ar"). */
    public static void saveTargets(Context ctx, String csv) {
        if (csv == null) return;
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("targets", csv.trim()).apply();
    }

    /** Called by the plugin when languages / display mode change while the bubble is running. */
    static void settingsChanged() {
        final BubbleService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            if (s.yt != null) s.yt.onSettingsChanged();
            s.prepareLocalTranslator();
            s.refreshHeader();
            for (Entry e : new ArrayList<>(s.history)) s.renderEntry(e);
            s.refreshLayout();
        });
    }

    /** Translation tone: "neutral" (default), "formal" or "casual". */
    public static void saveTone(Context ctx, String tone) {
        if (tone == null) return;
        String t = tone.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.equals("formal") && !t.equals("casual") && !t.equals("neutral")) return;
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("tone", t).apply();
    }

    /** Caption display mode: "both" (default, original + translation), "original" or "translation". */
    public static void saveDisplayMode(Context ctx, String mode) {
        if (mode == null) return;
        String m = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!m.equals("both") && !m.equals("original") && !m.equals("translation")) return;
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("displayMode", m).apply();
    }

    private String displayMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("displayMode", "both");
    }

    private String tone() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("tone", "neutral");
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  📺 حالت یوتیوب: زیرنویسِ ویدیوی در حالِ پخش (جدا از STT؛ منطقش در YtSubtitles/YtMedia)
    // ════════════════════════════════════════════════════════════════════════════

    /** اگه showBubble هنوز سرویس رو بالا نیاورده، درخواستِ روشن‌شدن همین‌جا نگه داشته می‌شه. */
    private static volatile boolean pendingYt = false;

    /** از BubblePlugin.ytSetEnabled: حالتِ یوتیوب رو روشن/خاموش می‌کنه. offsetMs = جابه‌جایی زمانِ زیرنویس. */
    static void setYoutubeEnabled(Context ctx, boolean on, long offsetMs) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("ytOffsetMs", offsetMs).apply();
        final BubbleService s = instance;
        if (s == null) { pendingYt = on; return; }
        s.main.post(() -> { if (on) s.startYoutube(true); else s.stopYoutube(true); });
    }

    static boolean youtubeActive() {
        BubbleService s = instance;
        return s != null && s.yt != null && s.yt.isActive();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService netTr = Executors.newFixedThreadPool(3);
    private final ExecutorService netPartial = Executors.newFixedThreadPool(2);
    private final AtomicInteger pending = new AtomicInteger(0);
    private final AtomicInteger textPending = new AtomicInteger(0);

    private WindowManager wm;
    private TextView bubble;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams bubbleLp;
    private int bubbleSize;

    private LinearLayout panel;
    private LinearLayout listBox;
    private HistoryScroll scroll;
    private TextView tvHeader;
    private TextView tvStatus;
    private WindowManager.LayoutParams panelLp;
    private boolean panelShown = false;
    private boolean userHidden = false;   // user closed the panel with the X: do not pop it up again until tapped
    private final ArrayList<Entry> history = new ArrayList<>();   // every sentence heard in this session (main thread only)
    private Entry live;                   // sentence currently being spoken (partial result)
    private YtSubtitles yt;               // 📺 حالت یوتیوب (null = خاموش)
    private TextView tvYt;                // دکمه‌ی ▶ در هدرِ پنل
    private TextView tvSave;              // دکمه‌ی 💾 (هم حالت یوتیوب، هم ترجمه‌ی زنده)
    private String liveKey = null;        // شناسه‌ی جلسه‌ی ترجمه‌ی زنده برای ذخیره (با پاک کردنِ تاریخچه ریست می‌شود)
    private long liveStartMs = 0;
    private final HashMap<Integer, Entry> ytEntries = new HashMap<>();   // شماره‌ی خطِ زیرنویس -> ردیفِ روی پنل
    private int reqCounter = 0;           // monotonically increasing translation request id
    private final Runnable hidePanel = this::removePanel;

    private ObjectAnimator pulse;
    private GestureDetector gestures;
    private boolean dragging = false;

    private MediaProjection mediaProjection;
    private AudioRecord record;
    private volatile boolean recording = false;

    private static volatile BubbleService instance;
    private volatile boolean micEngine = false;   // true = Android recognizer fed with SYSTEM audio
    private volatile SherpaEngine sherpaEngine;   // on-device Sherpa-ONNX recognizer (null = Google/server path)
    private volatile boolean gotAsrText = false;
    private volatile int voicedSinceText = 0;

    private Translator localTr;
    private String localTrKey = "";
    private volatile boolean localReady = false;
    private String lastPartialSrc = "";
    private long lastPartialAt = 0;
    private long lastRemotePartialAt = 0;
    private int partialSeq = 0;
    private int shownPartialSeq = 0;
    private boolean partialBusy = false;
    private int finalSeq = 0;
    private int shownFinalSeq = 0;
    private boolean partialScheduled = false;
    private volatile String prevFinalText = "";   // previous final sentence, sent as translation context
    // ✅ تغییر ۱: آخرین متن نهایی — برای جلوگیری از تکرار
    private volatile String lastFinalText = "";
    private final Runnable partialRunnable = () -> {
        partialScheduled = false;
        translatePartialNow();
    };

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
            showNotice(isFa() ? "مجوز ضبط صدا داده نشد" : "Audio capture permission denied");
        } else {
            running = true;
            addBubbleIfNeeded();
            prepareLocalTranslator();
            if (pendingYt) { pendingYt = false; main.post(() -> startYoutube(true)); }
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

    /** True when the app explicitly told us the spoken language (not "auto"). */
    private boolean srcExplicit() {
        String s = sourceLang();
        return s != null && !s.isEmpty() && !"auto".equals(s);
    }

    /**
     * Spoken language actually used. "auto" is slow (server transcription + LLM translation),
     * so for a language-learning app we assume English unless the target itself is English.
     * Pass sourceLang from the web app to override.
     */
    private String effectiveSource() {
        if (srcExplicit()) return sourceLang();
        return "en".equals(targetLang()) ? "auto" : "en";
    }

    /**
     * Every target language the user picked, minus the language being spoken (translating English
     * to English is pointless). Falls back to the UI language for old callers that send no list.
     */
    private List<String> activeTargets() {
        String csv = getSharedPreferences(PREFS, MODE_PRIVATE).getString("targets", "");
        ArrayList<String> out = new ArrayList<>();
        if (csv != null && !csv.isEmpty()) {
            for (String t : csv.split(",")) {
                t = t.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        if (out.isEmpty()) out.add(targetLang());
        String src = effectiveSource();
        if (src != null && !"auto".equals(src)) out.remove(src);
        return out;
    }

    private String primaryTarget() {
        List<String> a = activeTargets();
        return a.isEmpty() ? null : a.get(0);
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
                if (panelShown) { userHidden = true; removePanel(); }
                else {
                    userHidden = false;
                    if (history.isEmpty()) {
                        showNotice(isFa()
                                ? "نگه‌داشتن: شروع/توقف ضبط  ·  دوبار لمس: بستن  ·  یک‌بار لمس: تاریخچه"
                                : "Long-press: start/stop  ·  Double-tap: close  ·  Tap: history");
                    } else showPanel();
                }
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


    // ════════════════════════════════════════════════════════════════════════════
    //  Panel: scrollable history of every sentence + one translation line per target language
    // ════════════════════════════════════════════════════════════════════════════

    /** One recognised sentence and its translations (one per target language). */
    private static final class Entry {
        String src = "";
        final long createdAt = System.currentTimeMillis();
        final HashMap<String, String> tr = new HashMap<>();        // lang -> shown text ("…" = pending)
        final HashMap<String, String> trSrc = new HashMap<>();     // lang -> source text that translation was made from
        final HashMap<String, Integer> applied = new HashMap<>();  // lang -> id of the newest request applied (drops stale answers)
        LinearLayout box;
        LinearLayout rowsBox;
        TextView tvSrc;
        final HashMap<String, LinearLayout> rowBox = new HashMap<>();
        final HashMap<String, TextView> rowText = new HashMap<>();
    }

    /** ScrollView with a max height that follows new text unless the user scrolled up to read older lines. */
    private static final class HistoryScroll extends ScrollView {
        int maxHeightPx = Integer.MAX_VALUE;
        boolean atBottom = true;

        HistoryScroll(Context c) { super(c); }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (atBottom) {
                View c = getChildAt(0);
                if (c != null) scrollTo(0, Math.max(0, c.getHeight() - getHeight()));
            }
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            View c = getChildAt(0);
            if (c != null) atBottom = c.getHeight() - (getHeight() + getScrollY()) < 60;
        }
    }

    private void ensurePanel() {
        if (panel != null) return;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(6), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F01C2541"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COLOR_GOLD);
        panel.setBackground(bg);

        // header: title + languages, clear-history, close
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        tvHeader = new TextView(this);
        tvHeader.setTextColor(COLOR_GOLD);
        tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvHeader.setTypeface(Typeface.DEFAULT_BOLD);
        tvHeader.setSingleLine(true);
        header.addView(tvHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvYt = headerButton("\u25B6", v -> toggleYoutube());
        header.addView(tvYt);
        tvSave = headerButton("\uD83D\uDCBE", v -> saveCurrent());
        header.addView(tvSave);
        updateYtButton();
        header.addView(headerButton("\uD83D\uDDD1", v -> clearHistory()));
        header.addView(headerButton("\u2715", v -> { userHidden = true; removePanel(); }));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new HistoryScroll(this);
        scroll.maxHeightPx = Math.max(dp(170), (int) (screenH() * 0.42f));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvStatus = new TextView(this);
        tvStatus.setTextColor(Color.parseColor("#C8CCD8"));
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvStatus.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        tvStatus.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        tvStatus.setPadding(0, dp(4), 0, 0);
        tvStatus.setVisibility(View.GONE);
        panel.addView(tvStatus);

        // touchable (history scrolls, buttons work) but touches outside the panel still reach the app below
        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
    }

    private TextView headerButton(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private void refreshHeader() {
        if (tvHeader == null) return;
        StringBuilder sb = (yt != null && yt.isActive())
                ? new StringBuilder(msg("\u25B6 زیرنویس یوتیوب", "\u25B6 YouTube subtitles"))
                : new StringBuilder(msg("\uD83C\uDF99 ترجمه‌ی زنده", "\uD83C\uDF99 Live translation"));
        List<String> ts = activeTargets();
        for (int i = 0; i < ts.size(); i++) {
            sb.append(i == 0 ? "  \u00B7  " : " / ").append(ts.get(i).toUpperCase(Locale.ROOT));
        }
        tvHeader.setText(sb.toString());
    }

    private void computePanelPos() {
        panelLp.width = screenW() - dp(24);
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

    private void refreshLayout() {
        if (!panelShown || panel == null || wm == null) return;
        computePanelPos();
        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
    }

    private void showPanel() {
        if (wm == null || bubble == null) return;
        ensurePanel();
        refreshHeader();
        computePanelPos();
        try {
            if (!panelShown) { wm.addView(panel, panelLp); panelShown = true; }
            else wm.updateViewLayout(panel, panelLp);
        } catch (Exception e) { Log.w(TAG, "panel show failed", e); }
        // history stays on screen while recording / while there is history; a lone notice fades out
        main.removeCallbacks(hidePanel);
        if (!recording && history.isEmpty()) main.postDelayed(hidePanel, PANEL_HIDE_MS);
    }

    private void removePanel() {
        main.removeCallbacks(hidePanel);
        if (panelShown && panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
        }
        panelShown = false;
        if (tvStatus != null) { tvStatus.setText(""); tvStatus.setVisibility(View.GONE); }
    }

    private void clearStatus() {
        if (tvStatus != null) { tvStatus.setText(""); tvStatus.setVisibility(View.GONE); }
    }

    /** Short status line under the history (listening…, errors, hints). */
    private void showNotice(String m) {
        if (wm == null || bubble == null) return;
        ensurePanel();
        boolean has = m != null && !m.isEmpty();
        tvStatus.setText(has ? m : "");
        tvStatus.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!userHidden) showPanel();
    }

    private void afterChange() {
        if (!userHidden) showPanel();
        else refreshLayout();
    }

    private void clearHistory() {
        history.clear();
        liveKey = null;
        live = null;
        if (listBox != null) listBox.removeAllViews();
        lastFinalText = "";
        prevFinalText = "";
        lastPartialSrc = "";
        refreshLayout();
    }

    private Entry newEntry() {
        ensurePanel();
        final Entry e = new Entry();
        e.box = new LinearLayout(this);
        e.box.setOrientation(LinearLayout.VERTICAL);
        e.box.setPadding(0, dp(4), 0, dp(8));

        e.tvSrc = new TextView(this);
        e.tvSrc.setTextColor(Color.parseColor("#C8CCD8"));
        e.tvSrc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.tvSrc.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        e.tvSrc.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);

        e.rowsBox = new LinearLayout(this);
        e.rowsBox.setOrientation(LinearLayout.VERTICAL);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.topMargin = dp(8);

        e.box.addView(e.tvSrc);
        e.box.addView(e.rowsBox);
        e.box.addView(divider, dlp);
        listBox.addView(e.box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        history.add(e);
        while (history.size() > MAX_HISTORY) {
            Entry old = history.get(0);
            if (old == live) break;
            history.remove(0);
            listBox.removeView(old.box);
        }
        return e;
    }

    private void makeRow(Entry e, String lang) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // language tag always on the left
        row.setPadding(0, dp(3), 0, 0);
        row.setVisibility(View.GONE);

        TextView tag = new TextView(this);
        tag.setText(lang.toUpperCase(Locale.ROOT));
        tag.setTextColor(COLOR_GOLD);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setMinWidth(dp(26));
        tag.setPadding(0, dp(5), dp(6), 0);

        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);

        row.addView(tag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        e.rowsBox.addView(row);
        e.rowBox.put(lang, row);
        e.rowText.put(lang, tv);
    }

    /** Draws one sentence according to the display mode (both / original / translation). */
    private void renderEntry(Entry e) {
        if (e == null || e.box == null) return;
        String mode = displayMode();
        List<String> ts = activeTargets();
        boolean anyTr = false;
        for (String t : ts) {
            String v = e.tr.get(t);
            if (v != null && !v.isEmpty() && !v.equals("…")) { anyTr = true; break; }
        }
        boolean showSrc, showTr;
        if ("original".equals(mode)) { showSrc = true; showTr = false; }
        else if ("translation".equals(mode)) { showTr = !ts.isEmpty(); showSrc = ts.isEmpty() || !anyTr; }
        else { showSrc = true; showTr = !ts.isEmpty(); }

        e.tvSrc.setText(e.src);
        e.tvSrc.setVisibility(showSrc && !e.src.isEmpty() ? View.VISIBLE : View.GONE);

        for (String t : ts) {
            if (!e.rowBox.containsKey(t)) makeRow(e, t);   // created in target order -> stable order on screen
            LinearLayout row = e.rowBox.get(t);
            String v = e.tr.get(t);
            boolean has = v != null && !v.isEmpty();
            row.setVisibility(showTr && has ? View.VISIBLE : View.GONE);
            if (has) e.rowText.get(t).setText(v);
        }
        for (Map.Entry<String, LinearLayout> r : e.rowBox.entrySet()) {
            if (!ts.contains(r.getKey())) r.getValue().setVisibility(View.GONE);
        }
    }

    private void setTranslation(Entry e, String lang, String text, String fromSrc) {
        e.tr.put(lang, text == null ? "" : text);
        e.trSrc.put(lang, fromSrc == null ? "" : fromSrc);
        renderEntry(e);
        afterChange();
    }

    private void setTranslationSeq(Entry e, String lang, String text, String fromSrc, int id) {
        Integer a = e.applied.get(lang);
        if (a != null && id < a) return;     // an older answer must never overwrite a newer one
        e.applied.put(lang, id);
        setTranslation(e, lang, text, fromSrc);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Live recognition -> translation into ALL target languages
    // ════════════════════════════════════════════════════════════════════════════

    private void onPartialText(final String text) {
        if (wm == null || bubble == null || text == null || text.isEmpty()) return;
        if (live == null) live = newEntry();
        live.src = text;
        clearStatus();
        renderEntry(live);
        afterChange();

        List<String> ts = activeTargets();
        if (ts.isEmpty()) return;
        lastPartialSrc = text;
        // first language: instant on-device translation when its model is ready; the rest: free online services
        boolean localCovers = localReady && localTr != null;
        if (localCovers) {
            long wait = lastPartialAt + PARTIAL_TRANSLATE_INTERVAL_MS - SystemClock.uptimeMillis();
            if (wait <= 0) translatePartialNow();
            else if (!partialScheduled) {
                partialScheduled = true;
                main.postDelayed(partialRunnable, wait);
            }
        }
        remotePartialTranslate(text, localCovers ? new ArrayList<>(ts.subList(1, ts.size())) : ts);
    }

    private void translatePartialNow() {
        final String src = lastPartialSrc;
        final Translator tr = localTr;
        final List<String> ts = activeTargets();
        final Entry target = live;
        if (src.isEmpty() || tr == null || !localReady || partialBusy || ts.isEmpty() || target == null) return;
        final String lang = ts.get(0);
        partialBusy = true;
        lastPartialAt = SystemClock.uptimeMillis();
        final int seq = ++partialSeq;
        tr.translate(src).addOnCompleteListener(task -> {
            partialBusy = false;
            if (task.isSuccessful() && recording && seq > shownPartialSeq && live == target) {
                shownPartialSeq = seq;
                setTranslation(target, lang, task.getResult(), src);
            }
            if (recording && !lastPartialSrc.isEmpty() && !lastPartialSrc.equals(src)) {
                translatePartialNow();
            }
        });
    }

    private void remotePartialTranslate(final String text, final List<String> langs) {
        if (langs.isEmpty() || text == null || text.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRemotePartialAt < REMOTE_PARTIAL_INTERVAL_MS) return;
        if (!hasInternet()) return;
        final Entry target = live;
        if (target == null) return;
        lastRemotePartialAt = now;
        final String srcLang = effectiveSource();
        for (final String lang : langs) {
            final int id = ++reqCounter;
            try {
                netPartial.execute(() -> {
                    final String out;
                    try { out = FreeTranslator.translate(HTTP_FAST, text, srcLang, lang); }
                    catch (Exception ex) { return; }   // partials are best effort
                    main.post(() -> {
                        if (!recording || live != target) return;
                        setTranslationSeq(target, lang, out, text, id);
                    });
                });
            } catch (Exception ignored) {}
        }
    }

    private void onFinalText(final String text) {
        if (wm == null || bubble == null || text == null || text.trim().isEmpty()) return;
        // the server sometimes returns the same final text several times - show it once
        if (text.equals(lastFinalText)) {
            Log.d(TAG, "skipping duplicate final text");
            return;
        }
        lastFinalText = text;

        partialSeq++; shownPartialSeq = partialSeq;
        main.removeCallbacks(partialRunnable); partialScheduled = false;
        lastPartialSrc = "";
        final String ctxPrev = prevFinalText;
        prevFinalText = text;
        clearStatus();

        final Entry e = live != null ? live : newEntry();
        live = null;                      // this sentence is finished; the next partial starts a new line
        e.src = text;

        final List<String> ts = activeTargets();
        if (ts.isEmpty()) { renderEntry(e); afterChange(); return; }

        final String srcLang = effectiveSource();
        final boolean online = hasInternet();
        final String offlineMsg = msg("ترجمه در دسترس نیست (آفلاین)", "No translation (offline)");
        for (int i = 0; i < ts.size(); i++) {
            final String lang = ts.get(i);
            String have = e.tr.get(lang);
            boolean haveGood = have != null && !have.isEmpty() && !have.equals("…");
            if (haveGood && text.equals(e.trSrc.get(lang))) continue;   // live translation already matches the final text
            if (!haveGood) e.tr.put(lang, "…");
            if (!online) {
                final Translator tr = localTr;
                if (i == 0 && localReady && tr != null) {
                    tr.translate(text)
                            .addOnSuccessListener(out -> setTranslation(e, lang, out, text))
                            .addOnFailureListener(x -> setTranslation(e, lang, offlineMsg, text));
                } else {
                    e.tr.put(lang, offlineMsg);
                }
                continue;
            }
            submitTranslate(e, text, srcLang, lang, ctxPrev);
        }
        renderEntry(e);
        afterChange();
    }

    private void submitTranslate(final Entry e, final String text, final String srcLang,
                                 final String lang, final String ctxPrev) {
        if (textPending.get() >= MAX_TEXT_PENDING) {
            Log.w(TAG, "dropping translation (backlog)");
            e.tr.put(lang, "");
            return;
        }
        final int id = ++reqCounter;
        textPending.incrementAndGet();
        try {
            netTr.execute(() -> {
                String out;
                try { out = translateOne(text, srcLang, lang, ctxPrev); }
                catch (Exception ex) { Log.w(TAG, "translate failed", ex); out = netErrText(ex); }
                final String shown = out;
                textPending.decrementAndGet();
                main.post(() -> setTranslationSeq(e, lang, shown, text, id));
            });
        } catch (Exception ex) { textPending.decrementAndGet(); }
    }

    /** Free services first (Google -> MyMemory -> Lingva -> Libre); the app's AI worker is the last resort. */
    private String translateOne(String text, String srcLang, String tgt, String ctxPrev) throws Exception {
        try {
            return FreeTranslator.translate(HTTP_FAST, text, srcLang, tgt);
        } catch (Exception freeErr) {
            Log.w(TAG, "free translators failed (" + freeErr + "); using the app AI worker");
        }
        return translate(text, ctxPrev, tgt);
    }

    // ───────── 📺 حالت یوتیوب: روشن/خاموش و اتصال به پنل ─────────

    private void toggleYoutube() {
        if (yt != null && yt.isActive()) stopYoutube(true);
        else startYoutube(true);
    }

    private void updateYtButton() {
        if (tvYt == null) return;
        boolean on = yt != null && yt.isActive();
        tvYt.setTextColor(on ? COLOR_GOLD : Color.WHITE);
        tvYt.setAlpha(on ? 1f : 0.6f);
        if (tvSave != null) tvSave.setVisibility(View.VISIBLE);
    }

    private void saveCurrent() {
        if (yt != null && yt.isActive()) saveYoutube();
        else saveLive();
    }

    /** 💾 ترجمه‌ی زنده (صوتِ پخش‌شده از هر پلیر/برنامه): همه‌ی جمله‌های تاریخچه + ترجمه‌ها → «داستان‌های ذخیره‌شده». */
    private void saveLive() {
        try {
            final String offFa = "ترجمه در دسترس نیست (آفلاین)", offEn = "No translation (offline)";
            final List<String> ts = activeTargets();
            if (history.isEmpty()) { showNotice(msg("هنوز چیزی برای ذخیره نیست", "Nothing to save yet")); return; }
            if (liveKey == null) {
                liveStartMs = history.get(0).createdAt;
                liveKey = "live-" + liveStartMs;
            }
            JSONArray lines = new JSONArray();
            for (Entry e : new ArrayList<>(history)) {
                if (e == live || e.src == null || e.src.trim().isEmpty()) continue;   // جمله‌ی نیمه‌کاره/خالی
                JSONObject tr = new JSONObject();
                for (String t : ts) {
                    String v = e.tr.get(t);
                    if (v == null || v.trim().isEmpty() || v.equals("…") || v.equals(offFa) || v.equals(offEn)) continue;
                    tr.put(t, v);
                }
                lines.put(new JSONObject().put("t", Math.max(0, e.createdAt - liveStartMs) / 1000.0)
                        .put("s", e.src.trim()).put("tr", tr));
            }
            if (lines.length() == 0) { showNotice(msg("هنوز چیزی برای ذخیره نیست", "Nothing to save yet")); return; }
            long now = System.currentTimeMillis();
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String src = effectiveSource();
            JSONObject item = new JSONObject()
                    .put("key", liveKey)
                    .put("live", true)
                    .put("title", msg("ترجمه‌ی زنده", "Live translation"))
                    .put("lang", src == null || "auto".equals(src) ? "en" : src)
                    .put("targets", new JSONArray(ts))
                    .put("rev", now)
                    .put("savedAt", fmt.format(new java.util.Date(now)))
                    .put("lines", lines);
            boolean ok = YtSaved.add(this, item);
            showNotice(ok
                    ? msg("ذخیره شد ✓ (" + lines.length() + " خط) — در «داستان‌های ذخیره‌شده» اپ", "Saved ✓ (" + lines.length() + " lines) — see Saved stories in the app")
                    : msg("ذخیره نشد", "Save failed"));
        } catch (Throwable t) {
            showNotice(msg("ذخیره نشد", "Save failed"));
        }
    }

    /** 💾 زیرنویس + ترجمه‌ی نمایش‌داده‌شده + لینکِ ویدیو → «داستان‌های ذخیره‌شده»ی اپ. */
    private void saveYoutube() {
        YtSubtitles engine = yt;
        if (engine == null || !engine.isActive()) return;
        try {
            JSONObject snap = engine.snapshot();
            if (snap == null) {
                showNotice(msg("هنوز زیرنویسی برای ذخیره نیست", "Nothing to save yet"));
                return;
            }
            int n = snap.getJSONArray("lines").length();
            boolean ok = YtSaved.add(this, snap);
            showNotice(ok
                    ? msg("ذخیره شد ✓ (" + n + " خط) — در «داستان‌های ذخیره‌شده» اپ", "Saved ✓ (" + n + " lines) — see Saved stories in the app")
                    : msg("ذخیره نشد", "Save failed"));
        } catch (Throwable t) {
            showNotice(msg("ذخیره نشد", "Save failed"));
        }
    }

    private void startYoutube(boolean openSettingsIfNeeded) {
        if (wm == null || bubble == null) return;
        if (yt != null && yt.isActive()) return;
        userHidden = false;
        if (!YtMedia.hasAccess(this)) {
            showNotice(msg("برای زیرنویس یوتیوب، «دسترسی به اعلان‌ها» را برای این برنامه روشن کن و دوباره ▶ را بزن",
                    "For YouTube subtitles, turn on “Notification access” for this app, then tap ▶ again"));
            if (openSettingsIfNeeded) YtMedia.openAccessSettings(this);
            return;
        }
        if (recording) stopRecording();     // دو منبعِ هم‌زمان روی یک پنل نباشه
        YtSubtitles engine = new YtSubtitles(this, ytHost, WORKER_BASE, HTTP_FAST);
        engine.setOffsetMs(getSharedPreferences(PREFS, MODE_PRIVATE).getLong("ytOffsetMs", 0L));
        if (!engine.start()) {
            engine.stop();
            showNotice(msg("دسترسی به اعلان‌ها هنوز فعال نیست", "Notification access is not enabled yet"));
            if (openSettingsIfNeeded) YtMedia.openAccessSettings(this);
            return;
        }
        yt = engine;
        ytEntries.clear();
        refreshHeader();
        updateYtButton();
    }

    private void stopYoutube(boolean notify) {
        YtSubtitles engine = yt;
        yt = null;
        ytEntries.clear();
        if (engine != null) { try { engine.stop(); } catch (Throwable ignored) {} }
        if (engine == null) return;
        refreshHeader();
        updateYtButton();
        if (notify) showNotice(msg("زیرنویس یوتیوب خاموش شد", "YouTube subtitles off"));
    }

    private final YtSubtitles.Host ytHost = new YtSubtitles.Host() {
        @Override public List<String> targets() { return activeTargets(); }
        @Override public String requestedSource() { return effectiveSource(); }
        @Override public String tone() { return BubbleService.this.tone(); }
        @Override public String langName(String code) {
            String n = LANG_NAMES.get(code);
            return n != null ? n : code;
        }
        @Override public boolean fa() { return isFa(); }

        @Override public void notice(String m) {
            if (m == null || m.isEmpty()) { clearStatus(); return; }
            showNotice(m);
        }

        @Override public void show(int idx, String src, Map<String, String> tr, boolean reset) {
            if (wm == null || bubble == null) return;
            if (reset) { clearHistory(); ytEntries.clear(); }
            clearStatus();
            Entry e = newEntry();
            e.src = src;
            for (Map.Entry<String, String> x : tr.entrySet()) {
                e.tr.put(x.getKey(), x.getValue());
                e.trSrc.put(x.getKey(), src);
            }
            ytEntries.put(idx, e);
            for (java.util.Iterator<Integer> it = ytEntries.keySet().iterator(); it.hasNext(); ) {
                if (it.next() < idx - 80) it.remove();
            }
            renderEntry(e);
            afterChange();
        }

        @Override public void update(int idx, String lang, String text) {
            Entry e = ytEntries.get(idx);
            if (e == null) return;
            setTranslation(e, lang, text, e.src);
        }
    };

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
        userHidden = false;
        if (mediaProjection == null) {
            Intent i = new Intent(this, ProjectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); }
            catch (Exception e) { Log.e(TAG, "cannot launch ProjectionActivity", e); showNotice("⚠ " + briefErr(e)); }
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
            showNotice("⚠ " + briefErr(e));
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
        // ✅ تغییر ۲: ریست کردن lastFinalText
        lastFinalText = "";
        partialBusy = false;
        shownPartialSeq = partialSeq;
        lastPartialSrc = ""; live = null;
        speechHostRestarts = 0;
        gotAsrText = false; voicedSinceText = 0;
        final String src = effectiveSource();
        if (src != null && !"auto".equals(src) && SherpaModelManager.isAvailable(src)) {
            // Sherpa path: feedLoop feeds the OnlineStream (no SpeechHostActivity)
            if (startSherpaEngine(src)) return;
        }
        boolean micOk = Build.VERSION.SDK_INT >= 33
                && src != null && !src.isEmpty() && !"auto".equals(src)
                && SpeechRecognizer.isRecognitionAvailable(this);
        if (micOk && startMicEngine(src)) return;
        beginCapture();
    }

    /**
     * Recognizer engine: captures the phone's PLAYBACK audio (not the microphone), converts it to
     * 16 kHz mono PCM16 and streams it to SpeechRecognizer through PcmFeed / EXTRA_AUDIO_SOURCE.
     */
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
            showNotice(msg("🎙 گوش‌دادن به صدای سیستم…", "🎙 Listening to system audio…"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot start recognizer engine", e);
            micEngine = false; recording = false; setRecordingUi(false);
            PcmFeed.close();
            if (rec != null) { record = null; try { rec.stop(); } catch (Exception ignored) {} try { rec.release(); } catch (Exception ignored) {} }
            return false;
        }
    }

    /**
     * Sherpa engine: same playback capture + feedLoop as the recognizer engine, but the 16 kHz PCM
     * goes to SherpaEngine instead of PcmFeed. Returns false (-> Google/server path) if the model
     * is not downloaded or the capture cannot start.
     */
    private boolean startSherpaEngine(final String src) {
        if (SherpaModelManager.getModelDir(this, src) == null) return false;   // not downloaded yet
        AudioRecord rec = null;
        try {
            rec = buildPlaybackRecord();
            if (rec == null) return false;
            record = rec;
            micEngine = true;          // so feedLoop runs and asr* callbacks are accepted
            recording = true;
            setRecordingUi(true);
            rec.startRecording();
            final AudioRecord fr = rec;
            new Thread(() -> runSherpa(fr, src), "bubble-feed").start();
            showNotice(msg("🎙 گوش‌دادن به صدای سیستم (آفلاین)…", "🎙 Listening to system audio (offline)…"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot start sherpa engine", e);
            micEngine = false; recording = false; setRecordingUi(false);
            if (rec != null) { record = null; try { rec.stop(); } catch (Exception ignored) {} try { rec.release(); } catch (Exception ignored) {} }
            return false;
        }
    }

    /** Feed-thread entry for Sherpa: loads the model off the main thread, then runs feedLoop. */
    private void runSherpa(final AudioRecord rec, final String src) {
        SherpaEngine eng = SherpaEngine.create(getApplicationContext(), src);
        boolean stillActive = recording && micEngine && record == rec;
        if (eng != null && stillActive) {
            sherpaEngine = eng;
            // stop/cleanup may have run between the check and the assignment
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
            feedLoop(rec);   // releases rec in its finally
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

    /** Playback-capture AudioRecord at the best native rate (48 kHz, then 44.1 kHz, then 16 kHz). */
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
        final int frame = Math.max(1, rate / 50);                   // 20 ms of input
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

    /** Box-filter downsample of mono PCM16 to 16 kHz little-endian bytes. Returns byte count. */
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

    /** Leaves the recognizer engine and uses the server (chunked transcribe) path instead. */
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
        if (notice != null) showNotice(notice);
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
            showNotice(msg("🎙 در حال گوش‌دادن به صدای سیستم…", "🎙 Listening to system audio…"));
            new Thread(() -> captureLoop(rec), "bubble-capture").start();
        } catch (Exception e) {
            Log.e(TAG, "startCapture failed", e);
            recording = false; setRecordingUi(false);
            showNotice("⚠ " + briefErr(e));
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
        showNotice(msg("ضبط متوقف شد", "Stopped"));
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
        s.showNotice(s.msg("مجوز میکروفون لازم است", "Microphone permission required"));
    }

    static void asrClosed() {
        BubbleService s = instance;
        if (s == null || !s.micEngine) return;
        s.restartSpeechHost();
    }

    private void prepareLocalTranslator() {
        String src = TranslateLanguage.fromLanguageTag(effectiveSource());
        String primary = primaryTarget();
        String tgt = primary == null ? null : TranslateLanguage.fromLanguageTag(primary);
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
                    if (tr == localTr) localReady = true;
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


    private void processSegment(byte[] pcm) {
        try {
            final String text = transcribe(pcm);
            if (text.isEmpty()) return;
            main.post(() -> onFinalText(text));
        } catch (Exception e) {
            Log.w(TAG, "transcribe failed", e);
            final String m = netErrText(e);
            main.post(() -> showNotice(m));
        }
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


    private String translate(String text, String prevContext, String target) throws Exception {
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
        pendingYt = false;
        stopYoutube(false);
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
        netPartial.shutdownNow();
    }
}
