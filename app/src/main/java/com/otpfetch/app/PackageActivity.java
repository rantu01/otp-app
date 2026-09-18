package com.otpfetch.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * No Active Package screen: packages + payment methods are loaded from the
 * backend (never hardcoded). One-click copy for wallet numbers, TxID submit,
 * and live payment status (pending/approved/rejected+reason).
 */
public class PackageActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newCachedThreadPool();
    private LinearLayout packageList, methodList, myPaymentsList;
    private TextView statusView, selectedInfo;
    private EditText txidInput;
    private Button submitBtn, refreshBtn;

    private JSONArray packages = new JSONArray();
    private JSONArray methods = new JSONArray();
    private int selectedPackage = -1;
    private int selectedMethod = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hard entry gate: no session -> Auth. Logged-in sessions are verified
        // server-side in loadAll()/onResume (approved -> Main, blocked -> Auth).
        if (!SessionManager.isLoggedIn(this)) {
            Intent i = new Intent(this, ActivationActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }
        setContentView(R.layout.activity_package);
        packageList = findViewById(R.id.packageList);
        methodList = findViewById(R.id.methodList);
        myPaymentsList = findViewById(R.id.myPaymentsList);
        statusView = findViewById(R.id.pkgStatus);
        selectedInfo = findViewById(R.id.selectedInfo);
        txidInput = findViewById(R.id.txidInput);
        submitBtn = findViewById(R.id.submitBtn);
        refreshBtn = findViewById(R.id.pkgRefreshBtn);
        submitBtn.setOnClickListener(v -> submitPayment());
        refreshBtn.setOnClickListener(v -> loadAll());
        loadAll();
    }

    private void loadAll() {
        statusView.setText("Loading packages...");
        net.execute(() -> {
            try {
                // Live server gate first: approved -> Home, blocked -> Auth.
                // Only PENDING / NO_PACKAGE accounts may stay on this screen.
                final AccessGate.Result gate;
                try {
                    gate = AccessGate.check(this);
                } catch (Exception e) {
                    runOnUiThread(this::showOfflineBlock);
                    return;
                }
                if (gate.allowed) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Package active — opening app", Toast.LENGTH_SHORT).show();
                        Intent i = new Intent(this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    });
                    return;
                }
                if (!gate.needsPackage()) {
                    final String msg = gate.message.isEmpty() ? "Access denied." : gate.message;
                    runOnUiThread(() -> {
                        SessionManager.logout(this);
                        try { stopService(new Intent(this, FloatingService.class)); } catch (Exception ignored) {}
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        Intent i = new Intent(this, ActivationActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    });
                    return;
                }
                final boolean pending = "PENDING".equals(gate.reason);
                ApiClient.Resp p = ApiClient.get(this, "/api/packages", true);
                ApiClient.Resp m = ApiClient.get(this, "/api/payment-methods", true);
                ApiClient.Resp mine = ApiClient.get(this, "/api/payments/mine", true);
                ApiClient.Resp subs = null;
                try { subs = ApiClient.get(this, "/api/subscriptions/mine", true); } catch (Exception ignored) {}
                if (!p.ok()) throw new Exception(p.json.optString("error", "Failed to load packages"));
                packages = p.json.optJSONArray("packages");
                if (packages == null) packages = new JSONArray();
                methods = m.ok() ? m.json.optJSONArray("paymentMethods") : new JSONArray();
                if (methods == null) methods = new JSONArray();
                JSONArray my = mine.ok() ? mine.json.optJSONArray("payments") : new JSONArray();
                final JSONObject current;
                try { current = subs != null && subs.ok() ? subs.json.optJSONObject("current") : null; }
                catch (Exception e) { throw new RuntimeException(e); }
                final JSONObject curFinal = current;
                final String currentLine = currentPackageLine(current);
                runOnUiThread(() -> {
                    renderPackages();
                    renderMethods();
                    renderMine(my == null ? new JSONArray() : my);
                    if (pending) {
                        statusView.setText(currentLine.isEmpty()
                                ? "Account pending admin approval — choose a package below to activate."
                                : (currentLine + "\nAccount pending admin approval — choose a package below to activate."));
                    } else {
                        String base = packages.length() == 0 ? "No packages available right now." : "Choose a package:";
                        statusView.setText(currentLine.isEmpty() ? base : (currentLine + "\n" + base));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText("Offline: " + e.getMessage() + " — tap Refresh to retry."));
            }
        });
    }

    /** "Current: Weekly — 5 days left (till 2026-09-20)" or "" when none. */
    private static String currentPackageLine(JSONObject cur) {
        try {
            if (cur == null) return "";
            String name = cur.optString("packageName", "");
            String exp = cur.optString("expire", "");
            if (name.isEmpty() && exp.isEmpty()) return "";
            if (exp == null || exp.isEmpty()) return "Current: " + (name.isEmpty() ? "Free" : name);
            long ms = 0;
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d = f.parse(exp.length() >= 19 ? exp.substring(0, 19) : exp);
                if (d != null) ms = d.getTime();
            } catch (Exception ignored) { return "Current: " + name; }
            long days = (ms - System.currentTimeMillis()) / (24L * 60 * 60 * 1000);
            String left = days < 0 ? "expired" : (days == 0 ? "expires today" : (days == 1 ? "1 day left" : days + " days left"));
            String till = exp.length() >= 10 ? exp.substring(0, 10) : exp;
            return "Current: " + name + " — " + left + " (till " + till + ")";
        } catch (Exception ignored) { return ""; }
    }

    private void renderPackages() {        packageList.removeAllViews();
        for (int i = 0; i < packages.length(); i++) {
            final int idx = i;
            JSONObject o = packages.optJSONObject(i);
            String label = "৳" + o.optInt("price") + " — " + o.optInt("durationDays") + " Days\n" + o.optString("name");
            Button b = styledButton(label, idx == selectedPackage);
            b.setOnClickListener(v -> {
                selectedPackage = idx;
                renderPackages();
                renderMethods();
                updateSelectedInfo();
            });
            packageList.addView(b);
        }
    }

    private void renderMethods() {
        methodList.removeAllViews();
        for (int i = 0; i < methods.length(); i++) {
            final int idx = i;
            JSONObject o = methods.optJSONObject(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(16, 16, 16, 16);
            card.setBackgroundColor(ContextCompat.getColor(this,
                    idx == selectedMethod ? R.color.pkg_selected : R.color.pkg_card));

            TextView title = new TextView(this);
            title.setText(o.optString("name") + "  (" + o.optString("accountType", "Personal") + ")");
            title.setTextSize(15);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(this, R.color.title_text));
            card.addView(title);

            TextView num = new TextView(this);
            num.setText(o.optString("walletNumber"));
            num.setTextSize(20);
            num.setTypeface(null, android.graphics.Typeface.BOLD);
            num.setTextColor(ContextCompat.getColor(this, R.color.accent_blue));
            card.addView(num);

            TextView ins = new TextView(this);
            ins.setText(o.optString("instructions", ""));
            ins.setTextSize(12);
            ins.setTextColor(ContextCompat.getColor(this, R.color.muted_text));
            card.addView(ins);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button copyBtn = new Button(this);
            copyBtn.setText("Copy Number");
            copyBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("wallet", o.optString("walletNumber")));
                Toast.makeText(this, "Number copied", Toast.LENGTH_SHORT).show();
            });
            Button useBtn = new Button(this);
            useBtn.setText(idx == selectedMethod ? "✓ Selected" : "Use");
            useBtn.setOnClickListener(v -> {
                selectedMethod = idx;
                renderMethods();
                updateSelectedInfo();
            });
            row.addView(copyBtn);
            row.addView(useBtn);
            card.addView(row);
            methodList.addView(card);

            View sep = new View(this);
            sep.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12));
            methodList.addView(sep);
        }
    }

    private void renderMine(JSONArray my) {
        myPaymentsList.removeAllViews();
        if (my.length() == 0) {
            TextView t = new TextView(this);
            t.setText("No submissions yet.");
            myPaymentsList.addView(t);
            return;
        }
        for (int i = 0; i < my.length(); i++) {
            JSONObject o = my.optJSONObject(i);
            TextView t = new TextView(this);
            String s = "#" + o.optInt("id") + " " + o.optString("packageName") + " ৳" + o.optInt("amount")
                    + " via " + o.optString("paymentMethodName") + " — " + o.optString("status");
            if ("REJECTED".equals(o.optString("status")) && !o.optString("rejectionReason", "").isEmpty()) {
                s += "\nReason: " + o.optString("rejectionReason");
            }
            s += "\nTxID: " + o.optString("transactionId");
            t.setText(s);
            t.setPadding(12, 12, 12, 12);
            t.setBackgroundColor(ContextCompat.getColor(this, R.color.pkg_row));
            t.setTextColor(ContextCompat.getColor(this, R.color.title_text));
            myPaymentsList.addView(t);
            View sep = new View(this);
            sep.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8));
            myPaymentsList.addView(sep);
        }
    }

    private void updateSelectedInfo() {
        String p = selectedPackage >= 0 ? packages.optJSONObject(selectedPackage).optString("name") : "—";
        String m = selectedMethod >= 0 ? methods.optJSONObject(selectedMethod).optString("name") : "—";
        selectedInfo.setText("Package: " + p + "\nMethod: " + m);
    }

    private void submitPayment() {
        if (selectedPackage < 0 || selectedMethod < 0) {
            Toast.makeText(this, "Select a package and a payment method first", Toast.LENGTH_SHORT).show();
            return;
        }
        String txid = txidInput.getText().toString().trim();
        if (txid.isEmpty()) {
            Toast.makeText(this, "Enter the Transaction ID", Toast.LENGTH_SHORT).show();
            return;
        }
        submitBtn.setEnabled(false);
        statusView.setText("Submitting...");
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("packageId", packages.optJSONObject(selectedPackage).optInt("id"));
                body.put("paymentMethodId", methods.optJSONObject(selectedMethod).optInt("id"));
                body.put("transactionId", txid);
                ApiClient.Resp r = ApiClient.postIdempotent(this, "/api/payments", body, true);
                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    if (r.code == 401) {
                        bounceToActivation("Session expired. Please reactivate.");
                        return;
                    }
                    if (r.code == 403) {
                        bounceToActivation(r.json.optString("error", "Access denied."));
                        return;
                    }
                    if (r.ok()) {
                        statusView.setText("Payment Status: Pending — admin will verify shortly.");
                        txidInput.setText("");
                        Toast.makeText(this, "Submitted. Status: PENDING", Toast.LENGTH_SHORT).show();
                        loadAll();
                    } else {
                        statusView.setText(r.json.optString("error", "Submit failed"));
                        Toast.makeText(this, r.json.optString("error", "Submit failed"), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    submitBtn.setEnabled(true);
                    statusView.setText("Offline: " + e.getMessage());
                });
            }
        });
    }

    private Button styledButton(String label, boolean selected) {
        Button b = new Button(this);
        b.setText((selected ? "✓ " : "") + label);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? 0xFF00A152 : 0xFF6C3CE0));
        b.setTextColor(0xFFFFFFFF);
        return b;
    }

    private void bounceToActivation(String msg) {
        if (isFinishing()) return;
        SessionManager.logout(this);
        try { stopService(new Intent(this, FloatingService.class)); } catch (Exception ignored) {}
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        Intent i = new Intent(this, ActivationActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    /** Fail-closed offline state: without a live access check this screen stays unusable. */
    private void showOfflineBlock() {
        if (isFinishing()) return;
        statusView.setText("Offline — access not verified.");
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cannot verify access")
                .setMessage("The server could not be reached. This screen stays locked until access is verified.")
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> loadAll())
                .setNegativeButton("Logout", (d, w) -> bounceToActivation("Logged out."))
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        // Approval may have landed while away (or access revoked): re-verify.
        if (!SessionManager.isLoggedIn(this)) {
            bounceToActivation("Please activate this device.");
            return;
        }
        net.execute(() -> {
            final AccessGate.Result gate;
            try {
                gate = AccessGate.check(this);
            } catch (Exception ignored) {
                return; // stay; Refresh surfaces the offline block.
            }
            if (gate.allowed) {
                runOnUiThread(() -> {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                });
            } else if (!gate.needsPackage()) {
                final String msg = gate.message.isEmpty() ? "Access denied." : gate.message;
                runOnUiThread(() -> bounceToActivation(msg));
            }
        });
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
