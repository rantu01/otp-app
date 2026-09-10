package com.otpfetch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating chat-head style overlay (Messenger Chat Head behaviour).
 *
 * - Bubble stays on top of other apps, draggable, always visible in
 *   light AND dark mode (explicit blue oval background).
 * - Tap the bubble -> opens a popup with ALL app actions (server toggle,
 *   OTP fetcher, name generator). Tapping anywhere outside the popup
 *   card (or the Close button) dismisses it, exactly like Messenger.
 * - Removed ONLY when the service is stopped (Exit App stops it explicitly).
 */
public class FloatingService extends Service {

    public static final String CHANNEL_ID = "otp_floating";
    public static final int NOTIF_ID = 1001;
    public static volatile String latestCode = "";

    private static final String PREFS = "otp_fetch_prefs";
    private static final String KEY_SAVED = "savedAccountData";

    private WindowManager windowManager;
    private View bubble;
    private TextView bubbleText;

    private View popupView;
    private TextView pServerStatus;
    private Button pServerBtn;
    private EditText pAccount;
    private TextView pEmail;
    private Button pCopyEmail;
    private Button pGetCode;
    private TextView pStatus;
    private TextView pCode;
    private Button pCopyCode;
    private Button pClear;
    private Button pGenderMale, pGenderFemale, pGenderRandom;
    private String pGender = "male";
    private TextView pName;
    private Button pGenName;

    private static FloatingService instance;
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    public static boolean isRunning() { return instance != null; }

    /** Called when a new OTP arrives (from MainActivity or the popup itself). */
    public static void updateCode(String code) {
        latestCode = code == null ? "" : code;
        FloatingService s = instance;
        if (s == null) return;
        final String label = latestCode.isEmpty() ? "OTP" : latestCode;
        s.main.post(() -> {
            if (s.bubbleText != null) s.bubbleText.setText(label);
            if (s.pCode != null && !latestCode.isEmpty()) s.pCode.setText(latestCode);
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
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
                .setContentText("Tap the bubble for actions. Use Exit App to close fully.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ================= bubble =================

    private void showBubble() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        TextView tv = new TextView(this);
        tv.setText(latestCode.isEmpty() ? "OTP" : latestCode);
        tv.setTextSize(14);
        tv.setTextColor(getColor(R.color.bubble_text));
        tv.setGravity(Gravity.CENTER);
        int pad = dp(12);
        tv.setPadding(pad, pad, pad, pad);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(getColor(R.color.bubble_bg));
        tv.setBackground(oval);
        tv.setMinWidth(dp(56));
        tv.setMinHeight(dp(56));
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
                    if (!moved[0]) togglePopup();
                    return true;
            }
            return false;
        });

        try { windowManager.addView(bubble, params); }
        catch (Exception ignored) {}
    }

    // ================= Messenger-style popup =================

    private void togglePopup() {
        if (popupView != null) hidePopup();
        else showPopup();
    }

    private void showPopup() {
        // Full-screen dim layer: tapping anywhere outside the card dismisses,
        // exactly like Messenger Chat Heads.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.popup_dim));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        int outer = dp(24);
        scroll.setPadding(outer, outer, outer, outer);
        scroll.setClickable(true);
        scroll.setOnClickListener(v -> hidePopup());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(getColor(R.color.card_bg));
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        card.setClickable(true); // consume taps so they don't dismiss
        card.setOnClickListener(v -> { });

        card.addView(sectionTitle("rantuOTP", 17));
        pServerStatus = smallText("");
        card.addView(pServerStatus);
        pServerBtn = actionButton("Start Server");
        pServerBtn.setOnClickListener(v -> toggleServer());
        card.addView(pServerBtn);

        card.addView(sectionTitle("OTP Fetcher", 14));

        pAccount = new EditText(this);
        pAccount.setHint("Paste Account Data (Email|RefreshToken|ClientId)...");
        pAccount.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pAccount.setMinLines(3);
        pAccount.setTextSize(11);
        pAccount.setTextColor(getColor(R.color.title_text));
        pAccount.setText(prefs.getString(KEY_SAVED, ""));
        card.addView(pAccount);

        pEmail = new TextView(this);
        pEmail.setGravity(Gravity.CENTER);
        pEmail.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pEmail.setTextColor(getColor(R.color.accent_blue));
        pEmail.setVisibility(View.GONE);
        card.addView(pEmail);

        pCopyEmail = actionButton("Copy Email");
        pCopyEmail.setVisibility(View.GONE);
        pCopyEmail.setOnClickListener(v -> copy(pEmail.getText().toString()));
        card.addView(pCopyEmail);

        pGetCode = actionButton("GET CODE");
        pGetCode.setOnClickListener(v -> popupGetCode());
        card.addView(pGetCode);

        pStatus = smallText("");
        pStatus.setGravity(Gravity.CENTER);
        card.addView(pStatus);

        pCode = new TextView(this);
        pCode.setGravity(Gravity.CENTER);
        pCode.setTextSize(22);
        pCode.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pCode.setTextColor(getColor(R.color.otp_green));
        pCode.setText(latestCode.isEmpty() ? "------" : latestCode);
        card.addView(pCode);

        pCopyCode = actionButton("Copy Code");
        pCopyCode.setOnClickListener(v -> copy(pCode.getText().toString()));
        card.addView(pCopyCode);

        pClear = actionButton("Clear Saved Data");
        pClear.setOnClickListener(v -> {
            pAccount.setText("");
            prefs.edit().remove(KEY_SAVED).apply();
            refreshPopupEmail("");
            pStatus.setText("");
            pCode.setText("------");
        });
        card.addView(pClear);

        card.addView(sectionTitle("US Name Gen", 14));

        LinearLayout genderRow = new LinearLayout(this);
        genderRow.setOrientation(LinearLayout.HORIZONTAL);
        pGenderMale = genderButton("Male", "male");
        pGenderFemale = genderButton("Female", "female");
        pGenderRandom = genderButton("Random", "both");
        genderRow.addView(pGenderMale);
        genderRow.addView(pGenderFemale);
        genderRow.addView(pGenderRandom);
        card.addView(genderRow);
        refreshGenderUi();

        pGenName = actionButton("Generate Name");
        pName = new TextView(this);
        pName.setGravity(Gravity.CENTER);
        pName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pName.setTextColor(getColor(R.color.accent_blue));
        pName.setVisibility(View.GONE);
        Button pCopyName = actionButton("Copy Name");
        Button pCopySplit = actionButton("Copy First, Last");
        pGenName.setOnClickListener(v -> {
            String name = OtpHelper.generateName(pGender);
            pName.setText(name);
            pName.setVisibility(View.VISIBLE);
        });
        pCopyName.setOnClickListener(v -> copy(pName.getText().toString()));
        pCopySplit.setOnClickListener(v -> copy(OtpHelper.splitCopyFormat(pName.getText().toString())));
        card.addView(pGenName);
        card.addView(pName);
        card.addView(pCopyName);
        card.addView(pCopySplit);

        Button openApp = actionButton("Open Full App");
        openApp.setOnClickListener(v -> {
            hidePopup();
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        });
        Button close = actionButton("Close");
        close.setOnClickListener(v -> hidePopup());
        card.addView(openApp);
        card.addView(close);

        pAccount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(KEY_SAVED, s.toString()).apply();
                refreshPopupEmail(s.toString());
            }
        });
        refreshPopupEmail(pAccount.getText().toString());
        refreshPopupServerUi();

        scroll.addView(card);
        root.addView(scroll,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(root, params);
            popupView = root;
        } catch (Exception ignored) {}
    }

    private void hidePopup() {
        try {
            if (windowManager != null && popupView != null) {
                try {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(popupView.getWindowToken(), 0);
                } catch (Exception ignored) {}
                windowManager.removeViewImmediate(popupView);
            }
        } catch (Exception ignored) {}
        popupView = null;
        pServerStatus = null; pServerBtn = null; pAccount = null; pEmail = null;
        pCopyEmail = null; pGetCode = null; pStatus = null; pCode = null;
        pCopyCode = null; pClear = null; pName = null; pGenName = null;
        pGenderMale = null; pGenderFemale = null; pGenderRandom = null;
    }

    // ---------- popup actions (same logic as MainActivity) ----------

    private void toggleServer() {
        net.execute(() -> {
            try {
                if (OtpServer.getInstance().isRunning()) OtpServer.getInstance().stop();
                else OtpServer.getInstance().start();
            } catch (Exception e) {
                toast("Server failed: " + e.getMessage());
            }
            main.post(this::refreshPopupServerUi);
        });
    }

    private void refreshPopupServerUi() {
        if (pServerStatus == null || pServerBtn == null) return;
        boolean running = OtpServer.getInstance().isRunning();
        pServerStatus.setText(running ? "Server RUNNING — :3000" : "Server STOPPED");
        pServerStatus.setTextColor(getColor(running ? R.color.success : R.color.error));
        pServerBtn.setText(running ? "Stop Server" : "Start Server");
    }

    private void refreshPopupEmail(String data) {
        if (pEmail == null || pCopyEmail == null) return;
        String email = OtpHelper.extractEmail(data);
        if (!email.isEmpty()) {
            pEmail.setText(email);
            pEmail.setVisibility(View.VISIBLE);
            pCopyEmail.setVisibility(View.VISIBLE);
        } else {
            pEmail.setVisibility(View.GONE);
            pCopyEmail.setVisibility(View.GONE);
        }
    }

    private void popupGetCode() {
        if (pAccount == null) return;
        String data = pAccount.getText().toString().trim();
        if (data.isEmpty()) {
            popupStatus("Please paste account data first!", true);
            return;
        }
        final OtpHelper.Account acc;
        try {
            acc = OtpHelper.parseAccountData(data);
        } catch (IllegalArgumentException e) {
            popupStatus(e.getMessage(), true);
            return;
        }
        if (!OtpServer.getInstance().isRunning()) {
            try { OtpServer.getInstance().start(); }
            catch (Exception e) {
                popupStatus("Server failed to start: " + e.getMessage(), true);
                return;
            }
            refreshPopupServerUi();
        }
        popupStatus("Connecting to Microsoft Graph API...", false);
        if (pGetCode != null) pGetCode.setEnabled(false);
        net.execute(() -> {
            OtpServer.FetchResult r =
                    OtpServer.getInstance().fetchOtp(acc.email, acc.refreshToken, acc.clientId);
            main.post(() -> {
                if (pGetCode != null) pGetCode.setEnabled(true);
                if (r.success) {
                    updateCode(r.code);
                    popupStatus("OTP Fetched Successfully!", false);
                } else {
                    popupStatus(r.error != null ? r.error : "OTP Not Found", true);
                }
            });
        });
    }

    private void popupStatus(String msg, boolean isError) {
        if (pStatus == null) return;
        pStatus.setText(msg);
        pStatus.setTextColor(getColor(isError ? R.color.error : R.color.success));
    }

    // ---------- popup view helpers ----------

    private TextView sectionTitle(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(getColor(R.color.title_text));
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private TextView smallText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(getColor(R.color.title_text));
        return tv;
    }

    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private Button genderButton(String label, String value) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            pGender = value;
            refreshGenderUi();
        });
        return b;
    }

    private void refreshGenderUi() {
        if (pGenderMale == null) return;
        pGenderMale.setAlpha("male".equals(pGender) ? 1f : 0.5f);
        pGenderFemale.setAlpha("female".equals(pGender) ? 1f : 0.5f);
        pGenderRandom.setAlpha("both".equals(pGender) ? 1f : 0.5f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("rantuOTP", text));
        toast("Copied to clipboard");
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(FloatingService.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hidePopup();
        net.shutdownNow();
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
