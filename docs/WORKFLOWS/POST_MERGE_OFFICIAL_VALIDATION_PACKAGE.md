# Post-merge official validation package

**Status:** Active  
**Scope:** Every merge into `main`

## Rule

A merge into `main` is also a formal validation-package event. After every successful merge, the project workflow must produce and provide the current `main` build as a directly installable APK.

The package must come from the `Trusted Signed Debug APK` workflow run triggered by the merged `main` commit. A pull-request Debug APK is not a substitute for this package.

## Required sequence

1. Record the merge commit SHA and wait for the `main` push workflows to finish.
2. Require successful results for:
   - `Trusted Signed Debug APK`
   - `Android Debug APK`
   - `Exact Head Unit`
   - `Localization`
3. Confirm that the trusted workflow ran on `main` and that its head SHA is the merge commit.
4. Retrieve the trusted workflow artifact and verify its GitHub artifact digest after download.
5. Deliver the extracted APK directly, rather than only providing the ZIP archive.
6. Report the APK filename, source commit, workflow result, and the fact that it is the formal post-merge validation package.
7. Ask the user to install this package when another device-level acceptance pass is needed.

## Validation gates

The package may be presented as the formal validation package only when all of the following are true:

- The trusted workflow completed successfully.
- The workflow's signer and package checks passed.
- The package name is `com.siftalpha.studio`.
- The artifact belongs to the merged `main` commit.
- Launcher icon validation passed.
- The downloaded artifact digest matches the digest reported by GitHub.

If any gate fails, report the failure and do not label an APK as the formal validation package.

## Naming

Use a descriptive filename:

`SiftAlpha-Studio-trusted-main-<versionName>-code<versionCode>.apk`

Keep the original GitHub artifact available for traceability, but provide the extracted APK for installation.

## Existing automation

`.github/workflows/trusted-signed-debug-apk.yml` is the source of truth for this event. Its `push` trigger on `main`, protected signing verification, package/version checks, launcher-icon validation, and artifact upload must remain enabled.

This rule describes the post-merge delivery procedure; it does not turn the Debug APK into a public production release.
