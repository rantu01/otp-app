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
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Scroller;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating chat-head overlay.
 *
 * - Small bubble (44dp), draggable, tap to open compact popup.
 * - Popup: compact centered card (300dp) with ✕ in the top corner.
 *   Tapping outside the card dismisses it.
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
    private Spinner pCountrySpinner;
    private TextView pDomain;
    private TextView pSuggestedEmail;
    private int pCountry = 0;
    private boolean pProgrammaticCountry = false;
    private Button pGenderMale, pGenderFemale, pGenderRandom;
    private String pGender = "male";
    private TextView pFirstName, pLastName;
    private Button pGenName;
    private boolean pProgrammaticAccount = false;
    private boolean pProgrammaticGender = false;

    private static FloatingService instance;
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    /**
     * FAIL-CLOSED widget gate: popup OTP/server/name actions stay disabled
     * until the backend explicitly confirms access (verified on service start).
     * MainActivity stops this service outright whenever access is revoked.
     */
    private volatile boolean serviceAccessAllowed = false;
    private final OtpServer.OtpListener autoOtpListener = (email, code) ->
            main.post(() -> onAutoOtp(email, code));

    private final AutoFetchManager.StatusListener autoFetchStatus =
            new AutoFetchManager.StatusListener() {
                @Override public void onAutoFetchStarted(String email) {
                    main.post(() -> popupStatus("Loading " + email + "...", false));
                }
                @Override public void onAutoFetchError(String email, String error) {
                    main.post(() -> popupStatus(error, true));
                }
            };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (p, key) -> main.post(() -> onSharedStateChanged(key));

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
        if (latestCode.isEmpty()) {
            String savedCode = OtpAutoActions.getLastCode(this);
            if (savedCode != null && !savedCode.isEmpty()) latestCode = savedCode;
        }
        pGender = OtpAutoActions.getGender(this);
        pCountry = OtpAutoActions.getCountry(this);
        OtpServer.getInstance().init(getApplicationContext());
        OtpServer.getInstance().addOtpListener(autoOtpListener);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        AutoFetchManager.addStatusListener(autoFetchStatus);
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        showBubble();
        verifyServiceAccess();
    }

    /** Widget entry gate: no session or no backend approval -> kill the widget. */
    private void verifyServiceAccess() {
        if (!SessionManager.isLoggedIn(this)) {
            stopSelf();
            return;
        }
        net.execute(() -> {
            try {
                AccessGate.Result r = AccessGate.check(this);
                if (r.allowed) {
                    serviceAccessAllowed = true;
                } else {
                    // Revoked/expired/pending mid-session: no widget.
                    serviceAccessAllowed = false;
                    main.post(() -> {
                        toast(r.message.isEmpty() ? "Access revoked" : r.message);
                        stopSelf();
                    });
                }
            } catch (Exception e) {
                // Offline: stay fail-closed (actions blocked) until verified.
                serviceAccessAllowed = false;
            }
        });
    }

    /** Popup action guard: blocks + re-verifies when access is unconfirmed. */
    private boolean requireServiceAccess() {
        if (serviceAccessAllowed) return true;
        toast("Locked — verifying access");
        verifyServiceAccess();
        return false;
    }

    /** Single-app sync: reflect state written by the main app. */
    private void onSharedStateChanged(String key) {
        if (key == null) return;
        try {
            if (OtpAutoActions.KEY_SAVED.equals(key)) {
                if (pAccount == null) return;
                String saved = prefs.getString(KEY_SAVED, "");
                if (!saved.equals(pAccount.getText().toString())) {
                    pProgrammaticAccount = true;
                    pAccount.setText(saved);
                    pProgrammaticAccount = false;
                    refreshPopupEmail(saved);
                }
            } else if (OtpAutoActions.KEY_GEN_NAME.equals(key)) {
                String name = OtpAutoActions.getGenName(this);
                if (name != null && !name.isEmpty()) showPopupName(name);
            } else if (OtpAutoActions.KEY_GENDER.equals(key)) {
                String g = OtpAutoActions.getGender(this);
                if (g != null && !g.isEmpty() && !g.equals(pGender)) {
                    pProgrammaticGender = true;
                    pGender = g;
                    pProgrammaticGender = false;
                    refreshGenderUi();
                }
            } else if (OtpAutoActions.KEY_COUNTRY.equals(key)) {
                int c = OtpAutoActions.getCountry(this);
                if (c != pCountry && c >= 0 && c < CountryData.COUNTRIES.length) {
                    pCountry = c;
                    refreshDomainUi();
                    if (pCountrySpinner != null) {
                        pProgrammaticCountry = true;
                        pCountrySpinner.setSelection(c);
                        pProgrammaticCountry = false;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** Re-sent OTPs update the open popup in place; only the selected app auto-opens. */
    private void onAutoOtp(String email, String code) {
        updateCode(code);
        try {
            if (popupView == null) return;
            if (pCode != null) pCode.setText(code);
            if (pStatus != null) popupStatus("✓ " + email + " · copied", false);
        } catch (Exception ignored) {}
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
                .setContentTitle("Rantu_OTP")
                .setContentText("Tap bubble for OTP. Exit App closes fully.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ================= bubble (small 44dp) =================

    private void showBubble() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        TextView tv = new TextView(this);
        tv.setText(latestCode.isEmpty() ? "OTP" : latestCode);
        tv.setTextSize(12);
        tv.setTextColor(getColor(R.color.bubble_text));
        tv.setGravity(Gravity.CENTER);
        int pad = dp(8);
        tv.setPadding(pad, pad, pad, pad);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(getColor(R.color.bubble_bg));
        oval.setStroke(dp(2), 0xFFFFFFFF);
        tv.setBackground(oval);
        tv.setMinWidth(dp(44));
        tv.setMinHeight(dp(44));
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

    // ================= compact popup (300dp, ✕ top corner, tap-outside closes) =================

    private void togglePopup() {
        if (popupView != null) hidePopup();
        else showPopup();
    }

    private void showPopup() {
        try {
            pCountry = OtpAutoActions.getCountry(this);
        } catch (Exception ignored) { pCountry = 0; }
        if (pCountry < 0 || pCountry >= CountryData.COUNTRIES.length) pCountry = 0;
        // Full-screen dim layer: tapping anywhere outside the card dismisses.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.popup_dim));
        root.setClickable(true);
        root.setOnClickListener(v -> hidePopup());

        // Compact centered card (slightly smaller, easier to handle).
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(getColor(R.color.card_bg));
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) card.setElevation(dp(8));
        int pad = dp(10);
        card.setPadding(pad, pad, pad, pad);
        card.setClickable(true); // consume taps so they don't dismiss
        card.setOnClickListener(v -> { });

        int cardW = dp(300);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.8);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);

        // Header: title + ✕ top corner.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Rantu_OTP");
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.accent_blue));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        header.addView(title);
        Button closeX = new Button(this);
        closeX.setText("✕");
        closeX.setTextSize(13);
        LinearLayout.LayoutParams xLp = new LinearLayout.LayoutParams(
                dp(38), dp(38));
        closeX.setLayoutParams(xLp);
        tint(closeX, R.color.exit_red);
        closeX.setOnClickListener(v -> hidePopup());
        header.addView(closeX);
        card.addView(header);

        // Scrollable compact body.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.min(maxH, dp(460)));
        scroll.setLayoutParams(scrollLp);
        // Taps on empty scroll area outside inner content also dismiss.
        scroll.setOnClickListener(v -> hidePopup());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClickable(true);
        body.setOnClickListener(v -> { });

        pServerStatus = smallText("");
        pServerStatus.setGravity(Gravity.CENTER);
        body.addView(pServerStatus);
        pServerBtn = actionButton("Start");
        tint(pServerBtn, R.color.float_purple);
        pServerBtn.setOnClickListener(v -> toggleServer());
        body.addView(pServerBtn);

        body.addView(sectionTitle("OTP", 13));

        pAccount = new EditText(this);
        pAccount.setHint("Email|Token|ID");
        pAccount.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pAccount.setLines(3);
        pAccount.setMaxLines(3);
        pAccount.setVerticalScrollBarEnabled(true);
        pAccount.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        pAccount.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        pAccount.setMovementMethod(new ScrollingMovementMethod());
        pAccount.setScroller(new Scroller(this));
        pAccount.setHorizontallyScrolling(false);
        pAccount.setGravity(Gravity.TOP);
        pAccount.setTextSize(11);
        pAccount.setTextColor(getColor(R.color.title_text));
        pAccount.setHintTextColor(getColor(R.color.input_hint));
        pAccount.setBackgroundColor(getColor(R.color.info_bg));
        pAccount.setPadding(dp(8), dp(8), dp(8), dp(8));
        pAccount.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        pAccount.setText(prefs.getString(KEY_SAVED, ""));
        body.addView(pAccount);

        pEmail = new TextView(this);
        pEmail.setGravity(Gravity.CENTER);
        pEmail.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pEmail.setTextColor(getColor(R.color.accent_blue));
        pEmail.setVisibility(View.GONE);
        body.addView(pEmail);

        pCopyEmail = actionButton("Copy Email");
        tint(pCopyEmail, R.color.accent_teal);
        pCopyEmail.setTextColor(getColor(R.color.on_tint));
        pCopyEmail.setVisibility(View.GONE);
        pCopyEmail.setOnClickListener(v -> copy(pEmail.getText().toString()));
        body.addView(pCopyEmail);

        pGetCode = actionButton("GET CODE");
        tint(pGetCode, R.color.go_green);
        pGetCode.setTextColor(getColor(R.color.on_tint));
        pGetCode.setOnClickListener(v -> popupGetCode());
        body.addView(pGetCode);

        pStatus = smallText("");
        pStatus.setGravity(Gravity.CENTER);
        body.addView(pStatus);

        pCode = new TextView(this);
        pCode.setGravity(Gravity.CENTER);
        pCode.setTextSize(20);
        pCode.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pCode.setTextColor(getColor(R.color.otp_green));
        pCode.setText(latestCode.isEmpty() ? "------" : latestCode);
        body.addView(pCode);

        pCopyCode = actionButton("Copy");
        tint(pCopyCode, R.color.go_green);
        pCopyCode.setTextColor(getColor(R.color.on_tint));
        pCopyCode.setOnClickListener(v -> copy(pCode.getText().toString()));
        body.addView(pCopyCode);

        pClear = actionButton("Clear");
        tint(pClear, R.color.danger);
        pClear.setOnClickListener(v -> {
            pAccount.setText("");
            prefs.edit().remove(KEY_SAVED).apply();
            refreshPopupEmail("");
            pStatus.setText("");
            pCode.setText("------");
        });
        body.addView(pClear);

        body.addView(sectionTitle("Names", 13));

        pCountry = OtpAutoActions.getCountry(this);
        pCountrySpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, CountryData.displayNames());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pCountrySpinner.setAdapter(adapter);
        if (pCountry >= 0 && pCountry < CountryData.COUNTRIES.length) {
            pCountrySpinner.setSelection(pCountry);
        }
        pCountrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pProgrammaticCountry) return;
                pCountry = pos;
                OtpAutoActions.setCountry(FloatingService.this, pos);
                refreshDomainUi();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        body.addView(pCountrySpinner);

        pDomain = smallText("");
        pDomain.setGravity(Gravity.CENTER);
        pDomain.setTextColor(getColor(R.color.accent_blue));
        body.addView(pDomain);
        refreshDomainUi();

        pGender = OtpAutoActions.getGender(this);
        LinearLayout genderRow = new LinearLayout(this);
        genderRow.setOrientation(LinearLayout.HORIZONTAL);
        pGenderMale = genderButton("Male", "male");
        pGenderFemale = genderButton("Female", "female");
        pGenderRandom = genderButton("Random", "both");
        genderRow.addView(pGenderMale);
        genderRow.addView(pGenderFemale);
        genderRow.addView(pGenderRandom);
        body.addView(genderRow);
        refreshGenderUi();

        pGenName = actionButton("Generate");
        tint(pGenName, R.color.accent_orange);
        pGenName.setTextColor(getColor(R.color.on_tint));
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        pFirstName = nameField("---", R.color.accent_blue);
        pLastName = nameField("---", R.color.accent_pink);
        pFirstName.setOnClickListener(v -> {
            copy(pFirstName.getText().toString());
            toast("Copied");
        });
        pLastName.setOnClickListener(v -> {
            copy(pLastName.getText().toString());
            toast("Copied");
        });
        nameRow.addView(pFirstName);
        nameRow.addView(pLastName);
        pSuggestedEmail = smallText("");
        pSuggestedEmail.setGravity(Gravity.CENTER);
        pSuggestedEmail.setTextColor(getColor(R.color.accent_teal));
        pSuggestedEmail.setClickable(true);
        pSuggestedEmail.setOnClickListener(v -> copy(pSuggestedEmail.getText().toString()));
        body.addView(pGenName);
        body.addView(nameRow);
        body.addView(pSuggestedEmail);
        String savedName = OtpAutoActions.getGenName(this);
        if (savedName != null && !savedName.isEmpty()) showPopupName(savedName);
        Button pCopyName = actionButton("Copy");
        tint(pCopyName, R.color.accent_orange);
        pCopyName.setTextColor(getColor(R.color.on_tint));
        pGenName.setOnClickListener(v -> {
            try {
                if (!requireServiceAccess()) return;
                String name = OtpHelper.generateName(pCountry, pGender);
                if (name == null || name.trim().isEmpty()) return;
                OtpAutoActions.setGenName(this, name);
                showPopupName(name);
            } catch (Exception ignored) {}
        });
        pCopyName.setOnClickListener(v -> copy(
                (pFirstName.getText().toString() + " " + pLastName.getText().toString()).trim()));
        body.addView(pCopyName);

        Button openApp = actionButton("Full App");
        tint(openApp, R.color.accent_teal);
        openApp.setTextColor(getColor(R.color.on_tint));
        openApp.setOnClickListener(v -> {
            hidePopup();
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        });
        body.addView(openApp);

        pAccount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String val = s.toString();
                refreshPopupEmail(val);
                if (pProgrammaticAccount) return; // synced echo from main app
                prefs.edit().putString(KEY_SAVED, val).apply();
                // Locked widget must not trigger background OTP fetches either.
                if (!serviceAccessAllowed) return;
                // Auto-fetch: valid account lines start fetching immediately, no Get OTP tap.
                AutoFetchManager.onAccountDataChanged(FloatingService.this, val);
            }
        });
        refreshPopupEmail(pAccount.getText().toString());
        // Popup opened with existing data behaves like the main app: fetch right away.
        if (!pAccount.getText().toString().trim().isEmpty() && serviceAccessAllowed) {
            AutoFetchManager.onAccountDataChanged(this, pAccount.getText().toString());
        }
        refreshPopupServerUi();

        scroll.addView(body);
        card.addView(scroll);
        root.addView(card, cardLp);

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
        pCopyCode = null; pClear = null; pFirstName = null; pLastName = null; pGenName = null;
        pGenderMale = null; pGenderFemale = null; pGenderRandom = null;
        pCountrySpinner = null; pDomain = null; pSuggestedEmail = null;
    }

    private void showPopupName(String fullName) {
        try {
            if (pFirstName == null || pLastName == null) return;
            if (fullName == null || fullName.trim().isEmpty()) return;
            pFirstName.setText(firstOf(fullName));
            pLastName.setText(lastOf(fullName));
            if (pSuggestedEmail != null) {
                String sug = OtpHelper.suggestEmail(fullName, pCountry);
                pSuggestedEmail.setText(sug == null ? "" : sug);
            }
        } catch (Exception ignored) {}
    }

    private void refreshDomainUi() {
        if (pDomain == null) return;
        try {
            pDomain.setText("@" + CountryData.byIndex(pCountry).domain);
        } catch (Exception ignored) {}
    }

    private static String firstOf(String fullName) {
        if (fullName == null) return "";
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 0 ? "" : parts[0];
    }

    private static String lastOf(String fullName) {
        if (fullName == null) return "";
        String[] parts = fullName.trim().split("\\s+");
        return parts.length < 2 ? "" : parts[parts.length - 1];
    }

    private TextView nameField(String text, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(15);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(getColor(colorRes));
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ---------- popup actions (same logic as MainActivity) ----------

    private void toggleServer() {
        net.execute(() -> {
            try {
                if (OtpServer.getInstance().isRunning()) OtpServer.getInstance().stop();
                else {
                    if (!serviceAccessAllowed) {
                        main.post(() -> toast("Locked — access not approved"));
                        return;
                    }
                    OtpServer.getInstance().start();
                }
            } catch (Exception e) {
                toast("Failed: " + e.getMessage());
            }
            main.post(this::refreshPopupServerUi);
        });
    }

    private void refreshPopupServerUi() {
        if (pServerStatus == null || pServerBtn == null) return;
        boolean running = OtpServer.getInstance().isRunning();
        pServerStatus.setText(running ? "● Running :3000" : "○ Stopped");
        pServerStatus.setTextColor(getColor(running ? R.color.success : R.color.error));
        pServerBtn.setText(running ? "Stop" : "Start");
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
        if (!requireServiceAccess()) {
            popupStatus("Locked — access not approved", true);
            return;
        }
        String data = pAccount.getText().toString().trim();
        if (data.isEmpty()) {
            popupStatus("Paste account data first", true);
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
                popupStatus("Start failed: " + e.getMessage(), true);
                return;
            }
            refreshPopupServerUi();
        }
        popupStatus("Loading...", false);
        if (pGetCode != null) pGetCode.setEnabled(false);
        net.execute(() -> {
            OtpServer.FetchResult r =
                    OtpServer.getInstance().fetchOtp(acc);
            main.post(() -> {
                if (pGetCode != null) pGetCode.setEnabled(true);
                if (r.success) {
                    updateCode(r.code);
                    popupStatus("✓ Copied", false);
                } else {
                    popupStatus(r.error != null ? r.error : "Not found", true);
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

    private void tint(Button b, int colorRes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                b.setBackgroundTintList(ColorStateList.valueOf(getColor(colorRes)));
            }
        } catch (Exception ignored) {}
    }

    private TextView sectionTitle(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(getColor(R.color.accent_blue));
        tv.setPadding(0, dp(6), 0, dp(2));
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
        b.setTextSize(13);
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
            if (pProgrammaticGender) return;
            pGender = value;
            OtpAutoActions.setGender(FloatingService.this, value);
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
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Rantu_OTP", text));
        toast("Copied");
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
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener); }
        catch (Exception ignored) {}
        AutoFetchManager.removeStatusListener(autoFetchStatus);
        try { OtpServer.getInstance().removeOtpListener(autoOtpListener); } catch (Exception ignored) {}
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
