# OTP Android App: Design System

## Direction

A light, operational interface built around deep cobalt, slate text, quiet blue surfaces, and semantic green, amber, and red states. Surfaces are flat and purposeful; decoration never competes with the OTP or access state.

## Tokens

- Primary: `accent_blue` / `#3157C7`; deep primary: `header_start` / `#243B8F`.
- Text: `title_text` / `#172033`; secondary: `muted_text` / `#6B7280`; hint: `input_hint`.
- Surfaces: `screen_bg` / `#F5F7FB`; `card_bg` / `#FFFFFF`; informational surface: `info_bg` / `#EEF3FF`.
- Semantic: `success` and `otp_green` for confirmed states, `accent_orange` for pending, `danger` for blocked or destructive actions.
- Borders: one-pixel `stroke` with no decorative gradients.

## Type and spacing

Use the platform sans family with bold headings and tabular/monospace numerals for OTP codes. Base spacing is 8dp: 8, 12, 16, 24, and 32dp. Content gutters are 16dp on phones. Primary controls are at least 48dp high. Cards use 12-16dp corners and a subtle one-pixel border.

## Layout

The customer app prioritizes access status, bridge status, mode selection, then the active task. On wider screens, retain the same order and allow content to breathe rather than adding decorative columns. Bottom navigation and drawers retain safe-area padding supplied by the system.

## Elevation

Prefer border plus restrained elevation. Use elevation only to separate a modal, floating widget, or active card from its parent surface. Avoid stacked cards and heavy shadows.
