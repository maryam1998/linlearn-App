package com.linglearn.app.overlay;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaPlayer;
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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import java.util.Arrays;
import java.util.HashSet;
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
    private static final int MAX_SEG_MS = 8000;           // قبلاً ۳۵۰۰: صدا وسطِ جمله بریده می‌شد؛ حالا فقط در جمله‌های خیلی بلند
    private static final int SRV_PARTIAL_EVERY_MS = 500;     // حالت سرور: هر ۵۰۰ms صدای جمع‌شده برای پیش‌نمایشِ زنده فرستاده می‌شود
    private static final int SRV_PARTIAL_MIN_VOICED_MS = 350;
    private static final long SRV_PAUSE_COMMIT_MS = 1600;
    private static final long TAIL_MIN_MS = 220;            // ترجمه‌ی زنده‌ی انتهای جمله حداکثر هر ~۲۲۰ms
    // ✅ تغییر ۴: از 300 به 1400 (جمله‌های کامل‌تر، متنِ باکیفیت‌تر)
    private static final int SILENCE_CUT_MS = 1000;       // مکثِ کوتاهِ نفس‌گیری قطعه را نبُرد
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
    private static final int MAX_HISTORY = 400;

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
    private final ExecutorService netPartial = Executors.newFixedThreadPool(4);
    private final ExecutorService sttPartialEx = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean srvPartialBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile int srvSegId = 0;
    private final AtomicInteger pending = new AtomicInteger(0);
    private final AtomicInteger textPending = new AtomicInteger(0);

    private WindowManager wm;
    private BubbleView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private int bubbleSize;

    private LinearLayout panel;
    private LinearLayout listBox;
    private HistoryScroll scroll;
    private TextView tvHeader;
    private TextView tvStatus;
    private WindowManager.LayoutParams panelLp;
    private boolean panelShown = false;
    private boolean panelFree = false;    // کاربر کادر را با لمسِ سربرگ جابه‌جا کرده → دیگر به حباب چسبیده نیست
    private int panelFreeX = 0, panelFreeY = 0;
    private boolean userHidden = false;   // user closed the panel with the X: do not pop it up again until tapped
    private final ArrayList<Entry> history = new ArrayList<>();   // every sentence heard in this session (main thread only)
    private Entry live;                   // sentence currently being spoken (partial result)
    private YtSubtitles yt;               // 📺 حالت یوتیوب (null = خاموش)
    private TextView tvYt;                // دکمه‌ی ▶ در هدرِ پنل
    private TextView tvSave;              // دکمه‌ی 💾 (هم حالت یوتیوب، هم ترجمه‌ی زنده)
    private String lastSourceKey = "";     // پلیر|عنوانِ آخرین منبعِ صدا؛ برای نگه داشتنِ تاریخچه وقتی همان فایل ادامه پیدا می‌کند
    private String liveKey = null;        // شناسه‌ی جلسه‌ی ترجمه‌ی زنده برای ذخیره (با پاک کردنِ تاریخچه ریست می‌شود)
    private long liveStartMs = 0;
    private boolean ytListMode = false;       // 📺 لیستِ کاملِ زیرنویس در پنل است (سقفِ تاریخچه اعمال نشود)
    private int ytListGen = 0;
    private int ytCur = -1;                   // جمله‌ی پررنگ‌شده
    private int ytWanted = -1;                // جمله‌ی جاری در حینِ ساختنِ لیست
    private boolean ytWantedForce = false;
    private boolean ytRefreshQueued = false;
    private static final float YT_DIM = 0.42f;
    private final HashMap<Integer, Entry> ytEntries = new HashMap<>();   // شماره‌ی خطِ زیرنویس -> ردیفِ روی پنل
    private int reqCounter = 0;           // monotonically increasing translation request id
    private final Runnable hidePanel = this::removePanel;

    // ── نمایش/پنهان‌شدنِ خودکارِ کادر ───────────────────────────────────────
    // وقتی چیزی پخش نمی‌شه (ویدیو pause، صدا قطع، یا ضبط متوقف شد) کادر خودش جمع می‌شه و
    // با شروعِ دوباره‌ی پخش خودش برمی‌گرده. بستنِ دستی با ▾ همچنان تا لمسِ حباب پنهان می‌مونه.
    private static final long IDLE_HIDE_MS = 1000;   // بعد از قطعِ صدا/ویدیو کادر تقریباً بلافاصله بسته می‌شه
    private volatile long lastVoiceMs = 0;   // آخرین لحظه‌ای که صدایی از سیستم شنیده شد
    private volatile long lastTextMs = 0;    // آخرین لحظه‌ای که متنِ تشخیص‌داده‌شده رسید
    private volatile long lastActiveMs = 0;  // آخرین لحظه‌ای که «چیزی در حالِ پخش» یا کاربر مشغول بود
    private boolean idleHidden = false;      // کادر را خودکار (نه کاربر) بسته‌ایم
    private final Runnable idleRunnable = this::idleTick;

    private boolean somethingPlaying(long now) {
        YtSubtitles y = yt;
        if (y != null && y.isPlaying()) return true;
        return recording && (now - lastVoiceMs < 1200 || now - lastTextMs < 2000);
    }

    private void idleTick() {
        main.removeCallbacks(idleRunnable);
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        boolean active = somethingPlaying(now);
        boolean busy = (wordCard != null && wordCard.getVisibility() == View.VISIBLE)
                || (scroll != null && (scroll.frozen || SystemClock.uptimeMillis() - scroll.lastUserTouch < 6000));
        if (active || busy) lastActiveMs = now;
        if (active && idleHidden) {
            reopenFromIdle();
        } else if (!active && !busy && panelShown && !userHidden && now - lastActiveMs > IDLE_HIDE_MS) {
            idleHidden = true;
            userHidden = true;
            final boolean wasFree = panelFree;   // جای دستیِ کادر با بسته‌شدنِ خودکار گم نشه
            removePanel();
            panelFree = wasFree;
        }
        main.postDelayed(idleRunnable, 700);
    }

    /** کادری که خودکار بسته شده بود دوباره باز می‌شه و (در حالتِ یوتیوب) سرِ جمله‌ی در حالِ پخش می‌ره. */
    private void reopenFromIdle() {
        idleHidden = false;
        userHidden = false;
        lastActiveMs = SystemClock.elapsedRealtime();
        // پلیرِ همان فایل (کتابِ صوتی/پادکست…) ادامه پیدا کرد → تاریخچه می‌مونه؛ منبعِ دیگه یا برنامه‌ی بدونِ MediaSession → پاک می‌شه
        if (yt == null && !history.isEmpty()) {
            String key = "";
            try {
                YtMedia.Now np = YtMedia.nowPlaying(this);
                if (np != null && np.title != null && !np.title.isEmpty()) key = np.pkg + "|" + np.title;
            } catch (Throwable ignored) {}
            if (key.isEmpty() || !key.equals(lastSourceKey)) clearHistory();
            lastSourceKey = key;
        } else if (yt == null) {
            try {
                YtMedia.Now np = YtMedia.nowPlaying(this);
                lastSourceKey = (np != null && np.title != null && !np.title.isEmpty()) ? np.pkg + "|" + np.title : "";
            } catch (Throwable ignored) { lastSourceKey = ""; }
        }
        showPanel();
        if (yt != null && ytCur >= 0) {
            ytWantedForce = true;
            main.postDelayed(() -> applyYtCurrent(false), 250);
        }
    }

    /** متنِ تازه رسید: اگه کادر را خودکار بسته بودیم، دوباره اجازه‌ی نمایش می‌دیم. */
    private void wakeFromIdle() {
        lastTextMs = SystemClock.elapsedRealtime();
        if (idleHidden) { idleHidden = false; userHidden = false; }
    }

    private ObjectAnimator pulse;
    private boolean dragging = false;

    private MediaProjection mediaProjection;
    private AudioRecord record;
    private volatile boolean recording = false;

    private static volatile BubbleService instance;
    private volatile boolean micEngine = false;   // true = Android recognizer fed with SYSTEM audio
    private volatile PcmSink sherpaEngine;   // on-device Sherpa-ONNX recognizer (null = Google/server path)
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
    // ── ترجمه‌ی زنده‌ی نرم و پایدار ──
    private static final long SRC_RENDER_MS = 30;       // متنِ اصلی حداکثر هر ۲۲۰ms به‌روز می‌شود (نه با هر تکانِ تشخیص)
    private static final long PAUSE_COMMIT_MS = 700;     // با ۷۰۰ms سکوت، باقیِ جمله هم ترجمه می‌شود
    private static final long PAUSE_COMMIT_SONG_MS = 6000;   // حالت آهنگ: پیش‌نمایش‌ها هر چند ثانیه می‌آن، پس زود commit نکن
    private static final int TAIL_GUARD_WORDS = 2;       // ۳ کلمه‌ی آخر هنوز ممکن است توسطِ تشخیص عوض شوند
    private static final int CHUNK_MIN_WORDS = 3;
    private static final int CHUNK_MAX_WORDS = 9;
    private static final String CHUNK_FAIL = "\u0000";
    private static final java.util.Set<String> BREAK_BEFORE = new HashSet<>(Arrays.asList(
            "and", "but", "then", "that", "which", "when", "because", "so", "or", "while",
            "who", "where", "as", "if", "though", "until", "after", "before"));
    private static final long SENT_PAUSE_MS = 800;       // مکثِ این‌قدری = پایانِ جمله → خطِ جدید (قبلاً ۵۰۰ و وسطِ جمله می‌شکست)
    private static final long SENT_PAUSE_INCOMPLETE_MS = 2000;   // جمله ناتمام به‌نظر می‌رسد (آخرش «the/to/and/…» است یا خیلی کوتاه است) → بیشتر صبر کن
    private static final int MAX_LINE_WORDS = 22;        // جمله‌ی بدونِ مکث/نقطه از این بلندتر شد، سرِ یک ویرگول/حرفِ ربط شکسته می‌شود
    private static final java.util.Set<String> ABBREVIATIONS = new HashSet<>(Arrays.asList(
            "mr.", "mrs.", "ms.", "dr.", "st.", "prof.", "jr.", "sr.", "vs.", "mt.", "no.", "gen.", "col.",
            "capt.", "lt.", "sgt.", "rev.", "hon.", "messrs.", "etc.", "e.g.", "i.e."));
    private int consumedWords = 0;                       // چند کلمه‌ی اولِ گفتارِ جاری قبلاً به‌صورتِ خطِ جدا بسته شده (تشخیصِ گفتار تجمعی است)
    private String pendingHyp = "";
    private long lastSrcRenderAt = 0;
    private boolean srcRenderScheduled = false;
    private TextView selectingTv = null;                 // متنی که الان انگشتِ کاربر رویش است (بازنویسی نشود)
    private final Runnable srcRenderRunnable = () -> { srcRenderScheduled = false; flushPartial(false); };
    private final Runnable pauseCommitRunnable = () -> {
        if (cumulativeAsr() && words(pendingHyp).length >= 4) endSentenceByPause();
        else flushPartial(true);
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
            lastActiveMs = SystemClock.elapsedRealtime();
            main.removeCallbacks(idleRunnable);
            main.postDelayed(idleRunnable, 700);
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
        // در حالتِ یوتیوب، «زبانِ مبدأ» = زبانِ زیرنویسِ همان ویدیو (مثلاً es)، نه زبانِ صدای تنظیمات؛
        // وگرنه برای ویدیوی غیرانگلیسی، «انگلیسی» به‌اشتباه از ترجمه‌ها حذف می‌شد.
        String src = (yt != null && yt.isActive()) ? yt.trackLang() : effectiveSource();
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
        bubbleSize = dp(60);

        bubble = new BubbleView(this);
        bubble.setContentDescription("LingoLearn");

        bubbleLp = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS      // ← لازم برای اینکه نیمی از حباب بیرون از صفحه برود
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = screenW() - bubbleSize - dp(8);
        bubbleLp.y = dp(200);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final long longMs = ViewConfiguration.getLongPressTimeout();

        // لمس = شروع/توقفِ ضبط ، نگه‌داشتن = تاریخچه ، ✕ روی حباب = بستنِ کامل ،
        // کشیدنِ حباب روی دایره‌ی ✕ پایینِ صفحه (مثل مسنجر) = بستنِ کامل ، لمسِ حبابِ نیمه‌پنهان فقط آن را بیرون می‌آورد.
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean longDone = false, onBadge = false;
            final Runnable longRun = new Runnable() {
                @Override public void run() {
                    if (dragging || onBadge || bubble == null) return;
                    longDone = true;
                    bubble.setPressedLook(false);
                    bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    toggleHistoryFromBubble();
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (bubble == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        main.removeCallbacks(idleDockRun);
                        main.removeCallbacks(longRun);
                        if (dockAnim != null) { dockAnim.cancel(); dockAnim = null; }
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = bubbleLp.x; startY = bubbleLp.y;
                        dragging = false; longDone = false;
                        onBadge = bubble.hitBadge(e.getX(), e.getY());
                        bubble.setPressedLook(true);
                        if (!onBadge) main.postDelayed(longRun, longMs);
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            dragging = true; onBadge = false;
                            main.removeCallbacks(longRun);
                            bubble.setPressedLook(false);
                            bubble.setDragging(true);
                            showCloseTarget();
                        }
                        if (dragging) {
                            int nx = clamp(Math.round(startX + dx), 0, screenW() - bubbleSize);
                            int ny = clamp(Math.round(startY + dy), 0, screenH() - bubbleSize);
                            boolean hot = closeView != null
                                    && Math.hypot(nx + bubbleSize / 2f - tgtCx, ny + bubbleSize / 2f - tgtCy) < dp(84);
                            if (hot != overTarget) {
                                overTarget = hot;
                                if (closeView != null) closeView.setHot(hot);
                                bubble.setHot(hot);
                                if (hot) bubble.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            }
                            if (hot) { nx = tgtCx - bubbleSize / 2; ny = tgtCy - bubbleSize / 2; }   // می‌نشیند روی ✕
                            bubbleLp.x = nx; bubbleLp.y = ny;
                            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
                            panelFree = false;                       // کشیدنِ حباب، کادر را دوباره کنارِ حباب می‌آورد
                            movePanel();
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        main.removeCallbacks(longRun);
                        bubble.setPressedLook(false);
                        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                        if (dragging) {
                            dragging = false;
                            bubble.setDragging(false);
                            if (up && overTarget) { closeBubbleAnimated(); return true; }
                            hideCloseTarget();
                            bubble.setHot(false);
                            dockBubble(recording);                   // مغناطیس: بعد از رها کردن به نزدیک‌ترین لبه می‌چسبد
                        } else if (up && !longDone) {
                            if (onBadge) {
                                if (bubble.hitBadge(e.getX(), e.getY())) {
                                    bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                    shutdown();
                                    return true;
                                }
                            } else {
                                bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                if (bubble.collapseAmount() > 0.5f) dockBubble(false);   // لمسِ اولِ حبابِ نیمه‌پنهان: فقط بیرون می‌آید (کلیکِ ناخواسته ضبط را شروع نکند)
                                else if (idleHidden && recording) reopenFromIdle();   // کادر خودکار بسته شده بود: لمس فقط بازش می‌کنه، ضبط را قطع نمی‌کنه
                                else toggleRecording();
                            }
                        }
                        scheduleIdleDock();                          // چند ثانیه بی‌کاری → نیمه‌پنهان کنار لبه
                        return true;
                    }
                }
                return true;
            }
        });

        try { wm.addView(bubble, bubbleLp); }
        catch (Exception e) { Log.e(TAG, "addView failed", e); shutdown(); }
        scheduleIdleDock();
    }

    private void toggleHistoryFromBubble() {
        if (panelShown) { idleHidden = false; userHidden = true; removePanel(); return; }
        idleHidden = false;
        userHidden = false;
        lastActiveMs = SystemClock.elapsedRealtime() + 8000;   // بازکردنِ دستیِ تاریخچه: کادر چند ثانیه‌ی بیشتر می‌مونه
        if (history.isEmpty()) {
            showNotice(isFa()
                    ? "لمس: شروع/توقف ضبط  ·  نگه‌داشتن: تاریخچه  ·  ✕ یا کشیدن به پایین: بستن"
                    : "Tap: start/stop  ·  Hold: history  ·  ✕ or drag down: close");
        } else showPanel();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private ValueAnimator dockAnim;
    private final Runnable idleDockRun = () -> { if (!dragging) dockBubble(true); };

    /** چند ثانیه بعد از آخرین لمس، حباب خودش کنار لبه جمع می‌شود (کم‌رنگ‌تر و نیمه‌پنهان) تا مزاحمِ محتوا نباشد. */
    private void scheduleIdleDock() {
        main.removeCallbacks(idleDockRun);
        main.postDelayed(idleDockRun, 3500);
    }

    /** حباب را به نزدیک‌ترین لبه‌ی چپ/راست می‌چسباند؛ collapse=true یعنی نیمی از آن بیرون از صفحه می‌رود (آیکون در نیمه‌ی دیدنی می‌ماند). */
    private void dockBubble(final boolean collapse) {
        if (wm == null || bubble == null || bubbleLp == null) return;
        if (dockAnim != null) { dockAnim.cancel(); dockAnim = null; }
        final int hide = collapse ? Math.round(bubbleSize * 0.5f) : 0;
        final boolean left = bubbleLp.x + bubbleSize / 2 < screenW() / 2;
        final int side = left ? -1 : 1;
        final int target = left ? -hide : screenW() - bubbleSize + hide;
        final int from = bubbleLp.x;
        final float c0 = bubble.collapseAmount(), c1 = collapse ? 1f : 0f;
        dockAnim = ValueAnimator.ofFloat(0f, 1f);
        dockAnim.setDuration(240);
        dockAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        dockAnim.addUpdateListener(a -> {
            if (wm == null || bubble == null) return;
            float t = (Float) a.getAnimatedValue();
            bubbleLp.x = Math.round(from + (target - from) * t);
            bubble.setDock(side, c0 + (c1 - c0) * t);
            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
            movePanel();
        });
        dockAnim.start();
    }

    // ── دایره‌ی ✕ پایینِ صفحه: فقط هنگامِ کشیدنِ حباب دیده می‌شود (مثل مسنجر) ──
    private CloseTargetView closeView;
    private WindowManager.LayoutParams closeLp;
    private boolean overTarget = false;
    private int tgtCx, tgtCy;

    private void showCloseTarget() {
        if (wm == null || closeView != null) return;
        int sz = dp(84);
        closeView = new CloseTargetView(this);
        closeLp = new WindowManager.LayoutParams(
                sz, sz,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        closeLp.gravity = Gravity.TOP | Gravity.START;
        tgtCx = screenW() / 2;
        tgtCy = screenH() - dp(120);
        closeLp.x = tgtCx - sz / 2;
        closeLp.y = tgtCy - sz / 2;
        closeView.setAlpha(0f); closeView.setScaleX(0.6f); closeView.setScaleY(0.6f);
        try {
            wm.addView(closeView, closeLp);
            closeView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        } catch (Exception ex) { closeView = null; }
    }

    private void hideCloseTarget() {
        overTarget = false;
        final CloseTargetView v = closeView;
        closeView = null;
        if (v == null || wm == null) return;
        v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(120)
                .withEndAction(() -> { try { wm.removeView(v); } catch (Exception ignored) {} }).start();
    }

    private void removeCloseTargetNow() {
        overTarget = false;
        CloseTargetView v = closeView;
        closeView = null;
        if (v != null && wm != null) { try { wm.removeView(v); } catch (Exception ignored) {} }
    }

    /** رها کردنِ حباب روی ✕: کوچک و محو می‌شود و سرویس کاملاً بسته می‌شود. */
    private void closeBubbleAnimated() {
        final BubbleView b = bubble;
        hideCloseTarget();
        if (b == null) { shutdown(); return; }
        b.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150)
                .withEndAction(this::shutdown).start();
    }

    /** دایره‌ی تیره‌ی ✕؛ وقتی حباب نزدیک می‌شود بزرگ و قرمز می‌شود. */
    private static final class CloseTargetView extends View {
        private final float d;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.animation.ArgbEvaluator argb = new android.animation.ArgbEvaluator();
        private float hot = 0f;
        private ValueAnimator hotAnim;

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

        void setHot(boolean h) {
            if (hotAnim != null) hotAnim.cancel();
            hotAnim = ValueAnimator.ofFloat(hot, h ? 1f : 0f);
            hotAnim.setDuration(140);
            hotAnim.addUpdateListener(a -> { hot = (Float) a.getAnimatedValue(); invalidate(); });
            hotAnim.start();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (hotAnim != null) { hotAnim.cancel(); hotAnim = null; }
        }

        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float sc = 1f + 0.22f * hot;
            cv.save();
            cv.scale(sc, sc, cx, cy);
            fill.setColor((Integer) argb.evaluate(hot, 0xE61C2541, COLOR_REC));
            fill.setShadowLayer(8f * d, 0f, 2f * d, 0x66000000);
            cv.drawCircle(cx, cy, 26f * d, fill);
            cv.drawCircle(cx, cy, 25f * d, ring);
            float k = 7.5f * d;
            cv.drawLine(cx - k, cy - k, cx + k, cy + k, cross);
            cv.drawLine(cx - k, cy + k, cx + k, cy - k, cross);
            cv.restore();
        }
    }

    /** حبابِ طراحی‌شده: دایره‌ی گرادیانی + حلقه‌ی طلایی + میکروفونِ برداری؛ هنگامِ ضبط حلقه‌ی قرمز + موجِ تپنده.
     *  حالتِ آرام: کم‌رنگ و نیمه‌پنهان کنارِ لبه. نشانِ ✕ (بستنِ کامل) فقط وقتی حباب بیرون است دیده می‌شود. */
    private static final class BubbleView extends View {
        private final float d;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeX = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private boolean rec = false, dragMode = false;
        private int side = 1;
        private float collapse = 0f, ripple = 0f, press = 0f, hot = 0f;
        private ValueAnimator rippleAnim, pressAnim, hotAnim;

        BubbleView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setLayerType(LAYER_TYPE_SOFTWARE, null);      // سایه‌ی نرم روی API پایین هم کار کند
            ring.setStyle(Paint.Style.STROKE);
            wave.setStyle(Paint.Style.STROKE);
            wave.setStrokeWidth(2f * d);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(Color.WHITE);
            fill.setColor(Color.WHITE);
            shine.setStyle(Paint.Style.STROKE);
            shine.setStrokeCap(Paint.Cap.ROUND);
            shine.setStrokeWidth(1.4f * d);
            shine.setColor(0x38FFFFFF);
            badge.setColor(0xF2FFFFFF);
            badgeX.setStyle(Paint.Style.STROKE);
            badgeX.setStrokeCap(Paint.Cap.ROUND);
            badgeX.setStrokeWidth(1.7f * d);
            badgeX.setColor(0xFF1C2541);
        }

        float collapseAmount() { return collapse; }

        void setDock(int s, float c) { side = s; collapse = c; applyAlpha(); invalidate(); }

        void setDragging(boolean g) { dragMode = g; invalidate(); }

        void setPressedLook(boolean p) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = ValueAnimator.ofFloat(press, p ? 1f : 0f);
            pressAnim.setDuration(p ? 90 : 170);
            pressAnim.addUpdateListener(a -> { press = (Float) a.getAnimatedValue(); invalidate(); });
            pressAnim.start();
        }

        void setHot(boolean h) {
            if (hotAnim != null) hotAnim.cancel();
            hotAnim = ValueAnimator.ofFloat(hot, h ? 1f : 0f);
            hotAnim.setDuration(140);
            hotAnim.addUpdateListener(a -> { hot = (Float) a.getAnimatedValue(); invalidate(); });
            hotAnim.start();
        }

        void setRecording(boolean r) {
            if (rec == r) return;
            rec = r;
            if (rippleAnim != null) { rippleAnim.cancel(); rippleAnim = null; }
            ripple = 0f;
            if (r) {
                rippleAnim = ValueAnimator.ofFloat(0f, 1f);
                rippleAnim.setDuration(1600);
                rippleAnim.setRepeatCount(ValueAnimator.INFINITE);
                rippleAnim.addUpdateListener(a -> { ripple = (Float) a.getAnimatedValue(); invalidate(); });
                rippleAnim.start();
            }
            applyAlpha();
            invalidate();
        }

        private void applyAlpha() { setAlpha(rec ? 1f : 1f - 0.38f * collapse); }

        private float discR() { return Math.min(getWidth(), getHeight()) / 2f - 8f * d; }

        private float badgeAlpha() { return dragMode ? 0f : Math.max(0f, 1f - collapse * 2f); }

        private float badgeCx() { return getWidth() / 2f - side * discR() * 0.80f; }

        private float badgeCy() { return getHeight() / 2f - discR() * 0.80f; }

        /** آیا لمس روی نشانِ ✕ است؟ (ناحیه‌ی لمس کمی بزرگ‌تر از ظاهرِ آن) */
        boolean hitBadge(float x, float y) {
            if (badgeAlpha() < 0.5f) return false;
            float dx = x - badgeCx(), dy = y - badgeCy();
            return dx * dx + dy * dy <= (16f * d) * (16f * d);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float r = discR();
            body.setShader(new LinearGradient(w / 2f, h / 2f - r, w / 2f, h / 2f + r, 0xFF34498A, 0xFF121A33, Shader.TileMode.CLAMP));
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (rippleAnim != null) { rippleAnim.cancel(); rippleAnim = null; }
            if (pressAnim != null) { pressAnim.cancel(); pressAnim = null; }
            if (hotAnim != null) { hotAnim.cancel(); hotAnim = null; }
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = discR();                                // ۸dp حاشیه برای سایه، موج و نشانِ ✕
            int accent = rec ? COLOR_REC : COLOR_GOLD;

            if (rec) {                                        // موجِ تپنده‌ی ضبط
                float rr = r + (Math.min(w, h) / 2f - 1f * d - r) * ripple;
                wave.setColor(COLOR_REC);
                wave.setAlpha((int) (140 * (1f - ripple)));
                cv.drawCircle(cx, cy, rr, wave);
            }

            float sc = (1f - 0.07f * press) * (1f - 0.22f * hot);
            cv.save();
            cv.scale(sc, sc, cx, cy);

            body.setShadowLayer(6f * d, 0f, 2f * d, 0x66000000);
            cv.drawCircle(cx, cy, r, body);

            ring.setColor(accent);
            ring.setStrokeWidth((rec ? 2.4f : 1.6f) * d);
            cv.drawCircle(cx, cy, r - 1f * d, ring);

            rf.set(cx - r + 3.5f * d, cy - r + 3.5f * d, cx + r - 3.5f * d, cy + r - 3.5f * d);
            cv.drawArc(rf, 205f, 130f, false, shine);         // درخشِ ظریفِ نیمه‌ی بالا

            // آیکونِ میکروفون؛ هنگامِ جمع‌شدن به سمتِ نیمه‌ی دیدنی می‌رود
            float ix = cx - side * collapse * (w * 0.22f);
            float k = 0.85f * d;
            rf.set(ix - 4.2f * k, cy - 11f * k, ix + 4.2f * k, cy + 2f * k);
            cv.drawRoundRect(rf, 4.2f * k, 4.2f * k, fill);
            line.setStrokeWidth(1.9f * k);
            rf.set(ix - 8f * k, cy - 8f * k, ix + 8f * k, cy + 7f * k);
            cv.drawArc(rf, 0f, 180f, false, line);
            cv.drawLine(ix, cy + 7f * k, ix, cy + 11f * k, line);
            cv.drawLine(ix - 4f * k, cy + 11f * k, ix + 4f * k, cy + 11f * k, line);

            if (rec) {                                        // نقطه‌ی قرمزِ «در حال ضبط»؛ سمتِ داخلیِ صفحه تا در حالتِ نیمه‌پنهان دیده شود
                float bx = cx - side * r * 0.72f, by = cy + r * 0.72f;
                fill.setColor(Color.WHITE);
                cv.drawCircle(bx, by, 5.2f * d, fill);
                fill.setColor(COLOR_REC);
                cv.drawCircle(bx, by, 3.6f * d, fill);
                fill.setColor(Color.WHITE);
            }
            cv.restore();

            float ba = badgeAlpha();                          // ✕ بستنِ کامل (سمتِ داخلیِ صفحه)
            if (ba > 0.02f) {
                float bx = badgeCx(), by = badgeCy(), br = 8.5f * d;
                badge.setShadowLayer(3f * d, 0f, 1f * d, ((int) (90 * ba) << 24));
                badge.setAlpha((int) (242 * ba));
                cv.drawCircle(bx, by, br, badge);
                badgeX.setAlpha((int) (255 * ba));
                float q = 3.1f * d;
                cv.drawLine(bx - q, by - q, bx + q, by + q, badgeX);
                cv.drawLine(bx - q, by + q, bx + q, by - q, badgeX);
            }
        }
    }


    // ════════════════════════════════════════════════════════════════════════════
    //  Panel: scrollable history of every sentence + one translation line per target language
    // ════════════════════════════════════════════════════════════════════════════

    /** One recognised sentence and its translations (one per target language). */
    private static final class Entry {
        String src = "";
        int shownChars = Integer.MAX_VALUE;   // تایپِ حرف‌به‌حرف: چند حرفِ اول نمایش داده شده (MAX = بدونِ تایپ)
        final long createdAt = System.currentTimeMillis();
        final HashMap<String, String> tr = new HashMap<>();        // lang -> shown text ("…" = pending)
        final HashMap<String, String> trSrc = new HashMap<>();     // lang -> source text that translation was made from
        final HashMap<String, Integer> applied = new HashMap<>();  // lang -> id of the newest request applied (drops stale answers)
        LinearLayout box;
        LinearLayout rowsBox;
        LinearLayout srcRow;            // [🔊][متنِ اصلی]
        TextView tvSrc;
        String srcLang = "en";          // زبانِ واقعیِ متنِ اصلیِ این جمله (برای 🔊 و کادرِ لغت)
        int ytIdx = -1;                 // شماره‌ی خطِ زیرنویسِ یوتیوب (برای چیدنِ مرتب بعد از seek)
        final HashMap<String, LinearLayout> rowBox = new HashMap<>();
        final HashMap<String, TextView> rowText = new HashMap<>();
        // ترجمه‌ی زنده‌ی تکه‌تکه (پایدار): تکه‌های متنِ اصلیِ «قفل‌شده» و ترجمه‌ی هر تکه به‌ازای هر زبان
        final ArrayList<String> chunkSrc = new ArrayList<>();
        final HashMap<String, ArrayList<String>> chunkTr = new HashMap<>();   // lang -> ترجمه‌ی هر تکه ("" = در انتظار)
        int committedWords = 0;
        // ترجمه‌ی زنده‌ی «دنباله‌ی» جمله (کلمه‌هایی که هنوز در تکه‌ی قفل‌شده نیستند) — فوری و کلمه‌به‌کلمه
        final HashMap<String, String> tailTr = new HashMap<>();
        final HashMap<String, Integer> tailTrFrom = new HashMap<>();
        final HashMap<String, String> tailAsked = new HashMap<>();
        final HashMap<String, Boolean> tailBusy = new HashMap<>();
        long tailLastAt = 0;
        boolean tailScheduled = false;
        long mediaPosMs = -1;                                              // موقعیتِ پلیرِ بیرونی (ms) وقتی این جمله شروع شد؛ -1 = نامشخص
        TextView srcSpeakBtn;                                              // 🔊 کنار متن اصلی
        TextView ytReplayBtn;                                              // ↺ پخش دوباره‌ی جمله با صدای پلیر یوتیوب
        final HashMap<String, TextView> rowSpeakBtn = new HashMap<>();     // 🔊 کنار ترجمه‌ی هر زبان
    }

    /** ScrollView with a max height that follows new text unless the user scrolled up to read older lines. */
    private static final class HistoryScroll extends ScrollView {
        int maxHeightPx = Integer.MAX_VALUE;
        boolean atBottom = true;
        boolean frozen = false;      // وقتی کاربر دارد متن انتخاب می‌کند / کادرِ لغت باز است، خودکار به پایین نپر
        boolean ytMode = false;      // 📺 لیستِ کاملِ زیرنویس: به‌جای پرش به انتها، خودِ برنامه خطِ جاری را اسکرول می‌کند
        long lastUserTouch = 0;      // آخرین لمسِ کاربر (تا وسطِ خواندن اسکرولش را نپرانیم)

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
                lastUserTouch = SystemClock.uptimeMillis();
            }
            return super.dispatchTouchEvent(ev);
        }

        HistoryScroll(Context c) { super(c); }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (atBottom && !frozen && !ytMode) {
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

    // ---------- 🎙 shadowing: ضبطِ صدای کاربر و پخشِ دوباره ----------
    private TextView tvShadowRec, tvShadowPlay;
    private boolean shadowRecording = false;
    private String shadowPath = null;
    private MediaPlayer shadowPlayer;

    private void updateShadowUi() {
        if (tvShadowRec != null) {
            tvShadowRec.setText(shadowRecording ? "\u23F9" : "\uD83C\uDF99");
            tvShadowRec.setTextColor(shadowRecording ? Color.parseColor("#FF5A5F") : Color.WHITE);
        }
        if (tvShadowPlay != null) {
            tvShadowPlay.setVisibility(!shadowRecording && shadowPath != null ? View.VISIBLE : View.GONE);
        }
    }

    private void shadowToggle() {
        if (shadowRecording) {
            shadowRecording = false;
            updateShadowUi();
            ShadowRecordActivity.stopIfRunning();
            return;
        }
        shadowStopPlayback();
        try {
            Intent i = new Intent(this, ShadowRecordActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
            shadowRecording = true;
            shadowPath = null;
            updateShadowUi();
            lastActiveMs = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            Log.w(TAG, "shadow record start failed", e);
            shadowRecording = false;
            updateShadowUi();
            showNotice(msg("⚠ ضبط شروع نشد", "⚠ Could not start recording"));
        }
    }

    static void shadowDone(final String path) {
        final BubbleService s = instance;
        if (s == null) return;
        s.uiHandlerPost(() -> {
            s.shadowRecording = false;
            s.shadowPath = path;
            s.updateShadowUi();
            if (path != null) s.shadowPlay();
        });
    }

    static void shadowFailed(final int kind) {
        final BubbleService s = instance;
        if (s == null) return;
        s.uiHandlerPost(() -> {
            s.shadowRecording = false;
            s.updateShadowUi();
            s.showNotice(kind == 2
                    ? s.msg("⚠ مجوز میکروفون لازم است", "⚠ Microphone permission required")
                    : s.msg("⚠ ضبط انجام نشد", "⚠ Recording failed"));
        });
    }

    private void uiHandlerPost(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private void shadowPlay() {
        if (shadowPath == null) return;
        shadowStopPlayback();
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(shadowPath);
            mp.setOnCompletionListener(m -> { try { m.release(); } catch (Exception ignored) {} if (shadowPlayer == m) shadowPlayer = null; });
            mp.setOnErrorListener((m, w, e) -> { try { m.release(); } catch (Exception ignored) {} if (shadowPlayer == m) shadowPlayer = null; return true; });
            mp.prepare();
            mp.start();
            shadowPlayer = mp;
            lastActiveMs = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            Log.w(TAG, "shadow play failed", e);
            shadowPlayer = null;
        }
    }

    private void shadowStopPlayback() {
        MediaPlayer mp = shadowPlayer; shadowPlayer = null;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
    }

    private void ensurePanel() {
        if (panel != null) return;
        loadViewPrefs();
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(6), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F01C2541"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COLOR_GOLD);
        panel.setBackground(bg);
        panelBgDrawable = bg;
        applyPanelAlpha();

        gripTop = makeGrip(false);
        panel.addView(gripTop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        // header: title + languages, clear-history, close
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        tvHeader = new TextView(this);
        tvHeader.setTextColor(COLOR_GOLD);
        style(tvHeader, 11, true);
        tvHeader.setSingleLine(true);
        header.addView(tvHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvYt = headerButton("\u25B6", v -> toggleYoutube());
        header.addView(tvYt);
        tvSave = headerButton("\uD83D\uDCBE", v -> saveCurrent());
        header.addView(tvSave);
        loadRepeatPref();
        tvRepeat = headerButton("\uD83D\uDD01", v -> cycleRepeat());
        tvRepeat.setPadding(dp(8), dp(6), dp(8), dp(6));
        header.addView(tvRepeat);
        updateRepeatButton();
        updateYtButton();
        TextView tvFont = headerButton("Aa", v -> toggleFontRow());
        tvFont.setTypeface(Typeface.DEFAULT_BOLD);
        tvFont.setOnLongClickListener(v -> { resetView(); return true; });
        header.addView(tvFont);
        TextView tvOpacity = headerButton("\uD83C\uDF13", v -> toggleOpacityRow());
        tvOpacity.setPadding(dp(8), dp(6), dp(8), dp(6));
        header.addView(tvOpacity);
        header.addView(headerButton("\uD83D\uDDD1", v -> clearHistory()));
        header.addView(headerButton("\u25BE", v -> { idleHidden = false; userHidden = true; removePanel(); }));   // ▾ = فقط کادرِ تاریخچه را پنهان می‌کند (بستنِ حباب: ✕ روی خودِ حباب)
        tvHeader.setPadding(0, dp(8), 0, dp(8));          // سطحِ لمسِ بزرگ‌تر برای کشیدنِ کادر
        final int dragSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        header.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        int[] loc = new int[2];
                        panel.getLocationOnScreen(loc);
                        startX = loc[0]; startY = loc[1];
                        downX = e.getRawX(); downY = e.getRawY();
                        moved = false;
                        main.removeCallbacks(hidePanel);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && (Math.abs(dx) > dragSlop || Math.abs(dy) > dragSlop)) moved = true;
                        if (moved) {
                            panelFree = true;
                            panelFreeX = Math.round(startX + dx);
                            panelFreeY = Math.round(startY + dy);
                            refreshLayout();
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        opacityRow = new LinearLayout(this);
        opacityRow.setOrientation(LinearLayout.HORIZONTAL);
        opacityRow.setGravity(Gravity.CENTER_VERTICAL);
        opacityRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        opacityRow.setVisibility(View.GONE);
        opacityBar = new SeekBar(this);
        opacityBar.setMax(100);
        opacityBar.setProgress(Math.round(panelAlpha * 100f));
        opacityBar.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_GOLD));
        opacityBar.setThumbTintList(android.content.res.ColorStateList.valueOf(COLOR_GOLD));
        final TextView opacityPct = new TextView(this);
        opacityPct.setTextColor(Color.WHITE);
        opacityPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        opacityPct.setMinWidth(dp(44));
        opacityPct.setGravity(Gravity.CENTER);
        opacityPct.setText(Math.round(panelAlpha * 100f) + "%");
        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                panelAlpha = progress / 100f;
                opacityPct.setText(progress + "%");
                applyPanelAlpha();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveViewPrefs(); }
        });
        opacityRow.addView(opacityBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        opacityRow.addView(opacityPct, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(opacityRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(buildFontRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildWordCard();
        panel.addView(wordCard);   // LayoutParams (با margin) داخلِ buildWordCard ست شده

        scroll = new HistoryScroll(this);
        scroll.maxHeightPx = panelMaxH();
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
        styleC(tvStatus, 13, false);
        tvStatus.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        tvStatus.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        tvStatus.setPadding(0, dp(4), 0, 0);
        tvStatus.setVisibility(View.GONE);
        panel.addView(tvStatus);

        gripBottom = makeGrip(true);
        panel.addView(gripBottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

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
        panelLp.width = panelWidthPx();
        if (panelFree) {                       // جای دلخواهِ کاربر؛ دستگیره‌ی تغییر اندازه پایین است
            panelLp.gravity = Gravity.TOP | Gravity.START;
            panelLp.x = clamp(panelFreeX, 0, Math.max(0, screenW() - panelLp.width));
            panelLp.y = clamp(panelFreeY, 0, Math.max(0, screenH() - dp(120)));
            if (gripBottom != null) gripBottom.setVisibility(View.VISIBLE);
            if (gripTop != null) gripTop.setVisibility(View.GONE);
            return;
        }
        panelLp.x = (screenW() - panelLp.width) / 2;
        int sh = screenH();
        boolean below = bubbleLp.y + bubbleSize / 2 < sh / 2;
        // دستگیره‌ی تغییر اندازه همیشه سمتِ دورتر از حباب است (کشیدن به‌سمتِ بیرون = بزرگ‌تر)
        if (gripBottom != null) gripBottom.setVisibility(below ? View.VISIBLE : View.GONE);
        if (gripTop != null) gripTop.setVisibility(below ? View.GONE : View.VISIBLE);
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
        stopPanelSpeech();
        main.removeCallbacks(hidePanel);
        if (panelShown && panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
        }
        panelShown = false;
        panelFree = false;
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
        if (idleHidden && has && m.startsWith("⚠")) { idleHidden = false; userHidden = false; }
        tvStatus.setText(has ? m : "");
        applyStyle(tvStatus);
        tvStatus.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!userHidden) showPanel();
    }

    private void afterChange() {
        if (!userHidden) showPanel();
        else refreshLayout();
    }

    private void clearHistory() {
        stopPanelSpeech();
        closeWordCard();
        history.clear();
        typing.clear();
        main.removeCallbacks(typeRunnable);
        typeScheduled = false;
        liveKey = null;
        live = null;
        consumedWords = 0;
        if (listBox != null) listBox.removeAllViews();
        lastFinalText = "";
        prevFinalText = "";
        lastPartialSrc = "";
        cancelLivePending();
        refreshLayout();
    }


    // ════════════════════════════════════════════════════════════════════════════
    //  🔊 خواندنِ جمله  +  👆 انتخابِ لغت/محدوده → کادرِ «افزودن به داستان / گرامر / لایتنر»
    // ════════════════════════════════════════════════════════════════════════════

    /** Supplier ساده (java.util.function روی API 23 نیست). */
    private interface Txt { String get(); }

    private TextView speakingBtn;                 // دکمه‌ای که الان در حالِ خواندن است
    private int speakToken = 0;
    private volatile boolean panelSpeaking = false;
    private volatile long panelQuietUntil = 0;

    /** وقتی خودِ پنل دارد می‌خواند، صدای آن نباید دوباره به متنِ زنده تبدیل شود. */
    private boolean asrMuted() {
        return panelSpeaking || SystemClock.uptimeMillis() < panelQuietUntil;
    }

    private String currentSrcLang() {
        if (yt != null && yt.isActive()) {
            String t = yt.trackLang();
            if (t != null && !t.isEmpty()) return t;
        }
        String s = effectiveSource();
        return (s == null || "auto".equals(s)) ? "en" : s;
    }

    private TextView speakerButton(final Txt text, final Txt lang) {
        return speakerButton(text, lang, null, null, null);
    }

    /** hl: متنی که هنگامِ خواندن جمله‌به‌جمله هایلایت می‌شود (null = بدونِ هایلایت). */
    private TextView speakerButton(final Txt text, final Txt lang, final TextView hl,
                                   final Entry ent, final String kind) {
        final TextView b = new TextView(this);
        b.setText("\uD83D\uDD0A");
        style(b, 18, false);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(44));
        b.setMinHeight(dp(40));
        b.setPadding(dp(6), dp(4), dp(6), dp(4));
        b.setOnClickListener(v -> toggleSpeak(b, text.get(), lang.get(), hl, ent, kind));
        return b;
    }

    private void setSpeaking(TextView b) {
        TextView old = speakingBtn;
        speakingBtn = b;
        if (old != null && old != b) { old.setText("\uD83D\uDD0A"); old.setTextColor(Color.WHITE); }
        if (b != null) { b.setText("\u23F9"); b.setTextColor(COLOR_GOLD); }
    }

    // ───────── 🔊 خواندنِ جمله‌به‌جمله با هایلایتِ بخشِ در حالِ خواندن (هم متنِ اصلی، هم ترجمه) ─────────

    private TextView speakTv = null;      // متنی که الان بخش‌هایش هایلایت می‌شود
    private static final java.util.regex.Pattern SENT_PAT = java.util.regex.Pattern.compile(
            "[^.!?\\u061F\\u3002\\uFF01\\uFF1F\\u2026]+[.!?\\u061F\\u3002\\uFF01\\uFF1F\\u2026]*");

    /** متن را به بخش‌های [start,end) می‌شکند: جمله‌ها؛ و جمله‌های بلند (مثلاً متنِ بدونِ نقطه‌گذاریِ تشخیصِ گفتار) در مرزِ ویرگول/حرفِ ربط. */
    private static ArrayList<int[]> splitSegments(String text) {
        ArrayList<int[]> out = new ArrayList<>();
        java.util.regex.Matcher m = SENT_PAT.matcher(text);
        while (m.find()) {
            int a = m.start(), z = m.end();
            while (a < z && Character.isWhitespace(text.charAt(a))) a++;
            while (z > a && Character.isWhitespace(text.charAt(z - 1))) z--;
            if (a >= z) continue;
            boolean hasLetter = false;
            for (int i = a; i < z && !hasLetter; i++) hasLetter = Character.isLetterOrDigit(text.charAt(i));
            if (!hasLetter) continue;                                   // مثلاً «…» تنها
            // جمله‌ی بلند → تکه‌های ~۸ تا ۱۲ کلمه‌ای
            ArrayList<int[]> ws = new ArrayList<>();
            java.util.regex.Matcher wm = java.util.regex.Pattern.compile("\\S+").matcher(text.substring(a, z));
            while (wm.find()) ws.add(new int[]{a + wm.start(), a + wm.end()});
            if (ws.size() <= CHUNK_MAX_WORDS + 2) { out.add(new int[]{a, z, 1}); continue; }
            String[] w = new String[ws.size()];
            for (int i = 0; i < w.length; i++) w[i] = text.substring(ws.get(i)[0], ws.get(i)[1]);
            int from = 0;
            while (from < w.length) {
                int end = pickBreak(w, from, w.length, true);
                if (end <= from) end = w.length;
                out.add(new int[]{ws.get(from)[0], ws.get(end - 1)[1], end >= w.length ? 1 : 0});
                from = end;
            }
        }
        if (out.isEmpty() && !text.trim().isEmpty()) out.add(new int[]{0, text.length(), 1});
        return out;
    }

    private void highlightSpeak(TextView tv, String expected, int a, int b) {
        if (tv == null) return;
        String plain = tv.getText().toString();
        if (!plain.equals(expected) || a < 0 || b <= a || b > plain.length()) return;
        SpannableString ss = new SpannableString(plain);
        ss.setSpan(new BackgroundColorSpan(Color.parseColor("#88C9A227")), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
        scrollToOffset(tv, a, b);
    }

    /** بخشِ در حالِ خواندن را داخلِ دیدِ لیست نگه می‌دارد. */
    private void scrollToOffset(final TextView tv, final int a, final int b) {
        if (scroll == null) return;
        scroll.post(() -> {
            android.text.Layout lay = tv.getLayout();
            if (lay == null || scroll.getChildAt(0) == null) return;
            int n = tv.getText().length();
            int top = lay.getLineTop(lay.getLineForOffset(Math.min(a, Math.max(0, n - 1))));
            int bottom = lay.getLineBottom(lay.getLineForOffset(Math.min(Math.max(a, b - 1), Math.max(0, n - 1))));
            View v = tv;
            int off = 0;
            while (v != scroll.getChildAt(0) && v.getParent() instanceof View) { off += v.getTop(); v = (View) v.getParent(); }
            int y0 = off + top, y1 = off + bottom;
            int vis0 = scroll.getScrollY(), vis1 = vis0 + scroll.getHeight();
            if (y0 < vis0 + dp(6) || y1 > vis1 - dp(6)) scroll.smoothScrollTo(0, Math.max(0, y0 - dp(28)));
        });
    }

    private void clearSpeakHighlight() {
        TextView t = speakTv;
        speakTv = null;
        if (t != null && t != cardSrcTv) clearHighlight(t);
        updateScrollFreeze();
        refreshRecentEntries();                 // متنی که حین خواندن فریز بود، به آخرین نسخه برمی‌گردد
    }

    // ───────── 🔁 تکرارِ هر جمله (مثلِ دکمه‌ی تکرارِ خودِ اپ): خاموش ← ۲ ← ۳ ← ∞ ─────────
    // «N» یعنی کلاً N بار خوانده شود. «∞» تا وقتی جمله‌ی بعدی هست سقفِ ۴۰ بار دارد و بعد خودکار می‌رود سراغِ جمله‌ی بعد؛
    // اگر جمله‌ی بعدی‌ای نباشد (آخرین جمله) واقعاً بی‌نهایت تکرار می‌شود تا خودت ⏹ بزنی.
    private static final int REPEAT_INF = -1;
    private static final int REPEAT_INF_CAP = 40;
    private int repeatSetting = 0;                 // 0 خاموش | 2 | 3 | REPEAT_INF
    private TextView tvRepeat;
    private SpeakRun run;                          // پخشِ جاری (null = ساکت)

    private final class SpeakRun {
        Entry ent; String kind; TextView btn; TextView hl; String text; String lang;
        ArrayList<int[]> segs; int unitStart = 0; int rep = 0; int my;
    }

    private void loadRepeatPref() {
        int v = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("repeatSetting", 0);
        repeatSetting = (v == 2 || v == 3 || v == REPEAT_INF) ? v : 0;
    }

    private void cycleRepeat() {
        repeatSetting = repeatSetting == 0 ? 2 : repeatSetting == 2 ? 3 : repeatSetting == 3 ? REPEAT_INF : 0;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("repeatSetting", repeatSetting).apply();
        updateRepeatButton();
        showNotice(repeatSetting == 0 ? msg("تکرار خاموش", "Repeat off")
                : repeatSetting == REPEAT_INF ? msg("تکرار ∞ — هر جمله، بعد جمله‌ی بعدی", "Repeat ∞ — each sentence, then the next")
                : msg("هر جمله " + repeatSetting + " بار، بعد جمله‌ی بعدی", "Each sentence " + repeatSetting + "×, then the next"));
    }

    /** عددِ روی دکمه‌ی ↺: ۲ / ۳ / ∞ (خاموش = فقط ↺). */
    private String replayLabel() {
        return repeatSetting == 0 ? "\u21BA" : repeatSetting == REPEAT_INF ? "\u21BA\u221E" : "\u21BA" + repeatSetting;
    }

    private void updateRepeatButton() {
        for (Entry en : ytEntries.values())
            if (en != null && en.ytReplayBtn != null) en.ytReplayBtn.setText(replayLabel());
        if (tvRepeat == null) return;
        tvRepeat.setText(repeatSetting == 0 ? "\uD83D\uDD01"
                : repeatSetting == REPEAT_INF ? "\uD83D\uDD01\u221E" : "\uD83D\uDD01" + repeatSetting);
        tvRepeat.setTextColor(repeatSetting == 0 ? Color.WHITE : COLOR_GOLD);
        tvRepeat.setAlpha(repeatSetting == 0 ? 0.6f : 1f);
    }

    private static String entryText(Entry n, String kind) {
        if ("src".equals(kind)) return n.src == null ? "" : n.src;
        TextView t = n.rowText.get(kind);
        return t == null ? "" : t.getText().toString();
    }

    /** جمله‌ی بعدیِ همان نوع (متنِ اصلی یا همان زبانِ ترجمه) که الان روی صفحه دیده می‌شود. */
    private Entry nextEntryFor(Entry ent, String kind) {
        if (ent == null) return null;
        int idx = history.indexOf(ent);
        if (idx < 0) return null;
        for (int j = idx + 1; j < history.size(); j++) {
            Entry n = history.get(j);
            if (n == live) continue;                                   // جمله‌ی نیمه‌کاره
            View vis = "src".equals(kind) ? n.srcRow : n.rowBox.get(kind);
            if (vis == null || vis.getVisibility() != View.VISIBLE) continue;
            String t = entryText(n, kind).trim();
            if (t.isEmpty() || t.equals("…")) continue;
            return n;
        }
        return null;
    }

    private void toggleSpeak(final TextView b, final String text, final String lang, final TextView hl,
                             final Entry ent, final String kind) {
        if (text == null || text.trim().isEmpty() || text.equals("…")) return;
        if (speakingBtn == b) { stopPanelSpeech(); return; }
        if (speakTv != null) clearSpeakHighlight();
        setSpeaking(b);
        final int my = ++speakToken;
        panelSpeaking = true;
        final SpeakRun r = new SpeakRun();
        r.ent = ent; r.kind = kind; r.btn = b; r.hl = hl; r.text = text; r.lang = lang; r.my = my;
        if (hl != null) {
            r.segs = splitSegments(text);
            speakTv = hl;
            updateScrollFreeze();
        } else {
            r.segs = new ArrayList<>();
            r.segs.add(new int[]{0, text.length(), 1});
        }
        run = r;
        speakSegment(r, 0);
    }

    private void speakSegment(final SpeakRun r, final int i) {
        if (r.my != speakToken || run != r) return;                 // پخشِ تازه‌تری شروع شده
        if (i >= r.segs.size()) { advanceOrFinish(r); return; }
        final int[] sg = r.segs.get(i);
        if (r.hl != null) highlightSpeak(r.hl, r.text, sg[0], sg[1]);
        PanelTts.speak(this, r.text.substring(sg[0], sg[1]), r.lang, 1.0f, ok -> {
            if (r.my != speakToken || run != r) return;
            if (!ok) { finishSpeak(r.btn, r.my); return; }
            boolean endOfSentence = sg.length < 3 || sg[2] == 1;
            if (!endOfSentence) { speakSegment(r, i + 1); return; }  // وسطِ یک جمله‌ی بلند: بخشِ بعدی
            // پایانِ یک «جمله»: تکرارِ همین جمله یا رفتن سراغِ بعدی
            boolean moreInRun = i + 1 < r.segs.size() || nextEntryFor(r.ent, r.kind) != null;
            int cap;
            if (repeatSetting == REPEAT_INF) cap = moreInRun ? REPEAT_INF_CAP : Integer.MAX_VALUE;
            else cap = repeatSetting == 0 ? 1 : repeatSetting;
            if (r.rep + 1 < cap) {
                r.rep++;
                final int restart = r.unitStart;
                main.postDelayed(() -> speakSegment(r, restart), repeatGapMs(r.text, sg));
            } else {
                r.rep = 0;
                r.unitStart = i + 1;
                speakSegment(r, i + 1);
            }
        });
    }

    private static long repeatGapMs(String text, int[] sg) {
        int n = words(text.substring(sg[0], sg[1])).length;
        return Math.min(1200, 350 + 30L * n);
    }

    /** بخش‌های این جمله تمام شد: اگر تکرار روشن است برو سراغِ جمله‌ی بعدیِ همان نوع، وگرنه تمام. */
    private void advanceOrFinish(final SpeakRun r) {
        Entry n = repeatSetting == 0 ? null : nextEntryFor(r.ent, r.kind);
        if (n == null) { finishSpeak(r.btn, r.my); return; }
        TextView nb = "src".equals(r.kind) ? n.srcSpeakBtn : n.rowSpeakBtn.get(r.kind);
        TextView nh = "src".equals(r.kind) ? n.tvSrc : n.rowText.get(r.kind);
        if (nb == null || nh == null) { finishSpeak(r.btn, r.my); return; }
        TextView old = r.hl;
        speakTv = null;                                             // هایلایتِ قبلی پاک و متنِ فریزشده به‌روز شود
        if (old != null && old != cardSrcTv) clearHighlight(old);
        refreshRecentEntries();
        String t = entryText(n, r.kind);
        if (t.trim().isEmpty()) { finishSpeak(r.btn, r.my); return; }
        r.ent = n; r.btn = nb; r.hl = nh; r.text = t;
        r.lang = "src".equals(r.kind) ? n.srcLang : r.kind;
        r.segs = splitSegments(t);
        r.unitStart = 0; r.rep = 0;
        speakTv = nh;
        updateScrollFreeze();
        setSpeaking(nb);
        speakSegment(r, 0);
    }

    private void finishSpeak(TextView b, int my) {
        if (my != speakToken) return;
        run = null;
        panelSpeaking = false;
        panelQuietUntil = SystemClock.uptimeMillis() + 1200;
        if (speakingBtn == b) setSpeaking(null);
        clearSpeakHighlight();
    }

    /** ↺ همان جمله را با صدای خودِ پلیر یوتیوب پخش می‌کند؛ تعداد تکرار از تنظیمِ 🔁 (۲ / ۳ / ∞) می‌آید. دوباره زدن = توقفِ تکرار. */
    private void replayInYoutube(Entry e) {
        if (e == null || e.ytIdx < 0 || yt == null || !yt.isActive()) return;
        if (yt.isLooping(e.ytIdx)) {
            yt.stopLoop();
            showNotice(msg("تکرار متوقف شد", "Repeat stopped"));
            return;
        }
        stopPanelSpeech();                                   // صدای برنامه با صدای یوتیوب قاطی نشود
        int times = repeatSetting == 0 ? 1 : repeatSetting;  // REPEAT_INF = -1 → بی‌نهایت
        if (!yt.replaySentence(e.ytIdx, times))
            showNotice(msg("پلیر یوتیوب در دسترس نیست", "YouTube player not available"));
        else if (times != 1)
            showNotice(times < 0 ? msg("تکرار ∞ با صدای یوتیوب", "Repeat ∞ with YouTube audio")
                    : msg("تکرار " + times + " بار با صدای یوتیوب", "Repeat " + times + "× with YouTube audio"));
    }

    private void stopPanelSpeech() {
        if (speakingBtn == null && !panelSpeaking && speakTv == null) return;
        speakToken++;
        run = null;
        PanelTts.stop(this);
        panelSpeaking = false;
        panelQuietUntil = SystemClock.uptimeMillis() + 800;
        TextView b = speakingBtn;
        speakingBtn = null;
        if (b != null) { b.setText("\uD83D\uDD0A"); b.setTextColor(Color.WHITE); }
        clearSpeakHighlight();
    }

    // ───────── انتخابِ لغت (تپ) یا محدوده (لانگ‌پرس + کشیدن) ─────────

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '\u2019' || c == '-' || c == '\u200C';
    }

    /** [start,end) لغتی که offset داخل/کنار آن است؛ null اگر روی فاصله/نشانه‌گذاری بود. */
    private static int[] wordBounds(CharSequence t, int off) {
        int n = t.length();
        if (n == 0) return null;
        if (off >= n) off = n - 1;
        if (off < 0) off = 0;
        if (!isWordChar(t.charAt(off))) {
            if (off > 0 && isWordChar(t.charAt(off - 1))) off--;
            else return null;
        }
        int a = off, b = off + 1;
        while (a > 0 && isWordChar(t.charAt(a - 1))) a--;
        while (b < n && isWordChar(t.charAt(b))) b++;
        while (a < b && (t.charAt(a) == '\'' || t.charAt(a) == '\u2019' || t.charAt(a) == '-')) a++;
        while (b > a && (t.charAt(b - 1) == '\'' || t.charAt(b - 1) == '\u2019' || t.charAt(b - 1) == '-')) b--;
        return a < b ? new int[]{a, b} : null;
    }

    private void highlight(TextView tv, int a, int b) {
        String plain = tv.getText().toString();
        SpannableString ss = new SpannableString(plain);
        if (a >= 0 && b > a && b <= plain.length()) {
            ss.setSpan(new BackgroundColorSpan(Color.parseColor("#66C9A227")), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(ss);
    }

    private void clearHighlight(TextView tv) {
        if (tv == null) return;
        CharSequence c = tv.getText();
        if (c instanceof Spanned) tv.setText(c.toString());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachSelect(final TextView tv, final Txt langOf) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final int[] st = new int[]{0, 0, 0};          // anchorOffset, curOffset, mode(0 idle / 1 pending / 2 selecting)
        final float[] down = new float[2];
        final Runnable longPress = () -> {
            if (st[2] != 1) return;
            st[2] = 2;
            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            selectingTv = tv;
            updateScrollFreeze();
            if (tv.getParent() != null) tv.getParent().requestDisallowInterceptTouchEvent(true);
            int[] w = wordBounds(tv.getText(), st[0]);
            if (w != null) highlight(tv, w[0], w[1]);
        };
        tv.setOnTouchListener((v, ev) -> {
            CharSequence txt = tv.getText();
            if (txt == null || txt.length() == 0) return false;
            int off = tv.getOffsetForPosition(ev.getX(), ev.getY());
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getX(); down[1] = ev.getY();
                    st[0] = off; st[1] = off; st[2] = 1;
                    selectingTv = tv;            // تا انگشت روی متن است، ترجمه‌ی زنده بازنویسی‌اش نکند
                    updateScrollFreeze();
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (st[2] == 1 && (Math.abs(ev.getX() - down[0]) > slop || Math.abs(ev.getY() - down[1]) > slop)) {
                        st[2] = 0;                                   // کشیدنِ معمولی → اسکرولِ لیست
                        main.removeCallbacks(longPress);
                        selectingTv = null;
                        updateScrollFreeze();
                        return true;
                    }
                    if (st[2] == 2) {
                        st[1] = off;
                        int[] a = wordBounds(txt, st[0]), c = wordBounds(txt, st[1]);
                        if (a != null && c != null) highlight(tv, Math.min(a[0], c[0]), Math.max(a[1], c[1]));
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    main.removeCallbacks(longPress);
                    int mode = st[2];
                    st[2] = 0;
                    String full = txt.toString();
                    int a0 = -1, b0 = -1;
                    if (mode == 1) {
                        int[] w = wordBounds(full, st[0]);
                        if (w != null) { a0 = w[0]; b0 = w[1]; }
                    } else if (mode == 2) {
                        int[] a = wordBounds(full, st[0]), c = wordBounds(full, st[1]);
                        if (a != null && c != null) { a0 = Math.min(a[0], c[0]); b0 = Math.max(a[1], c[1]); }
                        else if (a != null) { a0 = a[0]; b0 = a[1]; }
                    }
                    if (a0 >= 0 && b0 > a0) {
                        highlight(tv, a0, b0);
                        openWordCard(full.substring(a0, b0).trim(), langOf.get(), full, tv);
                    } else {
                        clearHighlight(tv);
                    }
                    selectingTv = null;
                    updateScrollFreeze();
                    refreshRecentEntries();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    if (st[2] == 2) clearHighlight(tv);
                    st[2] = 0;
                    selectingTv = null;
                    updateScrollFreeze();
                    return true;
            }
            return false;
        });
    }

    // ───────── کادرِ لغت: معنی + سه دکمه‌ی اپ ─────────

    private LinearLayout wordCard;
    private TextView cardTerm, cardMeaningTv, cardBtnStory, cardBtnGrammar, cardBtnLeitner;
    private TextView cardSrcTv;                       // متنی که هایلایت دارد
    private String cardWord, cardLang, cardSentence, cardMeaning = "", cardMeaningLang;
    private boolean cardStoryDone, cardGrammarDone, cardLeitnerDone;
    private int cardSeq = 0;

    private TextView cardAction(String label, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        style(b, 12, true);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        b.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
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
        wordCard.setPadding(dp(10), dp(8), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#26335F"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_GOLD);
        wordCard.setBackground(bg);
        wordCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6);
        wlp.bottomMargin = dp(4);
        wordCard.setLayoutParams(wlp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        top.setGravity(Gravity.CENTER_VERTICAL);
        cardTerm = new TextView(this);
        cardTerm.setTextColor(Color.WHITE);
        styleC(cardTerm, 17, true);
        cardTerm.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        cardTerm.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        top.addView(cardTerm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(speakerButton(() -> cardWord, () -> cardLang));
        // 📄 کلِ جمله را به‌جای لغت/محدوده انتخاب کن
        top.addView(headerButton("\uD83D\uDCC4", v -> {
            if (cardSentence == null || cardSrcTv == null || cardSentence.equals(cardWord)) return;
            String full = cardSrcTv.getText().toString();
            highlight(cardSrcTv, 0, full.length());
            openWordCard(cardSentence, cardLang, cardSentence, cardSrcTv);
        }));
        top.addView(headerButton("\u2715", v -> closeWordCard()));
        wordCard.addView(top);

        cardMeaningTv = new TextView(this);
        cardMeaningTv.setTextColor(Color.parseColor("#E8EAF2"));
        styleC(cardMeaningTv, 15, false);
        cardMeaningTv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        cardMeaningTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        cardMeaningTv.setPadding(0, dp(2), 0, dp(6));
        wordCard.addView(cardMeaningTv);

        cardBtnStory = cardAction("", v -> onCardStory());
        cardBtnGrammar = cardAction("", v -> onCardGrammar());
        cardBtnLeitner = cardAction("", v -> onCardLeitner());
        for (TextView b : new TextView[]{cardBtnStory, cardBtnGrammar, cardBtnLeitner}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            wordCard.addView(b, lp);
        }
    }

    private void refreshCardButtons() {
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

    private void setScrollCompact(boolean compact) { applyScrollMax(); }

    private void closeWordCard() {
        cardSeq++;
        if (cardSrcTv != null) { clearHighlight(cardSrcTv); cardSrcTv = null; }
        refreshRecentEntries();                // متنی که تا الان فریز بود، به آخرین نسخه برمی‌گردد
        if (wordCard == null || wordCard.getVisibility() == View.GONE) { updateScrollFreeze(); return; }
        wordCard.setVisibility(View.GONE);
        updateScrollFreeze();
        setScrollCompact(false);
        refreshLayout();
    }

    private void openWordCard(final String word, final String lang, final String sentence, final TextView srcTv) {
        if (word == null || word.isEmpty() || wm == null || bubble == null) return;
        ensurePanel();
        if (cardSrcTv != null && cardSrcTv != srcTv) {
            clearHighlight(cardSrcTv);
            cardSrcTv = srcTv;
            refreshRecentEntries();
        }
        cardSrcTv = srcTv;
        final int my = ++cardSeq;
        cardWord = word;
        cardLang = (lang == null || lang.isEmpty() || "auto".equals(lang)) ? "en" : lang;
        cardSentence = sentence == null ? word : sentence;
        cardMeaning = "";
        cardStoryDone = cardGrammarDone = cardLeitnerDone = false;
        final String nativeL = targetLang();
        String other = currentSrcLang();
        cardMeaningLang = cardLang.equals(nativeL) ? (other.equals(cardLang) ? "en" : other) : nativeL;

        cardTerm.setText(word);
        cardMeaningTv.setText("…");
        applyStyle(cardTerm);
        applyStyle(cardMeaningTv);
        refreshCardButtons();
        wordCard.setVisibility(View.VISIBLE);
        updateScrollFreeze();
        setScrollCompact(true);
        if (!userHidden) showPanel(); else refreshLayout();

        final String from = cardLang, to = cardMeaningLang;
        if (hasInternet()) {
            try {
                netTr.execute(() -> {
                    String out;
                    try { out = LiveTranslator.translate(this, HTTP_FAST, word, from, to, aiBackend); }
                    catch (Exception ex) { out = ""; }
                    final String res = out == null ? "" : out.trim();
                    main.post(() -> onCardMeaning(my, res));
                });
            } catch (Exception ex) { onCardMeaning(my, ""); }
        } else {
            final Translator tr = localTr;
            if (localReady && tr != null && from.equals(effectiveSource()) && to.equals(primaryTarget())) {
                tr.translate(word)
                        .addOnSuccessListener(o -> onCardMeaning(my, o == null ? "" : o.trim()))
                        .addOnFailureListener(x -> onCardMeaning(my, ""));
            } else {
                onCardMeaning(my, "");
            }
        }
    }

    private void onCardMeaning(int token, String meaning) {
        if (token != cardSeq || cardMeaningTv == null) return;
        if (meaning == null || meaning.isEmpty()) {
            cardMeaningTv.setText(msg("ترجمه در دسترس نیست — ذخیره بدون معنی هم کار می‌کند",
                    "No translation — saving still works"));
            applyStyle(cardMeaningTv);
            return;
        }
        cardMeaning = meaning;
        cardMeaningTv.setText(meaning);
        applyStyle(cardMeaningTv);
        // اگر قبل از رسیدنِ معنی دکمه‌ای زده شده بود، همان ردیف با معنی به‌روز می‌شود (گرامر یک‌بار ثبت می‌شود)
        if (cardStoryDone) queueWord("story");
        if (cardLeitnerDone) queueWord("leitner");
        refreshLayout();
    }

    private void queueWord(String action) {
        if (cardWord == null || cardWord.isEmpty()) return;
        try {
            String lc = cardLang == null ? "en" : cardLang;
            JSONObject o = new JSONObject()
                    .put("key", action + "|" + lc + "|" + cardWord.toLowerCase(Locale.ROOT))
                    .put("action", action)
                    .put("word", cardWord)
                    .put("lang", lc)
                    .put("meaning", cardMeaning == null ? "" : cardMeaning)
                    .put("meaningLang", cardMeaningLang == null ? "fa" : cardMeaningLang)
                    .put("sentence", cardSentence == null ? cardWord : cardSentence)
                    .put("rev", System.currentTimeMillis());
            WordQueue.add(this, o);
            BubblePlugin.notifyWordQueued();
        } catch (Throwable t) {
            showNotice(msg("ذخیره نشد", "Save failed"));
        }
    }

    private void onCardStory()   { if (cardStoryDone) return;   cardStoryDone = true;   queueWord("story");   refreshCardButtons(); }
    private void onCardGrammar() { if (cardGrammarDone) return; cardGrammarDone = true; queueWord("grammar"); refreshCardButtons(); }
    private void onCardLeitner() { if (cardLeitnerDone) return; cardLeitnerDone = true; queueWord("leitner"); refreshCardButtons(); }


    // ════════════════════════════════════════════════════════════════════════════
    //  🔎 اندازه‌ی کادر و فونت: دستگیره‌ی کرکره‌ای (یک انگشت) = ارتفاع ، A−/A+ = اندازه‌ی متن ، Aa = نوع فونت
    // ════════════════════════════════════════════════════════════════════════════

    private static final float FONT_MIN = 0.7f, FONT_MAX = 2.4f;

    /** یک گزینه‌ی فونت. keys == null → فونتِ سیستم (sys؛ null = پیش‌فرض).
     *  وگرنه فایلِ فونت از assets/fonts پیدا می‌شود؛ نامِ دقیقِ فایل مهم نیست، وجودِ کلیدواژه در نام کافی است
     *  (مثلاً BNazanin.ttf ، B Nazanin.ttf ، BNazaninBd.ttf). فونت‌های optional فقط وقتی فایلشان باشد در لیست می‌آیند. */
    private static final class FontOpt {
        final String id, faLabel, enLabel, sys;
        final String[] keys;
        final boolean noBold, tall, optional;
        final float sizeMul;
        boolean resolved;
        Typeface reg, bold;

        FontOpt(String id, String faLabel, String enLabel, String sys, String[] keys,
                boolean noBold, boolean tall, float sizeMul, boolean optional) {
            this.id = id; this.faLabel = faLabel; this.enLabel = enLabel; this.sys = sys; this.keys = keys;
            this.noBold = noBold; this.tall = tall; this.sizeMul = sizeMul; this.optional = optional;
        }
    }

    private static final FontOpt[] FA_FONTS = {
            new FontOpt("fa_default", "پیش‌فرض", "Default", null, null, false, false, 1f, false),
            new FontOpt("fa_bzar", "بی‌زر", "B Zar", null, new String[]{"bzar"}, false, false, 1.08f, false),
            new FontOpt("fa_bnazanin", "بی‌نازنین", "B Nazanin", null, new String[]{"bnazanin", "nazanin"}, false, false, 1.12f, false),
            new FontOpt("fa_btitr", "بی‌تیتر", "B Titr", null, new String[]{"btitr", "titr"}, true, false, 1f, false),
            new FontOpt("fa_nastaliq", "نستعلیق", "Nastaliq", null, new String[]{"nastaliq", "nastaleeq"}, true, true, 1.1f, false),
            new FontOpt("fa_vazir", "وزیرمتن", "Vazirmatn", null, new String[]{"vazir"}, false, false, 1f, true),
    };

    private static final FontOpt[] EN_FONTS = {
            new FontOpt("en_default", "پیش‌فرض", "Default", null, null, false, false, 1f, false),
            new FontOpt("en_medium", "متوسط", "Medium", "sans-serif-medium", null, false, false, 1f, false),
            new FontOpt("en_light", "نازک", "Light", "sans-serif-light", null, false, false, 1f, false),
            new FontOpt("en_serif", "سریف", "Serif", "serif", null, false, false, 1f, false),
            new FontOpt("en_cond", "فشرده", "Condensed", "sans-serif-condensed", null, false, false, 1f, false),
            new FontOpt("en_mono", "تک‌فاصله", "Mono", "monospace", null, false, false, 1f, false),
            new FontOpt("en_inter", "Inter", "Inter", null, new String[]{"inter"}, false, false, 1f, true),
            new FontOpt("en_lora", "Lora", "Lora", null, new String[]{"lora"}, false, false, 1f, true),
    };

    private float fontScale = 1f;
    private String fontFaId = "fa_default";    // فونتِ متنِ فارسی/عربی
    private String fontEnId = "en_default";    // فونتِ متنِ انگلیسی/لاتین
    private float panelHFrac = 0.42f;
    private float panelWFrac = -1f;            // -1 = تمام‌عرض
    private float panelAlpha = 1f;             // شفافیتِ پس‌زمینه‌ی کادر: ۱ = کاملاً مات (solid)، ۰ = کاملاً شفاف
    private LinearLayout opacityRow;
    private LinearLayout fontRow;
    private final ArrayList<TextView> faChips = new ArrayList<>();
    private final ArrayList<TextView> enChips = new ArrayList<>();
    private SeekBar opacityBar;
    private GradientDrawable panelBgDrawable;
    private View gripTop, gripBottom;

    private void loadViewPrefs() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        fontScale = Math.max(FONT_MIN, Math.min(FONT_MAX, sp.getFloat("fontScale", 1f)));
        fontFaId = sp.getString("fontFa", "fa_default");
        fontEnId = sp.getString("fontEn", "en_default");
        panelHFrac = Math.max(0.12f, Math.min(0.85f, sp.getFloat("panelHFrac", 0.42f)));
        panelWFrac = sp.getFloat("panelWFrac", -1f);
        panelAlpha = Math.max(0f, Math.min(1f, sp.getFloat("panelAlphaV2", 1f)));
    }

    private void saveViewPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat("fontScale", fontScale)
                .putString("fontFa", fontFaId).putString("fontEn", fontEnId)
                .putFloat("panelHFrac", panelHFrac).putFloat("panelWFrac", panelWFrac)
                .putFloat("panelAlphaV2", panelAlpha).apply();
    }

    private void applyPanelAlpha() {
        if (panelBgDrawable == null) return;
        int a = Math.round(Math.max(0f, Math.min(1f, panelAlpha)) * 255f);
        panelBgDrawable.setColor((a << 24) | 0x1C2541);
        panel.invalidate();
    }

    /** 🌓 نوارِ تنظیمِ شفافیت (۰ تا ۱۰۰٪) را باز/بسته می‌کند. */
    private void toggleOpacityRow() {
        if (opacityRow == null) return;
        boolean show = opacityRow.getVisibility() != View.VISIBLE;
        opacityRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && fontRow != null) fontRow.setVisibility(View.GONE);
        refreshLayout();
    }

    /** Aa → انتخابِ فونتِ فارسی و فونتِ انگلیسی (جدا از هم). */
    private void toggleFontRow() {
        if (fontRow == null) return;
        boolean show = fontRow.getVisibility() != View.VISIBLE;
        fontRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && opacityRow != null) opacityRow.setVisibility(View.GONE);
        refreshLayout();
    }

    private int panelMaxH() { return Math.max(dp(60), (int) (screenH() * panelHFrac)); }

    private int panelWidthPx() {
        int full = screenW() - dp(24);
        if (panelWFrac < 0) return full;
        return clamp(Math.round(screenW() * panelWFrac), dp(220), full);
    }

    private void applyScrollMax() {
        if (scroll == null) return;
        boolean compact = wordCard != null && wordCard.getVisibility() == View.VISIBLE;
        scroll.maxHeightPx = compact ? Math.max(dp(90), (int) (panelMaxH() * 0.55f)) : panelMaxH();
        scroll.requestLayout();
    }

    // ── فونت: فارسی و انگلیسی جدا؛ نوعِ فونتِ هر متن از خودِ حروفش تشخیص داده می‌شود ──

    private String[] fontAssets;

    private String[] fontAssetList() {
        if (fontAssets == null) {
            try { fontAssets = getAssets().list("fonts"); } catch (IOException ex) { fontAssets = null; }
            if (fontAssets == null) fontAssets = new String[0];
        }
        return fontAssets;
    }

    private static String normName(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\.(ttf|otf)$", "").replaceAll("[^a-z0-9]", "");
    }

    private void resolveFont(FontOpt o) {
        if (o.resolved) return;
        o.resolved = true;
        if (o.keys == null) return;
        String regFile = null, boldFile = null;
        for (String f : fontAssetList()) {
            String lf = f.toLowerCase(Locale.ROOT);
            if (!lf.endsWith(".ttf") && !lf.endsWith(".otf")) continue;
            String n = normName(f);
            boolean hit = false;
            for (String k : o.keys) if (n.contains(k)) { hit = true; break; }
            if (!hit) continue;
            boolean isBold = n.endsWith("bd") || n.contains("bold");
            if (isBold) { if (boldFile == null) boldFile = f; }
            else if (regFile == null) regFile = f;
        }
        if (regFile == null) regFile = boldFile;            // مثلاً BTitrBd.ttf تنها فایلِ موجود است
        if (regFile == null) { Log.w(TAG, "font file not found in assets/fonts for " + o.id); return; }
        try {
            o.reg = Typeface.createFromAsset(getAssets(), "fonts/" + regFile);
            if (boldFile != null && !boldFile.equals(regFile)) o.bold = Typeface.createFromAsset(getAssets(), "fonts/" + boldFile);
            else o.bold = o.noBold ? o.reg : Typeface.create(o.reg, Typeface.BOLD);
        } catch (Throwable t) {
            Log.w(TAG, "font load failed: " + regFile, t);
            o.reg = null; o.bold = null;
        }
    }

    private boolean fontAvailable(FontOpt o) {
        if (o.keys == null) return true;                    // فونتِ سیستم
        resolveFont(o);
        return o.reg != null;
    }

    private Typeface typefaceOf(FontOpt o, boolean bold) {
        if (o.keys != null) {
            resolveFont(o);
            if (o.reg != null) return bold && o.bold != null ? o.bold : o.reg;
        }
        return Typeface.create(o.sys, bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static FontOpt fontById(FontOpt[] arr, String id) {
        for (FontOpt o : arr) if (o.id.equals(id)) return o;
        return arr[0];
    }

    private static boolean hasArabicScript(CharSequence s) {
        if (s == null) return false;
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) return true;
        }
        return false;
    }

    /** اندازه + نوعِ فونتِ یک TextView را ثبت و اعمال می‌کند (برای تغییرِ بعدیِ یک‌جا). */
    private void style(TextView t, float baseSp, boolean bold) {
        t.setTag(new float[]{baseSp, bold ? 1f : 0f, 0f});
        applyStyle(t);
    }

    /** مثلِ style، ولی برای «متنِ محتوا» (جمله، ترجمه، لغت، معنی): فونتِ انتخابی‌ی فارسی/انگلیسی روی آن اعمال می‌شود. */
    private void styleC(TextView t, float baseSp, boolean bold) {
        t.setTag(new float[]{baseSp, bold ? 1f : 0f, 1f});
        applyStyle(t);
    }

    private void applyStyle(TextView t) {
        Object tag = t.getTag();
        if (!(tag instanceof float[])) return;
        float[] st = (float[]) tag;
        boolean bold = st[1] > 0;
        FontOpt o = null;
        if (st.length > 2 && st[2] > 0) {
            o = hasArabicScript(t.getText()) ? fontById(FA_FONTS, fontFaId) : fontById(EN_FONTS, fontEnId);
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, st[0] * fontScale * (o != null ? o.sizeMul : 1f));
        if (o == null) {
            t.setTypeface(Typeface.create((String) null, bold ? Typeface.BOLD : Typeface.NORMAL));
            return;
        }
        t.setTypeface(typefaceOf(o, bold && !o.noBold));
        t.setLineSpacing(0f, o.tall ? 1.3f : 1f);           // نستعلیق بلند است؛ بدونِ این، بالا/پایینِ حروف بریده می‌شود
    }

    private void restyle(View v) {
        if (v instanceof TextView) applyStyle((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) restyle(g.getChildAt(i));
        }
    }

    private void relayoutAfterStyle() {
        if (panel == null) return;
        restyle(panel);
        panel.requestLayout();
        refreshLayout();
    }

    private void setFontScale(float v, boolean persist) {
        float q = Math.round(Math.max(FONT_MIN, Math.min(FONT_MAX, v)) * 20f) / 20f;   // قدم‌های ۰٫۰۵ تا لرزش/کندی نداشته باشد
        if (Math.abs(q - fontScale) < 0.001f) { if (persist) saveViewPrefs(); return; }
        fontScale = q;
        relayoutAfterStyle();
        if (persist) saveViewPrefs();
    }

    // ── ردیفِ انتخابِ فونت (زیرِ هدرِ پنل) ──

    private View buildFontRow() {
        fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.VERTICAL);
        fontRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        fontRow.setVisibility(View.GONE);
        fontRow.setPadding(0, dp(2), 0, dp(6));
        faChips.clear(); enChips.clear();
        fontRow.addView(fontLine("فارسی", FA_FONTS, true));
        fontRow.addView(fontLine("English", EN_FONTS, false));
        refreshFontChips();
        return fontRow;
    }

    private View fontLine(String label, FontOpt[] opts, boolean fa) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        line.setPadding(0, dp(2), 0, dp(2));

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextColor(COLOR_GOLD);
        lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        lb.setTypeface(Typeface.DEFAULT_BOLD);
        lb.setMinWidth(dp(50));
        line.addView(lb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (FontOpt o : opts) {
            if (o.optional && !fontAvailable(o)) continue;
            TextView c = fontChip(o, fa);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            chips.addView(c, lp);
            (fa ? faChips : enChips).add(c);
        }
        hs.addView(chips, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    private TextView fontChip(final FontOpt o, final boolean fa) {
        TextView c = new TextView(this);
        c.setTag(o);
        c.setText(fa ? o.faLabel : o.enLabel);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * (fa ? o.sizeMul : 1f));
        c.setGravity(Gravity.CENTER);
        c.setSingleLine(true);
        c.setPadding(dp(12), dp(o.tall ? 8 : 5), dp(12), dp(o.tall ? 8 : 5));
        c.setLineSpacing(0f, o.tall ? 1.25f : 1f);
        c.setTypeface(typefaceOf(o, false));                 // پیش‌نمایش: نامِ هر فونت با خودِ همان فونت
        c.setOnClickListener(v -> pickFont(o, fa));
        return c;
    }

    private void refreshFontChips() {
        paintChips(faChips, fontFaId);
        paintChips(enChips, fontEnId);
    }

    private void paintChips(ArrayList<TextView> chips, String sel) {
        for (TextView c : chips) {
            FontOpt o = (FontOpt) c.getTag();
            boolean on = o.id.equals(sel);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            g.setColor(on ? COLOR_GOLD : Color.parseColor("#26FFFFFF"));
            c.setBackground(g);
            c.setTextColor(on ? Color.parseColor("#1C2541") : Color.WHITE);
            c.setAlpha(fontAvailable(o) ? 1f : 0.4f);
        }
    }

    private void pickFont(FontOpt o, boolean fa) {
        if (!fontAvailable(o)) {
            showNotice(msg("فایلِ فونتِ «" + o.faLabel + "» داخل assets/fonts پیدا نشد",
                    "Font file for " + o.enLabel + " not found in assets/fonts"));
            return;
        }
        if (fa) fontFaId = o.id; else fontEnId = o.id;
        refreshFontChips();
        relayoutAfterStyle();
        saveViewPrefs();
    }

    private void resetView() {
        fontScale = 1f; fontFaId = "fa_default"; fontEnId = "en_default";
        panelHFrac = 0.42f; panelWFrac = -1f; panelAlpha = 1f;
        if (opacityBar != null) opacityBar.setProgress(100);
        applyPanelAlpha();
        applyScrollMax();
        refreshFontChips();
        relayoutAfterStyle();
        saveViewPrefs();
        showNotice(msg("اندازه و فونت به حالت اولیه برگشت", "Size and font reset"));
    }

    /** دستگیره‌ی «کرکره‌ای»: یک خطِ باریک؛ با یک انگشت به‌سمتِ بیرون بکش = کادر باز می‌شود، به‌سمتِ داخل = جمع می‌شود.
     *  سمتِ راستِ همان نوار یک کپسولِ کوچکِ A− | A+ برای اندازه‌ی متن است. */
    @SuppressLint("ClickableViewAccessibility")
    private View makeGrip(final boolean atBottom) {
        final FrameLayout f = new FrameLayout(this);

        final GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#59FFFFFF"));
        pillBg.setCornerRadius(dp(2));
        final View pill = new View(this);
        pill.setBackground(pillBg);
        f.addView(pill, new FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER));

        // کپسولِ اندازه‌ی متن
        LinearLayout cap = new LinearLayout(this);
        cap.setOrientation(LinearLayout.HORIZONTAL);
        cap.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        cap.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable capBg = new GradientDrawable();
        capBg.setColor(Color.parseColor("#1AFFFFFF"));
        capBg.setCornerRadius(dp(10));
        cap.setBackground(capBg);
        cap.addView(fontStep("A\u2212", 11, -0.1f));
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        cap.addView(div, new LinearLayout.LayoutParams(dp(1), dp(12)));
        cap.addView(fontStep("A+", 14, +0.1f));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(22),
                (atBottom ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        if (atBottom) clp.setMarginStart(dp(2)); else clp.setMarginEnd(dp(2));
        f.addView(cap, clp);

        // 🎙 دکمه‌ی ضبطِ shadowing (پایین-راستِ کادر)
        if (atBottom) {
            LinearLayout sh = new LinearLayout(this);
            sh.setOrientation(LinearLayout.HORIZONTAL);
            sh.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            sh.setGravity(Gravity.CENTER_VERTICAL);
            tvShadowPlay = new TextView(this);
            tvShadowPlay.setText("\u25B6");
            tvShadowPlay.setTextColor(Color.WHITE);
            tvShadowPlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvShadowPlay.setGravity(Gravity.CENTER);
            tvShadowPlay.setPadding(dp(10), 0, dp(10), 0);
            tvShadowPlay.setVisibility(View.GONE);
            tvShadowPlay.setOnClickListener(v -> shadowPlay());
            sh.addView(tvShadowPlay, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tvShadowRec = new TextView(this);
            tvShadowRec.setTextColor(Color.WHITE);
            tvShadowRec.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvShadowRec.setGravity(Gravity.CENTER);
            tvShadowRec.setPadding(dp(10), 0, dp(10), 0);
            tvShadowRec.setOnClickListener(v -> shadowToggle());
            sh.addView(tvShadowRec, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            updateShadowUi();
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(22), Gravity.END | Gravity.CENTER_VERTICAL);
            slp.setMarginEnd(dp(2));
            f.addView(sh, slp);
        }

        final float[] d = new float[2];      // downY, startH(frac)
        f.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    d[0] = ev.getRawY(); d[1] = panelHFrac;
                    pillBg.setColor(COLOR_GOLD);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = (ev.getRawY() - d[0]) * (atBottom ? 1f : -1f);
                    panelHFrac = Math.max(0.12f, Math.min(0.85f, d[1] + dy / screenH()));
                    if (scroll != null) scroll.atBottom = false;
                    applyScrollMax();
                    refreshLayout();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pillBg.setColor(Color.parseColor("#59FFFFFF"));
                    saveViewPrefs();
                    return true;
            }
            return true;
        });
        return f;
    }

    private TextView fontStep(String label, int sp, final float delta) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor("#B3FFFFFF"));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(34));
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setOnClickListener(v -> setFontScale(fontScale + delta, true));
        return t;
    }

    private Entry newEntry() {
        ensurePanel();
        final Entry e = new Entry();
        e.box = new LinearLayout(this);
        e.box.setOrientation(LinearLayout.VERTICAL);
        e.box.setPadding(0, dp(4), 0, dp(8));

        e.tvSrc = new TextView(this);
        e.tvSrc.setTextColor(Color.parseColor("#C8CCD8"));
        styleC(e.tvSrc, 13, false);
        e.tvSrc.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        e.tvSrc.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);

        e.rowsBox = new LinearLayout(this);
        e.rowsBox.setOrientation(LinearLayout.VERTICAL);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.topMargin = dp(8);

        e.srcLang = currentSrcLang();
        attachSelect(e.tvSrc, () -> e.srcLang);
        TextView srcSpeak = speakerButton(() -> e.src, () -> e.srcLang, e.tvSrc, e, "src");
        e.srcSpeakBtn = srcSpeak;
        e.srcRow = new LinearLayout(this);
        e.srcRow.setOrientation(LinearLayout.HORIZONTAL);
        e.srcRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        e.srcRow.setBaselineAligned(false);     // ← علتِ بریده‌شدنِ خطِ آخر: هم‌ترازیِ baseline متن را چند dp پایین می‌برد
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        srcLp.setMarginStart(dp(2));
        e.srcRow.addView(e.tvSrc, srcLp);
        e.srcRow.addView(srcSpeak, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView replay = new TextView(this);
        replay.setText(replayLabel());
        style(replay, 20, true);
        replay.setTextColor(Color.WHITE);
        replay.setGravity(Gravity.CENTER);
        replay.setMinWidth(dp(40));
        replay.setMinHeight(dp(40));
        replay.setPadding(dp(4), dp(4), dp(4), dp(4));
        replay.setVisibility(View.GONE);                    // فقط در لیستِ زیرنویسِ یوتیوب دیده می‌شود
        replay.setOnClickListener(v -> replayInYoutube(e));
        e.ytReplayBtn = replay;
        e.srcRow.addView(replay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        e.box.addView(e.srcRow);
        e.box.addView(e.rowsBox);
        e.box.addView(divider, dlp);
        listBox.addView(e.box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        history.add(e);
        while (!ytListMode && history.size() > MAX_HISTORY) {
            Entry old = history.get(0);
            if (old == live) break;
            history.remove(0);
            listBox.removeView(old.box);
            ytEntries.values().remove(old);
        }
        return e;
    }

    /** بعد از seek به عقب، خطِ تازه سرِ جای درستِ خودش (به ترتیبِ زمانِ زیرنویس) می‌نشیند. */
    private void placeByCueIndex(Entry e) {
        int pos = 0;
        for (int i = 0; i < history.size(); i++) {
            Entry h = history.get(i);
            if (h == e) continue;
            if (h.ytIdx < 0 || h.ytIdx < e.ytIdx) pos = i + 1;
        }
        int cur = history.indexOf(e);
        if (cur == pos || (cur == history.size() - 1 && pos >= history.size() - 1)) return;
        history.remove(e);
        listBox.removeView(e.box);
        if (pos > history.size()) pos = history.size();
        history.add(pos, e);
        listBox.addView(e.box, pos, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void scrollToEntry(final Entry e) {
        if (scroll == null || e == null || e.box == null) return;
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, e.box.getTop() - dp(4))));
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
        style(tag, 10, true);
        tag.setMinWidth(dp(26));
        tag.setPadding(dp(4), dp(5), 0, 0);

        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        styleC(tv, 16, true);
        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);

        attachSelect(tv, () -> lang);
        TextView rowSpeak = speakerButton(() -> tv.getText().toString(), () -> lang, tv, e, lang);
        e.rowSpeakBtn.put(lang, rowSpeak);
        row.setBaselineAligned(false);
        row.addView(tag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginStart(dp(2));
        row.addView(tv, tvLp);
        row.addView(rowSpeak, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

        setTextIfChanged(e.tvSrc, typedSrc(e));
        e.srcRow.setVisibility(showSrc && !e.src.isEmpty() ? View.VISIBLE : View.GONE);

        for (String t : ts) {
            if (!e.rowBox.containsKey(t)) makeRow(e, t);   // created in target order -> stable order on screen
            LinearLayout row = e.rowBox.get(t);
            String v = e.tr.get(t);
            boolean has = v != null && !v.isEmpty();
            row.setVisibility(showTr && has ? View.VISIBLE : View.GONE);
            if (has) setTextIfChanged(e.rowText.get(t), v);
        }
        for (Map.Entry<String, LinearLayout> r : e.rowBox.entrySet()) {
            if (!ts.contains(r.getKey())) r.getValue().setVisibility(View.GONE);
        }
    }

    /** متنِ بدونِ تغییر دوباره ست نمی‌شود، و متنی که کاربر دارد انتخابش می‌کند (یا کادرِ لغتش باز است) دست‌نخورده می‌ماند. */
    private void setTextIfChanged(TextView tv, String v) {
        if (tv == null || v == null) return;
        if (tv == cardSrcTv || tv == selectingTv || tv == speakTv) return;
        CharSequence cur = tv.getText();
        if (!(cur instanceof Spanned) && cur.toString().equals(v)) return;
        tv.setText(v);
        applyStyle(tv);
    }

    private void updateScrollFreeze() {
        if (scroll == null) return;
        scroll.frozen = selectingTv != null || speakTv != null
                || (wordCard != null && wordCard.getVisibility() == View.VISIBLE);
    }

    private void refreshRecentEntries() {
        for (int i = Math.max(0, history.size() - 6); i < history.size(); i++) renderEntry(history.get(i));
    }

    private void setTranslation(Entry e, String lang, String text, String fromSrc) {
        final String before = e.tr.get(lang);
        e.tr.put(lang, text == null ? "" : text);
        e.trSrc.put(lang, fromSrc == null ? "" : fromSrc);
        renderEntry(e);
        afterChange();
        softSwap(e, lang, before, text);
    }

    private static String stripDots(String v) {
        if (v == null) return "";
        v = v.trim();
        return v.endsWith("…") ? v.substring(0, v.length() - 1).trim() : v;
    }

    /** وقتی ترجمه‌ی نمایش‌داده‌شده واقعاً «بازنویسی» می‌شود (نه فقط ادامه‌دار)، نرم محو/ظاهر می‌شود تا ناگهان نپرد. */
    private void softSwap(Entry e, String lang, String before, String after) {
        try {
            final String b = stripDots(before), a = stripDots(after);
            if (b.isEmpty() || a.isEmpty() || a.equals(b) || a.startsWith(b) || b.startsWith(a)) return;
            final TextView tv = e.rowText.get(lang);
            if (tv == null || tv.getVisibility() != View.VISIBLE || tv == selectingTv || tv == cardSrcTv) return;
            tv.setAlpha(0.2f);
            tv.animate().alpha(1f).setDuration(280).start();
        } catch (Throwable ignored) {}
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

    private static String[] words(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    private static String joinWords(String[] w, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) { if (i > from) sb.append(' '); sb.append(w[i]); }
        return sb.toString();
    }

    // ───────── ✍️ تایپِ حرف‌به‌حرفِ متنِ زنده ─────────
    private final java.util.LinkedHashSet<Entry> typing = new java.util.LinkedHashSet<>();
    private boolean typeScheduled = false;
    private final Runnable typeRunnable = () -> {
        typeScheduled = false;
        for (Entry e : new ArrayList<>(typing)) {
            int len = e.src == null ? 0 : e.src.length();
            if (e.box == null || e.shownChars >= len) { typing.remove(e); continue; }
            int back = len - e.shownChars;
            e.shownChars += back > 120 ? 6 : back > 60 ? 3 : back > 25 ? 2 : 1;
            renderEntry(e);                       // اگه هنوز عقبه، typedSrc دوباره اضافه‌اش می‌کنه
        }
        if (!typing.isEmpty()) scheduleType();
        queueYtRefresh();
    };

    private void scheduleType() {
        if (typeScheduled) return;
        typeScheduled = true;
        main.postDelayed(typeRunnable, 28);
    }

    /** متنِ اصلیِ خطِ زنده: فقط تا حرفِ «رسیده» نشون داده می‌شه و بقیه یکی‌یکی ظاهر می‌شن. */
    private String typedSrc(Entry e) {
        String full = e.src == null ? "" : e.src;
        if (e.shownChars == Integer.MAX_VALUE) return full;
        if (full.isEmpty()) return full;
        if (e.shownChars >= full.length()) { e.shownChars = full.length(); return full; }
        if (e.shownChars < 1) e.shownChars = 1;
        int cut = e.shownChars;
        if (cut < full.length() && Character.isHighSurrogate(full.charAt(cut - 1))) cut++;
        typing.add(e);
        scheduleType();
        return full.substring(0, cut);
    }

    private void cancelLivePending() {
        main.removeCallbacks(srcRenderRunnable);
        srcRenderScheduled = false;
        main.removeCallbacks(pauseCommitRunnable);
        pendingHyp = "";
    }

    /** هر تکانِ تشخیصِ گفتار فقط «آخرین فرضیه» را ذخیره می‌کند؛ نمایش با ریتمِ ثابت و نرم به‌روز می‌شود. */
    private void onPartialText(final String raw) {
        if (wm == null || bubble == null || raw == null || raw.isEmpty()) return;
        if (asrMuted()) return;
        wakeFromIdle();
        final String text = stripConsumed(raw);          // بخشِ قبلاً‌بسته‌شده (خط‌های جدا) دوباره نمایش داده نشود
        if (text.isEmpty()) return;
        pendingHyp = text;
        if (live == null) live = newLiveEntry();
        clearStatus();
        long wait = lastSrcRenderAt + SRC_RENDER_MS - SystemClock.uptimeMillis();
        if (wait <= 0) {
            main.removeCallbacks(srcRenderRunnable);
            srcRenderScheduled = false;
            flushPartial(false);
        } else if (!srcRenderScheduled) {
            srcRenderScheduled = true;
            main.postDelayed(srcRenderRunnable, wait);
        }
        main.removeCallbacks(pauseCommitRunnable);
        main.postDelayed(pauseCommitRunnable, pauseMs());
    }

    private long pauseMs() {
        if (sherpaEngine instanceof WhisperEngine) return PAUSE_COMMIT_SONG_MS;
        if (!micEngine) return SRV_PAUSE_COMMIT_MS;       // حالت سرور: پیش‌نمایش‌ها فاصله دارند
        return looksIncomplete(pendingHyp) ? SENT_PAUSE_INCOMPLETE_MS : SENT_PAUSE_MS;
    }

    /** تشخیصِ گفتارِ زنده (Sherpa/Google) متنِ «کلِ گفتارِ جاری» را می‌دهد؛ حالتِ آهنگ (Whisper) پنجره‌ای است و تجمعی نیست. */
    private boolean cumulativeAsr() {
        return !(sherpaEngine instanceof WhisperEngine);
    }

    private String stripConsumed(String raw) {
        if (consumedWords <= 0 || !cumulativeAsr()) return raw;
        String[] w = words(raw);
        if (w.length <= consumedWords) return "";
        return joinWords(w, consumedWords, w.length);
    }

    private Entry newLiveEntry() {
        Entry e = newEntry();
        e.shownChars = 0;                 // متنِ زنده حرف‌به‌حرف تایپ می‌شود
        try {
            YtMedia.Now n = YtMedia.nowPlaying(this);
            if (n != null && n.posMs >= 0) e.mediaPosMs = Math.max(0, n.posMs - 800);   // ~تأخیرِ تشخیصِ گفتار
        } catch (Throwable ignored) {}
        return e;
    }

    // ───────── جمله‌به‌جمله ─────────

    private static boolean endsSentence(String word) {
        if (word == null || word.isEmpty()) return false;
        int n = word.length();
        while (n > 0 && "\"'\u201D\u2019)\u00BB]".indexOf(word.charAt(n - 1)) >= 0) n--;
        if (n == 0) return false;
        char c = word.charAt(n - 1);
        if (c == '!' || c == '?' || c == '\u061F' || c == '\u2026') return true;
        if (c != '.') return false;
        String low = word.substring(0, n).toLowerCase(Locale.ROOT);
        if (ABBREVIATIONS.contains(low)) return false;
        if (n == 2 && Character.isUpperCase(word.charAt(0))) return false;        // initials: J.
        return true;
    }

    /** اولین پایانِ جمله در w[from, limit): اندیسِ «بعد از» کلمه‌ی پایانی، یا -1. */
    private static int sentenceEnd(String[] w, int from, int limit) {
        for (int i = from; i < limit && i < w.length; i++) if (endsSentence(w[i])) return i + 1;
        return -1;
    }

    /** برای جمله‌ی بلندِ بدونِ نقطه: نزدیک‌ترین ویرگول/نقطه، بعد حرفِ ربط، در بازه‌ی [from+6, from+maxLen]. */
    private static int pickLineBreak(String[] w, int from, int maxLen) {
        int hi = Math.min(w.length, from + maxLen);
        int lo = Math.min(hi, from + 6);
        for (int i = hi; i >= lo; i--) {
            String x = w[i - 1];
            char c = x.isEmpty() ? ' ' : x.charAt(x.length() - 1);
            if (",;:\u060C".indexOf(c) >= 0 || endsSentence(x)) return i;
        }
        for (int i = hi - 1; i >= lo; i--) {
            if (BREAK_BEFORE.contains(w[i].toLowerCase(Locale.ROOT))) return i;
        }
        for (int i = hi; i >= lo; i--) {                               // برشِ اجباری: هرگز بعد از «the/of/to/and…» نه
            if (!endsDangling(w[i - 1])) return i;
        }
        return hi;
    }

    /** کلماتی که یک جمله با آن‌ها «تمام نمی‌شود»؛ اگر آخرِ متن بودند یعنی گوینده هنوز ادامه می‌دهد. */
    private static final java.util.Set<String> DANGLING = new HashSet<>(Arrays.asList(
            "a", "an", "the", "and", "but", "or", "so", "because", "that's", "which", "who", "whom", "whose",
            "when", "where", "while", "if", "though", "although", "until", "unless", "as", "than", "of", "to",
            "in", "on", "at", "by", "for", "with", "from", "into", "about", "over", "under", "between",
            "through", "during", "before", "after", "is", "are", "was", "were", "am", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "can", "could", "should", "shall",
            "may", "might", "must", "not", "my", "your", "his", "our", "their", "its", "some", "any", "very",
            "i", "we", "they", "he", "she", "i'm", "i've", "i'll", "i'd", "we're", "they're", "you're",
            "he's", "she's", "it's", "there's", "what", "how", "why", "also", "just", "even", "then"));

    private static boolean endsDangling(String word) {
        if (word == null || word.isEmpty()) return false;
        char last = word.charAt(word.length() - 1);
        if (",.;:!?\u061F\u060C\u2026".indexOf(last) >= 0) return false;       // علامتِ پایان/ویرگول دارد
        String low = word.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        low = low.replaceAll("^[\"'(\\[]+|[\"')\\]]+$", "");
        return DANGLING.contains(low);
    }

    /** آیا متنِ زنده هنوز ناتمام به‌نظر می‌رسد؟ (خیلی کوتاه یا آخرش کلمه‌ی «آویزان») */
    private static boolean looksIncomplete(String text) {
        String[] w = words(text);
        if (w.length == 0) return false;
        return w.length < 4 || endsDangling(w[w.length - 1]);
    }

    /** متنِ چندجمله‌ای/بلند را به خط‌هایی (هر کدام یک جمله) می‌شکند. */
    private static List<String> splitSentences(String text) {
        final ArrayList<String> out = new ArrayList<>();
        final String[] w = words(text);
        int from = 0;
        while (from < w.length) {
            int end = sentenceEnd(w, from, w.length);
            if (end < 0) break;
            out.add(joinWords(w, from, end));
            from = end;
        }
        while (w.length - from > MAX_LINE_WORDS + 4) {
            int end = pickLineBreak(w, from, MAX_LINE_WORDS);
            if (end <= from) break;
            out.add(joinWords(w, from, end));
            from = end;
        }
        if (from < w.length) out.add(joinWords(w, from, w.length));
        if (out.isEmpty()) out.add(text == null ? "" : text.trim());
        return out;
    }

    private void commitSentences(String text) {
        for (String part : splitSentences(text)) {
            if (!part.trim().isEmpty()) commitSentence(part);
        }
    }

    /** مکثِ بلند: جمله‌ی جاری همین‌جا بسته می‌شود و گفتارِ بعدی در خطِ تازه می‌آید. */
    private void endSentenceByPause() {
        final String hyp = pendingHyp;
        if (hyp == null || hyp.isEmpty()) return;
        final int n = words(hyp).length;
        consumedWords += n;
        commitSentences(hyp);
    }

    /** وسطِ گفتار: اگر نقطه/علامتِ پایانی در متنِ زنده آمد یا خط خیلی بلند شد، بخشِ اول به خطِ جدا بسته می‌شود. */
    private boolean splitLiveIfNeeded(String next) {
        final String[] w = words(next);
        final int n = w.length;
        int end = sentenceEnd(w, 0, n - 2);                   // علامتِ پایان در دو کلمه‌ی آخر هنوز ممکن است عوض شود
        if (end < 0 && n >= MAX_LINE_WORDS + TAIL_GUARD_WORDS) end = pickLineBreak(w, 0, MAX_LINE_WORDS);
        if (end <= 0 || end >= n) return false;
        final String head = joinWords(w, 0, end);
        final String tail = joinWords(w, end, n);
        consumedWords += end;
        commitSentences(head);                                // live = null؛ تایمرها هم پاک می‌شوند
        live = newLiveEntry();
        live.src = tail;
        pendingHyp = tail;
        lastSrcRenderAt = SystemClock.uptimeMillis();
        renderEntry(live);
        afterChange();
        main.postDelayed(pauseCommitRunnable, pauseMs());
        if (!activeTargets().isEmpty()) commitChunks(live, false);
        flushPartial(false);                                  // ممکن است باقیِ متن هم جمله‌ی کامل داشته باشد
        return true;
    }

    private void flushPartial(boolean pause) {
        final Entry e = live;
        if (e == null || pendingHyp.isEmpty()) return;
        final String next = smoothHyp(e.src, pendingHyp);
        lastSrcRenderAt = SystemClock.uptimeMillis();
        if (cumulativeAsr() && splitLiveIfNeeded(next)) return;
        if (!next.equals(e.src)) {
            e.src = next;
            renderEntry(e);
            afterChange();
        }
        if (!activeTargets().isEmpty()) { commitChunks(e, pause); requestTail(e); }
    }

    /** اصلاحِ کوچکِ انتهای جمله (مثلاً beings↔being) متنِ روی صفحه را عقب‌وجلو نمی‌کند. */
    private static String smoothHyp(String shown, String hyp) {
        if (shown == null || shown.isEmpty() || hyp.startsWith(shown)) return hyp;
        String[] a = words(shown), b = words(hyp);
        int common = 0;
        while (common < a.length && common < b.length && a[common].equals(b[common])) common++;
        if (b.length <= a.length && a.length - common <= 2 && b.length - common <= 2) return shown;
        return hyp;
    }

    // ───────── تکه‌تکه کردنِ جمله‌ی زنده ─────────
    // کلمه‌های پایدار (همه به‌جز ۳ کلمه‌ی آخر) در تکه‌های ۷ تا ۱۲ کلمه‌ای «قفل» و هر تکه فقط یک‌بار ترجمه می‌شود.
    // ترجمه‌ی قبلی هیچ‌وقت عوض نمی‌شود؛ فقط ترجمه‌ی تکه‌ی بعدی به انتهایش اضافه می‌شود → دیگر نمی‌پرد.

    private void reconcileChunks(Entry e, String[] w) {
        int keep = 0, kept = 0;
        for (int i = 0; i < e.chunkSrc.size(); i++) {
            String[] cw = words(e.chunkSrc.get(i));
            if (kept + cw.length > w.length) break;
            boolean same = true;
            for (int j = 0; j < cw.length; j++) {
                if (!cw[j].equals(w[kept + j])) { same = false; break; }
            }
            if (!same) break;
            kept += cw.length;
            keep = i + 1;
        }
        while (e.chunkSrc.size() > keep) removeChunk(e, e.chunkSrc.size() - 1);
        e.committedWords = kept;
    }

    private void removeChunk(Entry e, int idx) {
        e.chunkSrc.remove(idx);
        for (ArrayList<String> l : e.chunkTr.values()) if (idx < l.size()) l.remove(idx);
    }

    private static int pickBreak(String[] w, int from, int limit, boolean flushAll) {
        if (flushAll && limit - from <= CHUNK_MAX_WORDS + 2) return limit;
        int maxEnd = Math.min(limit, from + CHUNK_MAX_WORDS);
        for (int i = maxEnd; i >= from + 3; i--) {                     // اولویت: آخرین نقطه/ویرگول
            String x = w[i - 1];
            char c = x.isEmpty() ? ' ' : x.charAt(x.length() - 1);
            if (",.;:?!\u061F\u060C".indexOf(c) >= 0) return i;
        }
        for (int i = maxEnd - 1; i >= from + 4; i--) {                 // بعد: قبل از حرفِ ربط
            if (BREAK_BEFORE.contains(w[i].toLowerCase(Locale.ROOT))) return i;
        }
        for (int i = maxEnd; i >= from + CHUNK_MIN_WORDS; i--) {       // تکه را به «the/of/to/and…» ختم نکن
            if (!endsDangling(w[i - 1])) return i;
        }
        return maxEnd;
    }

    private void commitChunks(Entry e, boolean flushAll) {
        final String[] w = words(e.src);
        reconcileChunks(e, w);
        int limit = flushAll ? w.length : w.length - TAIL_GUARD_WORDS;
        if (flushAll) {                                           // مکثِ کوتاه وسطِ جمله: کلماتِ آویزانِ آخر قفل/ترجمه نشوند
            while (limit > e.committedWords + 1 && endsDangling(w[limit - 1])) limit--;
        }
        while (true) {
            int from = e.committedWords;
            if (limit - from < (flushAll ? 1 : CHUNK_MIN_WORDS)) break;
            int end = pickBreak(w, from, limit, flushAll);
            if (end <= from) break;
            addChunk(e, joinWords(w, from, end));
            e.committedWords = end;
        }
    }

    private void addChunk(Entry e, String text) {
        final int idx = e.chunkSrc.size();
        e.chunkSrc.add(text);
        for (String lang : activeTargets()) {
            ArrayList<String> l = e.chunkTr.get(lang);
            if (l == null) { l = new ArrayList<>(); e.chunkTr.put(lang, l); }
            int have = l.size();
            while (l.size() <= idx) l.add("");
            for (int i = have; i <= idx; i++) requestChunk(e, i, e.chunkSrc.get(i), lang);
        }
    }

    /** کش → سرویس‌های رایگان (+ بررسیِ مشکوک بودن/AI) → در آخر مترجمِ آفلاین. */
    private void requestChunk(final Entry e, final int idx, final String text, final String lang) {
        final String srcLang = effectiveSource();
        final boolean first = lang.equals(primaryTarget());
        String hit = TransCache.get(this).get(srcLang, lang, text);
        if (hit != null && !LiveTranslator.looksLikelyMistranslated(text, hit, lang, srcLang)) {
            applyChunk(e, idx, text, lang, hit);
            return;
        }
        if (!hasInternet()) { localChunk(e, idx, text, lang, first); return; }
        try {
            netPartial.execute(() -> {
                String out = null;
                try { out = LiveTranslator.translate(this, HTTP_FAST, text, srcLang, lang, aiBackend); }
                catch (Exception ex) { Log.d(TAG, "chunk translate failed: " + ex); }
                final String res = out;
                main.post(() -> {
                    if (res != null) applyChunk(e, idx, text, lang, res);
                    else localChunk(e, idx, text, lang, first);
                });
            });
        } catch (Exception ex) { localChunk(e, idx, text, lang, first); }
    }

    private void localChunk(final Entry e, final int idx, final String text, final String lang, boolean first) {
        final Translator tr = localTr;
        if (first && localReady && tr != null) {
            tr.translate(text)
                    .addOnSuccessListener(o -> applyChunk(e, idx, text, lang, o))
                    .addOnFailureListener(x -> applyChunk(e, idx, text, lang, null));
        } else {
            applyChunk(e, idx, text, lang, null);
        }
    }

    private void applyChunk(Entry e, int idx, String text, String lang, String out) {
        if (idx >= e.chunkSrc.size() || !e.chunkSrc.get(idx).equals(text)) return;     // تکه در این بین عوض/حذف شده
        ArrayList<String> l = e.chunkTr.get(lang);
        if (l == null || idx >= l.size()) return;
        l.set(idx, (out == null || out.trim().isEmpty()) ? CHUNK_FAIL : out.trim());
        refreshLiveTr(e, lang);
        if (e == live) requestTail(e);
    }

    /** ترجمه‌ی نمایش‌داده‌شده = تکه‌های قفل‌شده + ترجمه‌ی زنده‌ی دنباله‌ی جمله. */
    private void refreshLiveTr(Entry e, String lang) {
        String ts = e.trSrc.get(lang);
        if (ts != null && !ts.isEmpty()) return;          // ترجمه‌ی کاملِ جمله قبلاً نشسته؛ تکه‌ها بازنویسی‌اش نکنند
        ArrayList<String> l = e.chunkTr.get(lang);
        StringBuilder sb = new StringBuilder();
        boolean pending = false;
        if (l != null) {
            for (String v : l) {
                if (v.isEmpty()) { pending = true; break; }
                if (v.equals(CHUNK_FAIL)) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(v);
            }
        }
        String shown = e.tr.get(lang);
        boolean hasShown = shown != null && !shown.isEmpty() && !shown.equals("…");
        if (pending && hasShown) return;                  // تکه‌ی بعدی در راه است؛ ترجمه‌ی روی صفحه کوتاه نشود
        if (!pending) {
            String tt = e.tailTr.get(lang);
            Integer tf = e.tailTrFrom.get(lang);
            if (e == live && tt != null && !tt.isEmpty() && tf != null && tf == e.committedWords) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(tt);
            }
        }
        if (sb.length() == 0) return;
        String out = e == live ? sb + " …" : sb.toString();
        if (out.equals(shown)) return;
        e.tr.put(lang, out);
        e.trSrc.put(lang, "");
        renderEntry(e);
        afterChange();
    }

    /** ترجمه‌ی فوریِ «دنباله‌ی» جمله‌ی زنده (بدونِ منتظر ماندن برای تکمیلِ تکه‌ها). */
    private void requestTail(final Entry e) {
        if (e == null || e != live) return;
        final List<String> ts = activeTargets();
        if (ts.isEmpty()) return;
        final String[] w = words(e.src);
        final int from = e.committedWords;
        if (from >= w.length) { e.tailTr.clear(); e.tailAsked.clear(); return; }
        final String tail = joinWords(w, from, w.length);
        final long now = SystemClock.uptimeMillis();
        if (now - e.tailLastAt < TAIL_MIN_MS) {
            if (!e.tailScheduled) {
                e.tailScheduled = true;
                main.postDelayed(() -> { e.tailScheduled = false; if (e == live) requestTail(e); },
                        TAIL_MIN_MS - (now - e.tailLastAt) + 10);
            }
            return;
        }
        final String srcLang = effectiveSource();
        for (final String lang : ts) {
            Integer askedFrom = e.tailTrFrom.get(lang);
            if (tail.equals(e.tailAsked.get(lang)) && askedFrom != null && askedFrom == from) continue;
            if (Boolean.TRUE.equals(e.tailBusy.get(lang))) continue;     // وقتی جوابِ قبلی رسید، دوباره بررسی می‌شود
            e.tailLastAt = now;
            e.tailAsked.put(lang, tail);
            final boolean first = lang.equals(primaryTarget());
            String hit = TransCache.get(this).get(srcLang, lang, tail);
            if (hit != null && !LiveTranslator.looksLikelyMistranslated(tail, hit, lang, srcLang)) {
                applyTail(e, lang, from, hit);
                continue;
            }
            e.tailBusy.put(lang, true);
            if (!hasInternet()) {
                final Translator tr = localTr;
                if (first && localReady && tr != null) {
                    tr.translate(tail)
                            .addOnSuccessListener(o -> { e.tailBusy.put(lang, false); applyTail(e, lang, from, o); })
                            .addOnFailureListener(x -> e.tailBusy.put(lang, false));
                } else e.tailBusy.put(lang, false);
                continue;
            }
            try {
                netPartial.execute(() -> {
                    String out = null;
                    try { out = LiveTranslator.translate(this, HTTP_FAST, tail, srcLang, lang, aiBackend); }
                    catch (Exception ex) { Log.d(TAG, "tail translate failed: " + ex); }
                    final String res = out;
                    main.post(() -> {
                        e.tailBusy.put(lang, false);
                        if (res != null) applyTail(e, lang, from, res);
                        if (e == live) requestTail(e);
                    });
                });
            } catch (Exception ex) { e.tailBusy.put(lang, false); }
        }
    }

    private void applyTail(Entry e, String lang, int from, String out) {
        if (out == null || out.trim().isEmpty()) return;
        e.tailTr.put(lang, out.trim());
        e.tailTrFrom.put(lang, from);
        refreshLiveTr(e, lang);
    }

    /** ترجمه‌ی تکه‌های آماده‌ی پشتِ‌سرهم. completeOnly=true: اگر حتی یک تکه آماده/موفق نباشد null. */
    private static String joinChunks(Entry e, String lang, boolean completeOnly) {
        ArrayList<String> l = e.chunkTr.get(lang);
        if (l == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            String v = l.get(i);
            if (v.isEmpty()) { if (completeOnly) return null; break; }
            if (v.equals(CHUNK_FAIL)) { if (completeOnly) return null; continue; }
            if (sb.length() > 0) sb.append(' ');
            sb.append(v);
        }
        return sb.toString();
    }

    private final LiveTranslator.Ai aiBackend = new LiveTranslator.Ai() {
        @Override public String translate(String text, String tgt) throws Exception {
            return BubbleService.this.translate(text, "", tgt);
        }

        @Override public String verify(String text, String tgt, String draft) throws Exception {
            String name = LANG_NAMES.containsKey(tgt) ? LANG_NAMES.get(tgt) : tgt;
            String prompt = "Source text: \"" + text + "\"\n"
                    + "Draft translation into " + name + ": \"" + draft + "\"\n\n"
                    + "Is the draft an accurate, complete translation? If yes, reply with EXACTLY: OK\n"
                    + "If no, reply with ONLY the corrected translation \u2014 no quotes, no explanation, nothing else.";
            JSONObject body = new JSONObject();
            body.put(GENERATE_PROMPT_KEY, prompt);
            String resp = postBytes(HTTP_FAST, 1, WORKER_BASE + GENERATE_PATH,
                    "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
            return extractText(resp);
        }
    };

    private void onFinalText(final String raw) {
        if (wm == null || bubble == null || raw == null || raw.trim().isEmpty()) return;
        if (asrMuted()) return;
        wakeFromIdle();
        // the server sometimes returns the same final text several times - show it once
        if (raw.equals(lastFinalText)) {
            Log.d(TAG, "skipping duplicate final text");
            return;
        }
        lastFinalText = raw;
        final String text = stripConsumed(raw);           // قسمت‌هایی که با مکث قبلاً خطِ جدا شده‌اند تکرار نشود
        consumedWords = 0;                                // گفتارِ جاری تمام شد
        if (text.trim().isEmpty()) {
            final Entry le = live;
            cancelLivePending();
            if (le != null && le.src != null && !le.src.trim().isEmpty()) commitSentences(le.src);
            return;
        }
        commitSentences(text);
    }

    /** یک جمله‌ی کامل: خطِ زنده بسته و ترجمه‌ی نهایی‌اش گرفته می‌شود. */
    private void commitSentence(final String text) {
        if (wm == null || bubble == null || text == null || text.trim().isEmpty()) return;

        cancelLivePending();
        lastPartialSrc = "";
        final String ctxPrev = prevFinalText;
        prevFinalText = text;
        clearStatus();

        final Entry e = live != null ? live : newLiveEntry();
        live = null;                      // this sentence is finished; the next partial starts a new line
        e.src = text;

        final List<String> ts = activeTargets();
        if (ts.isEmpty()) { renderEntry(e); afterChange(); return; }

        final String srcLang = effectiveSource();
        final boolean online = hasInternet();
        final String offlineMsg = msg("ترجمه در دسترس نیست (آفلاین)", "No translation (offline)");
        final String[] fw = words(text);
        reconcileChunks(e, fw);
        for (int i = 0; i < ts.size(); i++) {
            final String lang = ts.get(i);
            // اگر تکه‌های زنده کلِ جمله را پوشانده‌اند و ترجمه‌شان مشکوک نیست، همان‌ها نهایی می‌شوند → هیچ پرشی در پایانِ جمله نیست
            if (fw.length > 0 && e.committedWords == fw.length) {
                String joined = joinChunks(e, lang, true);
                if (joined != null && !joined.isEmpty()
                        && !LiveTranslator.looksLikelyMistranslated(text, joined, lang, srcLang)) {
                    setTranslation(e, lang, joined, text);
                    continue;
                }
            }
            String have = e.tr.get(lang);
            boolean haveGood = have != null && !have.isEmpty() && !have.equals("…");
            if (haveGood && text.equals(e.trSrc.get(lang))) continue;   // live translation already matches the final text
            if (haveGood) {
                // ترجمه‌ی تکه‌ایِ فعلی را نگه دار (علامتِ «…»ی انتها را بردار) تا ترجمه‌ی کاملِ جمله برسد
                String cur = have.endsWith(" …") ? have.substring(0, have.length() - 2) : have;
                if (!cur.equals(have)) e.tr.put(lang, cur);
            }
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
        // کش → سرویس‌های رایگان → (اگر مشکوک بود) بازبینی/ترجمه با AI → کشِ نتیجه‌ی تأییدشده
        return LiveTranslator.translate(this, HTTP_FAST, text, srcLang, tgt, aiBackend);
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

    /**
     * 💾 ترجمه‌ی زنده (صوتِ پخش‌شده از هر پلیر/برنامه): همه‌ی جمله‌های تاریخچه + ترجمه‌ها → «داستان‌های ذخیره‌شده».
     * اگر دسترسیِ اعلان‌ها روشن باشد، منبعِ صدا (برنامه، عنوانِ فایل، لینک) و «موقعیتِ زمانیِ» هر جمله در همان فایل هم
     * ذخیره می‌شود (مثل زیرنویسِ ذخیره‌شده‌ی یوتیوب)، و ذخیره‌ی دوباره‌ی همان فایل خط‌ها را ادغام می‌کند.
     */
    private void saveLive() {
        try {
            final String offFa = "ترجمه در دسترس نیست (آفلاین)", offEn = "No translation (offline)";
            final List<String> ts = activeTargets();
            if (history.isEmpty()) { showNotice(msg("هنوز چیزی برای ذخیره نیست", "Nothing to save yet")); return; }
            if (liveKey == null) {
                liveStartMs = history.get(0).createdAt;
                liveKey = "live-" + liveStartMs;
            }
            final ArrayList<Entry> snap = new ArrayList<>(history);
            int counted = 0, withPos = 0;
            for (Entry e : snap) {
                if (e == live || e.src == null || e.src.trim().isEmpty()) continue;
                counted++;
                if (e.mediaPosMs >= 0) withPos++;
            }
            YtMedia.Now np = null;
            try { np = YtMedia.nowPlaying(this); } catch (Throwable ignored) {}
            // برنامه‌هایی مثلِ اینستاگرام/تیک‌تاک/تلگرام معمولاً MediaSession (عنوان) نمی‌دهند؛ اگر چیزی «در حالِ پخش»
            // نبود، برنامه‌ی مبدأ را از روی آخرین برنامه‌ی جلوی صفحه می‌گیریم (فقط نامِ برنامه، نه محتوا).
            if (np != null && (np.title == null || np.title.isEmpty())) np.title = (np.app == null || np.app.isEmpty()) ? np.pkg : np.app;
            final boolean hasSource = np != null && np.title != null && !np.title.isEmpty();
            final boolean useMedia = hasSource && counted > 0 && withPos * 5 >= counted * 4;   // ≥۸۰٪ جمله‌ها موقعیتِ پلیر دارند

            // 🔒 کپی‌رایت: متنِ صدا/ویدیو (و ترجمه‌اش) ذخیره نمی‌شود؛ فقط منبع (برنامه + عنوان + لینک) تا
            //    کاربر بعداً همان برنامه/لینک را دوباره باز کند.
            JSONArray lines = new JSONArray();
            if (!hasSource) {
                showNotice(msg("برای ذخیره‌ی اینستاگرام/تیک‌تاک/…: در همان برنامه «Share ← Hope» را بزن",
                        "For Instagram/TikTok/…: use “Share → Hope” inside that app"));
                return;
            }
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
            if (hasSource) {
                JSONObject so = new JSONObject()
                        .put("app", np.app == null ? "" : np.app)
                        .put("pkg", np.pkg == null ? "" : np.pkg)
                        .put("title", np.title)
                        .put("artist", np.artist == null ? "" : np.artist)
                        .put("url", np.url == null ? "" : np.url)
                        .put("inApp", np.inApp)
                        .put("timed", useMedia);
                item.put("source", so);
                item.put("title", np.title);
                item.put("channel", (np.artist != null && !np.artist.isEmpty()) ? np.artist : (np.app == null ? "" : np.app));
                if (np.url != null && !np.url.isEmpty()) item.put("url", np.url);
                if (useMedia) {   // همان فایل = همان ردیف (مثل videoId در یوتیوب) تا ذخیره‌ی دوباره خط‌ها را ادغام کند
                    item.put("key", "live-" + Integer.toHexString((np.pkg + "|" + np.title + "|" + np.artist).hashCode()));
                }
            }
            boolean ok = YtSaved.add(this, item);
            showNotice(ok
                    ? msg("منبع ذخیره شد ✓ — در «داستان‌های ذخیره‌شده» اپ", "Source saved ✓ — see Saved stories in the app")
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
            boolean ok = YtSaved.add(this, snap);
            showNotice(ok
                    ? msg("لینکِ ویدیو ذخیره شد ✓ — در «داستان‌های ذخیره‌شده» اپ", "Video link saved ✓ — see Saved stories in the app")
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
        ytListMode = false; ytListGen++; ytCur = -1;
        if (scroll != null) scroll.ytMode = false;
        if (engine != null) { try { engine.stop(); } catch (Throwable ignored) {} }
        if (engine == null) return;
        refreshHeader();
        updateYtButton();
        if (!recording) {          // خروج از حالتِ ویدیو → کادر هم همان لحظه بسته بشه و متنِ ویدیو پاک بشه
            clearHistory();
            removePanel();
            userHidden = false;
        } else if (notify) {
            showNotice(msg("زیرنویس یوتیوب خاموش شد", "YouTube subtitles off"));
        }
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

        @Override public void loadAll(final List<String> sentences, final Map<String, String[]> tr, final int cur) {
            if (wm == null || bubble == null) return;
            clearStatus();
            refreshHeader();     // زبانِ زیرنویسِ ویدیو معلوم شد → فهرستِ زبان‌های مقصدِ هدر را تازه کن
            final int g = ++ytListGen;
            clearHistory();
            ytEntries.clear();
            ytCur = -1;
            ytWanted = cur;
            ytWantedForce = true;
            ytListMode = true;
            ensurePanel();
            scroll.ytMode = true;
            scroll.atBottom = false;
            buildYtBatch(sentences, tr, 0, g);
        }

        @Override public void setCurrent(int sentIdx, boolean force) {
            ytWanted = sentIdx;
            ytWantedForce = ytWantedForce || force;
            applyYtCurrent(false);
        }

        @Override public void updateSentence(int sentIdx, String lang, String text) {
            Entry e = ytEntries.get(sentIdx);
            if (e == null || text == null || text.isEmpty()) return;
            e.tr.put(lang, text);
            e.trSrc.put(lang, e.src);
            renderEntry(e);
            queueYtRefresh();
        }

        @Override public void videoChanged() {
            if (wm == null || bubble == null) return;
            ytListGen++;
            clearHistory();
            ytEntries.clear();
            ytCur = -1;
        }
    };

    // ─── 📜 لیستِ کاملِ زیرنویس: همه کم‌رنگ، جمله‌ی در حالِ پخش پررنگ ───

    /** ردیف‌ها را دسته‌دسته (۴۰ تا در هر فریم) می‌سازد تا گوشی برای ویدیوهای بلند هنگ نکند. */
    private void buildYtBatch(final List<String> sents, final Map<String, String[]> tr, final int from, final int g) {
        if (g != ytListGen || wm == null || bubble == null) return;
        int end = Math.min(sents.size(), from + 40);
        for (int i = from; i < end; i++) {
            Entry e = newEntry();
            e.ytIdx = i;
            if (e.ytReplayBtn != null) e.ytReplayBtn.setVisibility(View.VISIBLE);
            e.src = sents.get(i);
            e.srcLang = currentSrcLang();
            for (Map.Entry<String, String[]> x : tr.entrySet()) {
                String[] a = x.getValue();
                if (a != null && i < a.length && a[i] != null && !a[i].isEmpty()) {
                    e.tr.put(x.getKey(), a[i]);
                    e.trSrc.put(x.getKey(), e.src);
                }
            }
            styleYtEntry(e, false);
            ytEntries.put(i, e);
            renderEntry(e);
        }
        afterChange();
        if (end < sents.size()) {
            final int nxt = end;
            main.post(() -> buildYtBatch(sents, tr, nxt, g));
        } else {
            applyYtCurrent(true);
        }
    }

    private void styleYtEntry(Entry e, boolean current) {
        if (e == null || e.box == null) return;
        e.box.setAlpha(current ? 1f : YT_DIM);
        e.tvSrc.setTextColor(current ? Color.WHITE : Color.parseColor("#C8CCD8"));
        e.tvSrc.setTag(new float[]{13f, current ? 1f : 0f});
        applyStyle(e.tvSrc);
    }

    private void applyYtCurrent(boolean listJustBuilt) {
        int idx = ytWanted;
        if (idx < 0) return;
        Entry n = ytEntries.get(idx);
        if (n == null) return;                 // هنوز در حالِ ساختنِ لیست؛ آخرِ ساخت دوباره صدا زده می‌شود
        boolean changed = ytCur != idx;
        if (changed) {
            Entry o = ytEntries.get(ytCur);
            if (o != null) styleYtEntry(o, false);
            ytCur = idx;
        }
        for (String t : activeTargets()) {
            String v = n.tr.get(t);
            if (v == null || v.isEmpty()) n.tr.put(t, "…");
        }
        styleYtEntry(n, true);
        renderEntry(n);
        boolean force = ytWantedForce || listJustBuilt;
        ytWantedForce = false;
        if (scroll != null && (changed || force)) {
            boolean userBusy = SystemClock.uptimeMillis() - scroll.lastUserTouch < 4000;
            if (force || !userBusy) scrollToYtEntry(n);
        }
        queueYtRefresh();
    }

    private void scrollToYtEntry(final Entry e) {
        if (scroll == null || e == null || e.box == null) return;
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, e.box.getTop() - scroll.getHeight() / 3)));
    }

    /** چند به‌روزرسانیِ پشتِ‌سرهم (ترجمه‌ی یک chunk) فقط یک بار layout پنل را تازه می‌کنند. */
    private void queueYtRefresh() {
        if (ytRefreshQueued) return;
        ytRefreshQueued = true;
        main.postDelayed(() -> { ytRefreshQueued = false; afterChange(); }, 120);
    }

    private void setRecordingUi(boolean rec) {
        if (bubble == null) return;
        bubble.setRecording(rec);
        if (rec) {
            lastVoiceMs = lastTextMs = lastActiveMs = SystemClock.elapsedRealtime();
            idleHidden = false;
        }
        main.removeCallbacks(idleRunnable);
        main.postDelayed(idleRunnable, 700);
        // شروعِ ضبط → حباب جمع می‌شود و می‌رود گوشه؛ توقف → کامل به لبه می‌چسبد و بعد از چند ثانیه دوباره جمع می‌شود
        main.postDelayed(() -> dockBubble(rec), rec ? 350 : 0);
        if (!rec) scheduleIdleDock();
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
        lastPartialSrc = ""; live = null; consumedWords = 0;
        cancelLivePending();
        speechHostRestarts = 0;
        gotAsrText = false; voicedSinceText = 0;
        final String src = effectiveSource();
        final String wModel = whisperModelPref();
        if (wModel != null) {
            // Song mode: offline Whisper (any language, incl. "auto"); falls through to the old paths if it can't start
            if (startSherpaEngine(src, wModel)) return;
        }
        if (src != null && !"auto".equals(src) && SherpaModelManager.isAvailable(src)) {
            // Sherpa path: feedLoop feeds the OnlineStream (no SpeechHostActivity)
            if (startSherpaEngine(src, null)) return;
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
            showNotice(msg("🎙 در حال اجرا…", "🎙 Running…"));
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
    private boolean startSherpaEngine(final String src, final String whisperModel) {
        if (whisperModel != null) {
            if (WhisperModelManager.getModelDir(this, whisperModel) == null) return false;   // not downloaded yet
        } else if (SherpaModelManager.getModelDir(this, src) == null) return false;           // not downloaded yet
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
            new Thread(() -> runSherpa(fr, src, whisperModel), "bubble-feed").start();
            showNotice(msg("🎙 در حال اجرا…", "🎙 Running…"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot start sherpa engine", e);
            micEngine = false; recording = false; setRecordingUi(false);
            if (rec != null) { record = null; try { rec.stop(); } catch (Exception ignored) {} try { rec.release(); } catch (Exception ignored) {} }
            return false;
        }
    }

    /** Feed-thread entry for Sherpa: loads the model off the main thread, then runs feedLoop. */
    private void runSherpa(final AudioRecord rec, final String src, final String whisperModel) {
        PcmSink eng = (whisperModel != null)
                ? WhisperEngine.create(getApplicationContext(), whisperModel, src)
                : SherpaEngine.create(getApplicationContext(), src);
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

    /** Selected Whisper ("song mode") model id if the user enabled it and it is downloaded, else null. */
    private String whisperModelPref() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!"whisper".equals(sp.getString("sttEngine", "sherpa"))) return null;
        String m = sp.getString("whisperModel", "base");
        return WhisperModelManager.getModelDir(this, m) != null ? m : null;
    }

    private void releaseSherpa() {
        PcmSink e = sherpaEngine;
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
                .excludeUid(android.os.Process.myUid())
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
                final PcmSink se = sherpaEngine;
                if (se != null) {
                    se.accept(out, outBytes);
                } else {
                    PcmFeed.write(out, outBytes);
                }
                final boolean loud = rms16(in, n) > SILENCE_RMS;
                if (loud) lastVoiceMs = SystemClock.elapsedRealtime();
                if (!gotAsrText) {
                    if (loud) voicedSinceText += 20;
                    // Whisper answers in ~10-30 s windows, so give it a much longer leash than the streaming engine
                    final int wd = (se instanceof WhisperEngine) ? ASR_WATCHDOG_VOICED_MS * 4 : ASR_WATCHDOG_VOICED_MS;
                    if (voicedSinceText >= wd) {
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
                    .excludeUid(android.os.Process.myUid())
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
            showNotice(msg("🎙 در حال اجرا…", "🎙 Running…"));
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
        lastActiveMs = SystemClock.elapsedRealtime() - IDLE_HIDE_MS + 1800;   // پیامِ توقف ~۲ ثانیه دیده می‌شه، بعد کادر خودش جمع می‌شه
    }

    private void captureLoop(AudioRecord rec) {
        byte[] buf = new byte[CHUNK_BYTES];
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        int segMs = 0, voicedMs = 0, silentMs = 0, sincePartialMs = 0;
        try {
            while (recording) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n == 0) continue;
                seg.write(buf, 0, n);
                int chunkMs = n * 1000 / (SAMPLE_RATE * 2);
                segMs += chunkMs;
                sincePartialMs += chunkMs;
                if (rms(buf, n) > SILENCE_RMS) { voicedMs += chunkMs; silentMs = 0; lastVoiceMs = SystemClock.elapsedRealtime(); }
                else silentMs += chunkMs;
                boolean cut = segMs >= MAX_SEG_MS || (segMs >= MIN_SEG_MS && silentMs >= SILENCE_CUT_MS);
                if (cut) {
                    srvSegId++;                                   // پیش‌نمایش‌های دیرِ این قطعه دور ریخته شوند
                    if (voicedMs >= MIN_VOICED_MS) submit(seg.toByteArray());
                    seg.reset(); segMs = voicedMs = silentMs = 0; sincePartialMs = 0;
                } else if (voicedMs == 0 && segMs >= 1000) {
                    seg.reset(); segMs = silentMs = 0; sincePartialMs = 0;
                } else if (voicedMs >= SRV_PARTIAL_MIN_VOICED_MS && sincePartialMs >= SRV_PARTIAL_EVERY_MS
                        && silentMs < SILENCE_CUT_MS) {
                    sincePartialMs = 0;
                    submitServerPartial(seg.toByteArray(), srvSegId);
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

    /** نسخه‌ی نهایی چیزِ جدیدی نداشت: خطِ زنده همون‌طور که هست بسته می‌شه (و ترجمه‌ی بقیه‌اش شروع می‌شه). */
    static void asrCommit() {
        BubbleService s = instance;
        if (s == null || !s.micEngine || s.live == null) return;
        s.flushPartial(true);
        s.cancelLivePending();
        s.live = null;
        s.consumedWords = 0;
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


    /** حالت سرور: متنِ نیمه‌کاره‌ی قطعه‌ی جاری را هر چند صد میلی‌ثانیه نشان بده (بدونِ منتظر ماندن برای پایانِ جمله). */
    private void submitServerPartial(final byte[] pcm, final int id) {
        if (!srvPartialBusy.compareAndSet(false, true)) return;
        try {
            sttPartialEx.execute(() -> {
                String t = "";
                try { t = transcribe(pcm); } catch (Exception ex) { Log.d(TAG, "partial transcribe failed: " + ex); }
                srvPartialBusy.set(false);
                final String text = t;
                if (text.isEmpty()) return;
                main.post(() -> {
                    if (id == srvSegId && recording && !micEngine) onPartialText(text);
                });
            });
        } catch (Exception ex) { srvPartialBusy.set(false); }
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
            if (r != null && !r.trim().isEmpty()) return stripThink(r);
        } catch (Exception ignored) {}
        return stripThink(raw);
    }

    /** بلوکِ <think>…</think> (فکرِ مدل) را حذف می‌کند و فقط پاسخ را نگه می‌دارد. */
    private static String stripThink(String s) {
        if (s == null) return "";
        return s.replaceAll("(?is)<think(ing)?>.*?</think(ing)?>", "")
                .replaceAll("(?is)<think(ing)?>.*$", "").trim();
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

    /** پیامِ کوتاه و قابل‌فهم از یک خطا برای نمایش در کادر. */
    private static String briefErr(Throwable e) {
        if (e == null) return "error";
        if (e instanceof HttpStatusException) return "HTTP " + ((HttpStatusException) e).code;
        if (e instanceof UnknownHostException) return "no internet";
        if (e instanceof SocketTimeoutException) return "timeout";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        m = m.replace('\n', ' ').trim();
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    /** بستنِ کاملِ سرویس (✕ روی حباب / ACTION_HIDE). */
    private void shutdown() {
        running = false;
        main.removeCallbacks(idleRunnable);
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    /** آزاد کردنِ همه‌ی منابع؛ از onDestroy صدا زده می‌شود. */
    private void cleanup() {
        running = false;
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);

        try { stopYoutube(false); } catch (Throwable ignored) {}

        recording = false;
        if (micEngine) {
            micEngine = false;
            try { SpeechHostActivity.finishIfRunning(); } catch (Throwable ignored) {}
            try { PcmFeed.close(); } catch (Throwable ignored) {}
        }
        releaseSherpa();
        AudioRecord r = record; record = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} }
        MediaProjection mp = mediaProjection; mediaProjection = null;
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) {} }

        try { PanelTts.stop(this); } catch (Throwable ignored) {}
        if (pulse != null) { try { pulse.cancel(); } catch (Exception ignored) {} pulse = null; }

        removePanel();
        removeCloseTargetNow();
        if (bubble != null && wm != null) { try { wm.removeView(bubble); } catch (Exception ignored) {} }
        bubble = null;

        closeLocalTranslator();
        net.shutdownNow();
        netTr.shutdownNow();
        netPartial.shutdownNow();
        sttPartialEx.shutdownNow();
    }
}
