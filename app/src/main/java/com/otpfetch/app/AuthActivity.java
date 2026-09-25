package com.otpfetch.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Credential Login / Register with username + password.
 *
 * Primary login screen alongside device activation: on every cold start with
 * a saved session the backend access is re-verified before routing:
 *
 *   access OK              -> MainActivity (Home)
 *   PENDING / NO_PACKAGE   -> PackageActivity (purchase/approval flow only)
 *   anything else          -> stay here, fully locked out (no bypass)
 *   unreachable backend    -> blocking Retry (fail closed, never "Later")
 *
 * One device per account: if the backend reports the account is live on
 * another device (HTTP 409 SESSION_IN_USE), the exact message is shown and
 * the user must log out there first. Backend URL is centralized
 * (see ApiConfig) — never entered manually.
 */
public class AuthActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private EditText loginInput, nameInput, passInput, referralCodeInput;
    private TextView hintView;
    private LinearLayout formBox;
    private Button loginBtn, registerBtn;
    private boolean routing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        loginInput = findViewById(R.id.loginInput);
        nameInput = findViewById(R.id.nameInput);
        passInput = findViewById(R.id.passInput);
        referralCodeInput = findViewById(R.id.referralCodeInput);
        hintView = findViewById(R.id.authHint);
        formBox = findViewById(R.id.authForm);
        loginBtn = findViewById(R.id.loginBtn);
        registerBtn = findViewById(R.id.registerBtn);

        loginBtn.setOnClickListener(v -> doAuth(false));
        registerBtn.setOnClickListener(v -> doAuth(true));

        // Saved session? Verify server-side on every cold start, then route.
        // No session -> show the login form (default).
        if (SessionManager.isLoggedIn(this)) {
            verifyAndRoute();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from Package/Main after logout must not auto-skip the form.
        if (!SessionManager.isLoggedIn(this)) {
            routing = false;
            setFormEnabled(true);
            if (hintView != null && (hintView.getText() == null
                    || hintView.getText().toString().startsWith("Verifying"))) {
                hintView.setText("");
            }
        }
    }

    private void setFormEnabled(boolean enabled) {
        if (formBox != null) {
            formBox.setVisibility(enabled ? View.VISIBLE : View.GONE);
        } else {
            int vis = enabled ? View.VISIBLE : View.GONE;
            loginInput.setVisibility(vis);
            nameInput.setVisibility(vis);
            passInput.setVisibility(vis);
        }
        if (loginBtn != null) loginBtn.setEnabled(enabled);
        if (registerBtn != null) registerBtn.setEnabled(enabled);
    }

    /** Live server check -> route. Fail CLOSED when the backend is unreachable. */
    private void verifyAndRoute() {
        if (routing) return;
        routing = true;
        setFormEnabled(false);
        hintView.setText("Verifying access...");
        net.execute(() -> {
            try {
                AccessGate.Result r = AccessGate.check(this);
                runOnUiThread(() -> routeByAccess(r, true));
            } catch (Exception e) {
                runOnUiThread(this::showOfflineBlock);
            }
        });
    }

    private void routeByAccess(AccessGate.Result r, boolean fromStartup) {
        if (r.allowed) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (r.httpCode == 401 || "SESSION_EXPIRED".equals(r.reason)
                || "NO_ACCOUNT".equals(r.reason)) {
            SessionManager.logout(this);
            routing = false;
            setFormEnabled(true);
            hintView.setText("Session expired. Please login again.");
            return;
        }
        if (r.needsPackage()) {
            // Active-or-pending account without an approved package:
            // purchase flow only, never Home.
            startActivity(new Intent(this, PackageActivity.class));
            finish();
            return;
        }
        // DISABLED / ACCESS_DENIED / anything else: full lockout.
        // Keep the saved token only so a pending user can still be re-checked;
        // disabled/blocked sessions are cleared so they cannot be reused.
        if ("DISABLED".equals(r.reason) || "ACCESS_DENIED".equals(r.reason)) {
            SessionManager.logout(this);
        }
        routing = false;
        setFormEnabled(true);
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
                    verifyAndRoute();
                })
                .setNegativeButton("Logout", (d, w) -> {
                    SessionManager.logout(this);
                    routing = false;
                    setFormEnabled(true);
                    hintView.setText("");
                })
                .show();
    }

    private void doAuth(boolean register) {
        String login = loginInput.getText().toString().trim();
        String pass = passInput.getText().toString();
        String name = nameInput.getText().toString().trim();
        if (login.isEmpty() || pass.isEmpty()) {
            toast("Username and password are required");
            return;
        }
        SessionManager.setBaseUrl(this, ApiConfig.DEFAULT_BASE_URL);
        hintView.setText(register ? "Creating account..." : "Logging in...");
        Button active = register ? registerBtn : loginBtn;
        Button other = register ? loginBtn : registerBtn;
        UiBusy.setBusy(active, register ? "Creating..." : "Logging in...");
        if (other != null) other.setEnabled(false);
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("password", pass);
                JSONObject resp;
                int code;
                if (register) {
                    if (login.contains("@")) body.put("email", login);
                    else body.put("phone", login);
                    body.put("name", name.isEmpty() ? login.split("@")[0] : name);
                    String referralCode = referralCodeInput.getText().toString().trim();
                    if (!referralCode.isEmpty()) body.put("referralCode", referralCode);
                    ApiClient.Resp r = ApiClient.post(this, "/api/auth/register", body, false);
                    resp = r.json;
                    code = r.code;
                    if (!r.ok()) throw new AuthFailed(code, resp.optString("error", "Register failed"));
                } else {
                    body.put("login", login);
                    ApiClient.Resp r = ApiClient.post(this, "/api/auth/login", body, false);
                    resp = r.json;
                    code = r.code;
                    if (!r.ok()) throw new AuthFailed(code, resp.optString("error", "Login failed"));
                }
                // Admins must use the Admin App / Website, never the User App.
                JSONObject user = resp.optJSONObject("user");
                if (user != null && "admin".equals(user.optString("role"))) {
                    runOnUiThread(() -> {
                        UiBusy.setIdle(loginBtn, "Login");
                        UiBusy.setIdle(registerBtn, "Create Account");
                        hintView.setText("This account is an admin. Please use the Admin App.");
                        toast("Admins cannot log into the User App");
                    });
                    return;
                }
                SessionManager.saveLogin(this, resp.optString("token", ""), user);
                final JSONObject respFinal = resp;
                runOnUiThread(() -> {
                    UiBusy.setIdle(loginBtn, "Login");
                    UiBusy.setIdle(registerBtn, "Create Account");
                    // Freshly-issued auth response already carries access state.
                    AccessGate.Result r = AccessGate.fromAuthResponse(respFinal);
                    if (r.allowed) {
                        toast("Welcome " + (user != null ? user.optString("name", "") : ""));
                    }
                    routeByAccess(r, false);
                });
            } catch (AuthFailed e) {
                runOnUiThread(() -> {
                    UiBusy.setIdle(loginBtn, "Login");
                    UiBusy.setIdle(registerBtn, "Create Account");
                    // 403 = backend account gate (pending/disabled/blocked):
                    // show the reason, keep any saved session for re-checks.
                    hintView.setText(e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    UiBusy.setIdle(loginBtn, "Login");
                    UiBusy.setIdle(registerBtn, "Create Account");
                    hintView.setText("Error: " + e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            }
        });
    }

    private static final class AuthFailed extends Exception {
        final int code;
        AuthFailed(int code, String msg) {
            super(msg);
            this.code = code;
        }
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
