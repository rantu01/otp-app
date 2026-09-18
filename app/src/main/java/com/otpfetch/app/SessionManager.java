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
    private static final String KEY_DEVICE = "deviceId";
    private static final String KEY_DEVICE_FALLBACK = "deviceIdFallback";
    /** Emulator loopback to host PC; real devices must set LAN IP in Auth screen. */
    public static final String DEFAULT_BASE = "http://10.0.2.2:4000";

    private SessionManager() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getBaseUrl(Context ctx) {
        String v = prefs(ctx).getString(KEY_BASE, DEFAULT_BASE);
        return (v == null || v.isEmpty()) ? DEFAULT_BASE : v.replaceAll("/+$", "");
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

    /** Device ID submitted on the activation screen (manual entry or auto-detected). */
    public static String getDeviceId(Context ctx) {
        return prefs(ctx).getString(KEY_DEVICE, "");
    }

    public static void setDeviceId(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY_DEVICE, id == null ? "" : id.trim()).apply();
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
