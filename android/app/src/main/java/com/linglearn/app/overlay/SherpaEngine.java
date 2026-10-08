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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class SherpaEngine implements PcmSink {

    private static final String TAG = "SherpaEngine";
    private static final int SAMPLE_RATE = 16000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private volatile boolean released = false;
    private volatile boolean failed = false;
    private String lastPartial = "";
    // صدا از thread ضبط فقط وارد صف می‌شود؛ رمزگشایی در thread جدا انجام می‌شود تا ضبط/UI هیچ‌وقت منتظر بسته نماند
    private final LinkedBlockingQueue<float[]> queue = new LinkedBlockingQueue<>(500);
    private Thread worker;

    private SherpaEngine(OnlineRecognizer r, OnlineStream s) {
        this.recognizer = r;
        this.stream = s;
        worker = new Thread(this::runWorker, "sherpa-decode");
        worker.setDaemon(true);
        worker.start();
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

            OnlineRecognizerConfig cfg = new OnlineRecognizerConfig();
            cfg.setFeatConfig(fc);
            cfg.setModelConfig(mc);
            cfg.setEndpointConfig(new EndpointConfig());
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

    @Override
    public void accept(byte[] pcm16k, int len) {
        if (pcm16k == null || len < 2 || released || failed) return;
        int n = len / 2;
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm16k[2 * i] & 0xff) | (pcm16k[2 * i + 1] << 8));
            f[i] = s / 32768f;
        }
        if (!queue.offer(f)) { queue.poll(); queue.offer(f); }   // صف پر شد (موتور خیلی عقب است) → قدیمی‌ترین تکه دور ریخته می‌شود
    }

    private void runWorker() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
        while (!released && !failed) {
            float[] first;
            try { first = queue.poll(100, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { return; }
            if (first == null) continue;
            String partialToSend = null, finalToSend = null;
            boolean fail = false;
            synchronized (lock) {
                if (released || failed) return;
                try {
                    stream.acceptWaveform(first, SAMPLE_RATE);
                    float[] nx;
                    while ((nx = queue.poll()) != null) stream.acceptWaveform(nx, SAMPLE_RATE);   // هرچه عقب مانده یک‌جا برسد
                    boolean decoded = false;
                    while (recognizer.isReady(stream)) { recognizer.decode(stream); decoded = true; }
                    if (decoded || recognizer.isEndpoint(stream)) {
                        String text = normalize(recognizer.getResult(stream).getText());
                        if (recognizer.isEndpoint(stream)) {
                            if (!text.isEmpty()) finalToSend = text;
                            recognizer.reset(stream);
                            lastPartial = "";
                        } else if (!text.isEmpty() && !text.equals(lastPartial)) {
                            lastPartial = text;
                            partialToSend = text;
                        }
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
    }

    @Override
    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            try { if (stream != null) stream.release(); } catch (Throwable e) { Log.w(TAG, "stream release", e); }
            try { if (recognizer != null) recognizer.release(); } catch (Throwable e) { Log.w(TAG, "recognizer release", e); }
            stream = null;
            recognizer = null;
            lastPartial = "";
        }
        queue.clear();
        Thread w = worker;
        if (w != null) w.interrupt();
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
