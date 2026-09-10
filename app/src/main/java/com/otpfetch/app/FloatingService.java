package com.otpfetch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Floating chat-head style overlay.
 *
 * - Stays on top of other apps (TYPE_APPLICATION_OVERLAY), draggable.
 * - Single tap re-opens MainActivity.
 * - Shows the latest fetched OTP code on the bubble.
 * - Removed ONLY when the service is stopped (Exit App stops it explicitly).
 */
public class FloatingService extends Service {

    public static final String CHANNEL_ID = "otp_floating";
    public static final int NOTIF_ID = 1001;
    public static volatile String latestCode = "";

    private WindowManager windowManager;
    private View bubble;
    private TextView bubbleText;
    private static FloatingService instance;

    public static boolean isRunning() { return instance != null; }

    /** Called by MainActivity when a new OTP arrives. */
    public static void updateCode(String code) {
        latestCode = code == null ? "" : code;
        FloatingService s = instance;
        if (s != null && s.bubbleText != null) {
            final String label = latestCode.isEmpty() ? "OTP" : latestCode;
            s.bubbleText.post(() -> s.bubbleText.setText(label));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        showBubble();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Floating widget", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("rantuOTP is floating")
                .setContentText("Tap the bubble to open the app. Use Exit App to close fully.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void showBubble() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        TextView tv = new TextView(this);
        tv.setText(latestCode.isEmpty() ? "OTP" : latestCode);
        tv.setTextSize(14);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(36, 36, 36, 36);
        tv.setBackgroundResource(android.R.drawable.btn_default);
        // Tint via background color filter is not critical; keep readable default.
        bubbleText = tv;
        bubble = tv;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 300;

        final float[] touchStart = new float[2];
        final int[] viewStart = new int[2];
        final boolean[] moved = {false};

        tv.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStart[0] = event.getRawX();
                    touchStart[1] = event.getRawY();
                    viewStart[0] = params.x;
                    viewStart[1] = params.y;
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (event.getRawX() - touchStart[0]);
                    int dy = (int) (event.getRawY() - touchStart[1]);
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved[0] = true;
                    params.x = viewStart[0] + dx;
                    params.y = viewStart[1] + dy;
                    try { windowManager.updateViewLayout(bubble, params); }
                    catch (Exception ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) openApp();
                    return true;
            }
            return false;
        });

        try { windowManager.addView(bubble, params); }
        catch (Exception ignored) {}
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (windowManager != null && bubble != null) windowManager.removeViewImmediate(bubble);
        } catch (Exception ignored) {}
        bubble = null;
        bubbleText = null;
        windowManager = null;
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
