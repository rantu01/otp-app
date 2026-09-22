# Rantu OTP - Stitch Design Brief

## 1. Product Context

Design two coordinated Android experiences for the Rantu OTP platform:

1. **Customer app**: a secure OTP fetching utility with subscription access, payment, US name generation, and a floating OTP widget.
2. **Admin app**: a fast mobile operations console for payment review, user access, packages, payment methods, subscriptions, and profit tracking.

The backend is a shared Node/Express + MongoDB REST API. The UI must make server-controlled access and payment states obvious. Never imply that a user has access when the backend has returned pending, disabled, expired, or unavailable status.

The current apps are native Android. Design mobile-first screens that can be implemented with Material Components. Use realistic data and complete loading, empty, error, disabled, and success states.

## 2. Shared Brand Direction

- Product feeling: dependable, quick, technical, and practical; this is a working tool, not a marketing website.
- Visual personality: clean utility with a confident blue-violet identity, balanced by teal for active tools, green for successful OTP/payment states, amber for pending states, and red only for destructive or blocked states.
- Avoid a generic purple gradient dashboard. Use a restrained indigo-to-cobalt brand accent, mostly light surfaces, dark readable text, and clear status colors.
- Use one strong sans-serif display/body family with excellent Android readability. Suggested direction: **Manrope** or **Plus Jakarta Sans**. Use tabular numerals for OTP codes, prices, counters, and transaction IDs.
- Use 8dp spacing units, 12-16dp corner radius, accessible contrast, 48dp minimum touch targets, and visible focus/error states.
- Cards should be functional grouping surfaces, not decorative containers. Keep hierarchy flat and scannable.
- Use familiar outline icons for account, package, server, copy, refresh, notifications, payments, users, settings, and logout. Add tooltips/content descriptions for unfamiliar icons.
- Support light theme first. Keep the visual system ready for dark theme without making dark mode the primary concept.

## 3. Customer App

### Customer user and primary job

The customer needs to paste account credentials, start the local bridge, retrieve an OTP quickly, copy it, and optionally keep it visible through the floating widget. They may also need to buy/renew access and track payment approval.

### Customer navigation

Use a compact top app bar with the Rantu OTP mark, access status, and account action. Use a bottom navigation or navigation drawer depending on screen width:

- **OTP Workspace**
- **Names**
- **Packages**
- **Profile**
- **Server / Floating widget settings**

The primary action must always remain easy to reach. Do not hide OTP retrieval behind a deep menu.

### Screen A: Activation / Login

- Brand mark and short line: “Secure OTP workspace”.
- Email or phone field.
- Password field with show/hide control.
- Primary action: **Sign in**.
- Secondary action: **Create account**.
- Clear inline states: invalid credentials, pending approval, disabled account, expired access, backend unavailable, retrying.
- Pending users should be guided to Packages instead of seeing a confusing blank home screen.
- Keep this screen calm and trustworthy; no promotional hero imagery.

### Screen B: OTP Workspace (primary screen)

Structure from top to bottom:

1. **Access status strip**
   - Active package name, remaining time, or “Access required”.
   - Account and Packages shortcuts.
   - Use green/amber/red status treatment with text, never color alone.
2. **Local bridge status card**
   - Status: Running / Stopped / Starting / Error.
   - Local endpoint label: `127.0.0.1:3000`.
   - Watched account count when running.
   - Large Start/Stop toggle action.
3. **Mode switch**
   - Segmented control: **OTP Fetcher** and **Name Generator**.
4. **OTP Fetcher panel**
   - Multiline input with hint `Email | RefreshToken | ClientId`.
   - Detect and preview the email after parsing.
   - Primary action: **Get code**.
   - Status line for fetching, no code found, invalid input, retry, and network/server errors.
   - Result area with a very prominent six-digit OTP, timestamp, copy action, and optional refresh action.
   - Secondary actions: Copy email and Clear saved data.
   - Never expose refresh token or client ID in the result area.
5. **Floating widget control**
   - Compact setting row showing enabled/disabled and permission state.
   - Explain overlay and notification permissions only when needed.

Important OTP states:

- Empty: explain the input format briefly.
- Valid input: show masked credential preview and detected email.
- Fetching: disable duplicate submission and show progress.
- Success: highlight code, copy confirmation, last-updated time.
- No code: explain that the inbox was checked and offer retry.
- Server stopped: make Start server the obvious next action.
- Access blocked/expired: lock the fetch action and link to Packages.

### Screen C: Name Generator

- Country selector, initially United States.
- Domain preview.
- Segmented choice: Male / Female / Random.
- Primary action: **Generate name**.
- Result block with generated name and email, each with an adjacent copy icon.
- Clear generated result state and a short, useful empty state.

### Screen D: Packages and Payment

- Current subscription summary: package, active/expired label, start/end date, remaining time.
- Package list with price in BDT (৳), duration, and a clear recommended option without aggressive upselling.
- Payment method cards for bKash, Nagad, Rocket, or configured methods.
- Show wallet number with copy icon and concise payment instructions.
- Transaction ID field with normalization-friendly helper text.
- Primary action: **Submit payment**.
- Payment history list with status chips: Pending, Approved, Rejected, Auto-verified.
- Rejected payments must show the rejection reason.
- Include refresh, loading skeleton, duplicate transaction error, offline error, and successful submission confirmation.

### Screen E: Profile

- Read-only identity section: name, email/username, phone.
- Change password form: current password, new password, confirm password.
- Inline validation and password visibility controls.
- Sign out action separated from the password form and confirmed before execution.

### Customer floating widget

Design the overlay as a small draggable circular or softly rounded bubble, not a permanent large panel. It shows the latest OTP only when available, supports tap-to-open, and has a clear enabled/permission-required state inside the app. The foreground-service notification should use the same brand language.

## 4. Admin App

### Admin user and primary job

An admin reviews incoming payments quickly, approves or rejects them safely, manages users and packages, and monitors revenue. The admin app is an operations tool: optimize for scanning, comparison, search, and low-friction decisions.

### Admin navigation

Use a top app bar with brand, sync/refresh action, notification bell, pending count badge, and logout. Use a horizontally scrollable tab bar or navigation rail on wider screens:

- **Dashboard**
- **Payments**
- **Received payments**
- **Users**
- **Packages**
- **Payment methods**
- **Profit**
- **Withdrawals**
- **Versions**

Keep Payments and Dashboard prominent. A pending count must be visible without opening a screen.

### Admin login

- NesaAdmin/Rantu OTP admin identity treatment, without using an emoji as the main brand mark.
- Email and password fields.
- Primary sign-in action.
- Role denied, invalid credentials, backend unavailable, and retry states.
- Explain that only admin-role accounts can enter the console.

### Admin Dashboard

Use a compact overview rather than oversized marketing cards:

- Pending payment count and amount.
- Approved revenue.
- Total users and active subscriptions.
- Expired subscriptions.
- Profit summary.
- Recent pending payments list with direct Review actions.
- Pull-to-refresh or explicit refresh with “last synced” timestamp.

On wider screens, use a two-column arrangement: KPI strip and recent activity/payment queue. On phones, stack sections in priority order.

### Payments

- Segmented filter: Pending / All.
- Search by user, email, or transaction ID.
- Sort/filter by status and date.
- Toggle list/card presentation only if it remains readable on a phone.
- Each payment row shows user, package, amount, TxID, submitted time, status, and auto-verified badge where applicable.
- Copy TxID action.
- Approve action opens a confirmation sheet showing amount, user, package, and expiry effect.
- Reject action requires a rejection reason.
- Handle already-reviewed race state with a clear refresh message; never show a false success.
- Empty states must distinguish “No pending payments” from “No results for this search”.

### Received Payments

- Inbox/outbox-style list for bKash/SMS received records.
- Search by transaction ID or sender.
- Show sender, amount, received time, raw SMS details, match status, and linked customer payment.
- **Verify TxID** action compares transaction ID and expected amount.
- Make mismatched amount/TxID visually obvious and require deliberate confirmation.

### Users

- Search by name, user ID, email, or phone.
- User row: identity, role, access status, package, expiry, and last activity.
- Actions: enable/disable access, convert paid/free role, assign package, remove user while retaining history.
- Destructive actions require confirmation and explain their immediate backend effect.
- Show pending, active, disabled, expired, and free states with text plus color/icon.

### Packages and Payment Methods

Use clear CRUD screens with a prominent add action and inline status:

- Packages: name, price, duration, active/inactive; create/edit/delete.
- Payment methods: provider name, wallet number, instructions, active/inactive; create/edit/delete.
- Use bottom sheets or full-screen forms with validation, unsaved-change protection, and success/error feedback.

### Profit and Withdrawals

- Profit: daily, weekly, total, withdrawn, remaining.
- Show the configured split clearly: Alamin 20%, Rantu 40%, Rony 40%.
- Withdrawals: recipient/person, phone, amount, note, remaining balance, and history.
- Prevent or warn on an amount greater than remaining balance.
- Prefer a compact table-like list with aligned numeric columns on wide screens and stacked rows on phones.

### Versions

- Platform/version list with current version, minimum version, update URL or release note, and active status.
- Make forced-update and optional-update states explicit.

## 5. System States and Trust Rules

Design all screens around these backend states:

- New accounts begin **Pending**.
- Access requires active status, access enabled, and a non-expired subscription unless the role is **Free**.
- Admin access changes apply immediately, even if a JWT still exists.
- Payment transaction IDs are normalized and unique; duplicate submission must be explained.
- Approval activates a subscription once. Renewal extends from the current expiry when still active.
- Backend/API unavailable must be a recoverable error with retry and last-known context, never a fake success.
- Loading should use skeletons or controlled progress indicators; avoid layout jumps.
- Every destructive action has confirmation, and every mutation has success/failure feedback.

## 6. Stitch Deliverables

Generate:

1. A complete customer Android flow: Login, OTP Workspace, Name Generator state, Packages/Payment, Payment History, Profile, and permission/error states.
2. A complete admin Android flow: Admin Login, Dashboard, Payments queue/detail/review, Received Payments, Users, Packages, Payment Methods, Profit, Withdrawals, Versions, and empty/loading/error states.
3. A shared design system page showing colors, typography, spacing, button variants, status chips, input states, cards, list rows, bottom sheets, dialogs, and navigation.
4. Light theme screens at phone size, plus at least one wider Android layout showing how admin tables/columns adapt.
5. Prototype links for the main actions: login, start server, get code, copy code, buy package, submit payment, approve/reject payment, disable user, and add package.

## 7. Seed Data for Mockups

Use believable but clearly fictional data:

- Customer: Rahim Hasan, active package “7 Days”, 5 days remaining.
- OTP result: `482 917` with a recent timestamp.
- Packages: ৳20 / 7 days and ৳35 / 20 days.
- Payment methods: bKash, Nagad, Rocket with masked or placeholder wallet numbers.
- Admin pending payments with Pending, Approved, Rejected, and Auto-verified examples.
- User statuses: Pending, Active, Disabled, Expired, Free.
- Profit split: Alamin 20%, Rantu 40%, Rony 40%.

Do not place real secrets, refresh tokens, passwords, API keys, or production wallet credentials in any design or mockup.

## 8. Ready-to-use Stitch Prompt

Create a polished, mobile-first native Android design system and complete interactive flows for **Rantu OTP**, a secure OTP utility with a separate admin operations console. Design two coordinated but distinct products: a calm, fast customer app centered on a large OTP result and subscription/payment access; and a dense, efficient admin app centered on pending payment review and user/package management. Use a restrained indigo/cobalt brand accent on light surfaces, teal for tools, green for success, amber for pending, and red only for blocked/destructive states. Use Manrope or Plus Jakarta Sans, tabular numerals for OTPs and money, 8dp spacing, 12-16dp corners, accessible contrast, 48dp touch targets, familiar outline icons, and no decorative marketing hero. Include every loading, empty, error, disabled, pending, approved, rejected, expired, and backend-unavailable state described above. Make the OTP code the most visually prominent customer result, and make the admin pending payment count and review actions the most prominent admin controls. Provide phone layouts and one wider responsive admin layout.