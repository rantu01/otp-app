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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
import java.util.List;

/**
 * Android port of popup.html / popup.js + server controls.
 *
 * Tabs:
 *  1. OTP — paste "Email|RefreshToken|ClientId", GET CODE,
 *           copy e-mail / copy code, clear.
 *  2. Names — country + gender + generate + copy (with e-mail suggestion).
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
    private static final int REQ_NOTIF = 9002;

    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final OtpServer.OtpListener autoOtpListener = (email, code) ->
            main.post(() -> onAutoOtp(email, code));

    // server card
    private TextView serverStatus;
    private Button serverToggleBtn;

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
    private Spinner countrySpinner;
    private TextView domainView;
    private Button genNameBtn, copyNameBtn;
    private LinearLayout nameContainer;
    private TextView firstNameView, lastNameView, suggestedEmailView;
    private TextView nameStatus;
    private int currentCountry = 0;
    private boolean mProgrammaticCountry = false;

    // floating + exit
    private Button floatingBtn, exitBtn;

    // access / subscription gate (shared backend; OTP UI below is unchanged)
    private TextView accessStatus;
    private volatile boolean accessAllowed = true;
    private volatile String accessReason = "UNKNOWN";
    private volatile boolean gateDialogShowing = false;

    // auto OTP actions
    private CheckBox autoCopyCheck;
    private TextView selectedAppLabel;
    private Button selectAppBtn, clearAppBtn;

    private SharedPreferences prefs;
    private boolean mProgrammaticAccount = false;
    private boolean mProgrammaticGender = false;

    private final AutoFetchManager.StatusListener autoFetchStatus =
            new AutoFetchManager.StatusListener() {
                @Override public void onAutoFetchStarted(String email) {
                    main.post(() -> showStatus("Loading " + email + "...", false));
                }
                @Override public void onAutoFetchError(String email, String error) {
                    main.post(() -> showStatus(error, true));
                }
            };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (p, key) -> main.post(() -> onSharedStateChanged(key));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        OtpServer.getInstance().init(getApplicationContext());
        OtpServer.getInstance().addOtpListener(autoOtpListener);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        AutoFetchManager.addStatusListener(autoFetchStatus);
        requestNotifPermission();

        bindViews();
        bindServerCard();
        bindTabs();
        bindOtpTab();
        bindNameTab();
        bindAutoActions();
        bindFloatingAndExit();
        bindAccessBar();
        enforceAccessGate();

        // restore saved account data (parity with chrome.storage.local)
        String saved = prefs.getString(KEY_SAVED, "");
        if (!saved.isEmpty()) {
            accountDataInput.setText(saved);
            extractAndShowEmail(saved);
        }
        restoreSharedOtpState();
        restoreSharedNameState();
        restoreSharedGenderState();
        restoreSharedCountryState();
        refreshServerUi();
    }

    /** Single-app sync: reflect state written by the floating popup. */
    private void onSharedStateChanged(String key) {
        if (key == null) return;
        try {
            if (OtpAutoActions.KEY_SAVED.equals(key)) {
                String saved = prefs.getString(KEY_SAVED, "");
                if (!saved.equals(accountDataInput.getText().toString())) {
                    mProgrammaticAccount = true;
                    accountDataInput.setText(saved);
                    mProgrammaticAccount = false;
                    extractAndShowEmail(saved);
                }
            } else if (OtpAutoActions.KEY_LAST_CODE.equals(key)
                    || OtpAutoActions.KEY_LAST_EMAIL.equals(key)) {
                restoreSharedOtpState();
            } else if (OtpAutoActions.KEY_GEN_NAME.equals(key)) {
                restoreSharedNameState();
            } else if (OtpAutoActions.KEY_GENDER.equals(key)) {
                restoreSharedGenderState();
            } else if (OtpAutoActions.KEY_COUNTRY.equals(key)) {
                restoreSharedCountryState();
            }
        } catch (Exception ignored) {}
    }

    private void restoreSharedOtpState() {
        try {
            String code = OtpAutoActions.getLastCode(this);
            String email = OtpAutoActions.getLastEmail(this);
            if (code == null || code.isEmpty()) return;
            String current = "";
            try { current = OtpHelper.extractEmail(accountDataInput.getText().toString()); }
            catch (Exception ignored) {}
            if (!current.isEmpty() && !current.equalsIgnoreCase(email)) return;
            otpCode.setText(code);
            resultContainer.setVisibility(View.VISIBLE);
            FloatingService.updateCode(code);
        } catch (Exception ignored) {}
    }

    private void restoreSharedNameState() {
        try {
            String name = OtpAutoActions.getGenName(this);
            if (name == null || name.isEmpty()) return;
            showGeneratedName(name);
            nameStatus.setText("✓");
            nameStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
        } catch (Exception ignored) {}
    }

    private void restoreSharedCountryState() {
        try {
            int c = OtpAutoActions.getCountry(this);
            if (c < 0 || c >= CountryData.COUNTRIES.length) return;
            if (c == currentCountry) return;
            currentCountry = c;
            refreshDomainView();
            if (countrySpinner != null) {
                mProgrammaticCountry = true;
                countrySpinner.setSelection(c);
                mProgrammaticCountry = false;
            }
        } catch (Exception ignored) {}
    }

    private void restoreSharedGenderState() {
        try {
            String g = OtpAutoActions.getGender(this);
            mProgrammaticGender = true;
            if ("female".equalsIgnoreCase(g)) genderGroup.check(R.id.genderFemale);
            else if ("both".equalsIgnoreCase(g)) genderGroup.check(R.id.genderBoth);
            else genderGroup.check(R.id.genderMale);
            mProgrammaticGender = false;
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener); }
        catch (Exception ignored) {}
        AutoFetchManager.removeStatusListener(autoFetchStatus);
        OtpServer.getInstance().setLogListener(null);
        OtpServer.getInstance().removeOtpListener(autoOtpListener);
        super.onDestroy();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    /** Facebook-style: re-sent OTPs pop into the UI automatically, no Get Code tap needed. */
    private void onAutoOtp(String email, String code) {
        try {
            String current = "";
            try { current = OtpHelper.extractEmail(accountDataInput.getText().toString()); }
            catch (Exception ignored) {}
            if (!current.isEmpty() && !current.equalsIgnoreCase(email)) return;
            otpCode.setText(code);
            resultContainer.setVisibility(View.VISIBLE);
            // Copy + auto-open already ran centrally in OtpServer; just report it here.
            showStatus(autoOtpMessage(email), false);
            FloatingService.updateCode(code);
        } catch (Exception ignored) {}
    }

    private String autoOtpMessage(String email) {
        StringBuilder sb = new StringBuilder("✓ " + email);
        if (OtpAutoActions.isAutoCopyEnabled(this)) sb.append(" · copied");
        String label = OtpAutoActions.getSelectedLabel(this);
        String pkg = OtpAutoActions.getSelectedPackage(this);
        if (pkg != null && !pkg.isEmpty()) {
            sb.append(" · ").append(label.isEmpty() ? pkg : label);
        }
        return sb.toString();
    }

    // ---------------- view binding ----------------

    private void bindViews() {
        serverStatus = findViewById(R.id.serverStatus);
        serverToggleBtn = findViewById(R.id.serverToggleBtn);

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
        countrySpinner = findViewById(R.id.countrySpinner);
        domainView = findViewById(R.id.domainView);
        genNameBtn = findViewById(R.id.genNameBtn);
        copyNameBtn = findViewById(R.id.copyNameBtn);
        nameContainer = findViewById(R.id.nameContainer);
        firstNameView = findViewById(R.id.firstNameView);
        lastNameView = findViewById(R.id.lastNameView);
        suggestedEmailView = findViewById(R.id.suggestedEmailView);
        nameStatus = findViewById(R.id.nameStatus);

        floatingBtn = findViewById(R.id.floatingBtn);
        exitBtn = findViewById(R.id.exitBtn);
        accessStatus = findViewById(R.id.accessStatus);

        autoCopyCheck = findViewById(R.id.autoCopyCheck);
        selectedAppLabel = findViewById(R.id.selectedAppLabel);
        selectAppBtn = findViewById(R.id.selectAppBtn);
        clearAppBtn = findViewById(R.id.clearAppBtn);
    }

    // ---------------- server card ----------------

    private void bindServerCard() {
        OtpServer.getInstance().setLogListener(line ->
                main.post(this::refreshServerUi));
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
                    toast("Started");
                    refreshServerUi();
                });
            } catch (Exception e) {
                main.post(() -> {
                    toast("Failed: " + e.getMessage());
                    refreshServerUi();
                });
            }
        });
    }

    private void stopServer() {
        net.execute(() -> {
            OtpServer.getInstance().stop();
            main.post(() -> {
                toast("Stopped");
                refreshServerUi();
            });
        });
    }

    private void refreshServerUi() {
        boolean running = OtpServer.getInstance().isRunning();
        serverStatus.setText(running
                ? "● Running :3000 (" + OtpServer.getInstance().watchedCount() + ")"
                : "○ Stopped");
        serverStatus.setTextColor(ContextCompat.getColor(this, running ? R.color.success : R.color.error));
        serverToggleBtn.setText(running ? "Stop" : "Start");
        floatingBtn.setText(FloatingService.isRunning() ? "Floating: ON" : "Floating: OFF");
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
                extractAndShowEmail(val);
                if (mProgrammaticAccount) return; // synced echo from popup — don't re-save/re-fetch
                prefs.edit().putString(KEY_SAVED, val).apply();
                // Auto-fetch: valid account lines start fetching immediately, no Get OTP tap.
                AutoFetchManager.onAccountDataChanged(MainActivity.this, val);
            }
        });

        copyEmailBtn.setOnClickListener(v -> {
            copyToClipboard(extractedEmail.getText().toString());
            flash(copyEmailBtn, "✓");
        });

        getCodeBtn.setOnClickListener(v -> onGetCode());

        copyCodeBtn.setOnClickListener(v -> {
            copyToClipboard(otpCode.getText().toString());
            flash(copyCodeBtn, "✓");
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
        // Backend-validated gate: blocked/expired users cannot use protected features.
        if (SessionManager.isLoggedIn(this) && !accessAllowed) {
            showStatus("Access blocked (" + accessReason + "). Open Packages.", true);
            toast("Access blocked — check Packages");
            return;
        }
        String data = accountDataInput.getText().toString().trim();
        if (data.isEmpty()) {
            showStatus("Paste account data first", true);
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
                showStatus("Start failed: " + e.getMessage(), true);
                return;
            }
            refreshServerUi();
        }

        showStatus("Loading...", false);
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
                req.put("password", acc.password);
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
                        showStatus("✓ Copied", false);
                        FloatingService.updateCode(code);
                    } else {
                        showStatus(err, true);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    getCodeBtn.setEnabled(true);
                    showStatus("Failed: " + e.getMessage(), true);
                });
            }
        });
    }

    private void showStatus(String msg, boolean isError) {
        statusDiv.setText(msg);
        statusDiv.setTextColor(ContextCompat.getColor(this, isError ? R.color.error : R.color.success));
    }

    // ---------------- Name tab (port of popup.js) ----------------

    private void bindNameTab() {
        currentCountry = OtpAutoActions.getCountry(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, CountryData.displayNames());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countrySpinner.setAdapter(adapter);
        if (currentCountry >= 0 && currentCountry < CountryData.COUNTRIES.length) {
            countrySpinner.setSelection(currentCountry);
        }
        refreshDomainView();
        countrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (mProgrammaticCountry) return;
                currentCountry = pos;
                OtpAutoActions.setCountry(MainActivity.this, pos);
                refreshDomainView();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        genderGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (mProgrammaticGender) return;
            String gender = "male";
            if (checkedId == R.id.genderFemale) gender = "female";
            else if (checkedId == R.id.genderBoth) gender = "both";
            OtpAutoActions.setGender(this, gender);
        });
        genNameBtn.setOnClickListener(v -> {
            int checked = genderGroup.getCheckedRadioButtonId();
            String gender = "male";
            if (checked == R.id.genderFemale) gender = "female";
            else if (checked == R.id.genderBoth) gender = "both";
            String name = OtpHelper.generateName(currentCountry, gender);
            OtpAutoActions.setGenName(this, name);
            showGeneratedName(name);
            nameStatus.setText("✓");
            nameStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
        });
        firstNameView.setOnClickListener(v -> {
            copyToClipboard(firstNameView.getText().toString());
            toast("Copied");
        });
        lastNameView.setOnClickListener(v -> {
            copyToClipboard(lastNameView.getText().toString());
            toast("Copied");
        });
        suggestedEmailView.setOnClickListener(v -> {
            copyToClipboard(suggestedEmailView.getText().toString());
            toast("Copied");
        });
        copyNameBtn.setOnClickListener(v -> {
            copyToClipboard((firstNameView.getText().toString()
                    + " " + lastNameView.getText().toString()).trim());
            flash(copyNameBtn, "✓");
        });
    }

    private void refreshDomainView() {
        if (domainView == null) return;
        try {
            domainView.setText("@" + CountryData.byIndex(currentCountry).domain);
        } catch (Exception ignored) {}
    }

    private void showGeneratedName(String fullName) {
        if (nameContainer == null || firstNameView == null || lastNameView == null) return;
        firstNameView.setText(firstOf(fullName));
        lastNameView.setText(lastOf(fullName));
        if (suggestedEmailView != null) {
            String sug = OtpHelper.suggestEmail(fullName, currentCountry);
            suggestedEmailView.setText(sug == null ? "" : sug);
        }
        nameContainer.setVisibility(View.VISIBLE);
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

    // ---------------- auto OTP actions (auto-copy + auto-open app) ----------------

    private void bindAutoActions() {
        autoCopyCheck.setChecked(OtpAutoActions.isAutoCopyEnabled(this));
        autoCopyCheck.setOnCheckedChangeListener((v, checked) ->
                OtpAutoActions.setAutoCopyEnabled(this, checked));
        selectAppBtn.setOnClickListener(v -> showAppPickerDialog());
        clearAppBtn.setOnClickListener(v -> {
            OtpAutoActions.clearSelectedApp(this);
            refreshSelectedAppLabel();
            toast("Cleared");
        });
        refreshSelectedAppLabel();
    }

    private void refreshSelectedAppLabel() {
        if (selectedAppLabel == null) return;
        String pkg = OtpAutoActions.getSelectedPackage(this);
        String label = OtpAutoActions.getSelectedLabel(this);
        if (pkg == null || pkg.isEmpty()) {
            selectedAppLabel.setText("No auto-open app");
        } else {
            selectedAppLabel.setText("▶ " + (label.isEmpty() ? pkg : label));
        }
    }

    private void showAppPickerDialog() {
        toast("Loading...");
        selectAppBtn.setEnabled(false);
        net.execute(() -> {
            final List<OtpAutoActions.AppEntry> apps = OtpAutoActions.getInstalledApps(this);
                main.post(() -> {
                    selectAppBtn.setEnabled(true);
                    if (apps.isEmpty()) {
                        toast("None found");
                        return;
                    }
                String[] items = new String[apps.size()];
                for (int i = 0; i < apps.size(); i++) {
                    items[i] = apps.get(i).label + "\n" + apps.get(i).packageName;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Auto-open app")
                        .setItems(items, (d, which) -> {
                            OtpAutoActions.AppEntry picked = apps.get(which);
                            OtpAutoActions.saveSelectedApp(
                                    this, picked.packageName, picked.label);
                            refreshSelectedAppLabel();
                            toast("✓ " + picked.label);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (autoCopyCheck != null) {
                autoCopyCheck.setChecked(OtpAutoActions.isAutoCopyEnabled(this));
            }
            refreshSelectedAppLabel();
            refreshServerUi();
            enforceAccessGate();
        } catch (Exception ignored) {}
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
                    toast("Grant overlay, then retry");
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
        toast("Floating ON");
        main.postDelayed(this::refreshServerUi, 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) startFloatingService();
            else toast("Overlay denied");
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

    // ---------------- access gate (auth + version + subscription) ----------------

    private void bindAccessBar() {
        Button accountBtn = findViewById(R.id.accountBtn);
        Button packagesBtn = findViewById(R.id.packagesBtn);
        if (accountBtn != null) {
            accountBtn.setOnClickListener(v -> {
                if (SessionManager.isLoggedIn(this)) {
                    org.json.JSONObject u = SessionManager.getUser(this);
                    String info = u == null ? "Logged in" : u.optString("name", "") + " (" + u.optString("email", "") + u.optString("phone", "") + ")";
                    new AlertDialog.Builder(this)
                            .setTitle("Account")
                            .setMessage(info + "\nServer: " + SessionManager.getBaseUrl(this)
                                    + "\nAccess: " + accessReason)
                            .setPositiveButton("Refresh", (d, w) -> enforceAccessGate())
                            .setNegativeButton("Logout", (d, w) -> {
                                SessionManager.logout(this);
                                accessAllowed = true;
                                accessReason = "LOGGED_OUT";
                                setAccessText("Not logged in");
                                startActivity(new Intent(this, AuthActivity.class));
                            })
                            .show();
                } else {
                    startActivity(new Intent(this, AuthActivity.class));
                }
            });
        }
        if (packagesBtn != null) {
            packagesBtn.setOnClickListener(v -> {
                if (!SessionManager.isLoggedIn(this)) {
                    startActivity(new Intent(this, AuthActivity.class));
                    return;
                }
                startActivity(new Intent(this, PackageActivity.class));
            });
        }
    }

    private void setAccessText(final String s) {
        main.post(() -> {
            if (accessStatus != null) accessStatus.setText(s);
        });
    }

    /**
     * Startup flow: version check -> auth -> account/access/package check.
     * Fail-open when the backend is unreachable (offline): OTP keeps working
     * and the bar shows "Offline". Fail-closed when backend explicitly denies.
     */
    private void enforceAccessGate() {
        net.execute(() -> {
            try {
                String installed = ApiClient.appVersion(MainActivity.this);
                ApiClient.Resp ver = ApiClient.get(this,
                        "/api/versions/check?platform=android&version=" + installed, false);
                if (ver.ok() && ver.json.optBoolean("forceUpdate", false)) {
                    accessAllowed = false;
                    accessReason = "FORCE_UPDATE";
                    setAccessText("Update required");
                    main.post(() -> showForceUpdate(
                            ver.json.optString("updateUrl", ""),
                            ver.json.optString("message", "Please update to continue.")));
                    return;
                }
            } catch (Exception e) {
                setAccessText("Offline — access not verified");
                return; // no internet / backend down: don't brick the app
            }
            if (!SessionManager.isLoggedIn(this)) {
                accessAllowed = true; // allow local OTP until login; gate re-checks after login
                accessReason = "NOT_LOGGED_IN";
                setAccessText("Not logged in — tap Account");
                main.post(() -> {
                    if (!gateDialogShowing) {
                        gateDialogShowing = true;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Login required")
                                .setMessage("Please login to verify your package access.")
                                .setPositiveButton("Login", (d, w) -> {
                                    gateDialogShowing = false;
                                    startActivity(new Intent(MainActivity.this, AuthActivity.class));
                                })
                                .setNegativeButton("Later", (d, w) -> gateDialogShowing = false)
                                .show();
                    }
                });
                return;
            }
            try {
                ApiClient.Resp r = ApiClient.get(this, "/api/access/status", true);
                if (r.code == 401) {
                    SessionManager.logout(this);
                    accessAllowed = true;
                    accessReason = "SESSION_EXPIRED";
                    setAccessText("Session expired — tap Account");
                    return;
                }
                if (!r.ok()) {
                    setAccessText("Offline — access not verified");
                    return;
                }
                org.json.JSONObject access = r.json.optJSONObject("access");
                String reason = access == null ? "UNKNOWN" : access.optString("reason", "UNKNOWN");
                boolean allowed = access != null && access.optBoolean("allowed", false);
                accessAllowed = allowed;
                accessReason = reason;
                if (allowed) {
                    String exp = access.optString("packageExpireDate", "");
                    setAccessText("✓ Active: " + access.optString("packageName", "package") + (exp.isEmpty() ? "" : " till " + exp.substring(0, 10)));
                } else if ("NO_PACKAGE".equals(reason)) {
                    setAccessText("No active package — tap Packages");
                    main.post(this::showNoPackage);
                } else {
                    setAccessText("Access denied (" + reason + ")");
                    main.post(() -> showAccessDenied(access == null ? "Access denied." : access.optString("message", "Access denied.")));
                }
            } catch (Exception e) {
                setAccessText("Offline — access not verified");
            }
        });
    }

    private void showForceUpdate(String url, String message) {
        if (gateDialogShowing) return;
        gateDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("Update required")
                .setMessage(message.isEmpty() ? "Your app version is no longer supported." : message)
                .setCancelable(false)
                .setPositiveButton("Update Now", (d, w) -> {
                    try {
                        if (!url.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                        else Toast.makeText(this, "Ask admin for the new APK", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid update URL", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Recheck", (d, w) -> {
                    gateDialogShowing = false;
                    enforceAccessGate();
                })
                .show();
    }

    private void showAccessDenied(String message) {
        if (gateDialogShowing) return;
        gateDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("Access Denied")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Recheck", (d, w) -> {
                    gateDialogShowing = false;
                    enforceAccessGate();
                })
                .setNegativeButton("Logout", (d, w) -> {
                    gateDialogShowing = false;
                    SessionManager.logout(this);
                    startActivity(new Intent(this, AuthActivity.class));
                })
                .show();
    }

    private void showNoPackage() {
        if (gateDialogShowing) return;
        gateDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("No Active Package")
                .setMessage("Choose a package and complete payment to use the app.")
                .setPositiveButton("View Packages", (d, w) -> {
                    gateDialogShowing = false;
                    startActivity(new Intent(this, PackageActivity.class));
                })
                .setNegativeButton("Later", (d, w) -> gateDialogShowing = false)
                .show();
    }

    // ---------------- helpers ----------------

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("otp", text));
        toast("Copied");
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
