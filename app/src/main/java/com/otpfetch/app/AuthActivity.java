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
 * Credential Login / Register (DORMANT fallback, not in the UI flow).
 * The User App now opens on ActivationActivity (device activation) instead;
 * this screen is kept working but unlinked. Login / Register against the
 * shared backend, and — on every cold start with a saved session —
 * re-verifies backend access before routing anywhere:
 *
 *   access OK              -> MainActivity (Home)
 *   PENDING / NO_PACKAGE   -> PackageActivity (purchase/approval flow only)
 *   anything else          -> stay here, fully locked out (no bypass)
 *   unreachable backend    -> blocking Retry (fail closed, never "Later")
 *
 * Also configures the API base URL.
 */
public class AuthActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private EditText serverInput, loginInput, nameInput, passInput;
    private TextView hintView;
    private LinearLayout formBox;
    private boolean routing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        serverInput = findViewById(R.id.serverInput);
        loginInput = findViewById(R.id.loginInput);
        nameInput = findViewById(R.id.nameInput);
        passInput = findViewById(R.id.passInput);
        hintView = findViewById(R.id.authHint);
        formBox = findViewById(R.id.authForm);
        Button loginBtn = findViewById(R.id.loginBtn);
        Button registerBtn = findViewById(R.id.registerBtn);

        serverInput.setText(SessionManager.getBaseUrl(this));
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
            serverInput.setVisibility(vis);
            loginInput.setVisibility(vis);
            nameInput.setVisibility(vis);
            passInput.setVisibility(vis);
        }
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
        String base = serverInput.getText().toString().trim();
        String login = loginInput.getText().toString().trim();
        String pass = passInput.getText().toString();
        String name = nameInput.getText().toString().trim();
        if (base.isEmpty() || login.isEmpty() || pass.isEmpty()) {
            toast("Server, login and password are required");
            return;
        }
        SessionManager.setBaseUrl(this, base);
        hintView.setText(register ? "Creating account..." : "Logging in...");
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
                        hintView.setText("This account is an admin. Please use the Admin App.");
                        toast("Admins cannot log into the User App");
                    });
                    return;
                }
                SessionManager.saveLogin(this, resp.optString("token", ""), user);
                final JSONObject respFinal = resp;
                runOnUiThread(() -> {
                    // Freshly-issued auth response already carries access state.
                    AccessGate.Result r = AccessGate.fromAuthResponse(respFinal);
                    if (r.allowed) {
                        toast("Welcome " + (user != null ? user.optString("name", "") : ""));
                    }
                    routeByAccess(r, false);
                });
            } catch (AuthFailed e) {
                runOnUiThread(() -> {
                    // 403 = backend account gate (pending/disabled/blocked):
                    // show the reason, keep any saved session for re-checks.
                    hintView.setText(e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
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
