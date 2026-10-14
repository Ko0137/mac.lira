package com.example.myjarvis;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class FloatingWidgetService extends Service {

    private WindowManager windowManager;
    private FrameLayout floatingButton;
    private WindowManager.LayoutParams params;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (!getSharedPreferences("LiraPrefs", MODE_PRIVATE)
                .getBoolean("floating_widget_enabled", false)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this))) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingButton = new FrameLayout(this);
        floatingButton.setContentDescription("Голосовой помощник L.I.R.A. Нажми, чтобы поговорить");

        TextView logo = new TextView(this);
        logo.setText("L");
        logo.setTextColor(0xFF00E676);
        logo.setTextSize(23);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setShape(GradientDrawable.OVAL);
        buttonBackground.setColor(0xFF17212B);
        buttonBackground.setStroke(dp(2), 0xFF00E676);
        logo.setBackground(buttonBackground);
        logo.setElevation(dp(8));
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(dp(58), dp(58));
        floatingButton.addView(logo, logoParams);

        ImageView microphone = new ImageView(this);
        microphone.setImageResource(android.R.drawable.ic_btn_speak_now);
        microphone.setColorFilter(0xFF0E1621);
        GradientDrawable microphoneBackground = new GradientDrawable();
        microphoneBackground.setShape(GradientDrawable.OVAL);
        microphoneBackground.setColor(0xFF00E676);
        microphone.setBackground(microphoneBackground);
        microphone.setPadding(dp(4), dp(4), dp(4), dp(4));
        FrameLayout.LayoutParams microphoneParams =
                new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.BOTTOM | Gravity.END);
        microphoneParams.setMargins(0, 0, 0, 0);
        floatingButton.addView(microphone, microphoneParams);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int moveX = (int) (event.getRawX() - initialTouchX);
                        int moveY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(moveX) >= dp(10) || Math.abs(moveY) >= dp(10)) {
                            moved = true;
                        }
                        params.x = initialX + moveX;
                        params.y = initialY + moveY;
                        windowManager.updateViewLayout(floatingButton, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            startListening();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingButton, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startListening() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(LiraBackgroundService.EXTRA_START_VOICE, true);
        startActivity(intent);
    }

    @Override
    public void onDestroy() {
        if (floatingButton != null && windowManager != null) {
            windowManager.removeView(floatingButton);
            floatingButton = null;
        }
        super.onDestroy();
    }
}
