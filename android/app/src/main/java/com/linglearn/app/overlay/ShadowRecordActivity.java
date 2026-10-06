package com.linglearn.app.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;

/**
 * Activity ی نامرئی برای ضبطِ صدای کاربر (تمرینِ shadowing).
 * از BubbleService با Intent باز می‌شه؛ با stopIfRunning() ضبط تموم و فایل به BubbleService.shadowDone می‌ره.
 */
public class ShadowRecordActivity extends Activity {

    private static final String TAG = "ShadowRec";
    private static volatile ShadowRecordActivity current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File outFile;
    private boolean finishedReported = false;

    static void stopIfRunning() {
        final ShadowRecordActivity a = current;
        if (a == null) { BubbleService.shadowDone(null); return; }
        a.main.post(a::finishRecording);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } catch (Throwable ignored) {}

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report(null, 2);
            finish();
            return;
        }
        try {
            outFile = new File(getCacheDir(), "shadow_rec.m4a");
            if (outFile.exists()) outFile.delete();
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Throwable e) {
            Log.w(TAG, "record start failed", e);
            releaseRecorder();
            report(null, 1);
            finish();
        }
    }

    private void finishRecording() {
        String path = null;
        if (recorder != null) {
            try {
                recorder.stop();
                path = outFile.getAbsolutePath();
            } catch (Throwable e) {
                Log.w(TAG, "record stop failed", e);
            }
            releaseRecorder();
        }
        report(path, 0);
        finish();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
    }

    /** err: 0 = ok, 1 = خطای ضبط، 2 = نبودِ مجوزِ میکروفون */
    private void report(String path, int err) {
        if (finishedReported) return;
        finishedReported = true;
        if (err != 0) BubbleService.shadowFailed(err);
        else BubbleService.shadowDone(path);
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) {}
            releaseRecorder();
            report(null, 1);
        }
        if (current == this) current = null;
        super.onDestroy();
    }
}
