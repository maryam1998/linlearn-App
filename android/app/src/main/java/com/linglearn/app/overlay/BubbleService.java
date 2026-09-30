package com.linglearn.app.overlay;

import android.animation.ObjectAnimator;
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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
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
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BubbleService extends Service {

    private static final String TAG = "BubbleService";

    public static final String ACTION_SHOW = "com.linglearn.app.overlay.SHOW";
    public static final String ACTION_HIDE = "com.linglearn.app.overlay.HIDE";
    public static final String ACTION_PROJECTION_RESULT = "com.linglearn.app.overlay.PROJECTION_RESULT";
    public static final String ACTION_PROJECTION_DENIED = "com.linglearn.app.overlay.PROJECTION_DENIED";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    // ---- Backend (your Cloudflare Worker) ----
    private static final String WORKER_BASE = "https://phrasebook-api.maryam-s-sharifiyan.workers.dev";
    private static final String TRANSCRIBE_PATH = "/api/transcribe";
    private static final String GENERATE_PATH = "/api/generate";
    // JSON key your /api/generate expects the prompt under:
    private static final String GENERATE_PROMPT_KEY = "prompt";

    // ---- Audio / segmentation tuning ----
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_MS = 100;
    private static final int CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS / 1000;
    private static final int MIN_SEG_MS = 1500;
    private static final int MAX_SEG_MS = 4000;
    private static final int SILENCE_CUT_MS = 500;
    private static final int MIN_VOICED_MS = 600;
    private static final double SILENCE_RMS = 250.0;
    private static final int MAX_PENDING = 3;
    private static final long PANEL_HIDE_MS = 6000;

    // ---- Network resilience ----
    private static final int NET_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int TRANSCRIBE_READ_TIMEOUT_MS = 20000;
    private static final int TRANSLATE_READ_TIMEOUT_MS = 15000;

    private static final String PREFS = "bubble_prefs";
    private static final String CHANNEL_ID = "bubble_channel";
    private static final int NOTIF_ID = 4711;
    private static final int COLOR_INK = Color.parseColor("#1C2541");
    private static final int COLOR_GOLD = Color.parseColor("#C9A227");
    private static final int COLOR_REC = Color.parseColor("#E53935");

    public static volatile boolean running = false;

    private static final Map<String, String> LANG_NAMES = new HashMap<>();
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
    }

    public static void saveLangs(Context ctx, String target, String source) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (target != null && !target.isEmpty()) e.putString("target", target);
        if (source != null && !source.isEmpty()) e.putString("source", source);
        e.apply();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger(0);

    private WindowManager wm;
    private TextView bubble;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams bubbleLp;
    private int bubbleSize;

    private LinearLayout panel;
    private TextView tvSrc;
    private TextView tvTr;
    private WindowManager.LayoutParams panelLp;
    private boolean panelShown = false;
    private final Runnable hidePanel = this::removePanel;

    private ObjectAnimator pulse;
    private GestureDetector gestures;
    private boolean dragging = false;

    private MediaProjection mediaProjection;
    private AudioRecord record;
    private volatile boolean recording = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

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
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Foreground
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Bubble UI
    // ------------------------------------------------------------------

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

    private boolean isFa() {
        return "fa".equals(targetLang());
    }

    private void addBubbleIfNeeded() {
        if (bubble != null) return;
        if (!Settings.canDrawOverlays(this)) {
            shutdown();
            return;
        }
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
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showText("", isFa()
                        ? "نگه‌داشتن: شروع/توقف ضبط  ·  دوبار لمس: بستن"
                        : "Long-press: start/stop  ·  Double-tap: close");
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                shutdown();
                return true;
            }

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
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true;
                        if (dragging) {
                            bubbleLp.x = clamp(Math.round(startX + dx), 0, screenW() - bubbleSize);
                            bubbleLp.y = clamp(Math.round(startY + dy), 0, screenH() - bubbleSize);
                            try {
                                wm.updateViewLayout(bubble, bubbleLp);
                            } catch (Exception ignored) {
                            }
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

        try {
            wm.addView(bubble, bubbleLp);
        } catch (Exception e) {
            Log.e(TAG, "addView failed", e);
            shutdown();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void ensurePanel() {
        if (panel != null) return;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EB1C2541"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COLOR_GOLD);
        panel.setBackground(bg);

        tvSrc = new TextView(this);
        tvSrc.setTextColor(Color.parseColor("#C8CCD8"));
        tvSrc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvSrc.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        tvSrc.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);

        tvTr = new TextView(this);
        tvTr.setTextColor(Color.WHITE);
        tvTr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvTr.setTypeface(Typeface.DEFAULT_BOLD);
        tvTr.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        tvTr.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        tvTr.setPadding(0, dp(4), 0, 0);

        panel.addView(tvSrc);
        panel.addView(tvTr);

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
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
        try {
            wm.updateViewLayout(panel, panelLp);
        } catch (Exception ignored) {
        }
    }

    private void showText(String src, String tr) {
        if (wm == null || bubble == null) return;
        ensurePanel();
        boolean hasSrc = src != null && !src.isEmpty();
        tvSrc.setText(hasSrc ? src : "");
        tvSrc.setVisibility(hasSrc ? View.VISIBLE : View.GONE);
        boolean hasTr = tr != null && !tr.isEmpty();
        tvTr.setText(hasTr ? tr : "");
        tvTr.setVisibility(hasTr ? View.VISIBLE : View.GONE);
        tvTr.setPadding(0, hasSrc ? dp(4) : 0, 0, 0);

        computePanelPos();
        try {
            if (!panelShown) {
                wm.addView(panel, panelLp);
                panelShown = true;
            } else {
                wm.updateViewLayout(panel, panelLp);
            }
        } catch (Exception e) {
            Log.w(TAG, "panel show failed", e);
        }
        main.removeCallbacks(hidePanel);
        main.postDelayed(hidePanel, PANEL_HIDE_MS);
    }

    private void removePanel() {
        if (panelShown && panel != null && wm != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        panelShown = false;
    }

    private void setRecordingUi(boolean rec) {
        if (bubbleBg == null) return;
        bubbleBg.setStroke(dp(rec ? 4 : 3), rec ? COLOR_REC : COLOR_GOLD);
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
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

    // ------------------------------------------------------------------
    // Recording control
    // ------------------------------------------------------------------

    private void toggleRecording() {
        if (recording) {
            stopRecording();
            return;
        }
        if (mediaProjection == null) {
            Intent i = new Intent(this, ProjectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception e) {
                Log.e(TAG, "cannot launch ProjectionActivity", e);
                showText("", "⚠ " + briefErr(e));
            }
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
                @Override
                public void onStop() {
                    if (mediaProjection == mp) {
                        mediaProjection = null;
                        stopRecording();
                    }
                }
            }, main);
            mediaProjection = mp;
            startCapture();
        } catch (Exception e) {
            Log.e(TAG, "projection failed", e);
            showText("", "⚠ " + briefErr(e));
        }
    }

    private void startCapture() {
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
            showText("", isFa() ? "🎙 در حال گوش‌دادن به صدای سیستم…" : "🎙 Listening to system audio…");
            Thread t = new Thread(() -> captureLoop(rec), "bubble-capture");
            t.start();
        } catch (Exception e) {
            Log.e(TAG, "startCapture failed", e);
            recording = false;
            setRecordingUi(false);
            showText("", "⚠ " + briefErr(e));
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        setRecordingUi(false);
        showText("", isFa() ? "ضبط متوقف شد" : "Stopped");
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
                int chunkMs = n * 1000 / (SAMPLE_RATE * 2);
                boolean loud = rms(buf, n) > SILENCE_RMS;
                seg.write(buf, 0, n);
                segMs += chunkMs;
                if (loud) {
                    voicedMs += chunkMs;
                    silentMs = 0;
                } else {
                    silentMs += chunkMs;
                }
                boolean cut = segMs >= MAX_SEG_MS || (segMs >= MIN_SEG_MS && silentMs >= SILENCE_CUT_MS);
                if (cut) {
                    if (voicedMs >= MIN_VOICED_MS) submit(seg.toByteArray());
                    seg.reset();
                    segMs = voicedMs = silentMs = 0;
                } else if (voicedMs == 0 && segMs >= 1000) {
                    seg.reset();
                    segMs = silentMs = 0;
                }
            }
            if (voicedMs >= MIN_VOICED_MS) submit(seg.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "capture loop", e);
        } finally {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
            if (recording) {
                recording = false;
                main.post(() -> setRecordingUi(false));
            }
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

    // ------------------------------------------------------------------
    // Network: transcribe + translate
    // ------------------------------------------------------------------

    private void submit(final byte[] pcm) {
        if (pending.get() >= MAX_PENDING) {
            Log.w(TAG, "dropping segment (backlog)");
            return;
        }
        pending.incrementAndGet();
        try {
            net.execute(() -> {
                try {
                    processSegment(pcm);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (Exception e) {
            pending.decrementAndGet();
        }
    }

    private void processSegment(byte[] pcm) {
        try {
            final String text = transcribe(pcm);
            if (text.isEmpty()) return;
            main.post(() -> showText(text, "…"));
            String tr;
            try {
                tr = translate(text);
            } catch (Exception e) {
                Log.w(TAG, "translate failed", e);
                tr = "⚠ " + briefErr(e);
            }
            final String shown = tr;
            main.post(() -> showText(text, shown));
        } catch (Exception e) {
            Log.w(TAG, "transcribe failed", e);
            final String msg = "⚠ " + briefErr(e);
            main.post(() -> showText("", msg));
        }
    }

    private String transcribe(byte[] pcm) throws Exception {
        String src = sourceLang();
        String url = WORKER_BASE + TRANSCRIBE_PATH;
        if (src != null && !src.isEmpty() && !"auto".equals(src)) {
            url += "?lang=" + URLEncoder.encode(src, "UTF-8");
        }
        String resp = postBytes(url, "audio/wav", toWav(pcm), TRANSCRIBE_READ_TIMEOUT_MS);
        JSONObject j = new JSONObject(resp);
        return j.optString("text", "").trim();
    }

    private String translate(String text) throws Exception {
        String target = targetLang();
        String name = LANG_NAMES.containsKey(target) ? LANG_NAMES.get(target) : target;
        String prompt = "Translate the following text to " + name
                + ". Reply with ONLY the translation, no quotes, no explanations.\n\nText: " + text;
        JSONObject body = new JSONObject();
        body.put(GENERATE_PROMPT_KEY, prompt);
        String resp = postBytes(WORKER_BASE + GENERATE_PATH, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8), TRANSLATE_READ_TIMEOUT_MS);
        return extractText(resp);
    }

    private static String extractText(String raw) {
        try {
            String r = dig(new JSONTokener(raw).nextValue());
            if (r != null && !r.trim().isEmpty()) return r.trim();
        } catch (Exception ignored) {
        }
        return raw.trim();
    }

    private static String dig(Object o) {
        if (o instanceof String) return (String) o;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String[] keys = {"text", "result", "response", "output", "translation", "reply",
                    "content", "message", "choices", "candidates", "parts"};
            for (String k : keys) {
                if (j.has(k)) {
                    String s = dig(j.opt(k));
                    if (s != null) return s;
                }
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

        HttpStatusException(int code, String body) {
            super("HTTP " + code + " " + body);
            this.code = code;
        }
    }

    // Retries on dropped/stale connections ("unexpected end of stream"), timeouts and 5xx/429.
    private static String postBytes(String urlStr, String contentType, byte[] body, int readTimeoutMs)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= NET_ATTEMPTS; attempt++) {
            try {
                return postOnce(urlStr, contentType, body, readTimeoutMs);
            } catch (IOException e) {
                last = e;
                if (e instanceof HttpStatusException) {
                    int code = ((HttpStatusException) e).code;
                    if (code < 500 && code != 429) throw e;
                }
                Log.w(TAG, "request attempt " + attempt + " failed: " + e);
                if (attempt == NET_ATTEMPTS) break;
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private static String postOnce(String urlStr, String contentType, byte[] body, int readTimeoutMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setUseCaches(false);
            // Fresh connection every time: a pooled keep-alive socket that the carrier/Cloudflare
            // already closed is what causes "unexpected end of stream on ...Address".
            c.setRequestProperty("Connection", "close");
            c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) throw new HttpStatusException(code, resp);
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = is.read(b)) > 0) out.write(b, 0, n);
        is.close();
        return out.toString("UTF-8");
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

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    private void shutdown() {
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    private void cleanup() {
        running = false;
        main.removeCallbacksAndMessages(null);
        recording = false;
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        MediaProjection mp = mediaProjection;
        mediaProjection = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
        }
        removePanel();
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        bubble = null;
        net.shutdownNow();
    }
}
