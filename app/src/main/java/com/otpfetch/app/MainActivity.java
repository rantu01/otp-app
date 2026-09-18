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
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

import android.view.Menu;
import android.view.MenuItem;

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
    private Button accountBtn, packagesBtn;

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

    // bottom navigation + drawer (menu built programmatically, no new XML)
    private DrawerLayout drawerLayout;
    private BottomNavigationView bottomNav;
    private NavigationView navDrawer;
    private MenuItem drawerServerItem, drawerFloatingItem;
    private static final int NAV_OTP = 1;
    private static final int NAV_NAMES = 2;
    private static final int NAV_PACKAGES = 3;
    private static final int NAV_MENU = 4;
    private static final int DRAWER_TITLE = 101;
    private static final int DRAWER_ACCOUNT = 102;
    private static final int DRAWER_SERVER = 103;
    private static final int DRAWER_FLOATING = 104;
    private static final int DRAWER_UPDATES = 105;
    private static final int DRAWER_PACKAGES = 106;
    private static final int DRAWER_EXIT = 107;

    // access / subscription gate: FAIL-CLOSED — nothing on this screen is usable
    // until the backend explicitly confirms access (see enforceAccessGate).
    // App updates are ADVISORY only (see checkForUpdatesManual) — they never
    // block access; the user is merely notified when a new version exists.
    private TextView accessStatus;
    private volatile boolean accessAllowed = false;
    private volatile String accessReason = "VERIFYING";
    private volatile boolean gateDialogShowing = false;
    private volatile boolean updateAvailable = false;
    private volatile String updateUrl = "";
    private volatile String updateMessage = "";
    private volatile String latestVersion = "";
    private static boolean updateNoticeShown = false;
    // Data-saver: version checks are advisory — cache for 15 min so resume()
    // does not re-download the version payload on every foreground.
    private static volatile long lastVersionCheckAt = 0;
    private static final long VERSION_CHECK_TTL_MS = 15 * 60 * 1000;

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
        // Hard entry gate: no session -> Auth immediately, before anything else.
        // (ActivationActivity routes here only after server approval, but the back
        // stack, recents, notifications and explicit intents must never be able
        // to land on Home ungated.)
        if (!SessionManager.isLoggedIn(this)) {
            Intent noSession = new Intent(this, ActivationActivity.class);
            noSession.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(noSession);
            finish();
            return;
        }
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
        bindNavigation();
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

        drawerLayout = findViewById(R.id.drawerLayout);
        bottomNav = findViewById(R.id.bottomNav);
        navDrawer = findViewById(R.id.navDrawer);

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
            else if (!accessAllowed) {
                toast("Locked — access not approved");
                enforceAccessGate();
            }
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
        refreshDrawerTitles();
    }

    // ---------------- tabs ----------------

    private void bindTabs() {
        tabOtpBtn.setOnClickListener(v -> switchTab(true));
        tabNameBtn.setOnClickListener(v -> switchTab(false));
    }

    private void switchTab(boolean otp) {
        try {
            if (tabOtp == null || tabName == null || tabOtpBtn == null || tabNameBtn == null) return;
            tabOtp.setVisibility(otp ? View.VISIBLE : View.GONE);
            tabName.setVisibility(otp ? View.GONE : View.VISIBLE);
            tabOtpBtn.setEnabled(!otp);
            tabNameBtn.setEnabled(otp);
            tabOtpBtn.setAlpha(otp ? 1f : 0.5f);
            tabNameBtn.setAlpha(otp ? 0.5f : 1f);
            if (bottomNav != null) {
                int want = otp ? NAV_OTP : NAV_NAMES;
                if (bottomNav.getSelectedItemId() != want) bottomNav.setSelectedItemId(want);
            }
        } catch (Exception e) {
            try { Toast.makeText(this, "Tab error: " + e.getMessage(), Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
        }
    }

    // ---------------- bottom navigation + drawer ----------------

    /**
     * Bottom bar (OTP / Names / Packages / Menu) + sidebar drawer (Account,
     * Server, Floating widget, Updates, Packages, Exit). Drawer actions reuse
     * the existing card buttons via performClick(), so no behavior changes.
     */
    private void bindNavigation() {
        if (bottomNav != null) {
            Menu m = bottomNav.getMenu();
            m.add(Menu.NONE, NAV_OTP, 0, "OTP").setIcon(android.R.drawable.ic_menu_view);
            m.add(Menu.NONE, NAV_NAMES, 1, "Names").setIcon(android.R.drawable.ic_menu_edit);
            m.add(Menu.NONE, NAV_PACKAGES, 2, "Packages").setIcon(android.R.drawable.ic_menu_send);
            m.add(Menu.NONE, NAV_MENU, 3, "Menu").setIcon(android.R.drawable.ic_menu_more);
            bottomNav.setLabelVisibilityMode(BottomNavigationView.LABEL_VISIBILITY_LABELED);
            bottomNav.setSelectedItemId(NAV_OTP);
            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == NAV_OTP) switchTab(true);
                else if (id == NAV_NAMES) switchTab(false);
                else if (id == NAV_PACKAGES && packagesBtn != null) packagesBtn.performClick();
                else if (id == NAV_MENU && drawerLayout != null) {
                    refreshDrawerTitles();
                    drawerLayout.openDrawer(GravityCompat.START);
                }
                return true;
            });
        }
        if (navDrawer != null) {
            Menu dm = navDrawer.getMenu();
            dm.add(Menu.NONE, DRAWER_TITLE, 0,
                    "Rantu_OTP v" + ApiClient.appVersion(this)).setEnabled(false);
            dm.add(Menu.NONE, DRAWER_ACCOUNT, 1, "Account")
                    .setIcon(android.R.drawable.ic_menu_manage);
            dm.add(Menu.NONE, DRAWER_SERVER, 2, "Start Server")
                    .setIcon(android.R.drawable.ic_menu_compass);
            dm.add(Menu.NONE, DRAWER_FLOATING, 3, "Floating Widget")
                    .setIcon(android.R.drawable.ic_menu_view);
            dm.add(Menu.NONE, DRAWER_UPDATES, 4, "Check for Updates")
                    .setIcon(android.R.drawable.ic_menu_info_details);
            dm.add(Menu.NONE, DRAWER_PACKAGES, 5, "Packages")
                    .setIcon(android.R.drawable.ic_menu_send);
            dm.add(Menu.NONE, DRAWER_EXIT, 6, "Exit App")
                    .setIcon(android.R.drawable.ic_menu_close_clear_cancel);
            drawerServerItem = dm.findItem(DRAWER_SERVER);
            drawerFloatingItem = dm.findItem(DRAWER_FLOATING);
            navDrawer.setNavigationItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == DRAWER_ACCOUNT && accountBtn != null) accountBtn.performClick();
                else if (id == DRAWER_SERVER && serverToggleBtn != null) serverToggleBtn.performClick();
                else if (id == DRAWER_FLOATING && floatingBtn != null) floatingBtn.performClick();
                else if (id == DRAWER_UPDATES) checkForUpdatesManual();
                else if (id == DRAWER_PACKAGES && packagesBtn != null) packagesBtn.performClick();
                else if (id == DRAWER_EXIT) exitApp();
                if (drawerLayout != null) drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            });
        }
        if (drawerLayout != null) {
            drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
                @Override public void onDrawerOpened(View drawerView) {
                    refreshDrawerTitles();
                }
            });
            getOnBackPressedDispatcher().addCallback(this,
                    new androidx.activity.OnBackPressedCallback(true) {
                        @Override public void handleOnBackPressed() {
                            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                                drawerLayout.closeDrawer(GravityCompat.START);
                            } else {
                                setEnabled(false);
                                getOnBackPressedDispatcher().onBackPressed();
                            }
                        }
                    });
        }
        refreshDrawerTitles();
    }

    /** Keep drawer server/floating rows in sync with real state. */
    private void refreshDrawerTitles() {
        try {
            if (drawerServerItem != null) {
                drawerServerItem.setTitle(OtpServer.getInstance().isRunning()
                        ? "Stop Server" : "Start Server");
            }
            if (drawerFloatingItem != null) {
                drawerFloatingItem.setTitle(FloatingService.isRunning()
                        ? "Floating Widget: ON" : "Floating Widget: OFF");
            }
        } catch (Exception ignored) {}
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
                // Locked users must not trigger background OTP fetches either.
                if (!accessAllowed) return;
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
        // Backend-validated gate: NOTHING works until the server confirms access.
        if (!accessAllowed) {
            showStatus("Locked (" + accessReason + ") — verifying access.", true);
            toast("Locked — verifying access");
            enforceAccessGate();
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
        try {
            currentCountry = OtpAutoActions.getCountry(this);
            if (currentCountry < 0 || currentCountry >= CountryData.COUNTRIES.length) currentCountry = 0;
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, CountryData.displayNames());
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            if (countrySpinner != null) {
                countrySpinner.setAdapter(adapter);
                if (currentCountry >= 0 && currentCountry < CountryData.COUNTRIES.length) {
                    countrySpinner.setSelection(currentCountry);
                }
            }
            refreshDomainView();
            if (countrySpinner != null) {
        countrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                try {
                    if (mProgrammaticCountry) return;
                    if (pos < 0 || pos >= CountryData.COUNTRIES.length) return;
                    currentCountry = pos;
                    OtpAutoActions.setCountry(MainActivity.this, pos);
                    refreshDomainView();
                } catch (Exception ignored) {}
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
            }
            if (genderGroup != null) {
        genderGroup.setOnCheckedChangeListener((group, checkedId) -> {
            try {
                if (mProgrammaticGender) return;
                String gender = "male";
                if (checkedId == R.id.genderFemale) gender = "female";
                else if (checkedId == R.id.genderBoth) gender = "both";
                OtpAutoActions.setGender(this, gender);
            } catch (Exception ignored) {}
        });
            }
            if (genNameBtn != null) {
        genNameBtn.setOnClickListener(v -> {
            try {
                if (!accessAllowed) {
                    toast("Locked — access not approved");
                    enforceAccessGate();
                    return;
                }
                int checked = genderGroup != null ? genderGroup.getCheckedRadioButtonId() : R.id.genderMale;
                String gender = "male";
                if (checked == R.id.genderFemale) gender = "female";
                else if (checked == R.id.genderBoth) gender = "both";
                String name = OtpHelper.generateName(currentCountry, gender);
                if (name == null || name.trim().isEmpty()) {
                    if (nameStatus != null) nameStatus.setText("Try again");
                    return;
                }
                OtpAutoActions.setGenName(this, name);
                showGeneratedName(name);
                if (nameStatus != null) {
                    nameStatus.setText("✓");
                    nameStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
                }
            } catch (Exception e) {
                try { toast("Name error: " + e.getMessage()); } catch (Exception ignored) {}
            }
        });
            }
        if (firstNameView != null) {
        firstNameView.setOnClickListener(v -> {
            copyToClipboard(firstNameView.getText().toString());
            toast("Copied");
        });
        }
        if (lastNameView != null) {
        lastNameView.setOnClickListener(v -> {
            copyToClipboard(lastNameView.getText().toString());
            toast("Copied");
        });
        }
        if (suggestedEmailView != null) {
        suggestedEmailView.setOnClickListener(v -> {
            copyToClipboard(suggestedEmailView.getText().toString());
            toast("Copied");
        });
        }
        if (copyNameBtn != null) {
        copyNameBtn.setOnClickListener(v -> {
            try {
                String f = firstNameView != null ? firstNameView.getText().toString() : "";
                String l = lastNameView != null ? lastNameView.getText().toString() : "";
                copyToClipboard((f + " " + l).trim());
                flash(copyNameBtn, "✓");
            } catch (Exception ignored) {}
        });
        }
        } catch (Exception e) {
            try { toast("Names unavailable: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private void refreshDomainView() {
        if (domainView == null) return;
        try {
            if (currentCountry < 0 || currentCountry >= CountryData.COUNTRIES.length) currentCountry = 0;
            domainView.setText("@" + CountryData.byIndex(currentCountry).domain);
        } catch (Exception ignored) {}
    }

    private void showGeneratedName(String fullName) {
        try {
            if (nameContainer == null || firstNameView == null || lastNameView == null) return;
            if (fullName == null || fullName.trim().isEmpty()) return;
            firstNameView.setText(firstOf(fullName));
            lastNameView.setText(lastOf(fullName));
            if (suggestedEmailView != null) {
                String sug = OtpHelper.suggestEmail(fullName, currentCountry);
                suggestedEmailView.setText(sug == null ? "" : sug);
            }
            nameContainer.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            try { toast("Name error: " + e.getMessage()); } catch (Exception ignored) {}
        }
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
            } else if (!accessAllowed) {
                toast("Locked — access not approved");
                enforceAccessGate();
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
        accountBtn = findViewById(R.id.accountBtn);
        packagesBtn = findViewById(R.id.packagesBtn);
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
                                bounceTo(ActivationActivity.class, true);
                            })
                            .show();
                } else {
                    startActivity(new Intent(this, ActivationActivity.class));
                }
            });
        }
        if (packagesBtn != null) {
            packagesBtn.setOnClickListener(v -> {
                if (!SessionManager.isLoggedIn(this)) {
                    startActivity(new Intent(this, ActivationActivity.class));
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

    /** " (5 days left)" / " (expires today)" / "" when no expiry (e.g. Free role). */
    private static String daysRemainingText(String expireIso) {
        try {
            if (expireIso == null || expireIso.isEmpty()) return "";
            long ms = 0;
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                String cut = expireIso.length() >= 19 ? expireIso.substring(0, 19) : expireIso;
                java.util.Date d = f.parse(cut);
                if (d != null) ms = d.getTime();
            } catch (Exception ignored) { return ""; }
            if (ms <= 0) return "";
            long days = (ms - System.currentTimeMillis()) / (24L * 60 * 60 * 1000);
            if (days < 0) return " (expired)";
            if (days == 0) return " (expires today)";
            if (days == 1) return " (1 day left)";
            return " (" + days + " days left)";
        } catch (Exception ignored) { return ""; }
    }

    /**
     * Startup + resume flow: version check -> login check -> live access check.
     * FAIL-CLOSED: any denial, dead session or unreachable backend bounces out
     * of Home. There is intentionally no "Later" path — unverified users cannot
     * use this screen, and CLEAR_TASK prevents navigating back into it.
     */
    private void enforceAccessGate() {
        net.execute(() -> {
            try {
                // Advisory update check, cached (see VERSION_CHECK_TTL_MS).
                long now0 = System.currentTimeMillis();
                if (now0 - lastVersionCheckAt > VERSION_CHECK_TTL_MS) {
                    lastVersionCheckAt = now0;
                    String installed = ApiClient.appVersion(MainActivity.this);
                ApiClient.Resp ver = ApiClient.get(this,
                        "/api/versions/check?platform=android&version=" + installed, false);
                // Updates are advisory only: record availability, notify once,
                // and ALWAYS continue to the access checks below (never block).
                if (ver.ok() && (ver.json.optBoolean("forceUpdate", false)
                        || ver.json.optBoolean("updateRequired", false))) {
                    updateAvailable = true;
                    updateUrl = ver.json.optString("updateUrl", "");
                    updateMessage = ver.json.optString("message", "");
                    latestVersion = ver.json.optString("latestVersion", "");
                    main.post(() -> showUpdateNotice(false));
                }
            } catch (Exception e) {
                accessAllowed = false;
                accessReason = "OFFLINE";
                showOfflineBlock();
                return;
            }
            if (!SessionManager.isLoggedIn(this)) {
                accessAllowed = false;
                accessReason = "NOT_LOGGED_IN";
                main.post(() -> bounceTo(ActivationActivity.class, false));
                return;
            }
            final AccessGate.Result gate;
            try {
                gate = AccessGate.check(this);
            } catch (Exception e) {
                accessAllowed = false;
                accessReason = "OFFLINE";
                showOfflineBlock();
                return;
            }
            accessAllowed = gate.allowed;
            accessReason = gate.reason;
            if (gate.allowed) {
                String pkg = gate.packageName.isEmpty() ? "package" : gate.packageName;
                String till = gate.expireDate.length() >= 10
                        ? " till " + gate.expireDate.substring(0, 10) : "";
                String days = daysRemainingText(gate.expireDate);
                setAccessText("✓ Active: " + pkg + till + days);
                return;
            }
            if (gate.needsPackage()) {
                setAccessText("NO_PACKAGE".equals(gate.reason)
                        ? "No active package — opening Packages"
                        : "Pending admin approval — opening Packages");
                main.post(() -> bounceTo(PackageActivity.class, false));
                return;
            }
            // DISABLED / ACCESS_DENIED / SESSION_EXPIRED / NO_ACCOUNT / other:
            // full lockout, session cleared.
            final String msg = gate.message.isEmpty() ? "Access denied." : gate.message;
            setAccessText("Access denied (" + gate.reason + ")");
            main.post(() -> {
                toast(msg);
                bounceTo(ActivationActivity.class, true);
            });
        });
    }

    /** Fail-closed offline state: Retry re-verifies, Logout exits. No "Later". */
    private void showOfflineBlock() {
        setAccessText("Offline — access not verified");
        main.post(() -> {
            if (gateDialogShowing || isFinishing()) return;
            gateDialogShowing = true;
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Cannot verify access")
                    .setMessage("The server could not be reached. The app stays locked until access is verified.")
                    .setCancelable(false)
                    .setPositiveButton("Retry", (d, w) -> {
                        gateDialogShowing = false;
                        enforceAccessGate();
                    })
                    .setNegativeButton("Logout", (d, w) -> {
                        gateDialogShowing = false;
                        bounceTo(ActivationActivity.class, true);
                    })
                    .show();
        });
    }

    /**
     * Hard bounce: stop every background surface (server + floating widget),
     * optionally clear the session, and clear the back stack so Back can never
     * return into Home.
     */
    private void bounceTo(Class<?> target, boolean clearSession) {
        if (isFinishing()) return;
        accessAllowed = false;
        try { OtpServer.getInstance().stop(); } catch (Exception ignored) {}
        try { stopService(new Intent(this, FloatingService.class)); } catch (Exception ignored) {}
        if (clearSession) SessionManager.logout(this);
        Intent i = new Intent(this, target);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    /**
     * Optional-update notice: informs the user a new version exists but never
     * blocks the app. Shown once per session automatically; the navigation
     * drawer can re-trigger it manually at any time.
     */
    private void showUpdateNotice(boolean manual) {
        if (isFinishing()) return;
        if (!manual) {
            if (updateNoticeShown) return;
            updateNoticeShown = true;
        }
        String msg = updateMessage.isEmpty()
                ? ("A new version" + (latestVersion.isEmpty() ? "" : " (" + latestVersion + ")")
                        + " is available.")
                : updateMessage;
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage(msg + "\n\nYou can keep using the app — updating is optional.")
                .setCancelable(true)
                .setPositiveButton("Update Now", (d, w) -> {
                    try {
                        if (!updateUrl.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(updateUrl)));
                        else Toast.makeText(this, "Ask admin for the new APK", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid update URL", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    /** Manual update check for the navigation drawer. */
    private void checkForUpdatesManual() {
        toast("Checking for updates...");
        net.execute(() -> {
            try {
                String installed = ApiClient.appVersion(MainActivity.this);
                ApiClient.Resp ver = ApiClient.get(this,
                        "/api/versions/check?platform=android&version=" + installed, false);
                boolean avail = ver.ok() && (ver.json.optBoolean("forceUpdate", false)
                        || ver.json.optBoolean("updateRequired", false));
                if (avail) {
                    updateAvailable = true;
                    updateUrl = ver.json.optString("updateUrl", "");
                    updateMessage = ver.json.optString("message", "");
                    latestVersion = ver.json.optString("latestVersion", "");
                    main.post(() -> showUpdateNotice(true));
                } else {
                    main.post(() -> toast("You're on the latest version"));
                }
            } catch (Exception e) {
                main.post(() -> toast("Update check failed: offline"));
            }
        });
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
