# OTP Android App: Component Rules

## Naming

XML IDs use lower camel case and describe role (`getCodeBtn`, `resultContainer`). Resource names use lowercase snake case. Java domain classes use PascalCase; methods and fields use lower camel case.

## Atomic components

- **Surface:** a functional grouping using `bg_card`, consistent padding, and one border.
- **Primary action:** one filled cobalt or semantic button per task.
- **Secondary action:** quiet surface or outlined treatment; never compete with the primary action.
- **Status:** text plus semantic tint, using the existing status views and tokens.
- **Input:** label/hint, visible focus, inline error, and preserved value on recoverable failure.
- **Navigation:** active tab is filled and inactive tabs remain readable; preserve existing menu IDs and callback wiring.

Prefer existing views and helpers over new one-off components. Keep rendering helpers in the owning activity and keep API, persistence, and service behavior outside visual components. Any new component must define loading, empty, error, disabled, and success states before adoption.
