package com.otpfetch.app;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared logic ported from the Chrome extension (popup.js / background.js)
 * and the Node bridge (server.js).
 *
 * - Account-line parsing: "Email|RefreshToken|ClientId" in any order
 * - Facebook OTP extraction (5-6 digits)
 * - US name generator lists
 */
public final class OtpHelper {

    private OtpHelper() {}

    public static final class Account {
        public final String email;
        public final String refreshToken;
        public final String clientId;
        public final String password;
        public Account(String email, String refreshToken, String clientId) {
            this(email, refreshToken, clientId, "");
        }
        public Account(String email, String refreshToken, String clientId, String password) {
            this.email = email == null ? "" : email;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.clientId = clientId == null ? "" : clientId;
            this.password = password == null ? "" : password;
        }
    }

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^.+@.+\\..+$");

    private static boolean isEmail(String s) {
        return s != null && s.contains("@") && EMAIL_PATTERN.matcher(s).matches();
    }

    private static boolean isUuid(String s) {
        return s != null && UUID_PATTERN.matcher(s).matches();
    }

    /** Fallback public client IDs tried when pasted data has no UUID (e.g. Type 2). */
    public static final String[] FALLBACK_CLIENT_IDS = {
            "9e5f94bc-e8a4-4e73-b8be-63364c29d753",
            "d3590ed6-52b3-4102-aeff-aad2292ab01c",
            "1fec8e78-bce4-4aaf-ab1b-5451cc387264",
            "04b07795-8ddb-461a-bbee-02f9e1bf7b46",
    };

    /**
     * Parses raw pasted data. Supports:
     *  - Classic: "Email|RefreshToken|ClientId" (any order)
     *  - Type 1: "email|password|token|UUID|secondary-email" (5 parts; token may be absent)
     *  - Type 2: "email|password|token" (no UUID, no secondary)
     *  First e-mail wins (secondary e-mail is ignored), first UUID wins,
     *  longest long-part wins as the token, first remaining short part is the password.
     *  Throws IllegalArgumentException on bad format.
     */
    public static Account parseAccountData(String rawData) {
        if (rawData == null) throw new IllegalArgumentException("Invalid Format! Email, Refresh Token or Client ID missing.");
        String[] rawParts = rawData.split("\\|");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : rawParts) {
            String t = p == null ? "" : p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        String email = "";
        for (String part : parts) {
            if (isEmail(part)) { email = part; break; }
        }
        String clientId = "";
        for (String part : parts) {
            if (isUuid(part)) { clientId = part; break; }
        }
        // Legacy heuristic fallback: 36-char dashed part (non-email) counts as clientId.
        if (clientId.isEmpty()) {
            for (String part : parts) {
                if (!isEmail(part) && part.length() == 36 && part.contains("-")) { clientId = part; break; }
            }
        }
        String refreshToken = "";
        for (String part : parts) {
            if (isEmail(part)) continue;
            if (!clientId.isEmpty() && part.equals(clientId)) continue;
            if (isUuid(part)) continue;
            if (part.length() > 30 && part.length() > refreshToken.length()) {
                refreshToken = part;
            }
        }
        String password = "";
        for (String part : parts) {
            if (isEmail(part)) continue;
            if (!clientId.isEmpty() && part.equals(clientId)) continue;
            if (!refreshToken.isEmpty() && part.equals(refreshToken)) continue;
            if (isUuid(part)) continue;
            if (part.length() > 30) continue; // long non-token leftovers are not passwords
            if (part.length() >= 4) { password = part; break; }
        }
        if (email.isEmpty() || (refreshToken.isEmpty() && password.isEmpty())) {
            throw new IllegalArgumentException("Invalid Format! Email, Refresh Token or Client ID missing.");
        }
        // Password-only flow still needs a clientId (ROPC). Token flow can fall back.
        if (refreshToken.isEmpty() && clientId.isEmpty()) {
            throw new IllegalArgumentException("Invalid Format! Email, Refresh Token or Client ID missing.");
        }
        return new Account(email, refreshToken, clientId, password);
    }

    /** Extracts just the e-mail (first e-mail wins, so secondary e-mails are ignored). Returns "" if none. */
    public static String extractEmail(String rawData) {
        if (rawData == null) return "";
        for (String p : rawData.split("\\|")) {
            String part = p == null ? "" : p.trim();
            if (isEmail(part)) return part;
        }
        return "";
    }

    // Facebook sends either a 5-digit or a 6-digit code. A newer 6-digit
    // mail often arrives AFTER an older 5-digit one — using the stale
    // 5-digit code gets the account suspended. So: 6-digit always wins
    // inside one text, and callers scan newest mail first.
    // Lookarounds stop "1234567" matching as "234567".
    private static final Pattern OTP6_PATTERN =
            Pattern.compile("(?<!\\d)([0-9]{6})(?!\\d)");
    private static final Pattern OTP5_PATTERN =
            Pattern.compile("(?<!\\d)([0-9]{5})(?!\\d)");

    /** Returns the OTP code or null. Prefers a 6-digit code over a 5-digit one. */
    public static String extractFacebookOTP(String text) {
        if (text == null) return null;
        Matcher m6 = OTP6_PATTERN.matcher(text);
        while (m6.find()) {
            String code = m6.group(1);
            if (code != null && !code.equals("202600")) {
                return code;
            }
        }
        Matcher m5 = OTP5_PATTERN.matcher(text);
        while (m5.find()) {
            String code = m5.group(1);
            if (code != null && !code.equals("20260")) {
                return code;
            }
        }
        return null;
    }

    /** Length of a valid OTP code found in text, or 0. Used for 6-digit priority. */
    static int otpCodeLength(String text) {
        String c = extractFacebookOTP(text);
        return c == null ? 0 : c.length();
    }

    // ---- Country name generator (CountryData lists; user-extendable to 30-40) ----
    private static final String[] MALE = {"James","John","Robert","Michael","William","David","Richard","Joseph","Thomas","Charles","Christopher","Daniel","Matthew","Anthony","Donald","Mark","Paul","Steven","Andrew","Kenneth"};
    private static final String[] FEMALE = {"Mary","Patricia","Jennifer","Linda","Elizabeth","Barbara","Susan","Jessica","Sarah","Karen","Nancy","Lisa","Betty","Margaret","Sandra","Ashley","Kimberly","Emily"};
    private static final String[] LAST = {"Smith","Johnson","Williams","Brown","Jones","Garcia","Miller","Davis","Rodriguez","Martinez","Hernandez","Lopez","Gonzalez","Wilson","Anderson"};

    public static String generateName(String gender) {
        return generateName(0, gender);
    }

    /** Country-aware name: countryIndex into CountryData.COUNTRIES. */
    public static String generateName(int countryIndex, String gender) {
        try {
            CountryData.Country c = CountryData.byIndex(countryIndex);
            Random r = new Random();
            String first;
            if ("female".equalsIgnoreCase(gender)) {
                first = c.female[r.nextInt(c.female.length)];
            } else if ("both".equalsIgnoreCase(gender) || "random".equalsIgnoreCase(gender)) {
                int total = c.male.length + c.female.length;
                int pick = r.nextInt(total);
                first = pick < c.male.length ? c.male[pick] : c.female[pick - c.male.length];
            } else {
                first = c.male[r.nextInt(c.male.length)];
            }
            return first + " " + c.last[r.nextInt(c.last.length)];
        } catch (Exception e) {
            return generateName(gender);
        }
    }

    /** Suggested login e-mail for a generated name + country, e.g. anna.muller@outlook.de */
    public static String suggestEmail(String fullName, int countryIndex) {
        try {
            if (fullName == null) return "";
            String[] parts = fullName.trim().toLowerCase().split("\\s+");
            if (parts.length == 0) return "";
            String first = parts[0].replaceAll("[^a-z]", "");
            String last = parts.length > 1 ? parts[parts.length - 1].replaceAll("[^a-z]", "") : "";
            String local = last.isEmpty() ? first : first + "." + last;
            if (local.isEmpty()) return "";
            return local + "@" + CountryData.byIndex(countryIndex).domain;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Formats "First Last" as "First, Last" for one-click split copy.
     * e.g. "Rantu Mondal" -> "Rantu, Mondal". Single words pass through.
     */
    public static String splitCopyFormat(String fullName) {
        if (fullName == null) return "";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length < 2) return fullName.trim();
        return parts[0] + ", " + parts[parts.length - 1];
    }
}
