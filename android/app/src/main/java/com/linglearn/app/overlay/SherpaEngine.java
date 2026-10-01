package com.linglearn.app.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.util.Locale;

final class SherpaEngine {

    private static final String TAG = "SherpaEngine";
    private static final int SAMPLE_RATE = 16000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private boolean released = false;
    private boolean failed = false;
    private String lastPartial = "";
    private float[] floats = new float[1600];

    private SherpaEngine(OnlineRecognizer r, OnlineStream s) {
        this.recognizer = r;
        this.stream = s;
    }

    static boolean isAvailable(Context ctx, String lang) {
        return SherpaModelManager.getModelDir(ctx, lang) != null;
    }

    static SherpaEngine create(Context ctx, String lang) {
        File dir = SherpaModelManager.getModelDir(ctx, lang);
        if (dir == null) return null;
        OnlineRecognizer rec = null;
        try {
            OnlineTransducerModelConfig tr = new OnlineTransducerModelConfig();
            tr.setEncoder(new File(dir, SherpaModelManager.encoderFile(lang)).getAbsolutePath());
            tr.setDecoder(new File(dir, SherpaModelManager.decoderFile(lang)).getAbsolutePath());
            tr.setJoiner(new File(dir, SherpaModelManager.joinerFile(lang)).getAbsolutePath());

            OnlineModelConfig mc = new OnlineModelConfig();
            mc.setTransducer(tr);
            mc.setTokens(new File(dir, SherpaModelManager.tokensFile(lang)).getAbsolutePath());
            mc.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            mc.setDebug(false);
            mc.setProvider("cpu");

            FeatureConfig fc = new FeatureConfig();
            fc.setSampleRate(SAMPLE_RATE);
            fc.setFeatureDim(80);

            // تنظیم دقیق Endpoint — دقیقاً مثل LingoNative
            // rule1: 2.4s (جمله کامل قطع)
            // rule2: 1.4s (جمله فعلی بسته بشه) ← مهم‌ترین
            // rule3: 20s (حداکثر طول جمله)
            EndpointConfig ec = new EndpointConfig();
            try { ec.setRule1MinTrailingSilence(2.4f); } catch (Throwable ignored) {}
            try { ec.setRule2MinTrailingSilence(1.4f); } catch (Throwable ignored) {}
            try { ec.setRule3MinUtteranceLength(20f); } catch (Throwable ignored) {}

            OnlineRecognizerConfig cfg = new OnlineRecognizerConfig();
            cfg.setFeatConfig(fc);
            cfg.setModelConfig(mc);
            cfg.setEndpointConfig(ec);
            cfg.setEnableEndpoint(true);
            cfg.setDecodingMethod("greedy_search");

            rec = new OnlineRecognizer(null, cfg);
            OnlineStream st = rec.createStream("");
            Log.i(TAG, "model loaded from " + dir);
            return new SherpaEngine(rec, st);
        } catch (Throwable e) {
            Log.e(TAG, "cannot load model for " + lang, e);
            if (rec != null) { try { rec.release(); } catch (Throwable ignored) {} }
            return null;
        }
    }

    void accept(byte[] pcm16k, int len) {
        if (pcm16k == null || len < 2) return;
        String partialToSend = null, finalToSend = null;
        boolean fail = false;
        synchronized (lock) {
            if (released || failed) return;
            try {
                int n = len / 2;
                if (floats.length < n) floats = new float[n];
                for (int i = 0; i < n; i++) {
                    short s = (short) ((pcm16k[2 * i] & 0xff) | (pcm16k[2 * i + 1] << 8));
                    floats[i] = s / 32768f;
                }
                float[] samples = (floats.length == n) ? floats : java.util.Arrays.copyOf(floats, n);
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);

                String text = normalize(recognizer.getResult(stream).getText());
                if (recognizer.isEndpoint(stream)) {
                    if (!text.isEmpty()) finalToSend = text;
                    recognizer.reset(stream);
                    lastPartial = "";
                } else if (!text.isEmpty() && !text.equals(lastPartial)) {
                    lastPartial = text;
                    partialToSend = text;
                }
            } catch (Throwable e) {
                Log.e(TAG, "decode failed", e);
                failed = true;
                fail = true;
            }
        }
        if (finalToSend != null) { final String t = finalToSend; MAIN.post(() -> BubbleService.asrFinal(t)); }
        else if (partialToSend != null) { final String t = partialToSend; MAIN.post(() -> BubbleService.asrPartial(t)); }
        if (fail) MAIN.post(BubbleService::asrFallback);
    }

    void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            try { if (stream != null) stream.release(); } catch (Throwable e) { Log.w(TAG, "stream release", e); }
            try { if (recognizer != null) recognizer.release(); } catch (Throwable e) { Log.w(TAG, "recognizer release", e); }
            stream = null;
            recognizer = null;
            floats = new float[0];
            lastPartial = "";
        }
    }

    private static String normalize(String t) {
        if (t == null) return "";
        t = t.trim();
        if (t.isEmpty()) return "";
        if (t.equals(t.toUpperCase(Locale.ROOT)) && !t.equals(t.toLowerCase(Locale.ROOT))) {
            t = t.toLowerCase(Locale.ROOT).replaceAll("\\bi\\b", "I");
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }
}
