package com.linglearn.app.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpeechHostActivity extends Activity {

    public static final String EXTRA_LANG_TAG = "langTag";
    private static final String TAG = "SpeechHost";
    private static final int ERROR_STREAK_BEFORE_FALLBACK = 8;
    private static final int RESTART_DELAY_MS = 120;

    private static volatile SpeechHostActivity current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private Intent recIntent;
    private String langTag = "en-US";
    private boolean preferOffline = false;
    private boolean began = false;
    private int errorStreak = 0;
    // Read end of the pipe that carries the captured SYSTEM audio for the current session.
    private ParcelFileDescriptor audioSrc;

    static void finishIfRunning() {
        final SpeechHostActivity a = current;
        if (a != null) a.main.post(a::finish);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        // Android 12+ blocks touches that pass through another app's visible, non-transparent
        // window even when it is FLAG_NOT_TOUCHABLE -> the app underneath (YouTube/VLC/...) was
        // un-tappable while this host was alive. Fully transparent + 1x1 px = exempt / no overlap.
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.alpha = 0f;
        wlp.width = 1;
        wlp.height = 1;
        wlp.gravity = Gravity.TOP | Gravity.START;
        getWindow().setAttributes(wlp);

        String t = getIntent().getStringExtra(EXTRA_LANG_TAG);
        if (t != null && !t.isEmpty()) langTag = t;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            BubbleService.asrNeedsPermission(); finish(); return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            BubbleService.asrFallback(); finish(); return;
        }

        recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        createRecognizer();

        if (Build.VERSION.SDK_INT >= 33) {
            main.postDelayed(this::begin, 3000);
            try {
                rec.checkRecognitionSupport(recIntent, getMainExecutor(), new RecognitionSupportCallback() {
                    @Override public void onSupportResult(RecognitionSupport s) {
                        if (containsLang(s.getInstalledOnDeviceLanguages(), langTag)) preferOffline = true;
                        else if (containsLang(s.getSupportedOnDeviceLanguages(), langTag)
                                || containsLang(s.getPendingOnDeviceLanguages(), langTag)) {
                            try { if (rec != null) rec.triggerModelDownload(recIntent); }
                            catch (Exception e) { Log.w(TAG, "triggerModelDownload failed", e); }
                        }
                        begin();
                    }
                    @Override public void onError(int error) {
                        Log.w(TAG, "checkRecognitionSupport error " + error); begin();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "checkRecognitionSupport failed", e); begin();
            }
        } else begin();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String t = intent.getStringExtra(EXTRA_LANG_TAG);
        if (t != null && !t.isEmpty() && !t.equals(langTag)) {
            langTag = t;
            if (recIntent != null) recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag);
            errorStreak = 0;
            try { if (rec != null) rec.cancel(); } catch (Exception ignored) {}
            main.postDelayed(this::listen, RESTART_DELAY_MS);
        } else {
            errorStreak = 0;
            main.postDelayed(this::listen, 50);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        SpeechRecognizer r = rec; rec = null;
        if (r != null) {
            try { r.cancel(); } catch (Exception ignored) {}
            try { r.destroy(); } catch (Exception ignored) {}
        }
        closeAudioSrc();
        if (current == this) current = null;
        BubbleService.asrClosed();
    }

    private static boolean containsLang(List<String> tags, String tag) {
        if (tags == null) return false;
        String want = tag.toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = want.indexOf('-');
        String primary = dash > 0 ? want.substring(0, dash) : want;
        for (String raw : tags) {
            if (raw == null) continue;
            String e = raw.toLowerCase(Locale.ROOT).replace('_', '-');
            if (e.equals(want) || e.startsWith(primary + "-") || e.equals(primary)) return true;
            if ("zh".equals(primary) && (e.startsWith("cmn") || e.startsWith("yue"))) return true;
        }
        return false;
    }

    private void createRecognizer() {
        rec = SpeechRecognizer.createSpeechRecognizer(this);
        rec.setRecognitionListener(listener);
    }

    private void recreateRecognizer() {
        SpeechRecognizer r = rec; rec = null;
        if (r != null) { try { r.destroy(); } catch (Exception ignored) {} }
        createRecognizer();
    }

    private void begin() {
        if (began || isFinishing()) return;
        began = true; listen();
    }

    private void closeAudioSrc() {
        ParcelFileDescriptor p = audioSrc; audioSrc = null;
        if (p != null) { try { p.close(); } catch (Exception ignored) {} }
    }

    private void listen() {
        if (isFinishing() || rec == null) return;
        recIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
        // Every session gets a fresh pipe; BubbleService pours the captured playback audio
        // (16 kHz mono PCM16) into it, so the recognizer transcribes what the phone PLAYS,
        // not what the microphone hears. Needs Android 13+ (API 33).
        closeAudioSrc();
        ParcelFileDescriptor src = PcmFeed.open();
        if (src == null) { hardError(); return; }
        audioSrc = src;
        recIntent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, src);
        recIntent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        recIntent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        recIntent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
        try { rec.startListening(recIntent); }
        catch (Exception e) { Log.w(TAG, "startListening failed", e); hardError(); }
    }

    private void hardError() {
        errorStreak++;
        if (errorStreak >= ERROR_STREAK_BEFORE_FALLBACK) {
            BubbleService.asrFallback(); finish(); return;
        }
        if (errorStreak >= 2) recreateRecognizer();
        long delay = Math.min(1500L, 250L * errorStreak);
        main.postDelayed(this::listen, delay);
    }

    private static String firstResult(Bundle b) {
        if (b == null) return "";
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l == null || l.isEmpty() || l.get(0) == null) return "";
        return l.get(0).trim();
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { errorStreak = 0; }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onPartialResults(Bundle partialResults) {
            String t = firstResult(partialResults);
            if (!t.isEmpty()) { errorStreak = 0; BubbleService.asrPartial(t); }
        }

        @Override
        public void onResults(Bundle results) {
            String t = firstResult(results);
            if (!t.isEmpty()) { errorStreak = 0; BubbleService.asrFinal(t); }
            main.postDelayed(SpeechHostActivity.this::listen, 20);
        }

        @Override
        public void onError(int error) {
            switch (error) {
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    BubbleService.asrFallback(); finish(); return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    BubbleService.asrNeedsPermission(); finish(); return;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    main.postDelayed(SpeechHostActivity.this::listen, RESTART_DELAY_MS); return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    try { if (rec != null) rec.cancel(); } catch (Exception ignored) {}
                    main.postDelayed(SpeechHostActivity.this::listen, 300); return;
                case SpeechRecognizer.ERROR_CLIENT:
                    recreateRecognizer();
                    main.postDelayed(SpeechHostActivity.this::listen, 200); return;
                case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                    main.postDelayed(SpeechHostActivity.this::listen, 800); return;
                default:
                    Log.w(TAG, "recognizer error " + error); hardError();
            }
        }
    };
}
