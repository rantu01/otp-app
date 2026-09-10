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
        public Account(String email, String refreshToken, String clientId) {
            this.email = email;
            this.refreshToken = refreshToken;
            this.clientId = clientId;
        }
    }

    /** Parses raw pasted data. Throws IllegalArgumentException on bad format. */
    public static Account parseAccountData(String rawData) {
        if (rawData == null) throw new IllegalArgumentException("Invalid Format! Email, Refresh Token or Client ID missing.");
        String[] parts = rawData.split("\\|");
        String email = "", refreshToken = "", clientId = "";
        for (String p : parts) {
            String part = p.trim();
            if (part.contains("@")) {
                email = part;
            } else if (part.length() == 36 && part.contains("-")) {
                clientId = part;
            } else if (part.length() > 30) {
                refreshToken = part;
            }
        }
        if (email.isEmpty() || refreshToken.isEmpty() || clientId.isEmpty()) {
            throw new IllegalArgumentException("Invalid Format! Email, Refresh Token or Client ID missing.");
        }
        return new Account(email, refreshToken, clientId);
    }

    /** Extracts just the e-mail (for the e-mail preview box). Returns "" if none. */
    public static String extractEmail(String rawData) {
        if (rawData == null) return "";
        for (String p : rawData.split("\\|")) {
            String part = p.trim();
            if (part.contains("@")) return part;
        }
        return "";
    }

    // (?:kode|code|sandi|código|codice|FB-)?\s*([0-9]{5,6})\b  (case-insensitive)
    private static final Pattern OTP_PATTERN =
            Pattern.compile("(?:kode|code|sandi|c\u00f3digo|codice|FB-)?\\s*([0-9]{5,6})\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Returns the OTP code or null. Mirrors extractFacebookOTP() in server.js. */
    public static String extractFacebookOTP(String text) {
        if (text == null) return null;
        Matcher m = OTP_PATTERN.matcher(text);
        while (m.find()) {
            String code = m.group(1);
            if (code != null && !code.equals("20260") && !code.equals("202600")) {
                return code;
            }
        }
        return null;
    }

    // ---- US Name Generator (same lists as popup.js) ----
    private static final String[] MALE = {"James","John","Robert","Michael","William","David","Richard","Joseph","Thomas","Charles","Christopher","Daniel","Matthew","Anthony","Donald","Mark","Paul","Steven","Andrew","Kenneth"};
    private static final String[] FEMALE = {"Mary","Patricia","Jennifer","Linda","Elizabeth","Barbara","Susan","Jessica","Sarah","Karen","Nancy","Lisa","Betty","Margaret","Sandra","Ashley","Kimberly","Emily"};
    private static final String[] LAST = {"Smith","Johnson","Williams","Brown","Jones","Garcia","Miller","Davis","Rodriguez","Martinez","Hernandez","Lopez","Gonzalez","Wilson","Anderson"};

    public static String generateName(String gender) {
        Random r = new Random();
        String first;
        if ("female".equalsIgnoreCase(gender)) {
            first = FEMALE[r.nextInt(FEMALE.length)];
        } else if ("both".equalsIgnoreCase(gender) || "random".equalsIgnoreCase(gender)) {
            int total = MALE.length + FEMALE.length;
            int pick = r.nextInt(total);
            first = pick < MALE.length ? MALE[pick] : FEMALE[pick - MALE.length];
        } else {
            first = MALE[r.nextInt(MALE.length)];
        }
        return first + " " + LAST[r.nextInt(LAST.length)];
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
