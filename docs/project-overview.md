# OTP Android App: Project Overview

## Architecture

The app is a native Android application using AppCompat, Material Components, Java, and XML layouts. `MainActivity` owns the OTP workspace and delegates domain work to `OtpHelper`, the embedded `OtpServer`, `AutoFetchManager`, and `OtpAutoActions`. Authentication and access are handled by `ActivationActivity`, `AuthActivity`, and `AccessGate`; subscription, profile, and package flows live in their dedicated activities.

## Primary flows

1. The session gate routes unauthenticated users to activation or sign-in.
2. The OTP workspace validates backend access, starts or stops the local bridge, parses saved account data, fetches codes, and exposes copy/clear actions.
3. The Names tab generates a country-aware identity and persists the latest result.
4. Packages and payment screens expose subscription state and payment history.
5. Profile manages identity, password changes, and sign-out.
6. The floating service mirrors the latest OTP and can reopen the workspace.

## State management

Session and UI persistence use `SharedPreferences`. Server and network work run off the main thread through executors, then update views on the main looper. Access is fail-closed: the fetch workflow remains unavailable until the backend confirms permission. Loading, error, and success messages are rendered in existing view bindings so the presentation layer can change without changing business behavior.

## UI contract

Existing view IDs, listeners, routes, API calls, and service lifecycles are public implementation contracts. Visual changes should remain inside XML resources, drawables, dimensions, and theme tokens unless a behavior change is explicitly required.
