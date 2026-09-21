package com.otpfetch.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Minimal JWT session store (SharedPreferences).
 * Separate keys from the OTP prefs so access data never clobbers OTP state.
 */
public final class SessionManager {
    private static final String PREFS = "otp_access_prefs";
    private static final String KEY_TOKEN = "apiToken";
    private static final String KEY_USER = "apiUserJson";
    private static final String KEY_BASE = "apiBaseUrl";
    private static final String KEY_DEVICE_FALLBACK = "deviceIdFallback";
    /** Central default backend URL (see ApiConfig — change it in one place). */
    public static final String DEFAULT_BASE = ApiConfig.DEFAULT_BASE_URL;

    private SessionManager() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getBaseUrl(Context ctx) {
        String v = prefs(ctx).getString(KEY_BASE, DEFAULT_BASE);
        if (v == null || v.isEmpty()) return DEFAULT_BASE;
        v = v.trim().replaceAll("/+$", "");
        // Migrate stale manual entries (emulator loopback / LAN IPs users had
        // to type in before the URL was centralized) to the central default.
        if (v.contains("10.0.2.2") || v.contains("127.0.0.1") || v.contains("localhost")
                || v.matches("https?://192\\.168\\..*") || v.matches("https?://10\\..*")) {
            return DEFAULT_BASE;
        }
        return v;
    }

    public static void setBaseUrl(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_BASE, url == null ? DEFAULT_BASE : url.trim().replaceAll("/+$", "")).apply();
    }

    public static String getToken(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, "");
    }

    public static boolean isLoggedIn(Context ctx) {
        String t = getToken(ctx);
        return t != null && !t.isEmpty();
    }

    public static void saveLogin(Context ctx, String token, JSONObject user) {
        prefs(ctx).edit()
                .putString(KEY_TOKEN, token == null ? "" : token)
                .putString(KEY_USER, user == null ? "" : user.toString())
                .apply();
    }

    public static JSONObject getUser(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY_USER, "");
            if (s == null || s.isEmpty()) return null;
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static void logout(Context ctx) {
        prefs(ctx).edit().remove(KEY_TOKEN).remove(KEY_USER).apply();
    }

    /**
     * Stable per-install fallback when the OS provides no usable ANDROID_ID.
     * Generated once and persisted, so the activation screen is stable.
     */
    public static String getOrCreateFallbackDeviceId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String v = p.getString(KEY_DEVICE_FALLBACK, "");
        if (v == null || !v.matches("(?i)[a-f0-9]{16}")) {
            String rand = java.util.UUID.randomUUID().toString().replace("-", "").toLowerCase();
            v = rand.substring(0, 16);
            p.edit().putString(KEY_DEVICE_FALLBACK, v).apply();
        }
        return v;
    }
}
