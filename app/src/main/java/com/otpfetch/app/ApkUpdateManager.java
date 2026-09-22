package com.otpfetch.app;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Handles the full APK update lifecycle:
 * 1. Check latest version from backend
 * 2. Download APK with progress notification
 * 3. Launch Android Package Installer via FileProvider
 *
 * Uses manual download (HttpURLConnection) so we can show progress
 * and handle errors without relying on DownloadManager which exposes
 * the file via a content:// URI that older Android versions struggle with.
 * The file is saved in app-specific cache (no external storage permission needed).
 */
public class ApkUpdateManager {

    public interface Callback {
        void onResult(String message, boolean success);
    }

    private static final String CHANNEL_ID = "apk_update";
    private static final String NOTIF_TAG = "apk_update";

    /** Step 1: Check for updates. Returns the latest release JSON or null. */
    public static JSONObject checkForUpdate(Context ctx) throws Exception {
        String url = SessionManager.getBaseUrl(ctx) + "/api/app-update/latest";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        String token = SessionManager.getToken(ctx);
        if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
        }
        conn.disconnect();
        if (code < 200 || code >= 300) return null;
        JSONObject json = new JSONObject(sb.toString());
        if (!json.optBoolean("updateAvailable", false)) return null;
        return json;
    }

    /** Step 2: Download APK to app-specific cache. Progress via notification. */
    public static void downloadApk(Context ctx, String downloadUrl, String fileName, Callback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            java.io.InputStream is = null;
            java.io.FileOutputStream fos = null;
            try {
                ensureChannel(ctx);
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                int totalSize = 0;
                int downloaded = 0;
                File outFile = new File(ctx.getCacheDir(), fileName);
                String dl = downloadUrl;
                String dlLower = dl == null ? "" : dl.toLowerCase();
                String fullUrl = (dlLower.startsWith("http://") || dlLower.startsWith("https://"))
                        ? dl
                        : SessionManager.getBaseUrl(ctx) + dl;
                URL url = new URL(fullUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                String token = SessionManager.getToken(ctx);
                if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
                totalSize = conn.getContentLength();
                int code = conn.getResponseCode();
                if (code != 200) {
                    final String msg = "Download failed (HTTP " + code + ")";
                    showProgress(ctx, nm, fileName, -1, totalSize, msg);
                    if (cb != null) cb.onResult(msg, false);
                    return;
                }
                is = conn.getInputStream();
                fos = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                long lastNotify = 0;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    downloaded += len;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 500) {
                        lastNotify = now;
                        showProgress(ctx, nm, fileName, downloaded, totalSize, "Downloading...");
                    }
                }
                fos.flush(); fos.close(); fos = null;
                is.close(); is = null;
                conn.disconnect(); conn = null;
                if (!outFile.exists() || outFile.length() == 0) {
                    final String msg = "Downloaded file is empty or missing";
                    showProgress(ctx, nm, fileName, -1, totalSize, msg);
                    if (cb != null) cb.onResult(msg, false);
                    return;
                }
                showProgress(ctx, nm, fileName, downloaded, totalSize, "Download complete — installing...");
                if (cb != null) cb.onResult(outFile.getAbsolutePath(), true);
            } catch (Exception e) {
                if (fos != null) try { fos.close(); } catch (Exception ignored) {}
                if (is != null) try { is.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
                final String msg = "Download failed: " + e.getMessage();
                try { ensureChannel(ctx); } catch (Exception ignored) {}
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                showProgress(ctx, nm, fileName, -1, 0, msg);
                if (cb != null) cb.onResult(msg, false);
            }
        }).start();
    }

    /** Step 3: Launch Android Package Installer for the downloaded APK. */
    public static void installApk(Context ctx, String apkPath) {
        File file = new File(apkPath);
        if (!file.exists()) {
            Toast.makeText(ctx, "APK file not found", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", file);
        } else {
            uri = Uri.fromFile(file);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(ctx, "No installer available: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void showProgress(Context ctx, NotificationManager nm, String name, int downloaded, int total, String status) {
        try {
            int pct = total > 0 ? (int) ((downloaded * 100L) / total) : -1;
            String text = pct >= 0 ? status + " " + pct + "%" : status;
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("Updating " + name)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            if (nm != null) nm.notify(NOTIF_TAG, 1, b.build());
        } catch (Exception ignored) {}
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
            if (ch == null) {
                ch = new NotificationChannel(CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
        }
    }

    /** Clean up the downloaded APK file after installation. */
    public static void cleanApk(Context ctx, String apkPath) {
        if (apkPath == null || apkPath.isEmpty()) return;
        try { new File(apkPath).delete(); } catch (Exception ignored) {}
    }
}