package com.otpfetch.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-app auto-fetch: as soon as a valid account line
 * (e.g. "email|[password]|[token]|uuid") is typed/pasted in EITHER the
 * main app or the floating popup, an OTP fetch starts automatically —
 * no "Get OTP" tap needed. Both UIs stay in sync through the shared
 * prefs state plus OtpServer listeners.
 *
 * Debounced (800ms) so typing doesn't spam Graph, and guarded so the
 * same data isn't re-fetched within 30s (echo from the other UI, or
 * reopening the popup, won't double-fetch).
 */
public final class AutoFetchManager {

    private static final String TAG = "AutoFetch";
    private static final long DEBOUNCE_MS = 800;
    private static final long REFIRE_TTL_MS = 30_000;

    public interface StatusListener {
        void onAutoFetchStarted(String email);
        void onAutoFetchError(String email, String error);
    }

    private static final CopyOnWriteArrayList<StatusListener> listeners =
            new CopyOnWriteArrayList<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService bg = Executors.newSingleThreadExecutor();

    private static Runnable pending;
    private static String lastFiredData = "";
    private static long lastFiredAt = 0;

    private AutoFetchManager() {}

    public static void addStatusListener(StatusListener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public static void removeStatusListener(StatusListener l) {
        if (l != null) listeners.remove(l);
    }

    /** Call from either UI's text watcher (user edits only, not synced echoes). */
    public static synchronized void onAccountDataChanged(Context ctx, String data) {
        try {
            if (pending != null) handler.removeCallbacks(pending);
        } catch (Exception ignored) {}
        pending = null;
        if (ctx == null || data == null || data.trim().isEmpty()) {
            // Cleared: allow the same data to fetch again when re-pasted.
            lastFiredData = "";
            lastFiredAt = 0;
            return;
        }
        final String snapshot = data.trim();
        final OtpHelper.Account acc;
        try {
            acc = OtpHelper.parseAccountData(snapshot);
        } catch (IllegalArgumentException e) {
            return; // not a complete account line yet — wait for more input
        }
        final Context appCtx = ctx.getApplicationContext();
        for (StatusListener l : listeners) {
            try { l.onAutoFetchStarted(acc.email); } catch (Exception ignored) {}
        }
        pending = () -> doFetch(appCtx, snapshot);
        try {
            handler.postDelayed(pending, DEBOUNCE_MS);
        } catch (Exception e) {
            doFetch(appCtx, snapshot);
        }
    }

    private static void doFetch(Context appCtx, String data) {
        synchronized (AutoFetchManager.class) {
            long now = System.currentTimeMillis();
            if (data.equals(lastFiredData) && now - lastFiredAt < REFIRE_TTL_MS) {
                return; // same data already fetching/fetched — skip duplicate
            }
            lastFiredData = data;
            lastFiredAt = now;
        }
        bg.execute(() -> {
            try {
                OtpHelper.Account acc = OtpHelper.parseAccountData(data);
                if (!OtpServer.getInstance().isRunning()) {
                    try {
                        OtpServer.getInstance().start();
                    } catch (Exception e) {
                        notifyError(acc.email, "Server failed to start: " + e.getMessage());
                        return;
                    }
                }
                OtpServer.FetchResult r = OtpServer.getInstance().fetchOtp(acc);
                // Success fans out via OtpServer listeners (UI + copy + open app).
                if (!r.success) {
                    notifyError(acc.email, r.error != null ? r.error : "OTP Not Found");
                }
            } catch (Exception e) {
                Log.w(TAG, "auto-fetch failed: " + e.getMessage());
                notifyError("", "Failed to fetch OTP: " + e.getMessage());
            }
        });
    }

    private static void notifyError(String email, String error) {
        for (StatusListener l : listeners) {
            try { l.onAutoFetchError(email, error); } catch (Exception ignored) {}
        }
    }
}
