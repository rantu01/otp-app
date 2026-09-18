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

/** Login / Register against the shared backend. Also configures the API base URL. */
public class AuthActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private EditText serverInput, loginInput, nameInput, passInput;
    private TextView hintView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        serverInput = findViewById(R.id.serverInput);
        loginInput = findViewById(R.id.loginInput);
        nameInput = findViewById(R.id.nameInput);
        passInput = findViewById(R.id.passInput);
        hintView = findViewById(R.id.authHint);
        Button loginBtn = findViewById(R.id.loginBtn);
        Button registerBtn = findViewById(R.id.registerBtn);

        serverInput.setText(SessionManager.getBaseUrl(this));
        loginBtn.setOnClickListener(v -> doAuth(false));
        registerBtn.setOnClickListener(v -> doAuth(true));
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
                if (register) {
                    if (login.contains("@")) body.put("email", login);
                    else body.put("phone", login);
                    body.put("name", name.isEmpty() ? login.split("@")[0] : name);
                    ApiClient.Resp r = ApiClient.post(this, "/api/auth/register", body, false);
                    resp = r.json;
                    if (!r.ok()) throw new Exception(resp.optString("error", "Register failed"));
                } else {
                    body.put("login", login);
                    ApiClient.Resp r = ApiClient.post(this, "/api/auth/login", body, false);
                    resp = r.json;
                    if (!r.ok()) throw new Exception(resp.optString("error", "Login failed"));
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
                runOnUiThread(() -> {
                    toast("Welcome " + (user != null ? user.optString("name", "") : ""));
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hintView.setText("Error: " + e.getMessage());
                    toast("Failed: " + e.getMessage());
                });
            }
        });
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
