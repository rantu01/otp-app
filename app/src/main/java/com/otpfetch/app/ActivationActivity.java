package com.otpfetch.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-run App / Device Activation screen (sole LAUNCHER).
 *
 * Shows the device's Device ID (tap to copy), lets the user paste/type a
 * Device ID manually, and on Next activates against the backend
 * (POST /api/auth/device) and routes:
 *
 *   approved device account -> MainActivity (Home)
 *   pending / no package    -> PackageActivity (pay, then wait for admin)
 *   blocked account         -> stays here, fully locked out
 *   unreachable backend     -> blocking Retry (fail closed)
 *
 * Until an admin approves the activation/package, Home is unreachable —
 * every screen re-verifies server-side (see AccessGate).
 */
public class ActivationActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private TextView deviceIdText;
    private EditText deviceInput, serverInput;
    private TextView hintView;
    private Button nextBtn, exitBtn;
    private String detectedId = "";
    private boolean routing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activation);
        deviceIdText = findViewById(R.id.deviceIdText);
        deviceInput = findViewById(R.id.deviceInput);
        serverInput = findViewById(R.id.serverInput);
        hintView = findViewById(R.id.actHint);
        nextBtn = findViewById(R.id.nextBtn);
        exitBtn = findViewById(R.id.exitBtn);

        detectedId = detectDeviceId();
        deviceIdText.setText(detectedId);
        String saved = SessionManager.getDeviceId(this);
        deviceInput.setText(saved.isEmpty() ? detectedId : saved);
        // Backend host (emulator default; real devices need the PC's LAN IP).
        serverInput.setText(SessionManager.getBaseUrl(this));

        deviceIdText.setOnClickListener(v -> {
            copyToClipboard(deviceIdText.getText().toString());
            hintView.setText("Device ID copied — send it to your admin for activation.");
        });
        nextBtn.setOnClickListener(v -> doNext());
        exitBtn.setOnClickListener(v -> finishAffinity());

        // Returning session? Verify server-side, then route. Otherwise the
        // activation form above is the first-run experience.
        if (SessionManager.isLoggedIn(this)) {
            verifyAndRoute();
        }
    }

    /** ANDROID_ID (hex, like the reference) with a stable persisted fallback. */
    private String detectDeviceId() {
        try {
            String androidId = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId != null && androidId.matches("(?i)[a-f0-9]{6,64}")) {
                return androidId.toLowerCase();
            }
        } catch (Exception ignored) {}
        return SessionManager.getOrCreateFallbackDeviceId(this);
    }

    private void doNext() {
        String manual = deviceInput.getText().toString().trim();
        String raw = manual.isEmpty() ? detectedId : manual;
        String norm = raw.toLowerCase().replaceAll("[\\s\\-:]", "");
        if (!norm.matches("[a-f0-9]{6,64}")) {
            hintView.setText("Invalid Device ID. Use the shown ID or paste a valid one.");
            toast("Invalid Device ID");
            return;
        }
        String base = serverInput.getText().toString().trim();
        if (base.isEmpty()) {
            hintView.setText("Server URL is required.");
            toast("Server URL is required");
            return;
        }
        SessionManager.setBaseUrl(this, base);
        SessionManager.setDeviceId(this, norm);
        hintView.setText("Activating...");
        nextBtn.setEnabled(false);
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("deviceId", norm);
                ApiClient.Resp r = ApiClient.post(this, "/api/auth/device", body, false);
                if (!r.ok()) {
                    final String err = r.json.optString("error", "Activation failed");
                    runOnUiThread(() -> {
                        nextBtn.setEnabled(true);
                        hintView.setText(err);
                        toast(err);
                    });
                    return;
                }
                JSONObject user = r.json.optJSONObject("user");
                SessionManager.saveLogin(this, r.json.optString("token", ""), user);
                final JSONObject respFinal = r.json;
                runOnUiThread(() -> {
                    nextBtn.setEnabled(true);
                    routeByAccess(AccessGate.fromAuthResponse(respFinal));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    nextBtn.setEnabled(true);
                    hintView.setText("Error: " + e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            }
        });
    }

    /** Live server check -> route. Fail CLOSED when the backend is unreachable. */
    private void verifyAndRoute() {
        if (routing) return;
        routing = true;
        hintView.setText("Verifying access...");
        nextBtn.setEnabled(false);
        net.execute(() -> {
            try {
                AccessGate.Result r = AccessGate.check(this);
                runOnUiThread(() -> routeByAccess(r));
            } catch (Exception e) {
                runOnUiThread(this::showOfflineBlock);
            }
        });
    }

    private void routeByAccess(AccessGate.Result r) {
        routing = false;
        nextBtn.setEnabled(true);
        if (r.allowed) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (r.httpCode == 401 || "SESSION_EXPIRED".equals(r.reason)
                || "NO_ACCOUNT".equals(r.reason)) {
            SessionManager.logout(this);
            hintView.setText("Session expired — tap next to reactivate.");
            return;
        }
        if (r.needsPackage()) {
            // Pending approval or no subscription: purchase flow only, never Home.
            startActivity(new Intent(this, PackageActivity.class));
            finish();
            return;
        }
        // DISABLED / ACCESS_DENIED / anything else: full lockout on this screen.
        // The saved token is useless (backend denies it); clear blocked sessions
        // but keep pending ones for re-checks.
        if ("DISABLED".equals(r.reason) || "ACCESS_DENIED".equals(r.reason)) {
            SessionManager.logout(this);
        }
        hintView.setText(r.message.isEmpty() ? "Access denied." : r.message);
        toast(r.message.isEmpty() ? "Access denied" : r.message);
    }

    private void showOfflineBlock() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Cannot verify access")
                .setMessage("The server could not be reached. Activation must be verified before the app can be used.")
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> {
                    routing = false;
                    if (SessionManager.isLoggedIn(this)) verifyAndRoute();
                    else {
                        nextBtn.setEnabled(true);
                        hintView.setText("");
                    }
                })
                .setNegativeButton("Exit", (d, w) -> finishAffinity())
                .show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("deviceId", text));
        toast("Device ID copied");
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
