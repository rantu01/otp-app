# OTP Android App: UX Rules

- Every interactive target is at least 48dp and has a readable label or content description.
- Never communicate access, payment, or OTP state with color alone; pair color with text and, where useful, an icon.
- Disable duplicate submissions while a request is in flight and show a controlled progress state.
- Preserve the last known context when the API is unavailable, with a clear retry action.
- Empty states explain the next useful action. Search empty and genuinely empty data are distinct.
- Destructive actions require confirmation and name the backend effect.
- Success and failure feedback uses inline status first; use a toast only for short-lived confirmation such as copy.
- Keep OTP content prominent, never expose refresh tokens or client IDs in result surfaces, and make copy feedback immediate.
- Respect system status/navigation bars and safe-area insets. Do not place essential actions under them.
- Use short transitions and pressed states to confirm touch without delaying work.
