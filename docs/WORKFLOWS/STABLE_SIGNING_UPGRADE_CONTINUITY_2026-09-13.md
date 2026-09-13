# Stable Signing / Upgrade-in-Place Continuity Workflow

Date: 2026-09-13

Repository: `kuashan/siftalpha-studio`

## Outcome and invariants

This change establishes a protected trusted-signed debug APK path without changing product behavior.

- Public repository remains Public; private archive remains Private.
- `com.siftalpha.studio` remains unchanged.
- `0.7.0-alpha15` / `versionCode 77` remain unchanged.
- Runtime, SAF, Storage, Terminal, Editor, and UI business logic remain untouched.
- No uninstall or app-data clearing is part of validation.

## Historical signer verification

The stable development signer was recovered from a controlled private/local source. Before configuring Public-repository Secrets, its certificate was compared with the successful historical alpha15-chain APK artifact from the private archive.

Historical APK and stable keystore certificate SHA-256 fingerprints are both:

`1d96e9ce12c06e6ff0571cf8f82cf2461ac3b5261189ad06ab0747ce92f8192e`

Only this public fingerprint is recorded here. No keystore, private key, password, or base64 signing payload is recorded.

## Workflow contract

The ordinary public workflow remains fork-safe and secret-free:

- triggers: `push` to `main`, `pull_request`, and optional manual checks
- signing: normal Android/Gradle debug signing
- purpose: localization, unit, and ordinary build/testing artifact
- upgrade continuity: not guaranteed

The trusted workflow is isolated:

- trigger: `workflow_dispatch` only
- accepted context: `kuashan/siftalpha-studio` on `refs/heads/main`
- permissions: `contents: read`
- keystore: restored only under the runner temporary directory
- verification: certificate SHA-256 and package/version metadata before upload
- cleanup: temporary keystore removed with `if: always()`
- artifact: `SiftAlpha-Studio-trusted-debug-<version>-<short-sha>`

Gradle activates the trusted debug signing configuration only when all four protected inputs are present together with the temporary keystore path. Without them, `assembleDebug` continues to work with ordinary debug signing.

## Secret names

The Public repository uses only these GitHub Actions Secret names:

- `SIFTALPHA_DEBUG_KEYSTORE_B64`
- `SIFTALPHA_DEBUG_STORE_PASSWORD`
- `SIFTALPHA_DEBUG_KEY_ALIAS`
- `SIFTALPHA_DEBUG_KEY_PASSWORD`

Values must never be committed, printed, uploaded, or included in issues, pull requests, comments, or reports. Fork PRs never receive them.

## Upgrade test boundary

When an authorized Android device is available, install the trusted artifact with `adb install -r` and report these independently:

- Fresh install
- Upgrade install
- App data preserved

If no device is connected, or the same `versionCode` prevents a meaningful upgrade test, report the corresponding result as `NOT TESTED`; do not claim upgrade continuity from CI alone.
