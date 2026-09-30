package com.linglearn.app.overlay;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class BubbleService extends Service {

    private static final String TAG = "BubbleService";
    private WindowManager windowManager;
    private View bubbleView;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (bubbleView == null) {
            showBubble();
        }
        return START_STICKY;
    }

    private void showBubble() {
        TextView bubble = new TextView(this);
        bubble.setText("🎙");
        bubble.setTextSize(24f);
        bubble.setTextColor(Color.WHITE);
        bubble.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#1B4640"));
        bg.setStroke(4, Color.WHITE);
        bubble.setBackground(bg);

        int size = (int) (60 * getResources().getDisplayMetrics().density);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                size, size,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) moved = true;
                        params.x = initialX + dx;
                        params.y = initialY + dy;
                        windowManager.updateViewLayout(v, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            Log.d(TAG, "Bubble tapped — hiding");
                            stopSelf();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(bubble, params);
        bubbleView = bubble;
        Log.d(TAG, "Bubble shown");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bubbleView != null) {
            windowManager.removeView(bubbleView);
            bubbleView = null;
            Log.d(TAG, "Bubble removed");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}