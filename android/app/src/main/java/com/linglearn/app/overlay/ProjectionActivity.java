package com.linglearn.app.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

public class ProjectionActivity extends Activity {

    private static final String TAG = "ProjectionActivity";
    private static final int REQ_AUDIO = 1;
    private static final int REQ_PROJECTION = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else {
            launchProjection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchProjection();
            } else {
                deny();
            }
        }
    }

    private void launchProjection() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager is null");
            deny();
            return;
        }

        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: فقط Entire Screen (نه تک‌اپ) → اپ‌های دیگه قفل نمی‌شن
            intent = mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
            );
        } else {
            intent = mpm.createScreenCaptureIntent();
        }

        try {
            startActivityForResult(intent, REQ_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "cannot launch projection intent", e);
            deny();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, BubbleService.class)
                    .setAction(BubbleService.ACTION_PROJECTION_RESULT)
                    .putExtra(BubbleService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(BubbleService.EXTRA_DATA, data);
            startForegroundService(i);
            finish();
        } else {
            deny();
        }
    }

    private void deny() {
        startService(new Intent(this, BubbleService.class).setAction(BubbleService.ACTION_PROJECTION_DENIED));
        finish();
    }
}
