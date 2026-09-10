# rantuOTP — Android App

Native Android port of the two existing folders:

| Original | Android equivalent |
|---|---|
| `OTP Fetch/OTP Fetch` (Chrome extension: `popup.html`, `popup.js`, `background.js`) | `MainActivity` + `OtpHelper` (same OTP + US-name-generator logic, same UI flow) |
| `otp-bridge/otp-bridge/server.js` (Node/Express on `:3000`) | `OtpServer` (embedded Java HTTP server on `:3000`, same routes + caches + 250 ms poller) |

No new dependencies beyond AndroidX/AppCompat/Material — networking is plain
`HttpURLConnection` + `org.json` (ships with Android).

## Features

- **Start / Stop Server** — embedded bridge on `http://127.0.0.1:3000`
  - `POST /get-otp` `{ email, refreshToken, clientId }` → `{ success, code }`
  - `GET /health` → `{ status, watchedAccounts, cachedTokens, cachedOTP }`
  - Same behaviour as `server.js`: token cache (~1 h), 30 s OTP cache,
    seen-message dedup (50), parallel inbox+junk Graph fetch, 30 × 250 ms retry loop.
- **OTP Fetcher tab** — paste `Email|RefreshToken|ClientId` (any order), auto e-mail
  preview, GET CODE (via HTTP to the embedded server, exactly like the extension
  calls `http://localhost:3000/get-otp`), Copy Email, Copy Code, Clear Saved Data
  (persisted in `SharedPreferences`, parity with `chrome.storage.local`).
- **US Name Gen tab** — Male / Female / Random radio, Generate, Copy
  (same name lists as `popup.js`).
- **Floating widget** — chat-head bubble (`SYSTEM_ALERT_WINDOW` + foreground
  `FloatingService`). Draggable, stays above other apps, tap to reopen the app,
  bubble text updates with the latest OTP code.
- **Exit App** — stops the server, stops/removes the floating widget, finishes
  the app and kills the process.

## Project layout

```
OTP-Android-App/
  settings.gradle  build.gradle  gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/otpfetch/app/
    MainActivity.java     # UI: tabs, server card, floating toggle, exit
    OtpServer.java        # embedded :3000 port of server.js
    OtpHelper.java        # account parsing, OTP regex, name lists
    FloatingService.java  # overlay bubble foreground service
  app/src/main/res/layout/activity_main.xml
  app/src/main/res/values/{strings,themes}.xml
```

## Build (option A: GitHub Actions — no software needed)

1. Create a new GitHub repository.
2. Upload the **contents** of the `OTP-Android-App` folder as the repo root
   (so `settings.gradle`, `app/`, `.github/` are at the top level).
3. Push — the `Build rantuOTP APK` workflow (`.github/workflows/build-apk.yml`)
   starts automatically. After ~3–6 minutes, download `rantuOTP-apk`
   from the run's **Artifacts** section and install it on your phone.

## Build (option B: Android Studio)

1. Open `OTP-Android-App` in Android Studio (it will generate `local.properties`
   with your SDK path; a `local.properties.example` is included).
2. Grant internet on first run; accept the **Display over other apps** permission
   when enabling the floating widget.
3. Run on a device (min SDK 26, target/compile 34) or Build → Generate Signed APK.

## Notes

- `android:usesCleartextTraffic="true"` is set so the local `http://127.0.0.1:3000`
  loopback works; Microsoft Graph calls themselves remain HTTPS.
- On Android 13+, the app requests `POST_NOTIFICATIONS` for the foreground-service
  notification that keeps the floating widget alive.
- Pressing the system Back button only backgrounds the app; only **Exit App**
  fully stops the server + widget + process.
