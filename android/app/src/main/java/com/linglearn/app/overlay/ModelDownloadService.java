package com.linglearn.app.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * سرویس پیش‌زمینه برای دانلود صداها: تا وقتی دانلودی در جریانه، پروسه‌ی اپ زنده می‌مونه
 * (حتی اگه کاربر از اپ بیرون بره). خودش وقتی همه‌ی دانلودها تموم شد متوقف می‌شه.
 * خودِ دانلودها توی SherpaModelManager (نخ‌های جدا) انجام می‌شن؛ این سرویس فقط
 * پروسه رو نگه می‌داره و نوتیفیکیشنِ پیشرفت رو نشون می‌ده.
 */
public class ModelDownloadService extends Service {

    static final String ACTION_STOP = "com.linglearn.app.STOP_MODEL_DOWNLOAD";
    private static final String CHANNEL_ID = "model_download";
    private static final int NOTIF_ID = 4711;
    private static final int DONE_NOTIF_ID = 4712;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean started = false;

    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx.getApplicationContext(), ModelDownloadService.class);
            ContextCompat.startForegroundService(ctx.getApplicationContext(), i);
        } catch (Throwable ignored) {}
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            List<String> active = SherpaModelManager.getTtsDownloadingLangs();
            if (active.isEmpty()) {
                finishUp();
                return;
            }
            long mb = SherpaModelManager.getTtsDownloadedBytes() / (1024 * 1024);
            NotificationManagerCompat nm = NotificationManagerCompat.from(ModelDownloadService.this);
            try {
                nm.notify(NOTIF_ID, buildNotification(active.size(), mb));
            } catch (SecurityException ignored) {}
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // دکمه‌ی «توقف» توی نوتیفیکیشن: همه‌ی دانلودها متوقف می‌شن (سرویس خودش بعدش خاموش می‌شه)
            SherpaModelManager.cancelAllTtsDownloads();
            return START_NOT_STICKY;
        }
        createChannel();
        Notification n = buildNotification(Math.max(1, SherpaModelManager.getTtsDownloadingLangs().size()), 0);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linlearn:modelDownload");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire(2 * 60 * 60 * 1000L); // سقف ۲ ساعت
                }
            } catch (Throwable ignored) {}
            handler.post(tick);
        }
        return START_NOT_STICKY;
    }

    private void finishUp() {
        handler.removeCallbacks(tick);
        boolean all = true;
        for (String l : SherpaModelManager.ttsLanguages()) {
            if (SherpaModelManager.getTtsModelDir(this, l) == null) { all = false; break; }
        }
        try { stopForeground(true); } catch (Throwable ignored) {}
        if (!all) { stopSelf(); return; }
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("LingoLearn")
                    .setContentText("دانلود صداها تمام شد")
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent());
            NotificationManagerCompat.from(this).notify(DONE_NOTIF_ID, b.build());
        } catch (Throwable ignored) {}
        stopSelf();
    }

    private Notification buildNotification(int count, long mb) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("در حال دانلود صداهای آفلاین")
                .setContentText(count + " صدا · " + mb + " MB")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(openAppIntent())
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "توقف", stopIntent())
                .build();
    }

    private PendingIntent stopIntent() {
        Intent i = new Intent(this, ModelDownloadService.class).setAction(ACTION_STOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, 1, i, f);
    }

    private PendingIntent openAppIntent() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i == null) i = new Intent();
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, f);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "دانلود صداها", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
