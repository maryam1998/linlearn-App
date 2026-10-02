package com.linglearn.app.overlay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;

final class TtsEngine {

    private static final String TAG = "TtsEngine";

    private final OfflineTts tts;
    private final int sampleRate;
    private volatile boolean released = false;
    private volatile AudioTrack currentTrack = null;

    private TtsEngine(OfflineTts tts, int sampleRate) {
        this.tts = tts;
        this.sampleRate = sampleRate;
    }

    static TtsEngine create(Context ctx, String lang) {
        File dir = SherpaModelManager.getTtsModelDir(ctx, lang);
        if (dir == null) {
            Log.w(TAG, "TTS model not found for " + lang);
            return null;
        }
        try {
            OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig();
            vitsConfig.setModel(new File(dir, "model.onnx").getAbsolutePath());
            vitsConfig.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
            vitsConfig.setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath());

            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setVits(vitsConfig);
            modelConfig.setNumThreads(2);
            modelConfig.setDebug(false);

            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(modelConfig);

            // ✅ اصلاح: null به عنوان AssetManager + getSampleRate(0)
            OfflineTts engine = new OfflineTts(null, config);
            int sr = engine.getSampleRate(0);
            if (sr <= 0) sr = 22050;
            Log.i(TAG, "TTS loaded for " + lang + ", sampleRate=" + sr);
            return new TtsEngine(engine, sr);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load TTS for " + lang, e);
            return null;
        }
    }

    /** تبدیل متن به گفتار و پخش. speed = 1.0 برای سرعت عادی. */
    void speak(String text, float speed) {
        if (released || text == null || text.trim().isEmpty()) return;
        final String t = text.trim();
        final float sp = (speed <= 0f) ? 1.0f : speed;
        new Thread(() -> {
            try {
                // ✅ پارامتر دوم: speakerId = 0
                GeneratedAudio audio = tts.generate(t, 0, sp);
                if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                    Log.w(TAG, "Generated audio is empty");
                    return;
                }
                playPcm(audio.getSamples());
            } catch (Throwable e) {
                Log.e(TAG, "TTS generate failed", e);
            }
        }, "tts-speak").start();
    }

    private void playPcm(float[] samples) {
        if (released) return;
        int minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        int bufSize = Math.max(minBuf, samples.length * 2);

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        short[] pcm16 = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = Math.max(-1f, Math.min(1f, samples[i]));
            pcm16[i] = (short) (s * 32767);
        }

        currentTrack = track;
        try {
            track.play();
            track.write(pcm16, 0, pcm16.length);
            track.stop();
        } catch (Throwable e) {
            Log.e(TAG, "playback failed", e);
        } finally {
            if (currentTrack == track) currentTrack = null;
            try { track.release(); } catch (Throwable ignored) {}
        }
    }

    /** ✅ توقف پخش فعلی */
    void stop() {
        AudioTrack t = currentTrack;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
            currentTrack = null;
        }
    }

    /** ✅ آزادسازی کامل */
    void release() {
        if (released) return;
        released = true;
        stop();
        try { if (tts != null) tts.release(); } catch (Throwable ignored) {}
    }
}
