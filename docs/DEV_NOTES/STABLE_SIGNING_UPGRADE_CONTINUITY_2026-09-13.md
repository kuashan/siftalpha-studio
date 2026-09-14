# Stable Signing / Upgrade-in-Place Continuity

Date: 2026-09-13

Repository: `kuashan/siftalpha-studio`

## Scope

This infrastructure change keeps the alpha15 product snapshot unchanged while separating ordinary public CI from a protected trusted-signed build. It does not change Runtime, SAF, Storage, Terminal, Editor, or UI business logic, the application id, or the version.

- Application id: `com.siftalpha.studio`
- Version: `0.7.0-alpha15`
- `versionCode`: `77`
- Upgrade operation: `adb install -r` only; never uninstall or clear app data as part of this workflow

## Signer lineage

The stable development signer was recovered from the controlled private/local source and checked against the successful historical alpha15-chain APK artifact from the private archive before any public trusted build was configured.

- Historical stable APK certificate SHA-256: `1d96e9ce12c06e6ff0571cf8f82cf2461ac3b5261189ad06ab0747ce92f8192e`
- Stable keystore certificate SHA-256: `1d96e9ce12c06e6ff0571cf8f82cf2461ac3b5261189ad06ab0747ce92f8192e`
- Result: exact match

The certificate fingerprint is public verification metadata. The keystore, private key, passwords, and encoded signing payload remain outside the repository.

## Build separation

### Ordinary public/fork-safe CI

`android-debug-apk.yml` continues to run for pushes, pull requests, and manual checks. It uses the Android/Gradle default debug signing behavior, needs no repository secrets, and uploads the ordinary debug artifact for testing. It is not an upgrade artifact for an existing stable-development install.

### Trusted signed APK

`trusted-signed-debug-apk.yml` runs for pushes to, or manual dispatches on, the Public repository's `main` branch only. It has `contents: read` permissions, restores the protected keystore into `RUNNER_TEMP`, passes the four protected signing values to Gradle, verifies the signer and APK metadata, uploads a versioned trusted artifact, and removes the temporary keystore in an `always()` cleanup step.

Gradle enables the trusted debug signing configuration only when all four protected signing inputs and the runner-temporary keystore path are present. With no trusted inputs, local and ordinary CI builds continue to use normal debug signing.

## Protected secret names

Only these GitHub Actions Secret names are used; no values belong in source or logs:

- `SIFTALPHA_DEBUG_KEYSTORE_B64`
- `SIFTALPHA_DEBUG_STORE_PASSWORD`
- `SIFTALPHA_DEBUG_KEY_ALIAS`
- `SIFTALPHA_DEBUG_KEY_PASSWORD`

Fork pull requests do not invoke the trusted workflow and never receive these secrets.

## Validation record

The first trusted run completed successfully on commit `c252dd3c0234ddbd865f3a2e20429c5096511135`:

- Trusted Actions run: [34762538278](https://github.com/kuashan/siftalpha-studio/actions/runs/34762538278) — `success`
- Trusted artifact: `SiftAlpha-Studio-trusted-debug-0.7.0-alpha15-c252dd3`
- Artifact: [trusted APK artifact](https://github.com/kuashan/siftalpha-studio/actions/runs/34762538278/artifacts/10319452219)
- Exact-head/unit run: [34762684074](https://github.com/kuashan/siftalpha-studio/actions/runs/34762684074) — `success`
- Ordinary Android Debug APK run: [34762531706](https://github.com/kuashan/siftalpha-studio/actions/runs/34762531706) — `success`
- Localization run: [34762531689](https://github.com/kuashan/siftalpha-studio/actions/runs/34762531689) — `success`

- localization validators
- JVM unit tests
- ordinary local `assembleDebug` without trusted secrets
- `git diff --check`
- trusted Actions build
- trusted APK `apksigner verify --print-certs` signer equality with the historical fingerprint above
- trusted APK metadata equality with `com.siftalpha.studio`, `0.7.0-alpha15`, and `versionCode 77`
- separate fresh-install, upgrade-install, and app-data-preservation results; unavailable device coverage must be reported as `NOT TESTED`

Observed results:

- Localization validators: `PASS` (562 keys x 5 locales across 12 modules; 6 reachable Activity surfaces)
- JVM unit tests on Linux Actions: `PASS`
- Local ordinary `assembleDebug` without trusted inputs: `PASS` with the normal Android debug signer
- Local trusted signing smoke build: `PASS`
- Public-tree secret scan: `PASS`; no private-key block, embedded keystore payload, or plaintext signing password
- `git diff --check`: `PASS`
- Independent downloaded trusted artifact signer: exact match with the historical stable fingerprint above
- Independent downloaded trusted artifact metadata: exact match
- Fresh install: `NOT TESTED` (no authorized Android device connected)
- Upgrade install: `NOT TESTED` (no authorized Android device connected)
- App data preserved: `NOT TESTED` (no authorized Android device connected)
