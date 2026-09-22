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
 * Profile: shows the account username (never editable — usernames stay
 * unique and immutable) and lets the user change their password.
 */
public class ProfileActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private TextView usernameView, infoView;
    private EditText currentPassInput, newPassInput, confirmPassInput;
    private Button changeBtn, backBtn, signOutBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SessionManager.isLoggedIn(this)) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_profile);
        usernameView = findViewById(R.id.profileUsername);
        infoView = findViewById(R.id.profileInfo);
        currentPassInput = findViewById(R.id.currentPassInput);
        newPassInput = findViewById(R.id.newPassInput);
        confirmPassInput = findViewById(R.id.confirmPassInput);
        changeBtn = findViewById(R.id.changePassBtn);
        backBtn = findViewById(R.id.profileBackBtn);
        signOutBtn = findViewById(R.id.profileSignOutBtn);

        JSONObject u = SessionManager.getUser(this);
        String username = "";
        String info = "";
        if (u != null) {
            String email = u.optString("email", "");
            String phone = u.optString("phone", "");
            username = !email.isEmpty() ? email : phone;
            if (username.isEmpty()) username = u.optString("name", "");
            info = "Name: " + u.optString("name", "—");
        }
        usernameView.setText(username.isEmpty() ? "—" : username);
        infoView.setText(info);

        changeBtn.setOnClickListener(v -> changePassword());
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());
        if (signOutBtn != null) signOutBtn.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("You will need to sign in again to use the OTP workspace.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out", (d, w) -> {
                SessionManager.logout(this);
                Intent i = new Intent(this, ActivationActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }).show());
    }

    private void changePassword() {
        String cur = currentPassInput.getText().toString();
        String next = newPassInput.getText().toString();
        String confirm = confirmPassInput.getText().toString();
        if (cur.isEmpty() || next.isEmpty() || confirm.isEmpty()) {
            toast("All password fields are required");
            return;
        }
        if (next.length() < 4) {
            toast("New password must be at least 4 characters");
            return;
        }
        if (!next.equals(confirm)) {
            toast("New passwords do not match");
            return;
        }
        UiBusy.setBusy(changeBtn, "Updating...");
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("currentPassword", cur);
                body.put("newPassword", next);
                ApiClient.Resp r = ApiClient.post(this, "/api/auth/change-password", body, true);
                runOnUiThread(() -> {
                    UiBusy.setIdle(changeBtn, "Change Password");
                    if (r.ok()) {
                        toast("Password changed successfully");
                        currentPassInput.setText("");
                        newPassInput.setText("");
                        confirmPassInput.setText("");
                    } else if (r.code == 401) {
                        toast("Session expired. Please login again.");
                        SessionManager.logout(this);
                        Intent i = new Intent(this, AuthActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    } else {
                        toast(r.json.optString("error", "Password change failed"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    UiBusy.setIdle(changeBtn, "Change Password");
                    toast("Failed: " + e.getMessage());
                });
            }
        });
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
