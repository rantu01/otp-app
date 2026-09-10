package com.otpfetch.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android port of popup.html / popup.js + server controls.
 *
 * Tabs:
 *  1. OTP Fetcher  — paste "Email|RefreshToken|ClientId", GET CODE via the
 *                     embedded server (http://127.0.0.1:3000/get-otp),
 *                     copy e-mail / copy code, clear saved data.
 *  2. US Name Gen  — gender radio + generate + copy.
 *
 * Server card:
 *  - Start / Stop embedded OTP bridge (same logic as Node server.js).
 *  - Live log + watched-account count.
 *
 * Floating card:
 *  - Enable/disable chat-head bubble (FloatingService).
 *
 * Exit App:
 *  - Stops server + floating widget, finishes activities, kills process.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "otp_fetch_prefs";
    private static final String KEY_SAVED = "savedAccountData";
    private static final int REQ_OVERLAY = 9001;

    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    // server card
    private TextView serverStatus;
    private Button serverToggleBtn;
    private TextView serverLog;
    private ScrollView serverLogScroll;

    // tabs
    private Button tabOtpBtn, tabNameBtn;
    private LinearLayout tabOtp, tabName;

    // otp tab
    private EditText accountDataInput;
    private LinearLayout emailContainer;
    private TextView extractedEmail;
    private Button copyEmailBtn, getCodeBtn, copyCodeBtn, clearBtn;
    private TextView statusDiv;
    private LinearLayout resultContainer;
    private TextView otpCode;

    // name tab
    private RadioGroup genderGroup;
    private Button genNameBtn, copyNameBtn;
    private LinearLayout nameContainer;
    private TextView generatedName;
    private TextView nameStatus;

    // floating + exit
    private Button floatingBtn, exitBtn;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        bindViews();
        bindServerCard();
        bindTabs();
        bindOtpTab();
        bindNameTab();
        bindFloatingAndExit();

        // restore saved account data (parity with chrome.storage.local)
        String saved = prefs.getString(KEY_SAVED, "");
        if (!saved.isEmpty()) {
            accountDataInput.setText(saved);
            extractAndShowEmail(saved);
        }
        refreshServerUi();
    }

    @Override
    protected void onDestroy() {
        OtpServer.getInstance().setLogListener(null);
        super.onDestroy();
    }

    // ---------------- view binding ----------------

    private void bindViews() {
        serverStatus = findViewById(R.id.serverStatus);
        serverToggleBtn = findViewById(R.id.serverToggleBtn);
        serverLog = findViewById(R.id.serverLog);
        serverLogScroll = findViewById(R.id.serverLogScroll);

        tabOtpBtn = findViewById(R.id.tabOtpBtn);
        tabNameBtn = findViewById(R.id.tabNameBtn);
        tabOtp = findViewById(R.id.tabOtp);
        tabName = findViewById(R.id.tabName);

        accountDataInput = findViewById(R.id.accountData);
        emailContainer = findViewById(R.id.emailContainer);
        extractedEmail = findViewById(R.id.extractedEmail);
        copyEmailBtn = findViewById(R.id.copyEmailBtn);
        getCodeBtn = findViewById(R.id.getCodeBtn);
        statusDiv = findViewById(R.id.status);
        resultContainer = findViewById(R.id.resultContainer);
        otpCode = findViewById(R.id.otpCode);
        copyCodeBtn = findViewById(R.id.copyCodeBtn);
        clearBtn = findViewById(R.id.clearBtn);

        genderGroup = findViewById(R.id.genderGroup);
        genNameBtn = findViewById(R.id.genNameBtn);
        copyNameBtn = findViewById(R.id.copyNameBtn);
        nameContainer = findViewById(R.id.nameContainer);
        generatedName = findViewById(R.id.generatedName);
        nameStatus = findViewById(R.id.nameStatus);

        floatingBtn = findViewById(R.id.floatingBtn);
        exitBtn = findViewById(R.id.exitBtn);
    }

    // ---------------- server card ----------------

    private void bindServerCard() {
        OtpServer.getInstance().setLogListener(line ->
                main.post(() -> appendLog(line)));
        serverToggleBtn.setOnClickListener(v -> {
            if (OtpServer.getInstance().isRunning()) stopServer();
            else startServer();
        });
    }

    private void startServer() {
        net.execute(() -> {
            try {
                OtpServer.getInstance().start();
                main.post(() -> {
                    toast("Server started on port 3000");
                    appendLog("Server started — http://127.0.0.1:3000");
                    refreshServerUi();
                });
            } catch (Exception e) {
                main.post(() -> {
                    toast("Server failed: " + e.getMessage());
                    appendLog("Start failed: " + e.getMessage());
                    refreshServerUi();
                });
            }
        });
    }

    private void stopServer() {
        net.execute(() -> {
            OtpServer.getInstance().stop();
            main.post(() -> {
                toast("Server stopped");
                refreshServerUi();
            });
        });
    }

    private void refreshServerUi() {
        boolean running = OtpServer.getInstance().isRunning();
        serverStatus.setText(running
                ? "● Server RUNNING — http://127.0.0.1:3000  (watched: "
                + OtpServer.getInstance().watchedCount() + ")"
                : "○ Server STOPPED");
        serverStatus.setTextColor(running ? 0xFF1E8E3E : 0xFFB00020);
        serverToggleBtn.setText(running ? "Stop Server" : "Start Server");
        floatingBtn.setText(FloatingService.isRunning() ? "Disable Floating Widget" : "Enable Floating Widget");
    }

    private void appendLog(String line) {
        serverLog.append("[" + System.currentTimeMillis() + "] " + line + "\n");
        serverLogScroll.post(() -> serverLogScroll.fullScroll(View.FOCUS_DOWN));
        refreshServerUi();
    }

    // ---------------- tabs ----------------

    private void bindTabs() {
        tabOtpBtn.setOnClickListener(v -> switchTab(true));
        tabNameBtn.setOnClickListener(v -> switchTab(false));
    }

    private void switchTab(boolean otp) {
        tabOtp.setVisibility(otp ? View.VISIBLE : View.GONE);
        tabName.setVisibility(otp ? View.GONE : View.VISIBLE);
        tabOtpBtn.setEnabled(!otp);
        tabNameBtn.setEnabled(otp);
        tabOtpBtn.setAlpha(otp ? 1f : 0.5f);
        tabNameBtn.setAlpha(otp ? 0.5f : 1f);
    }

    // ---------------- OTP tab (port of popup.js) ----------------

    private void bindOtpTab() {
        accountDataInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String val = s.toString();
                prefs.edit().putString(KEY_SAVED, val).apply();
                extractAndShowEmail(val);
            }
        });

        copyEmailBtn.setOnClickListener(v -> {
            copyToClipboard(extractedEmail.getText().toString());
            flash(copyEmailBtn, "Copied!");
        });

        getCodeBtn.setOnClickListener(v -> onGetCode());

        copyCodeBtn.setOnClickListener(v -> {
            copyToClipboard(otpCode.getText().toString());
            flash(copyCodeBtn, "Copied!");
        });

        clearBtn.setOnClickListener(v -> {
            accountDataInput.setText("");
            prefs.edit().remove(KEY_SAVED).apply();
            emailContainer.setVisibility(View.GONE);
            resultContainer.setVisibility(View.GONE);
            statusDiv.setText("");
        });
    }

    private void extractAndShowEmail(String data) {
        String email = OtpHelper.extractEmail(data);
        if (!email.isEmpty()) {
            extractedEmail.setText(email);
            emailContainer.setVisibility(View.VISIBLE);
        } else {
            emailContainer.setVisibility(View.GONE);
        }
    }

    private void onGetCode() {
        String data = accountDataInput.getText().toString().trim();
        if (data.isEmpty()) {
            showStatus("Please paste account data first!", true);
            return;
        }
        final OtpHelper.Account acc;
        try {
            acc = OtpHelper.parseAccountData(data);
        } catch (IllegalArgumentException e) {
            showStatus(e.getMessage(), true);
            return;
        }

        // Auto-start embedded server if needed (mirrors "start the server" option).
        if (!OtpServer.getInstance().isRunning()) {
            try { OtpServer.getInstance().start(); }
            catch (Exception e) {
                showStatus("Server failed to start: " + e.getMessage(), true);
                return;
            }
            refreshServerUi();
        }

        showStatus("Connecting to Microsoft Graph API...", false);
        resultContainer.setVisibility(View.GONE);
        getCodeBtn.setEnabled(false);

        // Go through HTTP (http://127.0.0.1:3000/get-otp) exactly like the extension
        // talks to http://localhost:3000/get-otp — proves the embedded server works
        // for any local client, not just this Activity.
        net.execute(() -> {
            try {
                URL url = new URL("http://127.0.0.1:3000/get-otp");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject req = new JSONObject();
                req.put("email", acc.email);
                req.put("refreshToken", acc.refreshToken);
                req.put("clientId", acc.clientId);
                byte[] bytes = req.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();

                InputStream in = conn.getResponseCode() < 400
                        ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);
                br.close();
                JSONObject resp = new JSONObject(sb.toString());
                boolean ok = resp.optBoolean("success", false);
                String code = resp.optString("code", "");
                String err = resp.optString("error", "OTP Not Found");
                main.post(() -> {
                    getCodeBtn.setEnabled(true);
                    if (ok) {
                        otpCode.setText(code);
                        resultContainer.setVisibility(View.VISIBLE);
                        showStatus("OTP Fetched Successfully!", false);
                        FloatingService.updateCode(code);
                    } else {
                        showStatus(err, true);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    getCodeBtn.setEnabled(true);
                    showStatus("Failed to fetch OTP: " + e.getMessage(), true);
                });
            }
        });
    }

    private void showStatus(String msg, boolean isError) {
        statusDiv.setText(msg);
        statusDiv.setTextColor(isError ? 0xFFB00020 : 0xFF1E8E3E);
    }

    // ---------------- Name tab (port of popup.js) ----------------

    private void bindNameTab() {
        genNameBtn.setOnClickListener(v -> {
            int checked = genderGroup.getCheckedRadioButtonId();
            String gender = "male";
            if (checked == R.id.genderFemale) gender = "female";
            else if (checked == R.id.genderBoth) gender = "both";
            String name = OtpHelper.generateName(gender);
            generatedName.setText(name);
            nameContainer.setVisibility(View.VISIBLE);
            nameStatus.setText("Generated!");
            nameStatus.setTextColor(0xFF1E8E3E);
        });
        copyNameBtn.setOnClickListener(v -> {
            copyToClipboard(generatedName.getText().toString());
            flash(copyNameBtn, "Copied!");
        });
    }

    // ---------------- floating + exit ----------------

    private void bindFloatingAndExit() {
        floatingBtn.setOnClickListener(v -> {
            if (FloatingService.isRunning()) {
                stopService(new Intent(this, FloatingService.class));
                refreshServerUi();
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(this)) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_OVERLAY);
                    toast("Grant overlay permission, then tap Enable again");
                    return;
                }
                startFloatingService();
            }
        });

        exitBtn.setOnClickListener(v -> exitApp());
    }

    private void startFloatingService() {
        Intent i = new Intent(this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        toast("Floating widget enabled");
        main.postDelayed(this::refreshServerUi, 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) startFloatingService();
            else toast("Overlay permission denied — floating disabled");
        }
    }

    /**
     * Exit App: completely closes the app AND removes the floating widget.
     */
    private void exitApp() {
        try { OtpServer.getInstance().stop(); } catch (Exception ignored) {}
        try { stopService(new Intent(this, FloatingService.class)); } catch (Exception ignored) {}
        net.shutdownNow();
        finishAffinity();
        System.exit(0);
    }

    // ---------------- helpers ----------------

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("otp", text));
        toast("Copied to clipboard");
    }

    private void flash(Button b, String temp) {
        String orig = b.getText().toString();
        b.setText(temp);
        main.postDelayed(() -> b.setText(orig), 1500);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
