package com.otpfetch.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Dependency-free REST client for the shared backend.
 * All dynamic data (packages, wallet numbers, versions) comes from here —
 * nothing payment-related is hardcoded in the UI.
 */
public final class ApiClient {
    private ApiClient() {}

    public static final class Resp {
        public final int code;
        public final JSONObject json;
        Resp(int c, JSONObject j) { code = c; json = j; }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    private static Resp call(Context ctx, String method, String path, JSONObject body, boolean auth, String idemKey) throws Exception {
        String url = SessionManager.getBaseUrl(ctx) + path;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (auth) {
            String t = SessionManager.getToken(ctx);
            if (t != null && !t.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + t);
        }
        if (idemKey != null) conn.setRequestProperty("Idempotency-Key", idemKey);
        if (body != null) {
            conn.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
        }
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
        JSONObject json;
        try {
            json = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        } catch (Exception e) {
            json = new JSONObject();
            json.put("_raw", sb.toString());
        }
        return new Resp(code, json);
    }

    public static Resp get(Context ctx, String path, boolean auth) throws Exception {
        return call(ctx, "GET", path, null, auth, null);
    }

    public static Resp post(Context ctx, String path, JSONObject body, boolean auth) throws Exception {
        return call(ctx, "POST", path, body, auth, null);
    }

    public static Resp postIdempotent(Context ctx, String path, JSONObject body, boolean auth) throws Exception {
        return call(ctx, "POST", path, body, auth, UUID.randomUUID().toString());
    }

    /** App version name, e.g. "1.0" from app/build.gradle. */
    public static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    public static int appVersionCode(Context ctx) {
        try {
            android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
