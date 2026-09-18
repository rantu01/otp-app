package com.otpfetch.app;

import android.content.Context;

import org.json.JSONObject;

/**
 * Single source of truth for backend-validated access.
 *
 * Every entry point (Auth/Main/Package/FloatingService) routes through here,
 * so approval status is always re-checked server-side — no cached flag or
 * back-stack trick can bypass it.
 *
 * Routing contract (matches backend evaluateAccess reasons):
 *  - allowed                    -> MainActivity (Home)
 *  - NO_PACKAGE / PENDING       -> PackageActivity (purchase/approval flow only;
 *                                  pending accounts reach packages so the admin
 *                                  can approve them via payment verification)
 *  - DISABLED / ACCESS_DENIED / NO_ACCOUNT / SESSION_EXPIRED / anything else
 *                               -> ActivationActivity (fully locked out, session cleared)
 *  - HTTP 401                   -> session dead: logout + ActivationActivity
 *  - network error              -> fail CLOSED (Retry/Logout, never "Later")
 */
public final class AccessGate {
    private AccessGate() {}

    public static final class Result {
        public final boolean allowed;
        public final String reason;
        public final String message;
        public final String packageName;
        public final String expireDate;
        public final int httpCode;

        Result(boolean allowed, String reason, String message,
               String packageName, String expireDate, int httpCode) {
            this.allowed = allowed;
            this.reason = reason == null ? "UNKNOWN" : reason;
            this.message = message == null ? "" : message;
            this.packageName = packageName == null ? "" : packageName;
            this.expireDate = expireDate == null ? "" : expireDate;
            this.httpCode = httpCode;
        }

        /** Active account that only lacks a subscription -> purchase flow. */
        public boolean isNoPackage() {
            return !allowed && "NO_PACKAGE".equals(reason);
        }

        /**
         * May enter the package/purchase flow but never Home: fresh accounts
         * awaiting admin approval (PENDING) and active accounts without a
         * subscription (NO_PACKAGE).
         */
        public boolean needsPackage() {
            return isNoPackage() || (!allowed && "PENDING".equals(reason));
        }
    }

    /**
     * Live server check. Throws on network failure (caller must fail closed).
     * Never returns null.
     */
    public static Result check(Context ctx) throws Exception {
        ApiClient.Resp r = ApiClient.get(ctx, "/api/access/status", true);
        if (r.code == 401) {
            return new Result(false, "SESSION_EXPIRED",
                    "Session expired. Please reactivate.", "", "", r.code);
        }
        if (!r.ok()) {
            JSONObject access = r.json.optJSONObject("access");
            if (access != null) {
                return fromAccess(access, r.code);
            }
            // e.g. 403 from accessRequired-style responses without access body
            String err = r.json.optString("error", "Access denied.");
            String code = r.json.optString("code", "UNKNOWN");
            return new Result(false, code, err, "", "", r.code);
        }
        JSONObject access = r.json.optJSONObject("access");
        if (access == null) {
            return new Result(false, "UNKNOWN", "Could not verify access.", "", "", r.code);
        }
        return fromAccess(access, r.code);
    }

    public static Result fromAccess(JSONObject access, int httpCode) {
        boolean allowed = access.optBoolean("allowed", false);
        String reason = access.optString("reason", allowed ? "OK" : "UNKNOWN");
        String message = access.optString("message",
                allowed ? "Access granted." : "Access denied.");
        return new Result(allowed, reason, message,
                access.optString("packageName", ""),
                access.optString("packageExpireDate", ""), httpCode);
    }

    /** Parse the `access` object bundled in auth (login/register/device) responses. */
    public static Result fromAuthResponse(JSONObject resp) {
        JSONObject access = resp == null ? null : resp.optJSONObject("access");
        if (access == null) {
            return new Result(false, "UNKNOWN", "Could not verify access.", "", "", 200);
        }
        return fromAccess(access, 200);
    }
}
