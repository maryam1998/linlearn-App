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
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.WindowManager;

import java.util.ArrayList;

/**
 * Activity ی نامرئی که SpeechRecognizer رو اجرا می‌کنه.
 * صدا از PcmFeed (صدای پخش‌شده‌ی سیستم) می‌آد، نه از میکروفون.
 * نتیجه‌ها به BubbleService.asrPartial / asrFinal / asrFallback / asrNeedsPermission / asrClosed می‌رن.
 */
public class SpeechHostActivity extends Activity {

    static final String EXTRA_LANG_TAG = "lang_tag";

    private static final String TAG = "SpeechHost";
    private static final int MAX_CONSECUTIVE_ERRORS = 6;

    private static volatile SpeechHostActivity current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor readSide;
    private String langTag = "en-US";
    private boolean intentionalFinish = false;
    private boolean destroyed = false;
    private int consecutiveErrors = 0;

    /** از BubbleService صدا زده می‌شه؛ اگه در حالِ اجراست می‌بندتش (بدونِ اینکه asrClosed بزنه). */
    static void finishIfRunning() {
        final SpeechHostActivity a = current;
        if (a == null) return;
        a.intentionalFinish = true;
        a.main.post(a::finish);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        try {
            // لمس‌ها از این پنجره‌ی نامرئی رد بشن تا اپِ زیرش قابل‌استفاده بمونه
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } catch (Throwable ignored) {}

        String tag = getIntent() != null ? getIntent().getStringExtra(EXTRA_LANG_TAG) : null;
        if (tag != null && !tag.isEmpty()) langTag = tag;

        if (Build.VERSION.SDK_INT < 33) {
            abort(1);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            abort(2);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            abort(1);
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        } catch (Throwable e) {
            Log.w(TAG, "cannot create recognizer", e);
            abort(1);
            return;
        }
        startSession();
    }

    /** 1 = fallback به سرور، 2 = نیاز به مجوزِ میکروفون */
    private void abort(int kind) {
        intentionalFinish = true;
        if (kind == 2) BubbleService.asrNeedsPermission();
        else BubbleService.asrFallback();
        finish();
    }

    private void startSession() {
        if (destroyed || recognizer == null) return;
        if (Build.VERSION.SDK_INT < 33) { abort(1); return; }
        try {
            startSession33();
        } catch (Throwable e) {
            Log.w(TAG, "startSession failed", e);
            onFatalError();
        }
    }

    @android.annotation.TargetApi(33)
    private void startSession33() throws Exception {
        closeReadSide();
        readSide = PcmFeed.newSession();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
        recognizer.startListening(i);
    }

    private void restartSoon(long delayMs) {
        main.postDelayed(() -> {
            if (destroyed || recognizer == null) return;
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            startSession();
        }, delayMs);
    }

    private void onFatalError() {
        if (destroyed) return;
        intentionalFinish = true;
        BubbleService.asrFallback();
        finish();
    }

    private void closeReadSide() {
        ParcelFileDescriptor p = readSide;
        readSide = null;
        if (p != null) {
            try { p.close(); } catch (Throwable ignored) {}
        }
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        String s = list.get(0);
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onPartialResults(Bundle partialResults) {
            String t = first(partialResults);
            if (t != null) {
                consecutiveErrors = 0;
                BubbleService.asrPartial(t);
            }
        }

        @Override
        public void onResults(Bundle results) {
            String t = first(results);
            if (t != null) {
                consecutiveErrors = 0;
                BubbleService.asrFinal(t);
            }
            restartSoon(50);
        }

        @Override
        public void onError(int error) {
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    intentionalFinish = true;
                    BubbleService.asrNeedsPermission();
                    finish();
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    onFatalError();
                    return;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    // سکوت/نامفهوم: عادیه، session ی بعدی
                    restartSoon(50);
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    consecutiveErrors++;
                    if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) { onFatalError(); return; }
                    restartSoon(400);
                    return;
                default:
                    consecutiveErrors++;
                    if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) { onFatalError(); return; }
                    restartSoon(200);
            }
        }
    };

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        SpeechRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try { r.cancel(); } catch (Throwable ignored) {}
            try { r.destroy(); } catch (Throwable ignored) {}
        }
        closeReadSide();
        if (current == this) current = null;
        boolean unexpected = !intentionalFinish && !isChangingConfigurations();
        super.onDestroy();
        if (unexpected) BubbleService.asrClosed();
    }
}
