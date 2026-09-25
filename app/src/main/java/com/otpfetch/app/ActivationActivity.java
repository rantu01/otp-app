package com.otpfetch.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Credential Login / Register screen (sole LAUNCHER).
 *
 * Username + password only — no Device ID involvement.
 *
 * Routes:
 *   access OK              -> MainActivity (Home)
 *   PENDING / NO_PACKAGE   -> PackageActivity
 *   DISABLED / ACCESS_DENIED -> locked here
 *   unreachable backend    -> blocking Retry (fail closed)
 */
public class ActivationActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private EditText loginInput, passInput, referralCodeInput;
    private TextView hintView;
    private Button loginBtn, registerBtn, exitBtn;
    private boolean routing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activation);
        loginInput = findViewById(R.id.loginInput);
        passInput = findViewById(R.id.passInput);
        referralCodeInput = findViewById(R.id.referralCodeInput);
        hintView = findViewById(R.id.actHint);
        loginBtn = findViewById(R.id.loginBtn);
        registerBtn = findViewById(R.id.registerBtn);
        exitBtn = findViewById(R.id.exitBtn);

        loginBtn.setOnClickListener(v -> doLogin());
        registerBtn.setOnClickListener(v -> doRegister());
        exitBtn.setOnClickListener(v -> finishAffinity());

        if (SessionManager.isLoggedIn(this)) {
            verifyAndRoute();
        }
    }

    private void doLogin() {
        String login = loginInput.getText().toString().trim();
        String pass = passInput.getText().toString();
        if (login.isEmpty() || pass.isEmpty()) {
            toast("Username and password are required");
            return;
        }
        SessionManager.setBaseUrl(this, ApiConfig.DEFAULT_BASE_URL);
        hintView.setText("Logging in...");
        UiBusy.setBusy(loginBtn, "Logging in...");
        if (registerBtn != null) registerBtn.setEnabled(false);
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("login", login);
                body.put("password", pass);
                ApiClient.Resp r = ApiClient.post(this, "/api/auth/login", body, false);
                if (!r.ok()) throw new AuthFailed(r.code, r.json.optString("error", "Login failed"));
                JSONObject user = r.json.optJSONObject("user");
                SessionManager.saveLogin(this, r.json.optString("token", ""), user);
                final JSONObject respFinal = r.json;
                runOnUiThread(() -> {
                    UiBusy.setIdle(loginBtn, "Login");
                    if (registerBtn != null) { UiBusy.setIdle(registerBtn, "Create Account"); registerBtn.setEnabled(true); }
                    AccessGate.Result result = AccessGate.fromAuthResponse(respFinal);
                    if (result.allowed) {
                        toast("Welcome " + (user != null ? user.optString("name", "") : ""));
                    }
                    routeByAccess(result);
                });
            } catch (AuthFailed e) {
                runOnUiThread(() -> {
                    UiBusy.setIdle(loginBtn, "Login");
                    if (registerBtn != null) { UiBusy.setIdle(registerBtn, "Create Account"); registerBtn.setEnabled(true); }
                    hintView.setText(e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    UiBusy.setIdle(loginBtn, "Login");
                    if (registerBtn != null) { UiBusy.setIdle(registerBtn, "Create Account"); registerBtn.setEnabled(true); }
                    hintView.setText("Error: " + e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            }
        });
    }

    private void doRegister() {
        String login = loginInput.getText().toString().trim();
        String pass = passInput.getText().toString();
        if (login.isEmpty() || pass.isEmpty()) {
            toast("Username and password are required");
            return;
        }
        if (pass.length() < 4) {
            toast("Password must be at least 4 characters");
            return;
        }
        SessionManager.setBaseUrl(this, ApiConfig.DEFAULT_BASE_URL);
        hintView.setText("Creating account...");
        UiBusy.setBusy(registerBtn, "Creating...");
        loginBtn.setEnabled(false);
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                if (login.contains("@")) body.put("email", login);
                else body.put("phone", login);
                body.put("password", pass);
                String referralCode = referralCodeInput.getText().toString().trim();
                if (!referralCode.isEmpty()) body.put("referralCode", referralCode);
                ApiClient.Resp r = ApiClient.post(this, "/api/auth/register", body, false);
                if (!r.ok()) throw new AuthFailed(r.code, r.json.optString("error", "Register failed"));
                JSONObject user = r.json.optJSONObject("user");
                SessionManager.saveLogin(this, r.json.optString("token", ""), user);
                final JSONObject respFinal = r.json;
                runOnUiThread(() -> {
                    UiBusy.setIdle(registerBtn, "Create Account");
                    loginBtn.setEnabled(true);
                    AccessGate.Result result = AccessGate.fromAuthResponse(respFinal);
                    if (result.allowed) {
                        toast("Account created");
                    }
                    routeByAccess(result);
                });
            } catch (AuthFailed e) {
                runOnUiThread(() -> {
                    UiBusy.setIdle(registerBtn, "Create Account");
                    loginBtn.setEnabled(true);
                    hintView.setText(e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    UiBusy.setIdle(registerBtn, "Create Account");
                    loginBtn.setEnabled(true);
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
        UiBusy.setBusy(exitBtn, "Loading...");
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
        UiBusy.setIdle(exitBtn, "EXIT");
        if (r.allowed) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (r.httpCode == 401 || "SESSION_EXPIRED".equals(r.reason)
                || "NO_ACCOUNT".equals(r.reason)) {
            SessionManager.logout(this);
            hintView.setText("Session expired — please login again.");
            return;
        }
        if (r.needsPackage()) {
            startActivity(new Intent(this, PackageActivity.class));
            finish();
            return;
        }
        if ("DISABLED".equals(r.reason) || "ACCESS_DENIED".equals(r.reason)) {
            SessionManager.logout(this);
        }
        hintView.setText(r.message.isEmpty() ? "Access denied." : r.message);
        toast(r.message.isEmpty() ? "Access denied" : r.message);
    }

    private void showOfflineBlock() {
        if (isFinishing()) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cannot verify access")
                .setMessage("The server could not be reached. Access must be verified before the app can be used.")
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> {
                    routing = false;
                    if (SessionManager.isLoggedIn(this)) verifyAndRoute();
                    else { UiBusy.setIdle(exitBtn, "EXIT"); hintView.setText(""); }
                })
                .setNegativeButton("Exit", (d, w) -> finishAffinity())
                .show();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    private static final class AuthFailed extends Exception {
        final int code;
        AuthFailed(int code, String msg) { super(msg); this.code = code; }
    }
}