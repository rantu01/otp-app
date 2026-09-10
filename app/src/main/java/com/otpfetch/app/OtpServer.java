package com.otpfetch.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Embedded port of otp-bridge/server.js.
 *
 * Listens on port 3000 and serves:
 *   POST /get-otp  { email, refreshToken, clientId } -> { success, code }
 *   GET  /health   -> { status, watchedAccounts, cachedTokens, cachedOTP }
 *   OPTIONS *      -> CORS preflight
 *
 * Same behaviour as the Node version:
 *  - per-account access-token cache (no OAuth call on every request)
 *  - latest-OTP cache (30s TTL, instant return)
 *  - per-account seen-message dedup set (cap 50)
 *  - background poller (250ms) over all watched accounts
 *  - parallel inbox + junk fetch via Microsoft Graph
 *
 * Zero external dependencies: plain ServerSocket + HttpsURLConnection + org.json
 * (org.json ships with Android).
 */
public class OtpServer {

    private static final String TAG = "OtpServer";
    public static final int PORT = 3000;

    private static final long OTP_CACHE_TTL_MS = 30_000;
    private static final long TOKEN_SAFETY_MARGIN_MS = 300_000;
    private static final int SEEN_LIMIT = 50;
    private static final int POLL_INTERVAL_MS = 250;
    private static final int MAX_RETRIES = 30;
    private static final int RETRY_DELAY_MS = 250;

    public interface LogListener {
        void onLog(String line);
    }

    private static final OtpServer INSTANCE = new OtpServer();
    public static OtpServer getInstance() { return INSTANCE; }
    private OtpServer() {}

    // ---- state (mirrors server.js) ----
    private static final class TokenEntry {
        String accessToken; long expiresAt; String refreshToken; String clientId;
    }
    public static final class OtpEntry {
        public final String code; public final String receivedDateTime;
        public final String messageId; public final long ts;
        OtpEntry(String c, String r, String m, long t) { code = c; receivedDateTime = r; messageId = m; ts = t; }
    }
    private static final class GraphMsg {
        String id = ""; String subject = ""; String bodyPreview = "";
        String receivedDateTime = ""; String from = "";
    }

    private final Map<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();
    private final Map<String, OtpEntry> otpCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seenMessages = new ConcurrentHashMap<>();
    private final Map<String, OtpHelper.Account> watchedAccounts = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService clientPool;
    private ScheduledExecutorService poller;
    private Thread acceptThread;
    private volatile LogListener logListener;

    public void setLogListener(LogListener l) { this.logListener = l; }
    public boolean isRunning() { return running; }
    public int watchedCount() { return watchedAccounts.size(); }

    private void log(String s) {
        Log.i(TAG, s);
        LogListener l = logListener;
        if (l != null) {
            try { l.onLog(s); } catch (Exception ignored) {}
        }
    }

    // ================= lifecycle =================

    public synchronized void start() throws Exception {
        if (running) return;
        serverSocket = new ServerSocket(PORT);
        clientPool = Executors.newCachedThreadPool();
        running = true;
        startPoller();
        acceptThread = new Thread(this::acceptLoop, "otp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("Super-Fast OTP Bridge running on http://localhost:" + PORT);
    }

    public synchronized void stop() {
        running = false;
        if (poller != null) { poller.shutdownNow(); poller = null; }
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        if (clientPool != null) { clientPool.shutdownNow(); clientPool = null; }
        log("Server stopped.");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                ExecutorService pool = clientPool;
                if (pool != null) pool.execute(() -> handleClient(s));
                else try { s.close(); } catch (Exception ignored) {}
            } catch (Exception e) {
                if (running) log("Accept error: " + e.getMessage());
            }
        }
    }

    // ================= background poller =================

    private void startPoller() {
        if (poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(() -> {
            try {
                if (watchedAccounts.isEmpty()) return;
                List<Future<?>> futures = new ArrayList<>();
                ExecutorService exec = clientPool;
                if (exec == null) return;
                for (String email : watchedAccounts.keySet()) {
                    futures.add(exec.submit(() -> { pollAccount(email); return null; }));
                }
                for (Future<?> f : futures) {
                    try { f.get(6, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private OtpEntry pollAccount(String email) {
        OtpHelper.Account acc = watchedAccounts.get(email);
        if (acc == null) return null;
        try {
            String token = getAccessToken(acc.email, acc.clientId, acc.refreshToken);
            if (token == null) { watchedAccounts.remove(email); return null; }
            List<GraphMsg> messages = fetchInboxAndJunk(token);
            OtpEntry result = tryExtractOTP(messages, email);
            if (result != null) log("OTP caught for " + email + ": " + result.code);
            return result;
        } catch (Exception ignored) {}
        return null;
    }

    // ================= token management =================

    private String refreshAccessToken(String clientId, String refreshToken) {
        HttpsURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL("https://login.microsoftonline.com/common/oauth2/v2.0/token");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "client_id=" + URLEncoder.encode(clientId.trim(), "UTF-8")
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(refreshToken.trim(), "UTF-8")
                    + "&scope=" + URLEncoder.encode("https://graph.microsoft.com/Mail.Read", "UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(in);
            if (code >= 200 && code < 300) {
                JSONObject obj = new JSONObject(resp);
                if (obj.has("access_token")) {
                    String newRefresh = obj.optString("refresh_token", refreshToken);
                    long expiresIn = obj.optLong("expires_in", 3300);
                    // stash rotated refresh token via cache update in caller
                    TokenEntry te = new TokenEntry();
                    te.accessToken = obj.getString("access_token");
                    te.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
                    te.refreshToken = newRefresh;
                    te.clientId = clientId;
                    pendingToken.set(te);
                    return te.accessToken;
                }
            } else {
                log("Token refresh failed: HTTP " + code);
            }
        } catch (Exception e) {
            log("Token refresh failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private final ThreadLocal<TokenEntry> pendingToken = new ThreadLocal<>();

    private synchronized String getAccessToken(String email, String clientId, String refreshToken) {
        TokenEntry cached = tokenCache.get(email);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now + TOKEN_SAFETY_MARGIN_MS) {
            return cached.accessToken;
        }
        String latestRefresh = (cached != null && cached.refreshToken != null) ? cached.refreshToken : refreshToken;
        String token = refreshAccessToken(clientId, latestRefresh);
        TokenEntry te = pendingToken.get();
        pendingToken.remove();
        if (token == null) { tokenCache.remove(email); return null; }
        if (te == null) {
            te = new TokenEntry();
            te.accessToken = token;
            te.expiresAt = now + 3300 * 1000L;
            te.refreshToken = latestRefresh;
            te.clientId = clientId;
        }
        // keep watching with the newest refresh token (Microsoft may rotate it)
        OtpHelper.Account existing = watchedAccounts.get(email);
        if (existing != null && te.refreshToken != null && !te.refreshToken.equals(existing.refreshToken)) {
            watchedAccounts.put(email, new OtpHelper.Account(email, te.refreshToken, clientId));
        }
        tokenCache.put(email, te);
        log("Token refreshed for " + email);
        return token;
    }

    // ================= graph fetching =================

    private static final String GRAPH_QUERY =
            "$top=10&$select=subject,bodyPreview,receivedDateTime,from,id&$orderby=receivedDateTime desc";

    private List<GraphMsg> fetchInboxAndJunk(String accessToken) {
        ExecutorService exec = clientPool;
        List<GraphMsg> out = new ArrayList<>();
        try {
            Future<List<GraphMsg>> inboxF = null, junkF = null;
            if (exec != null) {
                inboxF = exec.submit(() -> graphGet(accessToken, "inbox"));
                junkF = exec.submit(() -> graphGet(accessToken, "junkemail"));
            }
            List<GraphMsg> inbox = (inboxF != null) ? getQuiet(inboxF) : graphGet(accessToken, "inbox");
            List<GraphMsg> junk = (junkF != null) ? getQuiet(junkF) : graphGet(accessToken, "junkemail");
            if (inbox != null) out.addAll(inbox);
            if (junk != null) out.addAll(junk);
        } catch (Exception ignored) {}
        Collections.sort(out, new Comparator<GraphMsg>() {
            @Override public int compare(GraphMsg a, GraphMsg b) {
                return b.receivedDateTime.compareTo(a.receivedDateTime);
            }
        });
        return out;
    }

    private List<GraphMsg> getQuiet(Future<List<GraphMsg>> f) {
        try { List<GraphMsg> r = f.get(4, TimeUnit.SECONDS); return r != null ? r : new ArrayList<GraphMsg>(); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private List<GraphMsg> graphGet(String token, String folder) {
        HttpsURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(
                    "https://graph.microsoft.com/v1.0/me/mailFolders/" + folder + "/messages?" + GRAPH_QUERY);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Prefer", "outlook.body-content-type=\"text\"");
            conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return new ArrayList<>();
            String resp = readAll(conn.getInputStream());
            JSONObject obj = new JSONObject(resp);
            JSONArray arr = obj.optJSONArray("value");
            List<GraphMsg> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    GraphMsg g = new GraphMsg();
                    g.id = m.optString("id", "");
                    g.subject = m.optString("subject", "");
                    g.bodyPreview = m.optString("bodyPreview", "");
                    g.receivedDateTime = m.optString("receivedDateTime", "");
                    JSONObject from = m.optJSONObject("from");
                    if (from != null) {
                        JSONObject ea = from.optJSONObject("emailAddress");
                        if (ea != null) g.from = ea.optString("address", "").toLowerCase();
                    }
                    list.add(g);
                }
            }
            return list;
        } catch (Exception ignored) {
            return new ArrayList<>();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ================= OTP detection =================

    private OtpEntry tryExtractOTP(List<GraphMsg> messages, String email) {
        Set<String> seen = seenMessages.get(email);
        if (seen == null) {
            seen = Collections.synchronizedSet(new LinkedHashSet<String>());
            seenMessages.put(email, seen);
        }
        synchronized (seen) {
            for (GraphMsg msg : messages) {
                if (msg.id == null || msg.id.isEmpty() || seen.contains(msg.id)) continue;
                seen.add(msg.id);
                while (seen.size() > SEEN_LIMIT) {
                    String first = seen.iterator().next();
                    seen.remove(first);
                }
                String sender = msg.from != null ? msg.from : "";
                String subject = msg.subject != null ? msg.subject : "";
                if (sender.contains("microsoft") || sender.contains("outlook") || subject.contains("Welcome")) {
                    continue;
                }
                String code = OtpHelper.extractFacebookOTP(subject + " " + (msg.bodyPreview != null ? msg.bodyPreview : ""));
                if (code != null) {
                    OtpEntry e = new OtpEntry(code, msg.receivedDateTime, msg.id, System.currentTimeMillis());
                    otpCache.put(email, e);
                    return e;
                }
            }
        }
        return null;
    }

    // ================= public fetch (used by HTTP route) =================

    public static final class FetchResult {
        public final boolean success; public final String code; public final String error;
        FetchResult(boolean s, String c, String e) { success = s; code = c; error = e; }
    }

    /** Blocking OTP fetch with the same aggressive retry loop as server.js. */
    public FetchResult fetchOtp(String email, String refreshToken, String clientId) {
        log("OTP request: " + email);
        OtpEntry cached = otpCache.get(email);
        if (cached != null && System.currentTimeMillis() - cached.ts < OTP_CACHE_TTL_MS) {
            log("Instant cache hit for " + email + ": " + cached.code);
            return new FetchResult(true, cached.code, null);
        }
        watchedAccounts.put(email, new OtpHelper.Account(email, refreshToken, clientId));

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            OtpEntry c = otpCache.get(email);
            if (c != null && System.currentTimeMillis() - c.ts < OTP_CACHE_TTL_MS) {
                return new FetchResult(true, c.code, null);
            }
            String token = getAccessToken(email, clientId, refreshToken);
            if (token == null) {
                return new FetchResult(false, null, "Token refresh failed. Refresh Token might be dead.");
            }
            List<GraphMsg> messages = fetchInboxAndJunk(token);
            OtpEntry result = tryExtractOTP(messages, email);
            if (result != null) {
                log("OTP found on attempt " + attempt + ": " + result.code);
                return new FetchResult(true, result.code, null);
            }
            if (attempt < MAX_RETRIES) {
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException e) { break; }
            }
        }
        return new FetchResult(false, null, "OTP not arrived yet. Server/email delay.");
    }

    // ================= minimal HTTP layer =================

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) { socket.close(); return; }
            String[] rl = requestLine.split(" ");
            if (rl.length < 2) { socket.close(); return; }
            String method = rl[0].toUpperCase();
            String path = rl[1].split("\\?")[0];

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            char[] bodyChars = new char[Math.max(0, contentLength)];
            int read = 0;
            while (read < contentLength) {
                int r = reader.read(bodyChars, read, contentLength - read);
                if (r == -1) break;
                read += r;
            }
            String body = new String(bodyChars, 0, read);

            if ("OPTIONS".equals(method)) {
                sendJson(out, 204, "");
                return;
            }
            if ("POST".equals(method) && "/get-otp".equals(path)) {
                handleGetOtp(out, body);
                return;
            }
            if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject o = new JSONObject();
                o.put("status", "ok");
                o.put("watchedAccounts", watchedAccounts.size());
                o.put("cachedTokens", tokenCache.size());
                o.put("cachedOTP", otpCache.size());
                o.put("pollerRunning", poller != null && !poller.isShutdown());
                sendJson(out, 200, o.toString());
                return;
            }
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("error", "Not found");
            sendJson(out, 404, err.toString());
        } catch (Exception e) {
            Log.w(TAG, "client error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void handleGetOtp(OutputStream out, String body) {
        try {
            JSONObject req = new JSONObject(body);
            String email = req.optString("email", "").trim();
            String refreshToken = req.optString("refreshToken", "").trim();
            String clientId = req.optString("clientId", "").trim();
            if (email.isEmpty() || refreshToken.isEmpty() || clientId.isEmpty()) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("error", "Missing required fields");
                sendJson(out, 400, err.toString());
                return;
            }
            FetchResult r = fetchOtp(email, refreshToken, clientId);
            JSONObject resp = new JSONObject();
            if (r.success) {
                resp.put("success", true);
                resp.put("code", r.code);
                sendJson(out, 200, resp.toString());
            } else if (r.error != null && r.error.contains("Token refresh")) {
                resp.put("success", false);
                resp.put("error", r.error);
                sendJson(out, 401, resp.toString());
            } else {
                resp.put("success", false);
                resp.put("error", r.error);
                sendJson(out, 200, resp.toString());
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("error", e.getMessage());
                sendJson(out, 500, err.toString());
            } catch (Exception ignored) {}
        }
    }

    private void sendJson(OutputStream out, int status, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 401 ? "Unauthorized" : status == 404 ? "Not Found"
                : status == 500 ? "Internal Server Error" : "No Content";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");
        h.append("Content-Type: application/json; charset=utf-8\r\n");
        h.append("Content-Length: ").append(bytes.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: Content-Type\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }
}
