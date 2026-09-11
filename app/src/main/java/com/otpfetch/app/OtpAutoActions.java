package com.otpfetch.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Central auto-actions executed whenever an OTP is received:
 *  1. Auto-copy the OTP to the clipboard (no "Get OTP" tap needed).
 *  2. Auto-open the user-selected installed app (if one was saved).
 *
 * Preferences are shared with MainActivity ("otp_fetch_prefs") so the
 * MainActivity picker and the background OtpServer stay in sync.
 */
public final class OtpAutoActions {

    private static final String TAG = "OtpAutoActions";

    private static final String PREFS = "otp_fetch_prefs";
    private static final String KEY_AUTO_COPY = "autoCopyEnabled";
    private static final String KEY_AUTO_OPEN_PKG = "autoOpenPackage";
    private static final String KEY_AUTO_OPEN_LABEL = "autoOpenLabel";

    private OtpAutoActions() {}

    // ---------------- prefs ----------------

    public static boolean isAutoCopyEnabled(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_COPY, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setAutoCopyEnabled(Context ctx, boolean enabled) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_AUTO_COPY, enabled).apply();
        } catch (Exception ignored) {}
    }

    public static String getSelectedPackage(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_AUTO_OPEN_PKG, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String getSelectedLabel(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_AUTO_OPEN_LABEL, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void saveSelectedApp(Context ctx, String packageName, String label) {
        try {
            SharedPreferences.Editor ed =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (packageName == null || packageName.isEmpty()) {
                ed.remove(KEY_AUTO_OPEN_PKG).remove(KEY_AUTO_OPEN_LABEL);
            } else {
                ed.putString(KEY_AUTO_OPEN_PKG, packageName);
                ed.putString(KEY_AUTO_OPEN_LABEL, label == null ? packageName : label);
            }
            ed.apply();
        } catch (Exception ignored) {}
    }

    public static void clearSelectedApp(Context ctx) {
        saveSelectedApp(ctx, "", "");
    }

    // ---------------- main entry point ----------------

    /**
     * Called centrally from OtpServer whenever an OTP arrives (both fresh
     * auto-detected codes and manual fetch results). Safe to call from any
     * thread; clipboard work is marshalled to the main looper.
     */
    public static void handleAutoOtp(Context ctx, String code) {
        if (ctx == null || code == null || code.isEmpty()) return;
        final Context appCtx = ctx.getApplicationContext();
        // Clipboard must run on the main thread on some OEM ROMs.
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (isAutoCopyEnabled(appCtx)) copyToClipboard(appCtx, code);
                openSelectedApp(appCtx);
            });
        } catch (Exception e) {
            // Fallback: try synchronously (e.g. no looper in unit tests).
            try {
                if (isAutoCopyEnabled(appCtx)) copyToClipboard(appCtx, code);
                openSelectedApp(appCtx);
            } catch (Exception ignored) {}
        }
    }

    public static void copyToClipboard(Context ctx, String text) {
        if (ctx == null || text == null || text.isEmpty()) return;
        try {
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("otp", text));
        } catch (Exception e) {
            Log.w(TAG, "clipboard copy failed: " + e.getMessage());
        }
    }

    /** Launches the user-selected app, if any. No-op when nothing is saved. */
    public static void openSelectedApp(Context ctx) {
        if (ctx == null) return;
        String pkg = getSelectedPackage(ctx);
        if (pkg == null || pkg.isEmpty()) return;
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                Log.w(TAG, "no launch intent for " + pkg);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(launch);
            Log.i(TAG, "auto-opened " + pkg);
        } catch (Exception e) {
            Log.w(TAG, "auto-open failed for " + pkg + ": " + e.getMessage());
        }
    }

    // ---------------- installed-app listing ----------------

    public static final class AppEntry {
        public final String label;
        public final String packageName;
        public AppEntry(String label, String packageName) {
            this.label = label == null ? packageName : label;
            this.packageName = packageName;
        }
        @Override public String toString() { return label + "\n" + packageName; }
    }

    /**
     * Returns launchable installed apps sorted A-Z by label.
     * Runs PackageManager queries; call off the main thread.
     */
    public static List<AppEntry> getInstalledApps(Context ctx) {
        List<AppEntry> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps =
                    pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo ai : apps) {
                try {
                    // Only user-launchable apps make sense for auto-open.
                    if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                    String label = String.valueOf(pm.getApplicationLabel(ai));
                    out.add(new AppEntry(label, ai.packageName));
                } catch (Exception ignored) {}
            }
            Collections.sort(out, new Comparator<AppEntry>() {
                @Override public int compare(AppEntry a, AppEntry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "getInstalledApps failed: " + e.getMessage());
        }
        return out;
    }
}
