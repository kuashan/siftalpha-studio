# v0.8 W1A UI Foundation

Date: 2026-09-13

Branch: `v0.8.0-uiux-rework`

## Scope

This slice starts the incremental v0.8 UI migration without changing Runtime, Termux/PRoot execution, process ownership, secret transport, SAF source handling, Web endpoint validation, or cleanup guards.

The current device-acceptance candidate is `0.8.0-alpha4` (`versionCode 80`). It supersedes alpha3 after real-device acceptance confirmed the browser preference worked but showed that the launcher icon still resolved through the old `ic_launcher` resource path on the test device. The application id remains `com.siftalpha.studio`; protected trusted signing is still a separate main-only path and is not claimed by this candidate.

## Compatibility baseline

- Android Gradle Plugin: 9.1.1
- Gradle: 9.3.1
- compile/target SDK: 36
- min SDK: 26
- JDK: 17
- AGP built-in Kotlin remains enabled; `kotlin-android` is not added.
- Compose compiler Gradle plugin: 2.2.10, matching the minimum KGP line supplied by AGP 9 built-in Kotlin.
- Compose BOM: 2026.03.01, intentionally kept below the Compose 1.12 line that requires compileSdk 37.
- Activity Compose: 1.13.0.

## Changes

- Added an independent `StudioComposeActivity` host so legacy `StudioActivity`, editor, terminal, Runtime, and IME behavior remain untouched.
- Added Material 3 light/dark semantic color tokens, typography, spacing, status colors, and small reusable card/action/status components.
- Added a real Settings screen for app language, preferred browser, and About/version information.
- Moved the single global language-picker entry from Home to Settings while continuing to use the existing `StudioLanguage` persistence and locale behavior.
- Added a persistent global browser preference. Runtime Center still performs the existing local URL and endpoint-availability checks, then opens the selected browser directly instead of showing a browser chooser on every launch. If the preference is missing or no longer valid, Runtime Center sends the user back to Settings rather than silently choosing another browser.
- Added the current approved SiftAlpha Studio S/A + terminal-prompt artwork as the launcher icon candidate.
- Alpha4 changes the manifest to a new launcher resource name (`siftalpha_launcher`) and supplies both base-anydpi bitmap wrappers and API 26+ adaptive-icon wrappers. This removes the old `ic_launcher` resource path from the application icon/roundIcon references and is intended to avoid OEM launcher/resource-cache fallback to the legacy icon assets.
- Expanded the localization source gate to include the Settings host and new `ui/**` Kotlin sources, including direct Compose `Text` and `contentDescription` literals.
- Added Settings strings for Simplified Chinese, Traditional Chinese, English, Korean, and Japanese.

## Transition boundary

The new Settings surface follows the light/dark semantic theme. Existing View-based screens remain on the accepted legacy dark theme in this slice. There is intentionally no global theme switch yet, and this work does not claim that legacy screens have been migrated to Material 3.

The browser preference changes only browser dispatch after the existing Runtime web-state and endpoint checks succeed. It does not change project execution, URL extraction, endpoint validation, Runtime state, or process ownership.

## Validation status

Real-device alpha3 result: preferred-browser selection/persistence and direct-open behavior passed on the authorized test device. Alpha3 launcher rendering failed acceptance because the device continued showing the old launcher icon.

Alpha4 must re-run pull-request CI and then repeat launcher-icon acceptance on device. Five-language switching/persistence, 320/360 dp rendering, 200% font scaling, screen-reader behavior, trusted-signer upgrade-in-place, and app-data preservation remain `NOT TESTED` unless separately recorded.
