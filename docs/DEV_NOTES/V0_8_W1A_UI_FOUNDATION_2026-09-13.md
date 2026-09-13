# v0.8 W1A UI Foundation

Date: 2026-09-13

Branch: `v0.8.0-uiux-rework`

## Scope

This slice starts the incremental v0.8 UI migration without changing Runtime, Termux/PRoot execution, process ownership, secret transport, SAF source handling, Web endpoint validation, or cleanup guards.

The current device-acceptance candidate is `0.8.0-alpha3` (`versionCode 79`). It supersedes the earlier alpha2 W1A candidate after the browser-preference and launcher-icon refinements. The application id remains `com.siftalpha.studio`; protected trusted signing is still a separate main-only path and is not claimed by this candidate.

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
- Added the current approved SiftAlpha Studio S/A + terminal-prompt artwork as the launcher icon candidate for this W1A acceptance pass.
- Expanded the localization source gate to include the Settings host and new `ui/**` Kotlin sources, including direct Compose `Text` and `contentDescription` literals.
- Added Settings strings for Simplified Chinese, Traditional Chinese, English, Korean, and Japanese.

## Transition boundary

The new Settings surface follows the light/dark semantic theme. Existing View-based screens remain on the accepted legacy dark theme in this slice. There is intentionally no global theme switch yet, and this work does not claim that legacy screens have been migrated to Material 3.

The browser preference changes only browser dispatch after the existing Runtime web-state and endpoint checks succeed. It does not change project execution, URL extraction, endpoint validation, Runtime state, or process ownership.

## Validation status

Remote pull-request CI must re-run for alpha3 before it becomes the new W1A real-device baseline. Real-device coverage, Android 13+ locale switching, legacy locale-wrapper switching, 320/360 dp rendering, 200% font scaling, screen-reader behavior, browser preference behavior, launcher rendering, and upgrade-in-place/data-preservation remain `NOT TESTED` until an authorized device is used.
